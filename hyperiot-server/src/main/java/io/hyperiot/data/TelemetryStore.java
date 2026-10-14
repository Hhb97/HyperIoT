package io.hyperiot.data;

import java.util.List;

/**
 * Storage of device property samples. Two implementations: the relational database ({@link JdbcTelemetryStore},
 * default) and InfluxDB 1.x ({@link InfluxTelemetryStore}).
 */
public interface TelemetryStore {

    /** One stored sample. Numbers and booleans (as 1/0) go to num, strings and enums to str. */
    record Row(long deviceId, String identifier, long ts, Double num, String str) {
    }

    record Point(long ts, Object value) {
    }

    void insertBatch(List<Row> rows);

    /** Samples in [from, to], oldest first. */
    List<Point> raw(long deviceId, String identifier, long from, long to, int limit);

    /**
     * Numeric samples aggregated into buckets of {@code interval} milliseconds aligned to the epoch.
     *
     * @param agg avg, max, min or last
     */
    List<Point> aggregate(long deviceId, String identifier, long from, long to, String agg, long interval, int limit);

    /** Deletes samples older than the cutoff; stores with their own retention (InfluxDB) may do nothing. */
    int deleteOlderThan(long cutoff);

    void deleteDevice(long deviceId);
}
