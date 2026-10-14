package io.hyperiot.access;

import io.hyperiot.common.ApiException;
import io.hyperiot.common.Hmac;
import io.hyperiot.config.HyperIotProperties;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceCredentials;
import io.hyperiot.device.DeviceService;
import io.hyperiot.device.DeviceStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * HTTP hooks for EMQX (external MQTT mode): authentication, authorization and connection events.
 * Every call must carry the shared secret in {@code X-Hook-Token}; without a configured token the hooks deny everything.
 */
@RestController
@RequestMapping("/api/mqtt")
@ConditionalOnProperty(name = "hyperiot.mqtt.mode", havingValue = "external")
public class EmqxHookController {

    private static final Logger log = LoggerFactory.getLogger(EmqxHookController.class);

    public record AuthRequest(String clientid, String username, String password) {
    }

    public record AclRequest(String clientid, String username, String topic, String action) {
    }

    public record EventRequest(String event, String clientid, String peername) {
    }

    private final HyperIotProperties props;
    private final DeviceService devices;
    private final DeviceStatusService status;

    public EmqxHookController(HyperIotProperties props, DeviceService devices, DeviceStatusService status) {
        this.props = props;
        this.devices = devices;
        this.status = status;
    }

    private void checkToken(String token) {
        String expected = props.getSecurity().getHookToken();
        if (expected == null || expected.isBlank()) {
            log.warn("EMQX hook called but hyperiot.security.hook-token is not configured; denying");
            throw ApiException.forbidden("hook token not configured");
        }
        if (!Hmac.safeEquals(expected, token)) {
            throw ApiException.forbidden("invalid hook token");
        }
    }

    @PostMapping("/auth")
    public Map<String, Object> auth(@RequestHeader(value = "X-Hook-Token", required = false) String token,
                                    @RequestBody AuthRequest req) {
        checkToken(token);
        // the platform's own connection
        if (req.username() != null && req.username().equals(props.getMqtt().getUsername())
                && !props.getMqtt().getPassword().isEmpty()
                && Hmac.safeEquals(props.getMqtt().getPassword(), req.password())) {
            return Map.of("result", "allow", "is_superuser", true);
        }
        DeviceCredentials.Identity id = DeviceCredentials.parseClientId(req.clientid());
        if (id == null || !DeviceCredentials.usernameMatches(id, req.username()) || req.password() == null) {
            return Map.of("result", "deny");
        }
        Optional<Device> d = devices.resolve(id.productKey(), id.deviceName());
        if (d.isEmpty() || !d.get().enabled()) {
            return Map.of("result", "deny");
        }
        boolean ok = DeviceCredentials.verifyPassword(d.get().deviceSecret(), id.productKey(), id.deviceName(), req.password(),
                props.getSecurity().isAllowPlainDevicePassword());
        return Map.of("result", ok ? "allow" : "deny", "is_superuser", false);
    }

    @PostMapping("/acl")
    public Map<String, Object> acl(@RequestHeader(value = "X-Hook-Token", required = false) String token,
                                   @RequestBody AclRequest req) {
        checkToken(token);
        DeviceCredentials.Identity id = DeviceCredentials.parseClientId(req.clientid());
        boolean ok = "publish".equalsIgnoreCase(req.action()) ? Topics.canPublish(id, req.topic())
                : "subscribe".equalsIgnoreCase(req.action()) && Topics.canSubscribe(id, req.topic());
        return Map.of("result", ok ? "allow" : "deny");
    }

    /** Body template configured in EMQX: {"event":"client.connected","clientid":"${clientid}","peername":"${peername}"} */
    @PostMapping("/event")
    public Map<String, Object> event(@RequestHeader(value = "X-Hook-Token", required = false) String token,
                                     @RequestBody EventRequest req) {
        checkToken(token);
        DeviceCredentials.Identity id = DeviceCredentials.parseClientId(req.clientid());
        if (id == null) {
            return Map.of("ok", true);
        }
        devices.resolve(id.productKey(), id.deviceName()).ifPresent(d -> {
            if ("client.connected".equals(req.event())) {
                String ip = req.peername() == null ? null : req.peername().replaceAll(":\\d+$", "");
                status.online(d, ip);
            } else if ("client.disconnected".equals(req.event())) {
                status.offline(d);
            }
        });
        return Map.of("ok", true);
    }
}
