package io.hyperiot.device;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hyperiot.bus.DeviceMessage;
import io.hyperiot.bus.EventBus;
import io.hyperiot.bus.MessageKind;
import io.hyperiot.common.Json;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public DeviceStatusService(DeviceRepository repo, EventBus bus) {
        this.repo = repo;
        this.bus = bus;
    }

    @PostConstruct
    void resetStaleOnlineState() {
        int n = repo.markAllOffline(System.currentTimeMillis());
        if (n > 0) {
            log.info("Reset {} devices to OFFLINE at startup", n);
        }
    }

    public void online(Device d, String ip) {
        long now = System.currentTimeMillis();
        repo.markOnline(d.id(), ip, now);
        publish(d, "ONLINE", ip, now);
    }

    public void offline(Device d) {
        long now = System.currentTimeMillis();
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
