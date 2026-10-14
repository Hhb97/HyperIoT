package io.hyperiot.data;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.common.Json;
import io.hyperiot.config.HyperIotProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Time-series storage in InfluxDB 1.x (1.8 tested against the documented HTTP API). One measurement
 * {@code telemetry}: tags {@code device_id} and {@code identifier}, fields {@code num} (float, also used for booleans
 * as 1/0) and {@code str} (string), millisecond timestamps. Expiry is handled by the retention policy, which is
 * created from {@code hyperiot.telemetry.retention-days}.
 *
 * <p>Only the HTTP API is used (line protocol for writes, InfluxQL for reads), so no client library is needed.
 */
@Component
@ConditionalOnProperty(name = "hyperiot.timeseries.type", havingValue = "influx")
public class InfluxTelemetryStore implements TelemetryStore {

    private static final Logger log = LoggerFactory.getLogger(InfluxTelemetryStore.class);
    private static final String MEASUREMENT = "telemetry";
    /** Identifiers come from validated thing models, this is a second line of defence before they enter a query. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");

    private final HyperIotProperties.Timeseries.Influx cfg;
    private final int retentionDays;
    private final HttpClient http;
    private final String authorization;

    public InfluxTelemetryStore(HyperIotProperties props) {
        this.cfg = props.getTimeseries().getInflux();
        this.retentionDays = props.getTelemetry().getRetentionDays();
        this.http = HttpClient.newBuilder().connectTimeout(cfg.getTimeout()).build();
        this.authorization = cfg.getUsername() == null || cfg.getUsername().isEmpty() ? null
                : "Basic " + Base64.getEncoder().encodeToString((cfg.getUsername() + ":" + cfg.getPassword()).getBytes(StandardCharsets.UTF_8));
    }

    @PostConstruct
    void init() {
        if (!cfg.isAutoCreate()) {
            return;
        }
        try {
            String db = quote(cfg.getDatabase());
            String rp = quote(cfg.getRetentionPolicy());
            command("CREATE DATABASE " + db, false);
            String policy = " ON " + db + " DURATION " + retentionDays + "d REPLICATION 1 DEFAULT";
            try {
                command("CREATE RETENTION POLICY " + rp + policy, false);
            } catch (IllegalStateException e) {
                // already exists (possibly with another duration): bring it in line with the configuration
                command("ALTER RETENTION POLICY " + rp + policy, false);
            }
            log.info("InfluxDB database {} ready at {} (retention {} days)", cfg.getDatabase(), cfg.getUrl(), retentionDays);
        } catch (RuntimeException e) {
            // not fatal: the database may have been prepared by an administrator, or Influx may start later
            log.warn("Could not prepare InfluxDB database '{}' ({}). Writes will fail until it exists and is reachable.",
                    cfg.getDatabase(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ write

    @Override
    public void insertBatch(List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder(rows.size() * 64);
        for (Row r : rows) {
            if (!SAFE_IDENTIFIER.matcher(r.identifier()).matches()) {
                continue;
            }
            body.append(line(r)).append('\n');
        }
        if (body.isEmpty()) {
            return;
        }
        String url = cfg.getUrl() + "/write?db=" + enc(cfg.getDatabase()) + "&precision=ms";
        HttpResponse<String> resp = send(request(url).POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("InfluxDB write failed: HTTP " + resp.statusCode() + " " + abbreviate(resp.body()));
        }
    }

    /** Line protocol for one sample: {@code telemetry,device_id=1,identifier=t num=25.5 1700000000000}. */
    static String line(Row r) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(MEASUREMENT).append(",device_id=").append(r.deviceId()).append(",identifier=").append(r.identifier()).append(' ');
        if (r.num() != null) {
            sb.append("num=").append(floatLiteral(r.num()));
            if (r.str() != null) {
                sb.append(',');
            }
        }
        if (r.str() != null) {
            sb.append("str=\"").append(r.str().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(' ').append(r.ts()).toString();
    }

    /** Always written with a decimal point: an integer literal would make Influx create an integer field. */
    static String floatLiteral(double d) {
        String s = new BigDecimal(Double.toString(d)).toPlainString();
        return s.indexOf('.') < 0 ? s + ".0" : s;
    }

    // ------------------------------------------------------------------ read

    @Override
    public List<Point> raw(long deviceId, String identifier, long from, long to, int limit) {
        String q = "SELECT \"num\", \"str\" FROM " + MEASUREMENT + where(deviceId, identifier, from, to) + " ORDER BY time ASC LIMIT " + limit;
        List<Point> out = new ArrayList<>();
        for (JsonNode v : values(query(q))) {
            JsonNode num = v.get(1);
            JsonNode str = v.get(2);
            Object value = num != null && !num.isNull() ? (Object) num.asDouble() : str != null && !str.isNull() ? str.asText() : null;
            if (value != null) {
                out.add(new Point(v.get(0).asLong(), value));
            }
        }
        return out;
    }

    @Override
    public List<Point> aggregate(long deviceId, String identifier, long from, long to, String agg, long interval, int limit) {
        String fn = switch (agg) {
            case "avg" -> "MEAN";
            case "max" -> "MAX";
            case "min" -> "MIN";
            case "last" -> "LAST";
            default -> throw new IllegalArgumentException("unknown aggregation " + agg);
        };
        String q = "SELECT " + fn + "(\"num\") FROM " + MEASUREMENT + where(deviceId, identifier, from, to)
                + " GROUP BY time(" + interval + "ms) fill(none) ORDER BY time ASC LIMIT " + limit;
        List<Point> out = new ArrayList<>();
        for (JsonNode v : values(query(q))) {
            if (v.get(1) != null && !v.get(1).isNull()) {
                out.add(new Point(v.get(0).asLong(), v.get(1).asDouble()));
            }
        }
        return out;
    }

    private static String where(long deviceId, String identifier, long from, long to) {
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("invalid identifier");
        }
        return " WHERE \"device_id\" = '" + deviceId + "' AND \"identifier\" = '" + identifier + "' AND time >= " + from
                + "ms AND time <= " + to + "ms";
    }

