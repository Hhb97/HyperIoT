package io.hyperiot.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.hyperiot.bus.DeviceMessage;
import io.hyperiot.bus.EventBus;
import io.hyperiot.bus.MessageKind;
import io.hyperiot.common.ApiException;
import io.hyperiot.common.Json;
import io.hyperiot.config.HyperIotProperties;
import io.hyperiot.dashboard.UplinkMeter;
import io.hyperiot.data.DeviceLogService;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceService;
import io.hyperiot.device.DeviceStatusService;
import io.hyperiot.model.ThingModel;
import io.hyperiot.model.ThingModelValidator;
import io.hyperiot.product.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Turns whatever a device sent (MQTT message, HTTP request, TCP frame) into validated bus messages.
 * This is the single place where uplink data is checked against the thing model.
 */
@Component
public class UplinkProcessor {

    private static final Logger log = LoggerFactory.getLogger(UplinkProcessor.class);
    private static final long MAX_PAST_MS = 7L * 24 * 3600 * 1000;
    private static final long MAX_FUTURE_MS = 5L * 60 * 1000;

    private final DeviceService devices;
    private final ProductService products;
    private final DeviceStatusService status;
    private final DeviceLogService deviceLog;
    private final DownlinkService downlink;
    private final EventBus bus;
    private final UplinkMeter meter;
    private final HyperIotProperties props;
    private final Cache<Long, RateBucket> limiters = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(5)).maximumSize(200_000).build();

    public UplinkProcessor(DeviceService devices, ProductService products, DeviceStatusService status,
                           DeviceLogService deviceLog, DownlinkService downlink, EventBus bus, UplinkMeter meter,
                           HyperIotProperties props) {
        this.devices = devices;
        this.products = products;
        this.status = status;
        this.deviceLog = deviceLog;
        this.downlink = downlink;
        this.bus = bus;
        this.meter = meter;
        this.props = props;
    }

    /** Entry point for MQTT: the topic carries the device identity. */
    public void onMqtt(String topic, byte[] payload) {
        Topics.Uplink up = Topics.parseUplink(topic);
        if (up == null) {
            log.debug("Ignoring message on non-uplink topic {}", topic);
            return;
        }
        Optional<Device> device = devices.resolve(up.productKey(), up.deviceName());
        if (device.isEmpty() || !device.get().enabled()) {
            return;
        }
        process(device.get(), up.type(), up.identifier(), payload, topic);
    }

    /** Entry point for gateways that already authenticated the device. */
    public void process(Device d, Topics.Type type, String identifier, byte[] payload, String topicForLog) {
        if (!allow(d.id())) {
            deviceLog.system(d.id(), "上行消息过于频繁，已丢弃（限流 " + props.getAccess().getRateLimitPerSecond() + " 条/秒）");
            return;
        }
        meter.mark();
        status.touch(d.id(), System.currentTimeMillis());
        deviceLog.up(d.id(), topicForLog, payload);

        JsonNode root;
        try {
            root = payload == null || payload.length == 0 ? Json.object() : Json.tree(payload);
        } catch (IllegalArgumentException e) {
            deviceLog.system(d.id(), "消息不是合法 JSON，已丢弃");
            return;
        }
        if (root == null || !root.isObject()) {
            deviceLog.system(d.id(), "消息必须是 JSON 对象，已丢弃");
            return;
        }
        long ts = resolveTs(root);
        String reqId = root.hasNonNull("id") ? root.get("id").asText() : null;
        try {
            switch (type) {
                case PROPERTY_POST -> onProperty(d, root, ts, reqId);
                case EVENT_POST -> onEvent(d, identifier, root, ts, reqId);
                case SERVICE_REPLY -> {
                    ObjectNode p = root.deepCopy();
                    p.put("identifier", identifier);
                    publish(MessageKind.COMMAND_REPLY, d, ts, p);
                }
                case SHADOW_GET -> publish(MessageKind.SHADOW_GET, d, ts, Json.object());
                case OTA_INFORM -> {
                    ObjectNode p = root.deepCopy();
                    p.put("inform", true);
                    publish(MessageKind.OTA_PROGRESS, d, ts, p);
                }
                case OTA_PROGRESS -> publish(MessageKind.OTA_PROGRESS, d, ts, root);
                default -> { }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to process {} from {}/{}: {}", type, d.productKey(), d.deviceName(), e.toString());
            deviceLog.system(d.id(), "处理 " + type + " 失败：" + e.getMessage());
        }
    }

    private void onProperty(Device d, JsonNode root, long ts, String reqId) {
        ThingModel model = products.thingModel(d.productKey());
        ThingModelValidator.Checked checked = ThingModelValidator.checkProperties(model, root.get("params"), props.getModel().isStrict());
        if (!checked.rejected().isEmpty()) {
            deviceLog.system(d.id(), "属性上报部分被拒绝：" + String.join("；", checked.rejected()));
        }
        if (!checked.accepted().isEmpty()) {
            ObjectNode p = Json.object();
            p.set("params", Json.toTree(checked.accepted()));
            if (reqId != null) {
                p.put("id", reqId);
            }
            publish(MessageKind.PROPERTY, d, ts, p);
        }
        if (reqId != null) {
            boolean ok = checked.rejected().isEmpty();
            reply(d, Topics.propertyPostReply(d.productKey(), d.deviceName()), reqId, ok ? 200 : 400,
                    ok ? "ok" : String.join("; ", checked.rejected()));
        }
    }

    private void onEvent(Device d, String identifier, JsonNode root, long ts, String reqId) {
        ThingModel model = products.thingModel(d.productKey());
        var def = model.event(identifier);
        if (def.isEmpty() && props.getModel().isStrict()) {
            deviceLog.system(d.id(), "事件 " + identifier + " 未在物模型中定义");
            if (reqId != null) {
                reply(d, Topics.eventPostReply(d.productKey(), d.deviceName(), identifier), reqId, 400, "事件未定义");
            }
            return;
        }
        Map<String, Object> params;
        try {
            params = def.isPresent() ? ThingModelValidator.checkParams(def.get().params(), root.get("params"), false)
                    : Map.of();
        } catch (ApiException e) {
            deviceLog.system(d.id(), "事件 " + identifier + " 参数无效：" + e.getMessage());
            if (reqId != null) {
                reply(d, Topics.eventPostReply(d.productKey(), d.deviceName(), identifier), reqId, 400, e.getMessage());
            }
            return;
        }
        ObjectNode p = Json.object();
        p.put("identifier", identifier);
        p.put("level", def.map(ThingModel.EventDef::level).filter(l -> l != null && !l.isBlank()).orElse("INFO"));
        p.set("params", Json.toTree(params));
        publish(MessageKind.EVENT, d, ts, p);
        if (reqId != null) {
            reply(d, Topics.eventPostReply(d.productKey(), d.deviceName(), identifier), reqId, 200, "ok");
        }
    }

    private void reply(Device d, String topic, String id, int code, String msg) {
        ObjectNode r = Json.object();
        r.put("id", id);
        r.put("code", code);
        r.put("msg", msg);
        downlink.send(d, topic, r.toString());
    }

    private void publish(MessageKind kind, Device d, long ts, JsonNode payload) {
        bus.publish(new DeviceMessage(kind, d.id(), d.productKey(), d.deviceName(), ts, payload));
    }

    static long resolveTs(JsonNode root) {
        long now = System.currentTimeMillis();
        if (root.hasNonNull("ts") && root.get("ts").isIntegralNumber()) {
            long ts = root.get("ts").asLong();
            if (ts >= now - MAX_PAST_MS && ts <= now + MAX_FUTURE_MS) {
                return ts;
            }
        }
        return now;
    }

    // ---------------------------------------------------------------- rate limit

    private boolean allow(long deviceId) {
        int rate = props.getAccess().getRateLimitPerSecond();
        if (rate <= 0) {
            return true;
        }
        return limiters.get(deviceId, k -> new RateBucket(rate)).tryAcquire();
    }

    /** Token bucket: {@code rate} tokens per second, capacity 2x rate. */
    static final class RateBucket {
        private final double rate;
        private final double capacity;
        private double tokens;
        private long last = System.nanoTime();

        RateBucket(int rate) {
            this.rate = rate;
            this.capacity = rate * 2.0;
            this.tokens = capacity;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - last) / 1e9 * rate);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
