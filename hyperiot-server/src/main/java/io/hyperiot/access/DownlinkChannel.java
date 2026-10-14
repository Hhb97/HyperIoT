package io.hyperiot.access;

/**
 * A way to reach a connected device: the MQTT broker, a raw TCP connection, ... The platform asks each channel in
 * turn; the first one that has the device returns true.
 */
public interface DownlinkChannel {

    /**
     * @param topic   logical MQTT-style topic (TCP devices receive it as the "topic" field of the frame)
     * @param payload JSON text
     * @return true if the device is connected through this channel and the message was handed over
     */
    boolean send(String productKey, String deviceName, String topic, String payload);

    /** Disconnects the device if it is connected through this channel (device disabled / deleted / secret reset). */
    default void kick(String productKey, String deviceName) {
    }
}
