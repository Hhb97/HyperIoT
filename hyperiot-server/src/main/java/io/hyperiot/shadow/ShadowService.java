package io.hyperiot.shadow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.hyperiot.access.DownlinkService;
import io.hyperiot.access.Topics;
import io.hyperiot.bus.DeviceMessage;
import io.hyperiot.bus.EventBus;
import io.hyperiot.bus.MessageKind;
import io.hyperiot.common.ApiException;
import io.hyperiot.common.Json;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceChangedEvent;
import io.hyperiot.device.DeviceService;
import io.hyperiot.model.ThingModel;
import io.hyperiot.model.ThingModelValidator;
import io.hyperiot.product.ProductService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Device shadow: the platform's copy of a device's last reported state plus the desired state that has not been
 * applied yet. Reports are merged in memory and written back in batches; the desired state is written through
 * immediately. The cache is node-local in this version.
 */
@Service
public class ShadowService {

    private static final Logger log = LoggerFactory.getLogger(ShadowService.class);

    public record Reported(Object value, long ts) {
    }

    /** API view. */
    public record ShadowView(Map<String, Reported> reported, Map<String, Object> desired, long version) {
    }

    static final class State {
        final long deviceId;
        final Map<String, Reported> reported = new LinkedHashMap<>();
        final Map<String, Object> desired = new LinkedHashMap<>();
        long version;

        State(long deviceId) {
            this.deviceId = deviceId;
        }
    }

