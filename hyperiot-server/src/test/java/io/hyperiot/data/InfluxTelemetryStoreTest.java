package io.hyperiot.data;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.hyperiot.config.HyperIotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks what the Influx store sends and how it reads answers, against a fake HTTP server that follows the documented
 * InfluxDB 1.x HTTP API. This does not replace a run against a real InfluxDB.
 */
class InfluxTelemetryStoreTest {

    private record Call(String method, String path, String query, String auth, String body) {
        String param(String name) {
            for (String kv : query.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0 && kv.substring(0, i).equals(name)) {
                    return URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        /** InfluxQL of a /query call, whether it came in the URL (GET) or the form body (POST). */
        String influxQl() {
            String q = param("q");
            if (q != null) {
                return q;
            }
            return URLDecoder.decode(body.replaceFirst("^q=", ""), StandardCharsets.UTF_8);
        }
    }

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Call, String[]> responder = c -> new String[]{"200", "{\"results\":[{\"statement_id\":0}]}"};

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Call call = new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery(),
                ex.getRequestHeaders().getFirst("Authorization"), body);
        calls.add(call);
        String[] r = responder.apply(call);
        byte[] out = r[1].getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(Integer.parseInt(r[0]), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    private InfluxTelemetryStore store(boolean auth) {
        HyperIotProperties p = new HyperIotProperties();
        var influx = p.getTimeseries().getInflux();
        influx.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        influx.setDatabase("hyperiot");
        if (auth) {
            influx.setUsername("writer");
            influx.setPassword("pw:with:colons");
        }
        p.getTelemetry().setRetentionDays(30);
        return new InfluxTelemetryStore(p);
    }

    @Test
    void lineProtocol() {
        assertThat(InfluxTelemetryStore.line(new TelemetryStore.Row(7, "temperature", 1700000000000L, 25.5, null)))
                .isEqualTo("telemetry,device_id=7,identifier=temperature num=25.5 1700000000000");
        assertThat(InfluxTelemetryStore.line(new TelemetryStore.Row(7, "fan", 1L, 1.0, null))).isEqualTo("telemetry,device_id=7,identifier=fan num=1.0 1");
        assertThat(InfluxTelemetryStore.line(new TelemetryStore.Row(7, "mode", 2L, null, "say \"hi\" \\ ok")))
                .isEqualTo("telemetry,device_id=7,identifier=mode str=\"say \\\"hi\\\" \\\\ ok\" 2");
    }

    @Test
    void numbersAreAlwaysFloatLiterals() {
        // an integer literal would make InfluxDB create an integer field and reject later float writes
        assertThat(InfluxTelemetryStore.floatLiteral(20)).isEqualTo("20.0");
        assertThat(InfluxTelemetryStore.floatLiteral(-3)).isEqualTo("-3.0");
        assertThat(InfluxTelemetryStore.floatLiteral(1.0E10)).isEqualTo("10000000000.0");
        assertThat(InfluxTelemetryStore.floatLiteral(1.5E-5)).isEqualTo("0.000015");
        assertThat(InfluxTelemetryStore.floatLiteral(0.1)).isEqualTo("0.1");
    }

    @Test
    void writesBatchesToTheWriteEndpointWithBasicAuth() {
        store(true).insertBatch(List.of(
                new TelemetryStore.Row(1, "temperature", 1000, 21.5, null),
                new TelemetryStore.Row(1, "mode", 1001, null, "auto")));
        Call c = calls.get(0);
        assertThat(c.method()).isEqualTo("POST");
        assertThat(c.path()).isEqualTo("/write");
        assertThat(c.param("db")).isEqualTo("hyperiot");
        assertThat(c.param("precision")).isEqualTo("ms");
        assertThat(c.auth()).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("writer:pw:with:colons".getBytes(StandardCharsets.UTF_8)));
        assertThat(c.body().split("\n")).containsExactly(
                "telemetry,device_id=1,identifier=temperature num=21.5 1000",
                "telemetry,device_id=1,identifier=mode str=\"auto\" 1001");
    }

    @Test
    void noAuthorizationHeaderWithoutCredentials() {
        store(false).insertBatch(List.of(new TelemetryStore.Row(1, "t", 1, 1.0, null)));
        assertThat(calls.get(0).auth()).isNull();
    }

