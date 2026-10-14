package io.hyperiot.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hyperiot.alarm.AlarmService;
import io.hyperiot.bus.DeviceMessage;
import io.hyperiot.bus.EventBus;
import io.hyperiot.bus.MessageKind;
import io.hyperiot.command.CommandService;
import io.hyperiot.common.Json;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes live telemetry, status changes, alarms and command results to the console. The first message of a
 * connection must carry a valid JWT; the token never appears in the URL.
 */
@Component
public class RealtimeHub extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    private static final long AUTH_DEADLINE_MS = 10_000;

    private record Client(ConcurrentWebSocketSessionDecorator session, long connectedAt, boolean[] authenticated) {
    }

    private final EventBus bus;
    private final JwtDecoder jwt;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    public RealtimeHub(EventBus bus, JwtDecoder jwt) {
        this.bus = bus;
        this.jwt = jwt;
    }

    @PostConstruct
    void start() {
        // every node needs every message for its own WebSocket clients, so each node gets its own consumer group
        bus.subscribe("realtime-" + java.util.UUID.randomUUID(), Set.of(MessageKind.PROPERTY, MessageKind.STATUS), this::onDevice);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        clients.put(session.getId(), new Client(new ConcurrentWebSocketSessionDecorator(session, 5000, 256 * 1024),
                System.currentTimeMillis(), new boolean[]{false}));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Client c = clients.get(session.getId());
        if (c == null || c.authenticated()[0]) {
            return; // authenticated clients have nothing to say
        }
        try {
            JsonNode n = Json.tree(message.getPayload());
            if ("auth".equals(n.path("type").asText()) && n.hasNonNull("token")) {
                jwt.decode(n.get("token").asText());
                c.authenticated()[0] = true;
                c.session().sendMessage(new TextMessage("{\"type\":\"ready\"}"));
                return;
            }
        } catch (Exception e) {
            // fall through: invalid token or malformed message
        }
        session.close(CloseStatus.POLICY_VIOLATION.withReason("authentication required"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        clients.remove(session.getId());
    }

    @Scheduled(fixedDelay = 5000)
    void closeUnauthenticated() {
        long cutoff = System.currentTimeMillis() - AUTH_DEADLINE_MS;
        for (Client c : clients.values()) {
            if (!c.authenticated()[0] && c.connectedAt() < cutoff) {
                try {
                    c.session().close(CloseStatus.POLICY_VIOLATION.withReason("authentication timeout"));
                } catch (Exception ignored) {
                    // already gone
                }
                clients.remove(c.session().getId());
            }
        }
    }

    // ---------------------------------------------------------------- sources

    private void onDevice(DeviceMessage m) {
        if (clients.isEmpty()) {
            return;
        }
        ObjectNode out = Json.object();
        out.put("deviceId", m.deviceId());
        out.put("productKey", m.productKey());
        out.put("deviceName", m.deviceName());
        out.put("ts", m.ts());
        if (m.kind() == MessageKind.PROPERTY) {
            out.put("type", "telemetry");
            out.set("params", m.payload().get("params"));
        } else {
            out.put("type", "status");
            out.put("status", m.payload().path("status").asText());
        }
        broadcast(out.toString());
    }

    @EventListener
    void onAlarm(AlarmService.AlarmChanged e) {
        ObjectNode out = Json.object();
        out.put("type", "alarm");
        out.set("alarm", Json.toTree(e.alarm()));
        broadcast(out.toString());
    }

    @EventListener
    void onCommand(CommandService.CommandUpdated e) {
        ObjectNode out = Json.object();
        out.put("type", "command");
        out.set("command", Json.toTree(e.command()));
        broadcast(out.toString());
    }

    private void broadcast(String json) {
        if (clients.isEmpty()) {
            return;
        }
        TextMessage msg = new TextMessage(json);
        for (Client c : clients.values()) {
            if (!c.authenticated()[0]) {
                continue;
            }
            try {
                c.session().sendMessage(msg);
            } catch (Exception e) {
                log.debug("Dropping websocket client {}: {}", c.session().getId(), e.toString());
                clients.remove(c.session().getId());
                try {
                    c.session().close();
                } catch (Exception ignored) {
                    // already gone
                }
            }
        }
    }
}
