package io.hyperiot.access;

import io.hyperiot.common.ApiException;
import io.hyperiot.common.Hmac;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP access for devices that cannot keep an MQTT connection (cellular modules that wake up and post, scripts, ...).
 * The body has the same JSON format as the MQTT payloads, authenticated by {@code X-Signature = hex(HMAC-SHA256(secret, body))}.
 * HTTP is uplink-only.
 */
@RestController
@RequestMapping("/device-api/{productKey}/{deviceName}")
public class HttpGatewayController {

    private static final int MAX_BODY = 65536;

    private final DeviceService devices;
    private final UplinkProcessor uplink;
    private final HttpPresence presence;

    public HttpGatewayController(DeviceService devices, UplinkProcessor uplink, HttpPresence presence) {
        this.devices = devices;
        this.uplink = uplink;
        this.presence = presence;
    }

    @PostMapping("/property")
    public ResponseEntity<Map<String, Object>> property(@PathVariable String productKey, @PathVariable String deviceName,
                                                        @RequestHeader(value = "X-Signature", required = false) String sig,
                                                        @RequestBody byte[] body, HttpServletRequest request) {
        return handle(productKey, deviceName, sig, body, request, Topics.Type.PROPERTY_POST, null);
    }

    @PostMapping("/event/{identifier}")
    public ResponseEntity<Map<String, Object>> event(@PathVariable String productKey, @PathVariable String deviceName,
                                                     @PathVariable String identifier,
                                                     @RequestHeader(value = "X-Signature", required = false) String sig,
                                                     @RequestBody byte[] body, HttpServletRequest request) {
        return handle(productKey, deviceName, sig, body, request, Topics.Type.EVENT_POST, identifier);
    }

    private ResponseEntity<Map<String, Object>> handle(String pk, String dn, String sig, byte[] body, HttpServletRequest request,
                                                       Topics.Type type, String identifier) {
        if (body.length > MAX_BODY) {
            throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "BAD_REQUEST", "body too large");
        }
        Device d = devices.resolve(pk, dn).filter(Device::enabled).orElse(null);
        // same response for "unknown device" and "bad signature": do not reveal which devices exist
        if (d == null || sig == null
                || !Hmac.safeEquals(Hmac.sha256Hex(d.deviceSecret().getBytes(StandardCharsets.UTF_8), body), sig.toLowerCase())) {
            throw ApiException.unauthorized("signature verification failed");
        }
        presence.seen(d, request.getRemoteAddr());
        String topic = Topics.base(pk, dn) + (type == Topics.Type.PROPERTY_POST ? "thing/property/post"
                : "thing/event/" + identifier + "/post");
        uplink.process(d, type, identifier, body, topic);
        return ResponseEntity.ok(Map.of("code", 200));
    }
}
