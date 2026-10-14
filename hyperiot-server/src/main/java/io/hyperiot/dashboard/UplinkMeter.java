package io.hyperiot.dashboard;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;

/** Counts uplink messages per minute for the dashboard (and exports a Prometheus counter). */
@Component
public class UplinkMeter {

    private static final long MINUTE = 60_000L;
    private static final int KEEP_MINUTES = 60;

    public record Bucket(long ts, long count) {
    }

    private final ConcurrentSkipListMap<Long, LongAdder> buckets = new ConcurrentSkipListMap<>();
    private final Counter total;

    public UplinkMeter(MeterRegistry meters) {
        this.total = Counter.builder("hyperiot.uplink.messages").description("Messages received from devices").register(meters);
    }

    public void mark() {
        buckets.computeIfAbsent(System.currentTimeMillis() / MINUTE * MINUTE, k -> new LongAdder()).increment();
        total.increment();
    }

    /** The last {@code minutes} minutes, oldest first, with zero-filled gaps. */
    public List<Bucket> last(int minutes) {
        long now = System.currentTimeMillis() / MINUTE * MINUTE;
        List<Bucket> out = new ArrayList<>(minutes);
        for (int i = minutes - 1; i >= 0; i--) {
            long ts = now - i * MINUTE;
            LongAdder a = buckets.get(ts);
            out.add(new Bucket(ts, a == null ? 0 : a.sum()));
        }
        return out;
    }

    @Scheduled(fixedDelay = 60_000)
    void prune() {
        buckets.headMap(System.currentTimeMillis() / MINUTE * MINUTE - KEEP_MINUTES * MINUTE).clear();
    }
}
