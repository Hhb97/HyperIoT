package io.hyperiot.bus;

import io.hyperiot.config.HyperIotProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Single-node bus. Every subscriber owns a few single-thread executors ("partitions"); a message is routed to
 * the partition of its device id, which keeps per-device ordering while subscribers stay isolated from each
 * other. Queues are bounded: when a subscriber cannot keep up, messages are dropped and counted rather than
 * letting the access layer run out of memory.
 */
@Component
@ConditionalOnProperty(name = "hyperiot.bus.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final int partitions;
    private final int queueSize;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Counter dropped;
    private final AtomicLong lastDropLog = new AtomicLong();

    public InMemoryEventBus(HyperIotProperties props, MeterRegistry meters) {
        this.partitions = Math.max(1, props.getBus().getPartitions());
        this.queueSize = Math.max(100, props.getBus().getQueueSize());
        this.dropped = Counter.builder("hyperiot.bus.dropped").description("Messages dropped because a subscriber was too slow")
                .register(meters);
    }

    @Override
    public void publish(DeviceMessage message) {
        int partition = (int) Math.floorMod(message.deviceId(), (long) partitions);
        for (Subscriber s : subscribers) {
            if (!s.kinds.contains(message.kind())) {
                continue;
            }
            boolean ok = true;
            try {
                s.executors[partition].execute(() -> deliver(s, message));
            } catch (RejectedExecutionException e) {
                ok = false; // queue full (or bus shutting down)
            }
            if (!ok) {
                dropped.increment();
                long now = System.currentTimeMillis();
                long last = lastDropLog.get();
                if (now - last > 10_000 && lastDropLog.compareAndSet(last, now)) {
                    log.warn("Event bus subscriber '{}' is overloaded, dropping messages (queue size {})", s.group, queueSize);
                }
            }
        }
    }

    private void deliver(Subscriber s, DeviceMessage message) {
        try {
            s.handler.accept(message);
        } catch (Exception e) {
            log.error("Subscriber '{}' failed to handle {} of device {}", s.group, message.kind(), message.deviceName(), e);
        }
    }

    @Override
    public void subscribe(String group, Set<MessageKind> kinds, Consumer<DeviceMessage> handler) {
        ThreadPoolExecutor[] executors = new ThreadPoolExecutor[partitions];
        for (int i = 0; i < partitions; i++) {
            int index = i;
            executors[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(queueSize),
                    r -> {
                        Thread t = new Thread(r, "bus-" + group + "-" + index);
                        t.setDaemon(true);
                        return t;
                    });
        }
        subscribers.add(new Subscriber(group, Set.copyOf(kinds), handler, executors));
    }

    @PreDestroy
    void shutdown() {
        List<ThreadPoolExecutor> all = new ArrayList<>();
        subscribers.forEach(s -> all.addAll(List.of(s.executors)));
        all.forEach(ThreadPoolExecutor::shutdown);
        for (ThreadPoolExecutor e : all) {
            try {
                e.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private record Subscriber(String group, Set<MessageKind> kinds, Consumer<DeviceMessage> handler,
                              ThreadPoolExecutor[] executors) {
    }
}
