package io.hyperiot.data;

import io.hyperiot.common.PageResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Per-device message log shown in the console ("what did this device send, what did we send to it").
 * Entries are queued and written in batches; if the queue is full the entry is dropped, logging never slows
 * down message processing.
 */
@Service
public class DeviceLogService {

    private static final Logger log = LoggerFactory.getLogger(DeviceLogService.class);
    private static final int MAX_PAYLOAD = 2048;
    private static final long RETENTION_MS = 3L * 24 * 3600 * 1000;

    public record Entry(long id, String direction, String topic, String payload, long ts) {
    }

    private record Pending(long deviceId, String direction, String topic, String payload, long ts) {
    }

    private final JdbcTemplate template;
    private final JdbcClient jdbc;
    private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>(50_000);

    public DeviceLogService(JdbcTemplate template, JdbcClient jdbc) {
        this.template = template;
        this.jdbc = jdbc;
    }

    public void up(long deviceId, String topic, byte[] payload) {
        add(deviceId, "UP", topic, payload == null ? null : new String(payload, java.nio.charset.StandardCharsets.UTF_8));
    }

    public void down(long deviceId, String topic, String payload) {
        add(deviceId, "DOWN", topic, payload);
    }

    public void system(long deviceId, String message) {
        add(deviceId, "SYS", "-", message);
    }

    private void add(long deviceId, String direction, String topic, String payload) {
        String p = payload == null ? null : payload.length() > MAX_PAYLOAD ? payload.substring(0, MAX_PAYLOAD) : payload;
        queue.offer(new Pending(deviceId, direction, topic.length() > 250 ? topic.substring(0, 250) : topic, p, System.currentTimeMillis()));
    }

    @Scheduled(fixedDelay = 1000)
    void flush() {
        if (queue.isEmpty()) {
            return;
        }
        List<Pending> batch = new ArrayList<>();
        queue.drainTo(batch, 2000);
        try {
            template.batchUpdate("INSERT INTO device_log(device_id, direction, topic, payload, ts) VALUES (?, ?, ?, ?, ?)",
                    batch, 500, (ps, p) -> {
                        ps.setLong(1, p.deviceId());
                        ps.setString(2, p.direction());
                        ps.setString(3, p.topic());
                        ps.setString(4, p.payload());
                        ps.setLong(5, p.ts());
                    });
        } catch (Exception e) {
            log.warn("Failed to write {} device log entries: {}", batch.size(), e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        flush();
    }

    @Scheduled(cron = "0 41 * * * *")
    void purge() {
        int n = jdbc.sql("DELETE FROM device_log WHERE ts < :c").param("c", System.currentTimeMillis() - RETENTION_MS).update();
        if (n > 0) {
            log.info("Device log retention: deleted {} entries", n);
        }
    }

    public PageResult<Entry> page(long deviceId, int page, int size) {
        long total = jdbc.sql("SELECT COUNT(*) FROM device_log WHERE device_id = :d").param("d", deviceId).query(Long.class).single();
        var items = jdbc.sql("SELECT id, direction, topic, payload, ts FROM device_log WHERE device_id = :d "
                        + "ORDER BY id DESC LIMIT :size OFFSET :offset")
                .param("d", deviceId).param("size", PageResult.normalizeSize(size)).param("offset", PageResult.offset(page, size))
                .query((rs, n) -> new Entry(rs.getLong("id"), rs.getString("direction"), rs.getString("topic"),
                        rs.getString("payload"), rs.getLong("ts"))).list();
        return new PageResult<>(total, items);
    }
}
