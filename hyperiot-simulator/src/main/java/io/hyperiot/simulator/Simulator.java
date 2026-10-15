package io.hyperiot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hivemq.client.mqtt.MqttClientExecutorConfig;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Simulated devices for trying out the platform: they report temperature / humidity periodically, answer service
 * invocations, apply property settings and perform OTA upgrades (download, verify, report progress).
 *
 * <pre>
 * # one device
 * java -jar hyperiot-simulator.jar --product-key abc123 --device-name dev1 --secret ... [--host localhost] [--port 1883] [--interval 5]
 *
 * # a fleet in one process: a CSV with productKey,deviceName,deviceSecret (the file the console's "batch create" exports),
 * # or deviceName,deviceSecret together with --product-key
 * java -Xmx2g -jar hyperiot-simulator.jar --devices-file devices.csv --host &lt;broker-host&gt; --interval 5 --connect-rate 200
 * </pre>
 *
 * Options: {@code --interval} seconds between reports (decimals allowed), {@code --connect-rate} new connections per second
 * (default 200), {@code --limit} only the first N devices of the file, {@code --qos} 0 or 1 (default 1), {@code --keepalive}
 * seconds (default 60), {@code --quiet} true/false (default: on for more than 20 devices), {@code --stagger-ms} the old way to
 * say {@code --connect-rate} (1000 / stagger-ms).
 *
 * <p>All devices share one Netty event loop, one scheduler and one HTTP client, so 10,000 devices need a handful of threads and
 * a few hundred MB of heap instead of tens of thousands of threads. While running it prints one status line every 5 seconds.
 *
 * <p>The secret of a single device can also be given through the HYPERIOT_DEVICE_SECRET environment variable, which keeps it
 * out of the process list.
 */