    private static List<JsonNode> values(JsonNode response) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode series : response.path("results").path(0).path("series")) {
            series.path("values").forEach(out::add);
        }
        return out;
    }

    // ------------------------------------------------------------------ retention / delete

    /** Expiry is the retention policy's job. */
    @Override
    public int deleteOlderThan(long cutoff) {
        return 0;
    }

    @Override
    public void deleteDevice(long deviceId) {
        try {
            command("DELETE FROM " + MEASUREMENT + " WHERE \"device_id\" = '" + deviceId + "'");
        } catch (RuntimeException e) {
            log.warn("Could not delete InfluxDB samples of device {}: {}", deviceId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ HTTP

    /** SELECT / SHOW go through GET. */
    private JsonNode query(String q) {
        String url = cfg.getUrl() + "/query?db=" + enc(cfg.getDatabase()) + "&epoch=ms&q=" + enc(q);
        return parse(send(request(url).GET().build()));
    }

    /** DDL and DELETE must be POSTed. */
    private void command(String q) {
        command(q, true);
    }

    private void command(String q, boolean withDatabase) {
        String body = "q=" + enc(q);
        String url = cfg.getUrl() + "/query" + (withDatabase ? "?db=" + enc(cfg.getDatabase()) : "");
        parse(send(request(url).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()));
    }

    private JsonNode parse(HttpResponse<String> resp) {
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("InfluxDB query failed: HTTP " + resp.statusCode() + " " + abbreviate(resp.body()));
        }
        JsonNode root = Json.tree(resp.body());
        // InfluxQL reports statement errors with HTTP 200
        String error = root.hasNonNull("error") ? root.get("error").asText() : root.path("results").path(0).path("error").asText(null);
        if (error != null) {
            throw new IllegalStateException(error);
        }
        return root;
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(cfg.getTimeout());
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return b;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("InfluxDB unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