    private final JdbcClient jdbc;
    private final EventBus bus;
    private final DeviceService devices;
    private final ProductService products;
    private final DownlinkService downlink;
    private final Cache<Long, State> cache = Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofMinutes(30)).build();
    /** States with changes not yet in the database; strong references so eviction can never lose data. */
    private final Map<Long, State> dirty = new ConcurrentHashMap<>();

    public ShadowService(JdbcClient jdbc, EventBus bus, DeviceService devices, ProductService products, DownlinkService downlink) {
        this.jdbc = jdbc;
        this.bus = bus;
        this.devices = devices;
        this.products = products;
        this.downlink = downlink;
    }

    @PostConstruct
    void start() {
        bus.subscribe("shadow", Set.of(MessageKind.PROPERTY, MessageKind.STATUS, MessageKind.SHADOW_GET), this::onMessage);
    }

    // ---------------------------------------------------------------- state access

    private State state(long deviceId) {
        State s = dirty.get(deviceId);
        if (s != null) {
            return s;
        }
        return cache.get(deviceId, this::load);
    }

    private State load(long deviceId) {
        State s = new State(deviceId);
        jdbc.sql("SELECT reported, desired, version FROM device_shadow WHERE device_id = :id").param("id", deviceId)
                .query((rs, n) -> {
                    Map<String, Reported> r = Json.read(rs.getString("reported"), new TypeReference<Map<String, Reported>>() { });
                    s.reported.putAll(r);
                    s.desired.putAll(Json.readMap(rs.getString("desired")));
                    s.version = rs.getLong("version");
                    return null;
                }).optional();
        return s;
    }

    public ShadowView view(long deviceId) {
        State s = state(deviceId);
        synchronized (s) {
            return new ShadowView(new LinkedHashMap<>(s.reported), new LinkedHashMap<>(s.desired), s.version);
        }
    }

    /** Current reported values (identifier -> value) for rule evaluation. */
    public Map<String, Object> reportedValues(long deviceId) {
        State s = state(deviceId);
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (s) {
            s.reported.forEach((k, v) -> out.put(k, v.value()));
        }
        return out;
    }

    // ---------------------------------------------------------------- desired state

    public ShadowView setDesired(long deviceId, Map<String, Object> raw) {
        Device d = devices.get(deviceId);
        ThingModel model = products.thingModel(d.productKey());
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("params 不能为空");
        }
        Map<String, Object> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            var def = model.property(e.getKey()).orElseThrow(() -> ApiException.badRequest("属性未定义：" + e.getKey()));
            if (!def.writable()) {
                throw ApiException.badRequest("属性 " + e.getKey() + " 是只读的（access=r）");
            }
            try {
                checked.put(e.getKey(), ThingModelValidator.coerce(def, Json.toTree(e.getValue())));
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("属性 " + e.getKey() + "：" + ex.getMessage());
            }
        }
        State s = state(deviceId);
        long version;
        Map<String, Object> delta;
        synchronized (s) {
            s.desired.putAll(checked);
            s.version++;
            version = s.version;
            delta = new LinkedHashMap<>(s.desired);
            persistDesired(s);
        }
        if ("ONLINE".equals(d.status())) {
            pushDesired(d, version, delta);
        }
        return view(deviceId);
    }

    private void persistDesired(State s) {
        // desired state is rare and important: write through, together with whatever reported state is pending
        flush(s);
        dirty.remove(s.deviceId, s);
    }

    private void pushDesired(Device d, long version, Map<String, Object> params) {
        if (params.isEmpty()) {
            return;
        }
        ObjectNode msg = Json.object();
        msg.put("id", UUID.randomUUID().toString());
        msg.put("version", version);
        msg.set("params", Json.toTree(params));
        downlink.send(d, Topics.propertySet(d.productKey(), d.deviceName()), msg.toString());
    }

    // ---------------------------------------------------------------- bus

    private void onMessage(DeviceMessage m) {
        switch (m.kind()) {
            case PROPERTY -> onReported(m);
            case STATUS -> {
                if ("ONLINE".equals(m.payload().path("status").asText())) {
                    onOnline(m);
                }
            }
            case SHADOW_GET -> onShadowGet(m);
            default -> { }
        }
    }

    private void onReported(DeviceMessage m) {
        JsonNode params = m.payload().get("params");
        if (params == null) {
            return;
        }
        State s = state(m.deviceId());
        synchronized (s) {
            params.fields().forEachRemaining(e -> {
                Reported old = s.reported.get(e.getKey());
                if (old != null && old.ts() > m.ts()) {
                    return; // out-of-order report, keep the newer value
                }
                Object v = plain(e.getValue());
                s.reported.put(e.getKey(), new Reported(v, m.ts()));
                Object want = s.desired.get(e.getKey());
                if (want != null && sameValue(want, v)) {
                    s.desired.remove(e.getKey()); // device applied the desired value
                }
            });
            dirty.put(s.deviceId, s);
        }
    }

    private void onOnline(DeviceMessage m) {
        State s = state(m.deviceId());
        long version;
        Map<String, Object> pending;
        synchronized (s) {
            if (s.desired.isEmpty()) {
                return;
            }
            version = s.version;
            pending = new LinkedHashMap<>(s.desired);
        }
        downlinkTo(m, version, pending);
    }

    private void downlinkTo(DeviceMessage m, long version, Map<String, Object> params) {
        ObjectNode msg = Json.object();
        msg.put("id", UUID.randomUUID().toString());
        msg.put("version", version);
        msg.set("params", Json.toTree(params));
        downlink.send(m.deviceId(), m.productKey(), m.deviceName(), Topics.propertySet(m.productKey(), m.deviceName()), msg.toString());
    }

    private void onShadowGet(DeviceMessage m) {
        ShadowView v = view(m.deviceId());
        ObjectNode state = Json.object();
        ObjectNode rep = state.putObject("reported");
        v.reported().forEach((k, r) -> rep.set(k, Json.toTree(r.value())));
        state.set("desired", Json.toTree(v.desired()));
        ObjectNode out = Json.object();
        out.set("state", state);
        out.put("version", v.version());
        out.put("ts", System.currentTimeMillis());
        downlink.send(m.deviceId(), m.productKey(), m.deviceName(), Topics.shadowGetReply(m.productKey(), m.deviceName()), out.toString());
    }

    private static Object plain(JsonNode n) {
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        return n.asText();
    }

    static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return a.equals(b);
    }

    // ---------------------------------------------------------------- persistence

    @Scheduled(fixedDelay = 1000)
    void flushDirty() {
        for (State s : dirty.values()) {
            try {
                synchronized (s) {
                    flush(s);
                    dirty.remove(s.deviceId, s);
                }
            } catch (Exception e) {
                log.warn("Failed to persist shadow of device {}: {}", s.deviceId, e.toString());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        flushDirty();
    }

    private void flush(State s) {
        String reported = Json.write(s.reported);
        String desired = Json.write(s.desired);
        long now = System.currentTimeMillis();
        int n = update(s, reported, desired, now);
        if (n == 0) {
            try {
                jdbc.sql("INSERT INTO device_shadow(device_id, reported, desired, version, updated_at) VALUES (:id, :r, :d, :v, :now)")
                        .param("id", s.deviceId).param("r", reported).param("d", desired).param("v", s.version).param("now", now).update();
            } catch (DuplicateKeyException e) {
                update(s, reported, desired, now);
            }
        }
    }

    private int update(State s, String reported, String desired, long now) {
        return jdbc.sql("UPDATE device_shadow SET reported = :r, desired = :d, version = :v, updated_at = :now WHERE device_id = :id")
                .param("r", reported).param("d", desired).param("v", s.version).param("now", now).param("id", s.deviceId).update();
    }

    @EventListener
    void onDeviceChanged(DeviceChangedEvent e) {
        if (e.type() == DeviceChangedEvent.Type.DELETED) {
            dirty.remove(e.deviceId());
            cache.invalidate(e.deviceId());
        }
    }
}
