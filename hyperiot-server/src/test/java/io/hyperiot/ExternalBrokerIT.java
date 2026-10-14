package io.hyperiot;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.common.Json;
import io.hyperiot.device.DeviceCredentials;
import io.hyperiot.support.IntegrationTestBase;
import io.hyperiot.support.TestPlatform;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The platform in "external broker" mode (as used with EMQX): a plain MQTT broker stands in for EMQX, devices
 * are authenticated through the HTTP hooks, and the platform talks to the broker as an ordinary client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExternalBrokerIT {

    private static final int BROKER_PORT = TestPlatform.freePort();
    private static final String HOOK_TOKEN = "test-hook-token-0123456789";
    private static final Server BROKER = new Server();

    static {
        Properties p = new Properties();
        p.setProperty("host", "127.0.0.1");
        p.setProperty("port", Integer.toString(BROKER_PORT));
        p.setProperty("allow_anonymous", "true");
        p.setProperty("persistence_enabled", "false");
        p.setProperty("telemetry_enabled", "false");
        p.setProperty("use_bugsnag", "false");
        p.setProperty("websocket_port", "disabled");
        try {
            BROKER.startServer(new MemoryConfig(p));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:hyperiot-ext-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        r.add("server.shutdown", () -> "immediate");
        r.add("hyperiot.mqtt.mode", () -> "external");
        r.add("hyperiot.mqtt.broker-url", () -> "tcp://127.0.0.1:" + BROKER_PORT);
        r.add("hyperiot.mqtt.password", () -> "platform-secret-123");
        r.add("hyperiot.mqtt.subscription", () -> "hyperiot/#"); // the stand-in broker has no shared subscriptions
        r.add("hyperiot.security.admin-password", () -> TestPlatform.ADMIN_PASSWORD);
        r.add("hyperiot.security.jwt-secret", () -> TestPlatform.JWT_SECRET);
        r.add("hyperiot.security.hook-token", () -> HOOK_TOKEN);
        r.add("hyperiot.ota.directory", () -> System.getProperty("java.io.tmpdir") + "/hyperiot-ota-ext");
    }

    @AfterAll
    static void stopBroker() {
        BROKER.stopServer();
    }

    @Autowired
    TestRestTemplate rest;

    private JsonNode hook(String path, String token, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.set("X-Hook-Token", token);
        }
        var r = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(Json.write(body), h), String.class);
        return Json.object().put("status", r.getStatusCode().value()).set("body", r.getBody() == null ? null : Json.tree(r.getBody()));
    }

    private IntegrationTestBase.Fixture fixture(TestPlatform.Api api) {
        JsonNode product = api.post("/api/products", "{\"name\":\"ext\"}").json();
        api.put("/api/products/" + product.path("id").asLong() + "/thing-model", IntegrationTestBase.THING_MODEL);
        String dn = "dev-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode d = api.post("/api/devices", Json.object().put("productKey", product.path("productKey").asText()).put("deviceName", dn)).json();
        return new IntegrationTestBase.Fixture(product.path("id").asLong(), product.path("productKey").asText(), d.path("id").asLong(), dn,
                d.path("deviceSecret").asText());
    }

    @Test
    void hooksRequireTheSharedToken() {
        var body = Json.object().put("clientid", "a.b").put("username", "b&a").put("password", "x");
        assertThat(hook("/api/mqtt/auth", null, body).path("status").asInt()).isEqualTo(403);
        assertThat(hook("/api/mqtt/auth", "wrong-token", body).path("status").asInt()).isEqualTo(403);
        assertThat(hook("/api/mqtt/auth", HOOK_TOKEN, body).path("status").asInt()).isEqualTo(200);
    }

    @Test
    void authHookChecksDeviceSignatures() {
        TestPlatform.Api api = new TestPlatform.Api(rest).login();
        var f = fixture(api);
        String clientId = DeviceCredentials.clientId(f.productKey(), f.deviceName());
        String username = DeviceCredentials.username(f.productKey(), f.deviceName());
        String good = DeviceCredentials.mqttPassword(f.secret(), f.productKey(), f.deviceName());

        assertThat(hook("/api/mqtt/auth", HOOK_TOKEN, Json.object().put("clientid", clientId).put("username", username).put("password", good))
                .path("body").path("result").asText()).isEqualTo("allow");
        assertThat(hook("/api/mqtt/auth", HOOK_TOKEN, Json.object().put("clientid", clientId).put("username", username).put("password", "bad"))
                .path("body").path("result").asText()).isEqualTo("deny");
        assertThat(hook("/api/mqtt/auth", HOOK_TOKEN, Json.object().put("clientid", "nope.ghost").put("username", "ghost&nope").put("password", good))
                .path("body").path("result").asText()).isEqualTo("deny");
        // the platform's own connection is a superuser
        var platform = hook("/api/mqtt/auth", HOOK_TOKEN, Json.object().put("clientid", "hyperiot-server").put("username", "hyperiot-platform")
                .put("password", "platform-secret-123")).path("body");
        assertThat(platform.path("result").asText()).isEqualTo("allow");
        assertThat(platform.path("is_superuser").asBoolean()).isTrue();

        // ACL
        String own = "hyperiot/" + f.productKey() + "/" + f.deviceName();
        assertThat(hook("/api/mqtt/acl", HOOK_TOKEN, Json.object().put("clientid", clientId).put("topic", own + "/thing/property/post").put("action", "publish"))
                .path("body").path("result").asText()).isEqualTo("allow");
        assertThat(hook("/api/mqtt/acl", HOOK_TOKEN, Json.object().put("clientid", clientId).put("topic", "hyperiot/other/dev/thing/property/post").put("action", "publish"))
                .path("body").path("result").asText()).isEqualTo("deny");
        assertThat(hook("/api/mqtt/acl", HOOK_TOKEN, Json.object().put("clientid", clientId).put("topic", own + "/#").put("action", "subscribe"))
                .path("body").path("result").asText()).isEqualTo("allow");
        assertThat(hook("/api/mqtt/acl", HOOK_TOKEN, Json.object().put("clientid", clientId).put("topic", "#").put("action", "subscribe"))
                .path("body").path("result").asText()).isEqualTo("deny");
    }

    @Test
    void uplinkAndDownlinkFlowThroughTheExternalBroker() throws Exception {
        TestPlatform.Api api = new TestPlatform.Api(rest).login();
        var f = fixture(api);
        // wait until the platform itself is connected to the broker
        await().atMost(Duration.ofSeconds(15)).until(() -> BROKER.listConnectedClients().stream().anyMatch(c -> "hyperiot-server".equals(c.getClientID())));

        try (TestPlatform.MqttDevice d = TestPlatform.MqttDevice.connectWithPassword(BROKER_PORT, f.productKey(), f.deviceName(), "ignored-by-test-broker")) {
            d.subscribeAll();
            // the broker would announce the connection; the hook is the other way to deliver the same fact
            hook("/api/mqtt/event", HOOK_TOKEN, Json.object().put("event", "client.connected")
                    .put("clientid", DeviceCredentials.clientId(f.productKey(), f.deviceName())).put("peername", "10.1.2.3:51234"));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode dev = api.get("/api/devices/" + f.deviceId()).json();
                assertThat(dev.path("status").asText()).isEqualTo("ONLINE");
                assertThat(dev.path("ip").asText()).isEqualTo("10.1.2.3");
            });

            d.publish("thing/property/post", "{\"id\":\"1\",\"params\":{\"temperature\":33.5}}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(api.get("/api/devices/" + f.deviceId() + "/shadow").json().path("reported").path("temperature").path("value").asDouble()).isEqualTo(33.5));
            assertThat(d.await("thing/property/post_reply", 5000)).isNotNull();

            JsonNode cmd = api.post("/api/devices/" + f.deviceId() + "/services/fanOn/invoke", "{}").json();
            var invoke = d.await("thing/service/fanOn/invoke", 5000);
            assertThat(invoke).as("command published through the external broker").isNotNull();
            assertThat(invoke.payload().path("id").asText()).isEqualTo(cmd.path("requestId").asText());

            hook("/api/mqtt/event", HOOK_TOKEN, Json.object().put("event", "client.disconnected")
                    .put("clientid", DeviceCredentials.clientId(f.productKey(), f.deviceName())));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(api.get("/api/devices/" + f.deviceId()).json().path("status").asText()).isEqualTo("OFFLINE"));
        }
    }
}
