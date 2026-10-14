package io.hyperiot.device;

import java.util.Map;

/**
 * API view of a device. {@code deviceSecret} is only filled in on create / detail / reset responses.
 */
public record Device(long id, String productKey, String deviceName, String nickname, String status, boolean enabled,
                     String firmwareVersion, String ip, Long lastOnlineAt, Long lastOfflineAt, Long lastSeenAt,
                     Map<String, String> tags, long createdAt, String deviceSecret) {

    public Device withoutSecret() {
        return new Device(id, productKey, deviceName, nickname, status, enabled, firmwareVersion, ip,
                lastOnlineAt, lastOfflineAt, lastSeenAt, tags, createdAt, null);
    }

    public String key() {
        return productKey + "/" + deviceName;
    }
}
