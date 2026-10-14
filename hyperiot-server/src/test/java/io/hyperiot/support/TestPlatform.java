package io.hyperiot.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.common.Json;
import io.hyperiot.device.DeviceCredentials;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Helpers shared by the integration tests: a REST client for the console API and a simulated MQTT device. */
public final class TestPlatform {

    public static final String ADMIN_PASSWORD = "test-admin-pass-1";
    public static final String JWT_SECRET = "test-jwt-secret-test-jwt-secret-0123456789";

    private TestPlatform() {
    }

    public static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Console API client authenticated as admin. */
    public static final class Api {
        private final TestRestTemplate rest;
        private String token;

        public Api(TestRestTemplate rest) {
            this.rest = rest;
        }

        public Api login() {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> r = rest.exchange("/api/auth/login", HttpMethod.POST,
                    new HttpEntity<>(Json.object().put("username", "admin").put("password", ADMIN_PASSWORD).toString(), h), String.class);
            if (!r.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("login failed: " + r.getStatusCode() + " " + r.getBody());
            }
            token = Json.tree(r.getBody()).get("token").asText();
            return this;
        }

        public String token() {
            return token;
        }

        public Response call(HttpMethod method, String path, Object body) {
            HttpHeaders h = new HttpHeaders();
            if (token != null) {
                h.setBearerAuth(token);
            }
            h.setContentType(MediaType.APPLICATION_JSON);
            String json = body == null ? null : body instanceof String s ? s : Json.write(body);
            ResponseEntity<String> r = rest.exchange(path, method, new HttpEntity<>(json, h), String.class);
            return new Response(r.getStatusCode().value(), r.getBody());
        }

        public Response get(String path) {
            return call(HttpMethod.GET, path, null);
        }

        public Response post(String path, Object body) {
            return call(HttpMethod.POST, path, body);
        }

        public Response put(String path, Object body) {
            return call(HttpMethod.PUT, path, body);
        }

        public Response delete(String path) {
            return call(HttpMethod.DELETE, path, null);
        }

        public Response upload(String path, byte[] content, String filename, MultiValueMap<String, String> fields) {
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(token);
            h.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            fields.forEach((k, v) -> body.add(k, v.get(0)));
            body.add("file", new ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            ResponseEntity<String> r = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
            return new Response(r.getStatusCode().value(), r.getBody());
        }
    }

    public record Response(int status, String body) {
        public JsonNode json() {
            return body == null || body.isBlank() ? null : Json.tree(body);
        }
    }

    public record Received(String topic, JsonNode payload) {
    }

    /** A simulated MQTT device that records everything it receives. */
    public static final class MqttDevice implements AutoCloseable {
        public final String productKey;
        public final String deviceName;
        public final BlockingQueue<Received> inbox = new LinkedBlockingQueue<>();
        private final MqttClient client;

        /** Connects with the correct HMAC password derived from the device secret. */
        public static MqttDevice connect(int port, String productKey, String deviceName, String secret) throws MqttException {
            return new MqttDevice(port, productKey, deviceName, DeviceCredentials.mqttPassword(secret, productKey, deviceName));
        }

        /** Connects with an arbitrary password (for negative tests). */
        public static MqttDevice connectWithPassword(int port, String productKey, String deviceName, String password) throws MqttException {
            return new MqttDevice(port, productKey, deviceName, password);
        }

        private MqttDevice(int port, String productKey, String deviceName, String password) throws MqttException {
            this.productKey = productKey;
            this.deviceName = deviceName;
            this.client = new MqttClient("tcp://localhost:" + port, DeviceCredentials.clientId(productKey, deviceName), new MemoryPersistence());
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    inbox.add(new Received(topic, Json.tree(message.getPayload())));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            MqttConnectOptions o = new MqttConnectOptions();
            o.setUserName(DeviceCredentials.username(productKey, deviceName));
            o.setPassword(password.toCharArray());
            o.setCleanSession(true);
            o.setAutomaticReconnect(false);
            o.setConnectionTimeout(5);
            client.connect(o);
        }

        public void subscribeAll() throws MqttException {
            client.subscribe("hyperiot/" + productKey + "/" + deviceName + "/#", 1);
        }

        public void publish(String suffix, String json) throws MqttException {
            MqttMessage m = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
            m.setQos(1);
            client.publish("hyperiot/" + productKey + "/" + deviceName + "/" + suffix, m);
        }

        public MqttClient client() {
            return client;
        }

        /** Waits for a message whose topic ends with {@code suffix}; others are skipped. */
        public Received await(String suffix, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Received r = inbox.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (r == null) {
                    return null;
                }
                if (r.topic().endsWith(suffix)) {
                    return r;
                }
            }
            return null;
        }

        public boolean isConnected() {
            return client.isConnected();
        }

        @Override
        public void close() {
            try {
                if (client.isConnected()) {
                    client.disconnect(1000);
                }
                client.close();
            } catch (MqttException ignored) {
                // best effort in tests
            }
        }
    }
}
