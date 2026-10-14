package io.hyperiot.bus;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.common.Json;

/**
 * Envelope of everything a device does, as seen by the platform. This is what travels on the event bus,
 * so it has to stay serializable as plain JSON.
 */
public record DeviceMessage(MessageKind kind, long deviceId, String productKey, String deviceName,
                            long ts, JsonNode payload) {

    public static DeviceMessage of(MessageKind kind, long deviceId, String productKey, String deviceName,
                                   long ts, Object payload) {
        return new DeviceMessage(kind, deviceId, productKey, deviceName, ts, Json.toTree(payload));
    }
}
