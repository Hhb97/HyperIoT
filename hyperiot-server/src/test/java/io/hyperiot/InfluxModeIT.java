package io.hyperiot;

import com.sun.net.httpserver.HttpServer;
import io.hyperiot.data.InfluxTelemetryStore;
import io.hyperiot.data.TelemetryStore;
import io.hyperiot.support.IntegrationTestBase;
import io.hyperiot.support.TestPlatform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The whole platform with {@code hyperiot.timeseries.type=influx}, talking to a fake InfluxDB. */
// a separate Spring context: it must not fight over the ports of the shared one
@TestPropertySource(properties = {"hyperiot.mqtt.port=${hyperiot.test.influx.mqtt.port}", "hyperiot.tcp.enabled=false"})
class InfluxModeIT extends IntegrationTestBase {

    private static final List<String> WRITES = new CopyOnWriteArrayList<>();
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    private static final HttpServer FAKE = start();

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/write", ex -> {
                WRITES.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                ex.sendResponseHeaders(204, -1);
                ex.close();
            });
            s.createContext("/query", ex -> {
                String q = ex.getRequestURI().getRawQuery() + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                QUERIES.add(java.net.URLDecoder.decode(q, StandardCharsets.UTF_8));
                byte[] out = ("{\"results\":[{\"statement_id\":0,\"series\":[{\"name\":\"telemetry\",\"columns\":[\"time\",\"num\",\"str\"],"
                        + "\"values\":[[1700000000000,25.5,null]]}]}]}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final int OWN_MQTT_PORT = TestPlatform.freePort();

    static {
        System.setProperty("hyperiot.test.influx.mqtt.port", Integer.toString(OWN_MQTT_PORT));
    }

    @Override
    protected int mqttPort() {
        return OWN_MQTT_PORT;
    }

    @DynamicPropertySource
    static void influx(DynamicPropertyRegistry r) {
        r.add("hyperiot.timeseries.type", () -> "influx");
        r.add("hyperiot.timeseries.influx.url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort());
    }

    @AfterAll
    static void stopFake() {
        FAKE.stop(0);
    }

    @Autowired
    TelemetryStore store;

    @Test
    void influxStoreIsWiredAndUsedForWritesAndReads() throws Exception {
        assertThat(store).isInstanceOf(InfluxTelemetryStore.class);
        assertThat(QUERIES).as("database and retention policy are prepared at startup").anyMatch(q -> q.contains("CREATE DATABASE"));

        Fixture f = createFixture();
        try (TestPlatform.MqttDevice d = connect(f)) {
            d.publish("thing/property/post", "{\"params\":{\"temperature\":25.5,\"humidity\":60}}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                String all = String.join("\n", WRITES);
                assertThat(all).contains("telemetry,device_id=" + f.deviceId() + ",identifier=temperature num=25.5 ")
                        .contains("telemetry,device_id=" + f.deviceId() + ",identifier=humidity num=60.0 ");
            });
        }
        // the history endpoint reads from "InfluxDB"
        var t = api.get("/api/devices/" + f.deviceId() + "/telemetry?identifier=temperature&from=1699999999000&to=1700000001000").json();
        assertThat(t.path("points").path(0).path("value").asDouble()).isEqualTo(25.5);
        assertThat(QUERIES).anyMatch(q -> q.contains("\"device_id\" = '" + f.deviceId() + "'") && q.contains("\"identifier\" = 'temperature'"));

        // deleting the device removes its series
        assertThat(api.delete("/api/devices/" + f.deviceId()).status()).isEqualTo(204);
        assertThat(QUERIES).anyMatch(q -> q.contains("DELETE FROM telemetry WHERE \"device_id\" = '" + f.deviceId() + "'"));
    }
}
