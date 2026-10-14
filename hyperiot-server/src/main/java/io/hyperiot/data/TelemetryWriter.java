package io.hyperiot.data;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.bus.DeviceMessage;
import io.hyperiot.bus.EventBus;
import io.hyperiot.bus.MessageKind;
import io.hyperiot.config.HyperIotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Buffers property samples and writes them to the time-series table in batches. The queue absorbs bursts
 * (peak shaving) so a spike of device traffic turns into a few large inserts instead of thousands of small ones.
 */
@Component
public class TelemetryWriter {

    private static final Logger log = LoggerFactory.getLogger(TelemetryWriter.class);
    private static final int QUEUE_CAPACITY = 200_000;

    private final TelemetryStore repo;
    private final EventBus bus;
    private final HyperIotProperties.Telemetry cfg;
    private final LinkedBlockingQueue<TelemetryStore.Row> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread flusher;
    private volatile boolean running = true;
    private volatile long dropped;

    public TelemetryWriter(TelemetryStore repo, EventBus bus, HyperIotProperties props) {
        this.repo = repo;
        this.bus = bus;
        this.cfg = props.getTelemetry();
        this.flusher = new Thread(this::loop, "telemetry-writer");
        this.flusher.setDaemon(true);
    }

    @PostConstruct
    void start() {
        bus.subscribe("telemetry-store", Set.of(MessageKind.PROPERTY), this::onProperty);
        flusher.start();
    }

    private void onProperty(DeviceMessage m) {
        JsonNode params = m.payload().get("params");
        if (params == null) {
            return;
        }
        params.fields().forEachRemaining(e -> {
            TelemetryStore.Row row = toRow(m.deviceId(), e.getKey(), m.ts(), e.getValue());
            if (row != null && !queue.offer(row)) {
                if (dropped++ % 1000 == 0) {
                    log.warn("Telemetry queue full, {} samples dropped so far", dropped);
                }
            }
        });
    }

    static TelemetryStore.Row toRow(long deviceId, String identifier, long ts, JsonNode v) {
        if (v.isBoolean()) {
            return new TelemetryStore.Row(deviceId, identifier, ts, v.asBoolean() ? 1.0 : 0.0, null);
        }
        if (v.isNumber()) {
            return new TelemetryStore.Row(deviceId, identifier, ts, v.asDouble(), null);
        }
        if (v.isTextual()) {
            String s = v.asText();
            return new TelemetryStore.Row(deviceId, identifier, ts, null, s.length() > 1024 ? s.substring(0, 1024) : s);
        }
        return null;
    }

    private void loop() {
        List<TelemetryStore.Row> batch = new ArrayList<>(cfg.getBatchSize());
        while (running || !queue.isEmpty()) {
            try {
                TelemetryStore.Row first = queue.poll(cfg.getFlushInterval().toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, cfg.getBatchSize() - 1);
                flush(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to write telemetry batch of {} rows", batch.size(), e);
                batch.clear();
            }
        }
        // drain what is left on shutdown
        List<TelemetryStore.Row> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            try {
                flush(rest);
            } catch (Exception e) {
                log.error("Failed to flush {} remaining telemetry rows", rest.size(), e);
            }
        }
    }

    /** A few retries with backoff ride out a short database / InfluxDB outage; after that the batch is dropped. */
    private void flush(List<TelemetryStore.Row> rows) throws InterruptedException {
        long backoff = 500;
        for (int attempt = 1; ; attempt++) {
            try {
                repo.insertBatch(rows);
                return;
            } catch (RuntimeException e) {
                if (attempt >= 4 || !running) {
                    throw e;
                }
                log.warn("Writing {} samples failed (attempt {}): {}. Retrying in {} ms", rows.size(), attempt, e.getMessage(), backoff);
                Thread.sleep(backoff);
                backoff *= 2;
            }
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        flusher.join(5000);
    }

    @Scheduled(cron = "0 17 3 * * *")
    void purge() {
        long cutoff = System.currentTimeMillis() - cfg.getRetentionDays() * 86_400_000L;
        int n = repo.deleteOlderThan(cutoff);
        if (n > 0) {
            log.info("Telemetry retention: deleted {} samples older than {} days", n, cfg.getRetentionDays());
        }
    }
}
