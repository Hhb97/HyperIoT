package io.hyperiot;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.common.Hmac;
import io.hyperiot.common.Json;
import io.hyperiot.support.IntegrationTestBase;
import io.hyperiot.support.TestPlatform;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class GatewaysAndOtaIT extends IntegrationTestBase {

    // ------------------------------------------------------------------ HTTP gateway

    private ResponseEntity<String> httpPost(Fixture f, String path, String body, String signature) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            h.set("X-Signature", signature);
        }
        return rest.exchange("/device-api/" + f.productKey() + "/" + f.deviceName() + path, HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    @Test
    void httpGatewayAcceptsSignedPropertyReports() {
        Fixture f = createFixture();
        String body = "{\"params\":{\"temperature\":22.5}}";
        ResponseEntity<String> ok = httpPost(f, "/property", body, Hmac.sha256Hex(f.secret(), body));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(shadow(f).path("reported").path("temperature").path("value").asDouble()).isEqualTo(22.5));
        assertThat(device(f).path("status").asText()).isEqualTo("ONLINE");
    }

    @Test
    void httpGatewayRejectsBadSignatureAndUnknownDevicesAlike() {
        Fixture f = createFixture();
        String body = "{\"params\":{\"temperature\":22.5}}";
        assertThat(httpPost(f, "/property", body, "deadbeef").getStatusCode().value()).isEqualTo(401);
        assertThat(httpPost(f, "/property", body, null).getStatusCode().value()).isEqualTo(401);
        ResponseEntity<String> unknown = rest.exchange("/device-api/nope/ghost/property", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(401);
        assertThat(shadow(f).path("reported").size()).isZero();
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ------------------------------------------------------------------ TCP gateway

    @Test
    void tcpGatewayAuthenticatesReportsAndReceivesCommands() throws Exception {
        Fixture f = createFixture();
        try (Socket socket = new Socket("127.0.0.1", TCP_PORT)) {
            socket.setSoTimeout(5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            String sign = Hmac.sha256Hex(f.secret(), "productKey" + f.productKey() + "deviceName" + f.deviceName());
            out.println(Json.object().put("type", "auth").put("productKey", f.productKey()).put("deviceName", f.deviceName())
                    .put("sign", sign));
            assertThat(Json.tree(in.readLine()).path("code").asInt()).isEqualTo(200);
            await().atMost(Duration.ofSeconds(5)).until(() -> "ONLINE".equals(device(f).path("status").asText()));

            out.println("{\"type\":\"ping\"}");
            assertThat(Json.tree(in.readLine()).path("type").asText()).isEqualTo("pong");

            out.println("{\"type\":\"property\",\"params\":{\"temperature\":31.0}}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(shadow(f).path("reported").path("temperature").path("value").asDouble()).isEqualTo(31.0));

            // a command reaches the TCP device as a "downlink" frame and its reply is matched
            JsonNode cmd = api.post("/api/devices/" + f.deviceId() + "/services/fanOn/invoke", "{}").json();
            JsonNode frame = Json.tree(in.readLine());
            assertThat(frame.path("type").asText()).isEqualTo("downlink");
            assertThat(frame.path("topic").asText()).endsWith("/thing/service/fanOn/invoke");
            assertThat(frame.path("payload").path("id").asText()).isEqualTo(cmd.path("requestId").asText());
            out.println("{\"type\":\"reply\",\"identifier\":\"fanOn\",\"id\":\"" + cmd.path("requestId").asText() + "\",\"code\":200}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(api.get("/api/commands?status=SUCCESS").body()).contains(cmd.path("requestId").asText()));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> "OFFLINE".equals(device(f).path("status").asText()));
    }

    @Test
    void tcpGatewayRejectsBadSignature() throws Exception {
        Fixture f = createFixture();
        try (Socket socket = new Socket("127.0.0.1", TCP_PORT)) {
            socket.setSoTimeout(5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(Json.object().put("type", "auth").put("productKey", f.productKey()).put("deviceName", f.deviceName())
                    .put("sign", "wrong"));
            assertThat(Json.tree(in.readLine()).path("code").asInt()).isEqualTo(401);
            assertThat(in.readLine()).as("connection closed after failed auth").isNull();
        }
    }

    // ------------------------------------------------------------------ OTA

    @Test
    void otaUpgradeEndToEnd() throws Exception {
        Fixture f = createFixture();
        byte[] firmware = new byte[4096];
        new java.util.Random(7).nextBytes(firmware);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(firmware));

        MultiValueMap<String, String> fields = new LinkedMultiValueMap<>();
        fields.add("productKey", f.productKey());
        fields.add("version", "2.0.0");
        fields.add("name", "release 2");
        TestPlatform.Response up = api.upload("/api/firmwares", firmware, "fw.bin", fields);
        assertThat(up.status()).as(up.body()).isEqualTo(200);
        JsonNode fw = up.json();
        assertThat(fw.path("sha256").asText()).isEqualTo(sha);
        assertThat(fw.path("size").asLong()).isEqualTo(4096);
        // same version twice is a conflict
        assertThat(api.upload("/api/firmwares", firmware, "fw.bin", fields).status()).isEqualTo(409);

        try (TestPlatform.MqttDevice d = connect(f)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> "ONLINE".equals(device(f).path("status").asText()));
            d.publish("ota/inform", "{\"version\":\"1.0.0\"}");
            await().atMost(Duration.ofSeconds(5)).until(() -> "1.0.0".equals(device(f).path("firmwareVersion").asText()));

            TestPlatform.Response created = api.post("/api/ota/tasks",
                    Json.object().put("name", "upgrade").put("firmwareId", fw.path("id").asLong()).toString());
            assertThat(created.status()).as(created.body()).isEqualTo(200);
            long taskId = created.json().path("id").asLong();

            var upgrade = d.await("ota/upgrade", 5000);
            assertThat(upgrade).as("upgrade instruction delivered").isNotNull();
            assertThat(upgrade.payload().path("version").asText()).isEqualTo("2.0.0");
            assertThat(upgrade.payload().path("sha256").asText()).isEqualTo(sha);

            // the signed URL works without a bearer token and serves the exact bytes
            String url = upgrade.payload().path("url").asText();
            String relative = url.substring(url.indexOf("/device-api"));
            ResponseEntity<byte[]> dl = rest.getForEntity(relative, byte[].class);
            assertThat(dl.getStatusCode().value()).isEqualTo(200);
            assertThat(dl.getBody()).isEqualTo(firmware);
            // a tampered signature is refused
            assertThat(rest.getForEntity(relative.replaceAll("sig=.{6}", "sig=000000"), String.class).getStatusCode().value()).isEqualTo(401);

            d.publish("ota/progress", "{\"version\":\"2.0.0\",\"step\":50}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode t = api.get("/api/ota/tasks/" + taskId).json();
                assertThat(t.path("devices").path(0).path("status").asText()).isEqualTo("DOWNLOADING");
                assertThat(t.path("devices").path(0).path("progress").asInt()).isEqualTo(50);
            });
            d.publish("ota/progress", "{\"version\":\"2.0.0\",\"step\":100}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode t = api.get("/api/ota/tasks/" + taskId).json();
                assertThat(t.path("status").asText()).isEqualTo("COMPLETED");
                assertThat(t.path("success").asInt()).isEqualTo(1);
                assertThat(device(f).path("firmwareVersion").asText()).isEqualTo("2.0.0");
            });
        }
        // a firmware that tasks refer to cannot be deleted
        assertThat(api.delete("/api/firmwares/" + fw.path("id").asLong()).status()).isEqualTo(409);
    }

    @Test
    void otaFailureIsReportedAndOfflineDevicesWaitForReconnect() throws Exception {
        Fixture f = createFixture();
        MultiValueMap<String, String> fields = new LinkedMultiValueMap<>();
        fields.add("productKey", f.productKey());
        fields.add("version", "3.1");
        JsonNode fw = api.upload("/api/firmwares", new byte[]{1, 2, 3, 4}, "fw.bin", fields).json();

        // device is offline when the task is created
        JsonNode task = api.post("/api/ota/tasks", Json.object().put("firmwareId", fw.path("id").asLong()).toString()).json();
        assertThat(task.path("devices").path(0).path("status").asText()).isEqualTo("PENDING");

        try (TestPlatform.MqttDevice d = connect(f)) {
            var upgrade = d.await("ota/upgrade", 5000);
            assertThat(upgrade).as("pending upgrade pushed on reconnect").isNotNull();
            d.publish("ota/progress", "{\"version\":\"3.1\",\"step\":-1,\"desc\":\"flash write error\"}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode t = api.get("/api/ota/tasks/" + task.path("id").asLong()).json();
                assertThat(t.path("devices").path(0).path("status").asText()).isEqualTo("FAILED");
                assertThat(t.path("devices").path(0).path("message").asText()).contains("flash write error");
                assertThat(t.path("status").asText()).isEqualTo("COMPLETED");
                assertThat(t.path("failed").asInt()).isEqualTo(1);
            });
        }
    }

    // ------------------------------------------------------------------ console security + websocket

    @Test
    void consoleApiRequiresAuthentication() {
        assertThat(rest.getForEntity("/api/products", String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(rest.getForEntity("/api/devices/1", String.class).getStatusCode().value()).isEqualTo(401);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("not-a-token");
        assertThat(rest.exchange("/api/products", HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode().value()).isEqualTo(401);
        // wrong credentials
        ResponseEntity<String> bad = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"wrong\"}", jsonHeaders()), String.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(401);
        // the secret of a device is never part of list responses
        Fixture f = createFixture();
        assertThat(api.get("/api/devices?productKey=" + f.productKey()).body()).doesNotContain(f.secret());
        assertThat(api.get("/api/devices/" + f.deviceId()).body()).contains(f.secret());
    }

    @Test
    void webSocketPushesTelemetryToAuthenticatedClients() throws Exception {
        Fixture f = createFixture();
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + httpPort + "/ws"), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            received.add(buf.toString());
                            buf.setLength(0);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }
                }).get(5, TimeUnit.SECONDS);
        ws.sendText(Json.object().put("type", "auth").put("token", api.token()).toString(), true).get(5, TimeUnit.SECONDS);
        assertThat(received.poll(5, TimeUnit.SECONDS)).contains("ready");

        try (TestPlatform.MqttDevice d = connect(f)) {
            d.publish("thing/property/post", "{\"params\":{\"temperature\":27.5}}");
            boolean sawTelemetry = false;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && !sawTelemetry) {
                String msg = received.poll(500, TimeUnit.MILLISECONDS);
                sawTelemetry = msg != null && msg.contains("\"telemetry\"") && msg.contains(f.deviceName()) && msg.contains("27.5");
            }
            assertThat(sawTelemetry).isTrue();
        }
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void webSocketWithoutValidTokenIsClosed() throws Exception {
        BlockingQueue<Integer> closed = new LinkedBlockingQueue<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + httpPort + "/ws"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closed.add(statusCode);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        ws.sendText("{\"type\":\"auth\",\"token\":\"forged\"}", true).get(5, TimeUnit.SECONDS);
        assertThat(closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1008);
    }
}
