package io.hyperiot.access;

import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceStatusService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP devices have no connection, so "online" means "was heard from recently": the first request marks the
 * device online, silence for {@link #IDLE_MS} marks it offline.
 */
@Component
public class HttpPresence {

    private static final long IDLE_MS = 5 * 60_000L;

    private record Seen(Device device, long ts) {
    }

    private final DeviceStatusService status;
    private final Map<Long, Seen> seen = new ConcurrentHashMap<>();

    public HttpPresence(DeviceStatusService status) {
        this.status = status;
    }

    public void seen(Device d, String ip) {
        Seen previous = seen.put(d.id(), new Seen(d, System.currentTimeMillis()));
        if (previous == null) {
            status.online(d, ip);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    void sweep() {
        long cutoff = System.currentTimeMillis() - IDLE_MS;
        Iterator<Map.Entry<Long, Seen>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Seen s = it.next().getValue();
            if (s.ts() < cutoff) {
                it.remove();
                status.offline(s.device());
            }
        }
    }
}