public final class Simulator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_NOTES = 20;

    private final String host;
    private final int port;
    private final long intervalMs;
    private final String firmware;
    private final boolean quiet;
    private final MqttQos qos;
    private final int keepAlive;
    private final int connectRate;

    private final ScheduledThreadPoolExecutor scheduler;
    /** OTA downloads block, so they get their own small pool instead of stalling the report scheduler. */
    private final ThreadPoolExecutor otaPool;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final MqttClientExecutorConfig executorConfig;

    private final List<Device> devices = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Stats stats = new Stats();
    private final AtomicInteger notes = new AtomicInteger();
    private volatile boolean stopping;

    private Simulator(Map<String, String> a, int deviceCount) {
        this.host = a.getOrDefault("host", "localhost");
        this.port = Integer.parseInt(a.getOrDefault("port", "1883"));
        this.intervalMs = Math.max(100, Math.round(Double.parseDouble(a.getOrDefault("interval", "5")) * 1000));
        this.firmware = a.getOrDefault("firmware", "1.0.0");
        this.quiet = Boolean.parseBoolean(a.getOrDefault("quiet", Boolean.toString(deviceCount > 20)));
        this.qos = "0".equals(a.get("qos")) ? MqttQos.AT_MOST_ONCE : MqttQos.AT_LEAST_ONCE;
        this.keepAlive = Integer.parseInt(a.getOrDefault("keepalive", "60"));
        int rate = Integer.parseInt(a.getOrDefault("connect-rate", "200"));
        if (a.containsKey("stagger-ms")) {
            long stagger = Long.parseLong(a.get("stagger-ms"));
            rate = stagger <= 0 ? Integer.MAX_VALUE / 10 : (int) Math.max(1, 1000 / stagger);
        }
        this.connectRate = deviceCount == 1 ? Integer.MAX_VALUE / 10 : rate;

        int cores = Runtime.getRuntime().availableProcessors();
        this.scheduler = new ScheduledThreadPoolExecutor(Math.max(2, cores), daemon("sim-scheduler"));
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.otaPool = new ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), daemon("sim-ota"));
        this.otaPool.allowCoreThreadTimeOut(true);
        // one config object for every client: they then share the same Netty event loop
        this.executorConfig = MqttClientExecutorConfig.builder().nettyThreads(Math.min(cores, 8)).build();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);

        List<String[]> list = new ArrayList<>(); // {productKey, deviceName, secret}
        if (a.containsKey("devices-file")) {
            list.addAll(readDevices(Path.of(a.get("devices-file")), a.get("product-key")));
            if (list.isEmpty()) {
                System.err.println("no devices found in " + a.get("devices-file"));
                System.exit(2);
            }
        } else {
            String secret = a.getOrDefault("secret", System.getenv("HYPERIOT_DEVICE_SECRET"));
            if (secret == null || secret.isBlank()) {
                System.err.println("missing --secret (or HYPERIOT_DEVICE_SECRET), or use --devices-file");
                System.exit(2);
            }
            list.add(new String[]{require(a, "product-key"), require(a, "device-name"), secret});
        }
        list = unique(list);
        if (a.containsKey("limit")) {
            list = list.subList(0, Math.min(list.size(), Integer.parseInt(a.get("limit"))));
        }

        Simulator sim = new Simulator(a, list.size());
        Runtime.getRuntime().addShutdownHook(new Thread(sim::stop));
        sim.run(list);
        Thread.currentThread().join();
    }

    /** CSV lines: productKey,deviceName,deviceSecret or deviceName,deviceSecret (then --product-key is used). */
    static List<String[]> readDevices(Path file, String defaultProductKey) throws java.io.IOException {
        List<String[]> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.replace("﻿", "").trim();
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

    /** The same client id twice makes the broker kick one of them out again and again, so a duplicate line is dropped. */
    static List<String[]> unique(List<String[]> in) {
        Set<String> seen = new HashSet<>();
        List<String[]> out = new ArrayList<>(in.size());
        int dup = 0;
        for (String[] d : in) {
            if (seen.add(d[0] + "." + d[1])) {
                out.add(d);
            } else {
                dup++;
            }
        }
        if (dup > 0) {
            System.err.println("ignored " + dup + " duplicate device line(s)");
        }
        return out;
    }

    // ------------------------------------------------------------------ fleet

    private void run(List<String[]> list) {
        System.out.println("starting " + list.size() + " simulated device(s) against tcp://" + host + ":" + port
                + ", connecting " + (connectRate > 100_000 ? "all at once" : connectRate + "/s")
                + ", report every " + intervalMs / 1000.0 + " s (about " + Math.round(list.size() * 1000.0 / intervalMs) + " msg/s), QoS "
                + qos.getCode());
        long started = System.currentTimeMillis();
        int[] next = {0};
        double[] tokens = {0};
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (stopping) {
                return;
            }
            try {
                tokens[0] += connectRate / 10.0;
                while (tokens[0] >= 1 && next[0] < list.size()) {
                    String[] d = list.get(next[0]++);
                    tokens[0]--;
                    try {
                        Device dev = new Device(d[0], d[1], d[2]);
                        devices.add(dev);
                        dev.connect();
                    } catch (Exception e) {
                        stats.connectFailed.increment();
                        note("[" + d[1] + "] could not start: " + e);
                    }
                }
                if (next[0] < list.size()) {
                    scheduler.schedule(tick[0], 100, TimeUnit.MILLISECONDS);
                } else {
                    System.out.println("all " + list.size() + " devices started connecting in "
                            + (System.currentTimeMillis() - started) / 1000 + " s");
                }
            } catch (RuntimeException e) {
                System.err.println("connect loop failed: " + e);
            }
        };
        scheduler.execute(tick[0]);
        scheduler.scheduleAtFixedRate(this::printStats, 5, 5, TimeUnit.SECONDS);
    }

    private long lastPublished;
    private long lastAcked;
    private long lastTime = System.nanoTime();

    private void printStats() {
        long now = System.nanoTime();
        double sec = (now - lastTime) / 1e9;
        long published = stats.published.sum();
        long acked = stats.acked.sum();
        Runtime rt = Runtime.getRuntime();
        System.out.printf("[stats] online %d/%d | sent %.0f/s acked %.0f/s failed %d skipped %d | down %d | refused %d "
                        + "conn-failed %d reconnects %d | heap %d MB%n",
                stats.online.get(), devices.size(), (published - lastPublished) / sec, (acked - lastAcked) / sec,
                stats.failed.sum(), stats.skipped.sum(), stats.downlinks.sum(), stats.refused.sum(),
                stats.connectFailed.sum(), stats.reconnects.sum(), (rt.totalMemory() - rt.freeMemory()) >> 20);
        lastPublished = published;
        lastAcked = acked;
        lastTime = now;
    }

    private void stop() {
        stopping = true;
        scheduler.shutdownNow();
        List<CompletableFuture<Void>> done = new ArrayList<>();
        for (Device d : devices) {
            try {
                done.add(d.client.disconnect().handle((v, e) -> null));
            } catch (RuntimeException ignored) {
                // never connected
            }
        }
        try {
            CompletableFuture.allOf(done.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // exiting anyway
        }
    }

    /** Failures worth reading are printed, but only the first few: 10,000 devices failing the same way is one problem. */
    private void note(String message) {
        if (notes.incrementAndGet() <= MAX_NOTES) {
            System.err.println(message + (notes.get() == MAX_NOTES ? "  (further messages are only counted)" : ""));
        }
    }

    private static final class Stats {
        final AtomicInteger online = new AtomicInteger();
        final LongAdder published = new LongAdder();
        final LongAdder acked = new LongAdder();
        final LongAdder failed = new LongAdder();
        final LongAdder skipped = new LongAdder();
        final LongAdder downlinks = new LongAdder();
        final LongAdder refused = new LongAdder();
        final LongAdder connectFailed = new LongAdder();
        final LongAdder reconnects = new LongAdder();
    }

    // ------------------------------------------------------------------ one device

    private final class Device {
        final String productKey;
        final String deviceName;
        final String base;
        final Mqtt3AsyncClient client;
        volatile boolean connected;
        boolean everConnected;
        double temperature = 24.0;
        int humidity = 55;
        volatile String mode = "auto";
        volatile boolean fan;
        volatile String firmwareVersion = firmware;
        ScheduledFuture<?> reporting;

        Device(String productKey, String deviceName, String secret) throws Exception {
            this.productKey = productKey;
            this.deviceName = deviceName;
            this.base = "hyperiot/" + productKey + "/" + deviceName + "/";
            String clientId = productKey + "." + deviceName;
            // password = HMAC-SHA256(secret, "clientId" + clientId + "deviceName" + dn + "productKey" + pk), the secret itself is never sent
            String password = hmacHex(secret, "clientId" + clientId + "deviceName" + deviceName + "productKey" + productKey);
            this.client = Mqtt3Client.builder()
                    .identifier(clientId)
                    .serverHost(host)
                    .serverPort(port)
                    .executorConfig(executorConfig)
                    .simpleAuth().username(deviceName + "&" + productKey).password(password.getBytes(StandardCharsets.UTF_8)).applySimpleAuth()
                    .addConnectedListener(c -> onConnected())
                    .addDisconnectedListener(this::onDisconnected)
                    .buildAsync();
            // one callback for the whole life of the client, so a reconnect does not register it twice
            client.publishes(MqttGlobalPublishFilter.ALL, this::onPublish);
        }

        void log(String message) {
            if (!quiet) {
                System.out.println("[" + deviceName + "] " + message);
            }
        }

        void connect() {
            client.connectWith().keepAlive(keepAlive).cleanSession(true).send().whenComplete((ack, err) -> {
                // success is handled by the connected listener, retries by the disconnected listener
                if (err != null) {
                    log("connect failed: " + err);
                }
            });
        }

        void onConnected() {
            connected = true;
            stats.online.incrementAndGet();
            boolean reconnect = everConnected;
            log(reconnect ? "reconnected" : "connected");
            // subscribe first, so that anything the platform queued for this device arrives, then announce the firmware
            // only what a device acts on; "base/#" would also echo the device's own uplink back to it and double the broker's work
            client.subscribeWith()
                    .addSubscription().topicFilter(base + "thing/property/set").qos(MqttQos.AT_LEAST_ONCE).applySubscription()
                    .addSubscription().topicFilter(base + "thing/service/+/invoke").qos(MqttQos.AT_LEAST_ONCE).applySubscription()
                    .addSubscription().topicFilter(base + "ota/upgrade").qos(MqttQos.AT_LEAST_ONCE).applySubscription()
                    .send().whenComplete((ack, err) -> {
                if (err != null) {
                    stats.failed.increment();
                    note("[" + deviceName + "] subscribe failed: " + err);
                    return;
                }
                publish("ota/inform", JSON.createObjectNode().put("version", firmwareVersion));
            });
            if (!everConnected) {
                everConnected = true;
                // a random start offset spreads many devices over the reporting interval
                long offset = 1000 + ThreadLocalRandom.current().nextLong(intervalMs);
                reporting = scheduler.scheduleAtFixedRate(this::report, offset, intervalMs, TimeUnit.MILLISECONDS);
            }
        }

        void onDisconnected(MqttClientDisconnectedContext ctx) {
            if (connected) {
                connected = false;
                stats.online.decrementAndGet();
            }
            if (stopping || ctx.getSource() == MqttDisconnectSource.USER) {
                return;
            }
            Throwable cause = ctx.getCause();
            boolean refused = false;
            if (cause instanceof Mqtt3ConnAckException refusal) {
                refused = true;
                stats.refused.increment();
                note("[" + deviceName + "] connection refused: " + refusal.getMqttMessage().getReturnCode()
                        + " (EMQX also answers this while the platform's auth hook cannot be reached)");
            } else if (!everConnected) {
                stats.connectFailed.increment();
                note("[" + deviceName + "] could not connect: " + (cause == null ? ctx.getSource() : cause.toString()));
            } else {
                log("connection lost: " + (cause == null ? ctx.getSource() : cause.toString()));
            }
            // exponential backoff with jitter, so thousands of devices do not come back in the same second after an outage;
            // a refused device tries again every 10-30 s: wrong credentials stay wrong, but a refusal can also be temporary
            long attempts = ctx.getReconnector().getAttempts();
            long delay = refused ? 10_000 + ThreadLocalRandom.current().nextLong(20_000)
                    : Math.min(30_000, 1000L << Math.min(attempts, 5)) + ThreadLocalRandom.current().nextLong(1000);
            stats.reconnects.increment();
            ctx.getReconnector().reconnect(true).delay(delay, TimeUnit.MILLISECONDS);
        }

        synchronized void report() {
            if (!connected) {
                stats.skipped.increment();
                return;
            }
            ThreadLocalRandom r = ThreadLocalRandom.current();
            // random walk with a slow pull back to 24, an occasional spike so rules have something to fire on
            temperature += (24 - temperature) * 0.05 + (r.nextDouble() - 0.5) * 2.0;
            if (r.nextInt(40) == 0) {
                temperature += 40;
            }
            temperature = Math.round(temperature * 10) / 10.0;
            humidity = Math.max(0, Math.min(100, humidity + r.nextInt(5) - 2));
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
            log("reported " + params);
            if (temperature > 70) {
                publish("thing/event/overheat/post", JSON.createObjectNode().put("ts", System.currentTimeMillis())
                        .set("params", JSON.createObjectNode().put("temp", temperature)));
            }
        }

        void onPublish(Mqtt3Publish p) {
            stats.downlinks.increment();
            String topic = p.getTopic().toString();
            try {
                onDownlink(topic.substring(base.length()), JSON.readTree(p.getPayloadAsBytes()));
            } catch (Exception e) {
                note("[" + deviceName + "] bad downlink on " + topic + ": " + e);
            }
        }

        void onDownlink(String suffix, JsonNode payload) {
            log("<- " + suffix + " " + payload);
            if (suffix.startsWith("thing/service/") && suffix.endsWith("/invoke")) {
                String service = suffix.substring("thing/service/".length(), suffix.length() - "/invoke".length());
                if ("fanOn".equals(service)) {
                    fan = true;
                } else if ("fanOff".equals(service)) {
                    fan = false;
                } else if ("reboot".equals(service)) {
                    synchronized (this) {
                        temperature = 24.0;
                    }
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
                otaPool.execute(() -> upgrade(payload));
            }
        }

        void upgrade(JsonNode job) {
            String version = job.path("version").asText();
            try {
                progress(version, 10, "downloading");
                HttpResponse<byte[]> resp = http.send(HttpRequest.newBuilder(URI.create(job.path("url").asText()))
                        .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofByteArray());
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
                // the flashing time is simulated without holding a thread
                scheduler.schedule(() -> {
                    firmwareVersion = version;
                    progress(version, 100, "done");
                    publish("ota/inform", JSON.createObjectNode().put("version", version));
                }, 1, TimeUnit.SECONDS);
            } catch (Exception e) {
                progress(version, -1, e.toString());
            }
        }

        void progress(String version, int step, String desc) {
            publish("ota/progress", JSON.createObjectNode().put("version", version).put("step", step).put("desc", desc));
        }

        void publish(String suffix, JsonNode json) {
            try {
                stats.published.increment();
                client.publishWith().topic(base + suffix).qos(qos).payload(JSON.writeValueAsBytes(json)).send()
                        .whenComplete((r, err) -> {
                            if (err == null) {
                                stats.acked.increment();
                            } else {
                                stats.failed.increment();
                                note("[" + deviceName + "] publish " + suffix + " failed: " + err);
                            }
                        });
            } catch (Exception e) {
                stats.failed.increment();
                note("[" + deviceName + "] publish " + suffix + " failed: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
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
