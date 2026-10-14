package io.hyperiot.bus;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Decouples device access from the services that process device messages (storage, rules, shadow, push).
 * Messages of one device are delivered to each subscriber in order. Two implementations exist: an in-memory
 * bus for standalone deployments and a Kafka bus for clusters.
 */
public interface EventBus {

    void publish(DeviceMessage message);

    /**
     * @param group   subscriber name; subscribers with different groups each receive every message,
     *                instances of the same group (on different nodes) share the traffic
     * @param kinds   message kinds of interest
     * @param handler invoked on a bus thread; must not block for long
     */
    void subscribe(String group, Set<MessageKind> kinds, Consumer<DeviceMessage> handler);
}
