package io.hyperiot.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hyperiot.access.DownlinkService;
import io.hyperiot.access.Topics;
import io.hyperiot.bus.DeviceMessage;
import io.hyperiot.bus.EventBus;
import io.hyperiot.bus.MessageKind;
import io.hyperiot.common.ApiException;
import io.hyperiot.common.Json;
import io.hyperiot.common.PageResult;
import io.hyperiot.config.HyperIotProperties;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceService;
import io.hyperiot.model.ThingModel;
import io.hyperiot.model.ThingModelValidator;
import io.hyperiot.product.ProductService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service invocation ("commands"). A command is stored first, then delivered. If the device is offline it waits
 * (until its TTL runs out) and is delivered when the device comes back; replies are matched by request id.
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    /** Published after every state change of a command, for the realtime push. */
    public record CommandUpdated(Command command) {
    }

    private final CommandRepository repo;
    private final DeviceService devices;
    private final ProductService products;
    private final DownlinkService downlink;
    private final EventBus bus;
    private final HyperIotProperties.Command cfg;
    private final ApplicationEventPublisher events;
    private final Map<String, CompletableFuture<Command>> waiters = new ConcurrentHashMap<>();

    public CommandService(CommandRepository repo, DeviceService devices, ProductService products, DownlinkService downlink,
                          EventBus bus, HyperIotProperties props, ApplicationEventPublisher events) {
        this.repo = repo;
        this.devices = devices;
        this.products = products;
        this.downlink = downlink;
        this.bus = bus;
        this.cfg = props.getCommand();
        this.events = events;
    }

    @PostConstruct
    void start() {
        bus.subscribe("command", Set.of(MessageKind.COMMAND_REPLY, MessageKind.STATUS), this::onMessage);
    }

    // ---------------------------------------------------------------- invoke

    public Command invoke(long deviceId, String identifier, JsonNode params, Long ttlSeconds) {
        Device d = devices.get(deviceId);
        if (!d.enabled()) {
            throw ApiException.conflict("设备已禁用");
        }
        ThingModel model = products.thingModel(d.productKey());
        ThingModel.ServiceDef def = model.service(identifier)
                .orElseThrow(() -> ApiException.badRequest("服务未在物模型中定义：" + identifier));
        Map<String, Object> checked = ThingModelValidator.checkParams(def.input(), params, true);

        Duration ttl = ttlSeconds == null ? cfg.getDefaultTtl() : Duration.ofSeconds(Math.max(1, ttlSeconds));
        if (ttl.compareTo(cfg.getMaxTtl()) > 0) {
            ttl = cfg.getMaxTtl();
        }
        long now = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        long id = repo.insert(requestId, d.id(), d.productKey(), d.deviceName(), identifier, Json.write(checked), now,
                now + ttl.toMillis());
        Command cmd = repo.findById(id).orElseThrow();
        if ("ONLINE".equals(d.status())) {
            cmd = deliver(d, cmd);
        } else {
            events.publishEvent(new CommandUpdated(cmd));
        }
        return cmd;
    }

    /** Invokes and waits for the reply for at most {@code timeoutMs}; returns the latest state either way. */
    public Command invokeAndWait(long deviceId, String identifier, JsonNode params, Long ttlSeconds, long timeoutMs) {
        Command cmd = invoke(deviceId, identifier, params, ttlSeconds);
        if (isFinal(cmd.status())) {
            return cmd;
        }
        CompletableFuture<Command> f = new CompletableFuture<>();
        waiters.put(cmd.requestId(), f);
        try {
            // the reply may have arrived between invoke() and registering the waiter
            repo.findById(cmd.id()).filter(c -> isFinal(c.status())).ifPresent(f::complete);
            return f.get(Math.max(100, Math.min(timeoutMs, 60_000)), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return repo.findById(cmd.id()).orElse(cmd);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cmd;
        } catch (java.util.concurrent.ExecutionException e) {
            return cmd;
        } finally {
            waiters.remove(cmd.requestId());
        }
    }

    private static boolean isFinal(String status) {
        return Command.SUCCESS.equals(status) || Command.FAILED.equals(status) || Command.TIMEOUT.equals(status);
    }

    /**
     * Claims the command (PENDING -> SENT) before sending, so that two nodes reacting to the same "device online"
     * event cannot both deliver it. If the device turns out to be unreachable the claim is released.
     */
    private Command deliver(Device d, Command cmd) {
        if (!repo.markSent(cmd.id(), System.currentTimeMillis())) {
            return repo.findById(cmd.id()).orElse(cmd); // someone else already took it
        }
        ObjectNode msg = Json.object();
        msg.put("id", cmd.requestId());
        msg.set("params", cmd.params() == null ? Json.object() : cmd.params());
        boolean sent = downlink.send(d, Topics.serviceInvoke(d.productKey(), d.deviceName(), cmd.identifier()), msg.toString());
        if (!sent) {
            repo.revertToPending(cmd.id());
        }
        Command current = repo.findById(cmd.id()).orElse(cmd);
        events.publishEvent(new CommandUpdated(current));
        return current;
    }

    // ---------------------------------------------------------------- bus

    private void onMessage(DeviceMessage m) {
        if (m.kind() == MessageKind.STATUS) {
            if ("ONLINE".equals(m.payload().path("status").asText())) {
                flushPending(m.deviceId());
            }
            return;
        }
        onReply(m);
    }

    private void flushPending(long deviceId) {
        var pending = repo.pendingFor(deviceId, System.currentTimeMillis());
        if (pending.isEmpty()) {
            return;
        }
        Device d = devices.get(deviceId);
        for (Command c : pending) {
            deliver(d, c);
        }
        log.info("Delivered {} queued commands to {}/{}", pending.size(), d.productKey(), d.deviceName());
    }

    private void onReply(DeviceMessage m) {
        JsonNode p = m.payload();
        String requestId = p.path("id").asText(null);
        if (requestId == null) {
            return;
        }
        repo.findByRequestId(requestId).ifPresent(cmd -> {
            if (cmd.deviceId() != m.deviceId()) {
                log.warn("Device {} replied to a command of another device, ignored", m.deviceName());
                return;
            }
            boolean ok = p.path("code").asInt(200) == 200;
            if (repo.markReplied(cmd.id(), ok ? Command.SUCCESS : Command.FAILED, p.toString(), System.currentTimeMillis())) {
                complete(repo.findById(cmd.id()).orElse(cmd));
            }
        });
    }

    private void complete(Command c) {
        events.publishEvent(new CommandUpdated(c));
        CompletableFuture<Command> f = waiters.get(c.requestId());
        if (f != null) {
            f.complete(c);
        }
    }

    // ---------------------------------------------------------------- timeouts

    @Scheduled(fixedDelay = 5000)
    void expire() {
        long now = System.currentTimeMillis();
        for (Command c : repo.timedOut(now, now - cfg.getReplyTimeout().toMillis())) {
            if (repo.markTimeout(c.id(), now)) {
                complete(repo.findById(c.id()).orElse(c));
            }
        }
    }

    // ---------------------------------------------------------------- queries

    public PageResult<Command> page(Long deviceId, String status, int page, int size) {
        return repo.page(deviceId, status, page, size);
    }
}
