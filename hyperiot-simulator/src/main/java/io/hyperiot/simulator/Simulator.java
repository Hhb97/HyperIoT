package io.hyperiot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A simulated device for trying out the platform: it reports temperature / humidity periodically, answers service
 * invocations, applies property settings and performs OTA upgrades (download, verify, report progress).
 *
 * <pre>
 * # one device
 * java -jar hyperiot-simulator.jar --product-key abc123 --device-name dev1 --secret ... [--host localhost] [--port 1883] [--interval 5]
 *
 * # many devices in one process: a CSV with productKey,deviceName,deviceSecret (the file the console's "batch create" exports),
 * # or deviceName,deviceSecret together with --product-key
 * java -jar hyperiot-simulator.jar --devices-file devices.csv --host 192.168.221.130 --interval 3 [--stagger-ms 200] [--quiet true]
 * </pre>
 *
 * The secret of a single device can also be given through the HYPERIOT_DEVICE_SECRET environment variable, which keeps it
 * out of the process list.
 */
public final class Simulator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String productKey;
    private final String deviceName;
    private final String base;
    private final Random random = new Random();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private MqttClient client;
    private volatile double temperature = 24.0;
    private volatile int humidity = 55;
    private volatile String mode = "auto";
    private volatile boolean fan = false;
    private volatile String firmwareVersion;
    private final boolean quiet;

    private Simulator(String productKey, String deviceName, String firmwareVersion, boolean quiet) {
        this.quiet = quiet;
        this.productKey = productKey;
        this.deviceName = deviceName;
        this.base = "hyperiot/" + productKey + "/" + deviceName + "/";
        this.firmwareVersion = firmwareVersion;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        String host = a.getOrDefault("host", "localhost");
        int port = Integer.parseInt(a.getOrDefault("port", "1883"));
        int interval = Integer.parseInt(a.getOrDefault("interval", "5"));
        String firmware = a.getOrDefault("firmware", "1.0.0");
        boolean quiet = Boolean.parseBoolean(a.getOrDefault("quiet", "false"));
        String brokerUrl = "tcp://" + host + ":" + port;

        List<String[]> devices = new ArrayList<>(); // {productKey, deviceName, secret}
        if (a.containsKey("devices-file")) {
            devices.addAll(readDevices(Path.of(a.get("devices-file")), a.get("product-key")));
            if (devices.isEmpty()) {
                System.err.println("no devices found in " + a.get("devices-file"));
                System.exit(2);
            }
        } else {
            String secret = a.getOrDefault("secret", System.getenv("HYPERIOT_DEVICE_SECRET"));
            if (secret == null || secret.isBlank()) {
                System.err.println("missing --secret (or HYPERIOT_DEVICE_SECRET), or use --devices-file");
                System.exit(2);
            }
            devices.add(new String[]{require(a, "product-key"), require(a, "device-name"), secret});
        }

        long staggerMs = Long.parseLong(a.getOrDefault("stagger-ms", devices.size() > 1 ? "200" : "0"));
        List<Simulator> running = new ArrayList<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> running.forEach(Simulator::stop)));
        System.out.println("starting " + devices.size() + " simulated device(s) against " + brokerUrl);
        for (String[] d : devices) {
            Simulator sim = new Simulator(d[0], d[1], firmware, quiet);
            running.add(sim);
            try {
                sim.start(brokerUrl, d[2], interval);
            } catch (Exception e) {
                System.err.println("[" + d[1] + "] could not start: " + e.getMessage());
            }
            if (staggerMs > 0) {
                Thread.sleep(staggerMs); // do not connect hundreds of devices in the same instant
            }
        }
        Thread.currentThread().join();
    }

    /** CSV lines: productKey,deviceName,deviceSecret or deviceName,deviceSecret (then --product-key is used). */
    static List<String[]> readDevices(Path file, String defaultProductKey) throws java.io.IOException {
        List<String[]> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("[,;\t]");
            for (int i = 0; i < f.length; i++) {
                f[i] = f[i].trim().replaceAll("^\"|\"$", "");
            }
            if (f[0].equalsIgnoreCase("productKey") || f[0].equalsIgnoreCase("deviceName")) {
                continue; // header
            }
            if (f.length >= 3) {
                out.add(new String[]{f[0], f[1], f[2]});
            } else if (f.length == 2 && defaultProductKey != null) {
                out.add(new String[]{defaultProductKey, f[0], f[1]});
            } else {
                System.err.println("skipping a line that is not productKey,deviceName,secret (or deviceName,secret with --product-key)");
            }
        }
        return out;
    }

    private void stop() {
        try {
            scheduler.shutdownNow();
            if (client != null && client.isConnected()) {
                client.disconnect(1000);
            }
        } catch (Exception ignored) {
            // exiting
        }
    }

    private void log(String message) {
        System.out.println("[" + deviceName + "] " + message);
    }

    private void start(String brokerUrl, String secret, int intervalSeconds) throws Exception {
        String clientId = productKey + "." + deviceName;
        // password = HMAC-SHA256(secret, "clientId" + clientId + "deviceName" + dn + "productKey" + pk), the secret itself is never sent
        String password = hmacHex(secret, "clientId" + clientId + "deviceName" + deviceName + "productKey" + productKey);
        client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                try {
                    client.subscribe(base + "#", 1);
                    log((reconnect ? "reconnected" : "connected") + " to " + serverURI + " as " + clientId);
                    publish("ota/inform", JSON.createObjectNode().put("version", firmwareVersion));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log("connection lost: " + cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                try {
                    onDownlink(topic.substring(base.length()), JSON.readTree(message.getPayload()));
                } catch (Exception e) {
                    System.err.println("[" + deviceName + "] bad downlink on " + topic + ": " + e);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });
        MqttConnectOptions o = new MqttConnectOptions();
        o.setUserName(deviceName + "&" + productKey);
        o.setPassword(password.toCharArray());
        o.setAutomaticReconnect(true);
        o.setCleanSession(true);
        o.setKeepAliveInterval(30);
        client.connect(o);

        // a random start offset spreads many devices over the reporting interval
        long offsetMs = 1000 + random.nextInt(Math.max(1, intervalSeconds * 1000));
        scheduler.scheduleAtFixedRate(this::report, offsetMs, intervalSeconds * 1000L, TimeUnit.MILLISECONDS);
    }

    private void report() {
        try {
            // random walk with a slow pull back to 24, an occasional spike so rules have something to fire on
            temperature += (24 - temperature) * 0.05 + (random.nextDouble() - 0.5) * 2.0;
            if (random.nextInt(40) == 0) {
                temperature += 40;
            }
            temperature = Math.round(temperature * 10) / 10.0;
            humidity = Math.max(0, Math.min(100, humidity + random.nextInt(5) - 2));
            ObjectNode params = JSON.createObjectNode();
            params.put("temperature", temperature);
            params.put("humidity", humidity);
            params.put("mode", mode);
            params.put("fan", fan);
            ObjectNode msg = JSON.createObjectNode();
            msg.put("id", Long.toString(System.nanoTime()));
            msg.put("ts", System.currentTimeMillis());
            msg.set("params", params);
            publish("thing/property/post", msg);
            if (!quiet) {
                log("reported " + params);
            }
            if (temperature > 70) {
                publish("thing/event/overheat/post", JSON.createObjectNode().put("ts", System.currentTimeMillis())
                        .set("params", JSON.createObjectNode().put("temp", temperature)));
            }
        } catch (Exception e) {
            System.err.println("[" + deviceName + "] report failed: " + e);
        }
    }

    private void onDownlink(String suffix, JsonNode payload) {
        if (!quiet) {
            log("<- " + suffix + " " + payload);
        }
        if (suffix.startsWith("thing/service/") && suffix.endsWith("/invoke")) {
            String service = suffix.substring("thing/service/".length(), suffix.length() - "/invoke".length());
            if ("fanOn".equals(service)) {
                fan = true;
            } else if ("fanOff".equals(service)) {
                fan = false;
            } else if ("reboot".equals(service)) {
                temperature = 24.0;
            }
            ObjectNode reply = JSON.createObjectNode().put("id", payload.path("id").asText()).put("code", 200).put("msg", "ok");
            publish("thing/service/" + service + "/reply", reply);
        } else if (suffix.equals("thing/property/set")) {
            JsonNode p = payload.path("params");
            if (p.has("mode")) {
                mode = p.get("mode").asText();
            }
            if (p.has("fan")) {
                fan = p.get("fan").asBoolean();
            }
            report(); // report the applied values so the platform clears the desired state
        } else if (suffix.equals("ota/upgrade")) {
            scheduler.execute(() -> upgrade(payload));
        }
    }

    private void upgrade(JsonNode job) {
        String version = job.path("version").asText();
        try {
            progress(version, 10, "downloading");
            HttpResponse<byte[]> resp = http.send(HttpRequest.newBuilder(URI.create(job.path("url").asText())).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                progress(version, -1, "download failed: HTTP " + resp.statusCode());
                return;
            }
            progress(version, 60, "verifying");
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resp.body()));
            if (!sha.equalsIgnoreCase(job.path("sha256").asText())) {
                progress(version, -1, "checksum mismatch");
                return;
            }
            progress(version, 90, "flashing");
            Thread.sleep(1000);
            firmwareVersion = version;
            progress(version, 100, "done");
            publish("ota/inform", JSON.createObjectNode().put("version", version));
        } catch (Exception e) {
            progress(version, -1, e.toString());
        }
    }

    private void progress(String version, int step, String desc) {
        publish("ota/progress", JSON.createObjectNode().put("version", version).put("step", step).put("desc", desc));
    }

    private void publish(String suffix, JsonNode json) {
        try {
            MqttMessage m = new MqttMessage(JSON.writeValueAsBytes(json));
            m.setQos(1);
            client.publish(base + suffix, m);
        } catch (Exception e) {
            System.err.println("[" + deviceName + "] publish " + suffix + " failed: " + e);
        }
    }

    private static String hmacHex(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                m.put(args[i].substring(2), args[++i]);
            }
        }
        return m;
    }

    private static String require(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isBlank()) {
            System.err.println("missing --" + key);
            System.exit(2);
        }
        return v;
    }
}
