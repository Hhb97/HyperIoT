package io.hyperiot.data;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.bus.DeviceMessage;
import io.hyperiot.bus.EventBus;
import io.hyperiot.bus.MessageKind;
import io.hyperiot.common.Json;
import io.hyperiot.common.PageResult;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Persists device events reported by devices and serves them to the console. */
@Component
public class EventStore {

    public record DeviceEvent(long id, String identifier, String level, JsonNode params, long ts) {
    }

    private final JdbcClient jdbc;
    private final EventBus bus;

    public EventStore(JdbcClient jdbc, EventBus bus) {
        this.jdbc = jdbc;
        this.bus = bus;
    }

    @PostConstruct
    void start() {
        bus.subscribe("event-store", Set.of(MessageKind.EVENT), this::onEvent);
    }

    private void onEvent(DeviceMessage m) {
        JsonNode p = m.payload();
        String level = p.hasNonNull("level") ? p.get("level").asText() : "INFO";
        jdbc.sql("INSERT INTO device_event(device_id, identifier, level, params, ts) VALUES (:d, :i, :l, :p, :ts)")
                .param("d", m.deviceId()).param("i", p.get("identifier").asText()).param("l", level)
                .param("p", p.hasNonNull("params") ? p.get("params").toString() : null).param("ts", m.ts()).update();
    }

    public PageResult<DeviceEvent> page(long deviceId, int page, int size) {
        long total = jdbc.sql("SELECT COUNT(*) FROM device_event WHERE device_id = :d").param("d", deviceId)
                .query(Long.class).single();
        var items = jdbc.sql("SELECT id, identifier, level, params, ts FROM device_event WHERE device_id = :d "
                        + "ORDER BY id DESC LIMIT :size OFFSET :offset")
                .param("d", deviceId).param("size", PageResult.normalizeSize(size)).param("offset", PageResult.offset(page, size))
                .query((rs, n) -> new DeviceEvent(rs.getLong("id"), rs.getString("identifier"), rs.getString("level"),
                        rs.getString("params") == null ? null : Json.tree(rs.getString("params")), rs.getLong("ts")))
                .list();
        return new PageResult<>(total, items);
    }
}