    @Test
    void writeErrorsSurface() {
        responder = c -> new String[]{"400", "{\"error\":\"partial write: field type conflict\"}"};
        assertThatThrownBy(() -> store(false).insertBatch(List.of(new TelemetryStore.Row(1, "t", 1, 1.0, null))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("400").hasMessageContaining("field type conflict");
    }

    @Test
    void unsafeIdentifiersNeverReachTheWire() {
        store(false).insertBatch(List.of(new TelemetryStore.Row(1, "x\" OR 1=1 --", 1, 1.0, null)));
        assertThat(calls).as("nothing to write").isEmpty();
        assertThatThrownBy(() -> store(false).raw(1, "x' OR '1'='1", 0, 10, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store(false).aggregate(1, "a b", 0, 10, "avg", 1000, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsRawSamples() {
        responder = c -> new String[]{"200", """
                {"results":[{"statement_id":0,"series":[{"name":"telemetry","columns":["time","num","str"],
                 "values":[[1000,21.5,null],[2000,null,"auto"]]}]}]}"""};
        var points = store(false).raw(7, "temperature", 500, 3000, 100);
        assertThat(points).extracting(TelemetryStore.Point::ts).containsExactly(1000L, 2000L);
        assertThat(points).extracting(TelemetryStore.Point::value).containsExactly(21.5, "auto");
        Call c = calls.get(0);
        assertThat(c.method()).isEqualTo("GET");
        assertThat(c.param("epoch")).isEqualTo("ms");
        assertThat(c.param("db")).isEqualTo("hyperiot");
        assertThat(c.param("q")).isEqualTo("SELECT \"num\", \"str\" FROM telemetry WHERE \"device_id\" = '7' AND \"identifier\" = 'temperature' "
                + "AND time >= 500ms AND time <= 3000ms ORDER BY time ASC LIMIT 100");
    }

    @Test
    void readsAggregates() {
        responder = c -> new String[]{"200", """
                {"results":[{"statement_id":0,"series":[{"name":"telemetry","columns":["time","mean"],
                 "values":[[60000,15.0],[120000,40.0]]}]}]}"""};
        var points = store(false).aggregate(7, "temperature", 0, 180000, "avg", 60000, 5000);
        assertThat(points).extracting(TelemetryStore.Point::ts).containsExactly(60000L, 120000L);
        assertThat(points).extracting(TelemetryStore.Point::value).containsExactly(15.0, 40.0);
        assertThat(calls.get(0).param("q")).contains("SELECT MEAN(\"num\") FROM telemetry").contains("GROUP BY time(60000ms) fill(none)")
                .endsWith("LIMIT 5000");
        store(false).aggregate(7, "temperature", 0, 180000, "max", 60000, 10);
        store(false).aggregate(7, "temperature", 0, 180000, "min", 60000, 10);
        store(false).aggregate(7, "temperature", 0, 180000, "last", 60000, 10);
        assertThat(calls.stream().map(c -> c.param("q")).toList()).anyMatch(q -> q.contains("MAX(\"num\")"))
                .anyMatch(q -> q.contains("MIN(\"num\")")).anyMatch(q -> q.contains("LAST(\"num\")"));
    }

    @Test
    void emptyResultMeansNoPoints() {
        responder = c -> new String[]{"200", "{\"results\":[{\"statement_id\":0}]}"};
        assertThat(store(false).raw(7, "temperature", 0, 10, 10)).isEmpty();
    }

    @Test
    void statementErrorsInsideA200AreReported() {
        responder = c -> new String[]{"200", "{\"results\":[{\"statement_id\":0,\"error\":\"database not found: \\\"hyperiot\\\"\"}]}"};
        assertThatThrownBy(() -> store(false).raw(7, "temperature", 0, 10, 10)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database not found");
    }

    @Test
    void startupCreatesDatabaseAndRetentionPolicy() {
        store(false).init();
        List<String> statements = calls.stream().map(Call::influxQl).toList();
        assertThat(statements).containsExactly(
                "CREATE DATABASE \"hyperiot\"",
                "CREATE RETENTION POLICY \"hyperiot_rp\" ON \"hyperiot\" DURATION 30d REPLICATION 1 DEFAULT");
        assertThat(calls).allMatch(c -> c.method().equals("POST") && c.path().equals("/query"));
    }

    @Test
    void existingRetentionPolicyIsAltered() {
        responder = c -> c.influxQl().startsWith("CREATE RETENTION")
                ? new String[]{"200", "{\"results\":[{\"statement_id\":0,\"error\":\"retention policy already exists\"}]}"}
                : new String[]{"200", "{\"results\":[{\"statement_id\":0}]}"};
        store(false).init();
        assertThat(calls.get(calls.size() - 1).influxQl())
                .isEqualTo("ALTER RETENTION POLICY \"hyperiot_rp\" ON \"hyperiot\" DURATION 30d REPLICATION 1 DEFAULT");
    }

    @Test
    void startupToleratesAnUnreachableInfluxDb() {
        InfluxTelemetryStore s = store(false);
        server.stop(0);
        s.init(); // must not throw: the platform should still start
    }

    @Test
    void deletingADeviceDeletesItsSeries() {
        store(false).deleteDevice(42);
        assertThat(calls.get(0).influxQl()).isEqualTo("DELETE FROM telemetry WHERE \"device_id\" = '42'");
        assertThat(store(false).deleteOlderThan(1)).as("expiry is the retention policy's job").isZero();
    }
}
