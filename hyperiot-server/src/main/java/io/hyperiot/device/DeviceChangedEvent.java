package io.hyperiot.device;

/** Published when something happens to a device that other components must react to (kick connection, evict caches). */
public record DeviceChangedEvent(Type type, long deviceId, String productKey, String deviceName) {

    public enum Type { DISABLED, DELETED, SECRET_RESET }
}
