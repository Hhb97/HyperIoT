package io.hyperiot.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.common.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Boots the whole platform once (embedded MQTT broker, TCP gateway, in-memory H2, in-memory bus) and shares it
 * between all integration tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// ports go through inline properties (not @DynamicPropertySource) so that a subclass with its own context can override them
@TestPropertySource(properties = {
        "hyperiot.mqtt.port=${hyperiot.test.mqtt.port}",
        "hyperiot.tcp.port=${hyperiot.test.tcp.port}",
        "hyperiot.tcp.enabled=true"
})
public abstract class IntegrationTestBase {

    protected static final int MQTT_PORT = TestPlatform.freePort();
    protected static final int TCP_PORT = TestPlatform.freePort();
    private static final Path OTA_DIR;

    static {
        System.setProperty("hyperiot.test.mqtt.port", Integer.toString(MQTT_PORT));
        System.setProperty("hyperiot.test.tcp.port", Integer.toString(TCP_PORT));
        try {
            OTA_DIR = Files.createTempDirectory("hyperiot-ota-test");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final String THING_MODEL = """
            {
              "properties": [
                {"identifier": "temperature", "name": "温度", "type": "double", "unit": "C", "min": -40, "max": 125, "access": "r"},
                {"identifier": "humidity", "name": "湿度", "type": "int", "min": 0, "max": 100, "access": "r"},
                {"identifier": "mode", "name": "模式", "type": "enum", "values": ["auto", "manual"], "access": "rw"},
                {"identifier": "fan", "name": "风扇", "type": "bool", "access": "rw"}
              ],
              "events": [
                {"identifier": "overheat", "name": "过热", "level": "WARN", "params": [{"identifier": "temp", "type": "double"}]}
              ],
              "services": [
                {"identifier": "reboot", "name": "重启", "input": [{"identifier": "delaySeconds", "type": "int", "min": 0, "max": 60}], "output": []},
                {"identifier": "fanOn", "name": "开风扇", "input": [], "output": []}
              ]
            }
            """;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:hyperiot-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        r.add("server.shutdown", () -> "immediate");
        r.add("hyperiot.mqtt.host", () -> "127.0.0.1");
        r.add("hyperiot.security.admin-password", () -> TestPlatform.ADMIN_PASSWORD);
        r.add("hyperiot.security.jwt-secret", () -> TestPlatform.JWT_SECRET);
        r.add("hyperiot.ota.directory", () -> OTA_DIR.toString());
        r.add("hyperiot.access.rate-limit-per-second", () -> 0);
        r.add("hyperiot.webhook.allow-private-addresses", () -> true);
    }

    @LocalServerPort
    protected int httpPort;

    @Autowired
    protected TestRestTemplate rest;

    protected TestPlatform.Api api;

    @BeforeEach
    void loginAdmin() {
        api = new TestPlatform.Api(rest).login();
    }

    /** A product with the standard thing model and one device in it. */
    public record Fixture(long productId, String productKey, long deviceId, String deviceName, String secret) {
    }

    protected Fixture createFixture() {
        JsonNode product = api.post("/api/products", Json.object().put("name", "test-" + UUID.randomUUID().toString().substring(0, 8))
                .put("description", "integration test")).json();
        long productId = product.get("id").asLong();
        String productKey = product.get("productKey").asText();
        TestPlatform.Response tm = api.put("/api/products/" + productId + "/thing-model", THING_MODEL);
        if (tm.status() != 200) {
            throw new IllegalStateException("thing model rejected: " + tm.body());
        }
        String deviceName = "dev-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode device = api.post("/api/devices", Json.object().put("productKey", productKey).put("deviceName", deviceName)).json();
        return new Fixture(productId, productKey, device.get("id").asLong(), deviceName, device.get("deviceSecret").asText());
    }

    /** Port of the embedded broker of this test's Spring context; subclasses with their own context override it. */
    protected int mqttPort() {
        return MQTT_PORT;
    }

    protected TestPlatform.MqttDevice connect(Fixture f) throws Exception {
        TestPlatform.MqttDevice d = TestPlatform.MqttDevice.connect(mqttPort(), f.productKey(), f.deviceName(), f.secret());
        d.subscribeAll();
        return d;
    }

    protected JsonNode device(Fixture f) {
        return api.get("/api/devices/" + f.deviceId()).json();
    }

    protected JsonNode shadow(Fixture f) {
        return api.get("/api/devices/" + f.deviceId() + "/shadow").json();
    }
}
