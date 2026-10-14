package io.hyperiot.data;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@ConditionalOnProperty(name = "hyperiot.timeseries.type", havingValue = "jdbc", matchIfMissing = true)
public class JdbcTelemetryStore implements TelemetryStore {

    private final JdbcTemplate template;
    private final JdbcClient jdbc;

    public JdbcTelemetryStore(JdbcTemplate template, JdbcClient jdbc) {
        this.template = template;
        this.jdbc = jdbc;
    }

    @Override
    public void insertBatch(List<Row> rows) {
        template.batchUpdate("INSERT INTO telemetry(device_id, identifier, ts, num_value, str_value) VALUES (?, ?, ?, ?, ?)",
                rows, 500, (ps, r) -> {
                    ps.setLong(1, r.deviceId());
                    ps.setString(2, r.identifier());
                    ps.setLong(3, r.ts());
                    if (r.num() == null) {
                        ps.setNull(4, java.sql.Types.DOUBLE);
                    } else {
                        ps.setDouble(4, r.num());
                    }
                    ps.setString(5, r.str());
                });
    }

    @Override
    public List<Point> raw(long deviceId, String identifier, long from, long to, int limit) {
        return jdbc.sql("SELECT ts, num_value, str_value FROM telemetry WHERE device_id = :d AND identifier = :i "
                        + "AND ts >= :from AND ts <= :to ORDER BY ts LIMIT :limit")
                .param("d", deviceId).param("i", identifier).param("from", from).param("to", to).param("limit", limit)
                .query((rs, n) -> {
                    double num = rs.getDouble("num_value");
                    Object v = rs.wasNull() ? rs.getString("str_value") : (Object) num;
                    return new Point(rs.getLong("ts"), v);
                }).list();
    }

    /** Aggregates numeric samples into fixed-size time buckets; aggregation is plain integer arithmetic on ts. */
    @Override
    public List<Point> aggregate(long deviceId, String identifier, long from, long to, String agg, long interval, int limit) {
        if ("last".equals(agg)) {
            return jdbc.sql("SELECT t.ts AS ts, t.num_value AS v FROM telemetry t JOIN ("
                            + "SELECT MAX(ts) AS mts FROM telemetry WHERE device_id = :d AND identifier = :i "
                            + "AND ts >= :from AND ts <= :to GROUP BY (ts / :iv)) m ON t.ts = m.mts "
                            + "WHERE t.device_id = :d AND t.identifier = :i AND t.num_value IS NOT NULL ORDER BY t.ts LIMIT :limit")
                    .param("d", deviceId).param("i", identifier).param("from", from).param("to", to)
                    .param("iv", interval).param("limit", limit)
                    .query((rs, n) -> new Point(rs.getLong("ts"), rs.getDouble("v"))).list();
        }
        String fn = switch (agg) {
            case "avg" -> "AVG";
            case "max" -> "MAX";
            case "min" -> "MIN";
            default -> throw new IllegalArgumentException("unknown aggregation " + agg);
        };
        return jdbc.sql("SELECT (ts / :iv) * :iv AS bucket, " + fn + "(num_value) AS v FROM telemetry "
                        + "WHERE device_id = :d AND identifier = :i AND ts >= :from AND ts <= :to AND num_value IS NOT NULL "
                        + "GROUP BY 1 ORDER BY 1 LIMIT :limit")
                .param("d", deviceId).param("i", identifier).param("from", from).param("to", to)
                .param("iv", interval).param("limit", limit)
                .query((rs, n) -> new Point(rs.getLong("bucket"), rs.getDouble("v"))).list();
    }

    @Override
    public int deleteOlderThan(long cutoff) {
        return jdbc.sql("DELETE FROM telemetry WHERE ts < :c").param("c", cutoff).update();
    }

    @Override
    public void deleteDevice(long deviceId) {
        jdbc.sql("DELETE FROM telemetry WHERE device_id = :d").param("d", deviceId).update();
    }
}
