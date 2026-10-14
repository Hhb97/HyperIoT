package io.hyperiot.device;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hyperiot.bus.DeviceMessage;
import io.hyperiot.bus.EventBus;
import io.hyperiot.bus.MessageKind;
import io.hyperiot.common.Json;
import io.hyperiot.config.HyperIotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tracks online state. Called by the access layer (MQTT / TCP / EMQX webhook); persists the change and announces
 * it on the bus so that commands, shadow deltas and OTA tasks that were waiting can be delivered.
 */
@Service
public class DeviceStatusService {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusService.class);

    private final DeviceRepository repo;
    private final EventBus bus;
    /** deviceId -> last time a message was seen; flushed to the database periodically instead of on every message. */
    private final Map<Long, Long> lastSeen = new ConcurrentHashMap<>();

    /** Online announcements that are being held back until the device had time to subscribe. */
    private final Map<Long, ScheduledFuture<?>> pendingAnnouncements = new ConcurrentHashMap<>();
    private final ScheduledExecutorService announcer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "online-announcer");
        t.setDaemon(true);
        return t;
    });
    private final long announceDelayMs;

    public DeviceStatusService(DeviceRepository repo, EventBus bus, HyperIotProperties props) {
        this.repo = repo;
        this.bus = bus;
        this.announceDelayMs = props.getAccess().getOnlineAnnounceDelay().toMillis();
    }

    @PreDestroy
    void shutdown() {
        announcer.shutdownNow();
    }

    @PostConstruct
    void resetStaleOnlineState() {
        int n = repo.markAllOffline(System.currentTimeMillis());
        if (n > 0) {
            log.info("Reset {} devices to OFFLINE at startup", n);
        }
    }

    /**
     * MQTT devices: the device is marked online right away, but the "online" announcement, which triggers delivery of
     * queued commands, desired state and OTA instructions, is delayed so that the device has subscribed by then.
     */
    public void online(Device d, String ip) {
        long now = System.currentTimeMillis();
        repo.markOnline(d.id(), ip, now);
        if (announceDelayMs <= 0) {
            publish(d, "ONLINE", ip, now);
            return;
        }
        ScheduledFuture<?> f = announcer.schedule(() -> {
            pendingAnnouncements.remove(d.id());
            try {
                publish(d, "ONLINE", ip, System.currentTimeMillis());
            } catch (RuntimeException e) {
                log.warn("Could not announce device {} online: {}", d.deviceName(), e.toString());
            }
        }, announceDelayMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = pendingAnnouncements.put(d.id(), f);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /** TCP / HTTP devices can receive immediately, nothing to wait for. */
    public void onlineNow(Device d, String ip) {
        long now = System.currentTimeMillis();
        repo.markOnline(d.id(), ip, now);
        publish(d, "ONLINE", ip, now);
    }

    public void offline(Device d) {
        long now = System.currentTimeMillis();
        ScheduledFuture<?> pending = pendingAnnouncements.remove(d.id());
        if (pending != null) {
            pending.cancel(false); // went away before it was announced
        }
        repo.markOffline(d.id(), now);
        lastSeen.remove(d.id());
        publish(d, "OFFLINE", null, now);
    }

    public void touch(long deviceId, long ts) {
        lastSeen.merge(deviceId, ts, Math::max);
    }

    private void publish(Device d, String status, String ip, long ts) {
        ObjectNode payload = Json.object().put("status", status);
        if (ip != null) {
            payload.put("ip", ip);
        }
        bus.publish(new DeviceMessage(MessageKind.STATUS, d.id(), d.productKey(), d.deviceName(), ts, payload));
    }

    @Scheduled(fixedDelay = 5000)
    void flushLastSeen() {
        if (lastSeen.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, Long> e : Map.copyOf(lastSeen).entrySet()) {
            try {
                repo.updateLastSeen(e.getKey(), e.getValue());
            } catch (Exception ex) {
                log.warn("Failed to update last_seen for device {}: {}", e.getKey(), ex.toString());
            }
            lastSeen.remove(e.getKey(), e.getValue());
        }
    }
}
