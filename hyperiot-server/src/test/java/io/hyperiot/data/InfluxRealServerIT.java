package io.hyperiot.data;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.common.Json;
import io.hyperiot.config.HyperIotProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link InfluxTelemetryStore} against a REAL InfluxDB 1.x. Skipped unless {@code HYPERIOT_TEST_INFLUX_URL} is set
 * (optionally {@code HYPERIOT_TEST_INFLUX_USER} / {@code HYPERIOT_TEST_INFLUX_PASSWORD}).
 *
 * <p>It creates a database with a unique name ({@code hyperiot_it_...}) and drops exactly that database afterwards;
 * nothing else on the server is touched.
 */
@EnabledIfEnvironmentVariable(named = "HYPERIOT_TEST_INFLUX_URL", matches = ".+")
class InfluxRealServerIT {

    private static final String URL = System.getenv("HYPERIOT_TEST_INFLUX_URL");
    private static final String USER = System.getenv("HYPERIOT_TEST_INFLUX_USER");
    private static final String PASSWORD = System.getenv("HYPERIOT_TEST_INFLUX_PASSWORD");
    private static final String DB = "hyperiot_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static InfluxTelemetryStore store;

    @BeforeAll
    static void prepare() {
        HyperIotProperties p = new HyperIotProperties();
        var influx = p.getTimeseries().getInflux();
        influx.setUrl(URL);
        influx.setDatabase(DB);
        if (USER != null) {
            influx.setUsername(USER);
            influx.setPassword(PASSWORD == null ? "" : PASSWORD);
        }
        p.getTelemetry().setRetentionDays(30);
        store = new InfluxTelemetryStore(p);
        store.init();
    }

    @AfterAll
    static void dropOnlyOurOwnDatabase() throws Exception {
        if (DB.startsWith("hyperiot_it_")) {
            admin("DROP DATABASE \"" + DB + "\"");
        }
    }

    private static JsonNode admin(String q) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(URL + "/query"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)));
        if (USER != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
        }
        return Json.tree(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void databaseAndRetentionPolicyAreCreated() throws Exception {
        JsonNode dbs = admin("SHOW DATABASES");
        assertThat(dbs.toString()).contains("\"" + DB + "\"");
        JsonNode rps = admin("SHOW RETENTION POLICIES ON \"" + DB + "\"");
        // CREATE DATABASE also makes an infinite "autogen" policy; ours must exist, expire after 30 days and be the default
        JsonNode ours = null;
        JsonNode autogen = null;
        for (JsonNode row : rps.path("results").path(0).path("series").path(0).path("values")) {
            if ("hyperiot_rp".equals(row.path(0).asText())) {
                ours = row;
            } else if ("autogen".equals(row.path(0).asText())) {
                autogen = row;
            }
        }
        assertThat(ours).as("retention policy hyperiot_rp").isNotNull();
        assertThat(ours.path(1).asText()).as("duration").isEqualTo("720h0m0s");
        assertThat(ours.path(4).asBoolean()).as("default policy").isTrue();
        if (autogen != null) {
            assertThat(autogen.path(4).asBoolean()).as("autogen is no longer the default").isFalse();
        }
    }

    @Test
    void writeThenReadRawAndAggregates() throws Exception {
        long base = System.currentTimeMillis() / 60_000 * 60_000 - 20 * 60_000;
        long dev = 4242;
        store.insertBatch(List.of(
                // whole numbers first, fractional later: must not trigger an integer/float field type conflict
                new TelemetryStore.Row(dev, "temperature", base + 1000, 10.0, null),
                new TelemetryStore.Row(dev, "temperature", base + 2000, 20.0, null),
                new TelemetryStore.Row(dev, "temperature", base + 61_000, 40.5, null),
                new TelemetryStore.Row(dev, "fan", base + 1000, 1.0, null),
                new TelemetryStore.Row(dev, "mode", base + 1000, null, "say \"hi\" \\ ok, a=b"),
                new TelemetryStore.Row(dev + 1, "temperature", base + 1000, 99.0, null)));

        var raw = store.raw(dev, "temperature", base, base + 120_000, 100);
        assertThat(raw).extracting(TelemetryStore.Point::ts).containsExactly(base + 1000, base + 2000, base + 61_000);
        assertThat(raw).extracting(TelemetryStore.Point::value).containsExactly(10.0, 20.0, 40.5);

        assertThat(store.raw(dev, "fan", base, base + 120_000, 10)).extracting(TelemetryStore.Point::value).containsExactly(1.0);
        assertThat(store.raw(dev, "mode", base, base + 120_000, 10)).extracting(TelemetryStore.Point::value)
                .containsExactly("say \"hi\" \\ ok, a=b");

        var avg = store.aggregate(dev, "temperature", base, base + 120_000, "avg", 60_000, 100);
        assertThat(avg).extracting(TelemetryStore.Point::ts).containsExactly(base, base + 60_000);
        assertThat(avg).extracting(TelemetryStore.Point::value).containsExactly(15.0, 40.5);
        assertThat(store.aggregate(dev, "temperature", base, base + 120_000, "max", 60_000, 100))
                .extracting(TelemetryStore.Point::value).containsExactly(20.0, 40.5);
        assertThat(store.aggregate(dev, "temperature", base, base + 120_000, "min", 60_000, 100))
                .extracting(TelemetryStore.Point::value).containsExactly(10.0, 40.5);
        assertThat(store.aggregate(dev, "temperature", base, base + 120_000, "last", 60_000, 100))
                .extracting(TelemetryStore.Point::value).containsExactly(20.0, 40.5);

        // samples of another device are not mixed in, and an empty range is simply empty
        assertThat(store.raw(dev + 1, "temperature", base, base + 120_000, 10)).hasSize(1);
        assertThat(store.raw(dev, "temperature", base + 500_000, base + 600_000, 10)).isEmpty();
        assertThat(store.aggregate(dev, "temperature", base + 500_000, base + 600_000, "avg", 60_000, 10)).isEmpty();

        // limit
        assertThat(store.raw(dev, "temperature", base, base + 120_000, 2)).hasSize(2);

        // deleting a device removes its series only
        store.deleteDevice(dev);
        assertThat(store.raw(dev, "temperature", base, base + 120_000, 10)).isEmpty();
        assertThat(store.raw(dev + 1, "temperature", base, base + 120_000, 10)).hasSize(1);
    }
}
