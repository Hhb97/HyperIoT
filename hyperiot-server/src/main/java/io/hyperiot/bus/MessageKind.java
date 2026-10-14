package io.hyperiot.bus;

/** The kinds of device messages that flow through the event bus. Each kind maps to one Kafka topic. */
public enum MessageKind {
    /** Property report: payload {params:{identifier:value}} (already validated against the thing model). */
    PROPERTY,
    /** Event report: payload {identifier, params}. */
    EVENT,
    /** Online / offline: payload {status:"ONLINE"|"OFFLINE", ip?}. */
    STATUS,
    /** Reply to a service invocation: payload {id, code, data, msg}. */
    COMMAND_REPLY,
    /** OTA progress: payload {version, step, desc}. */
    OTA_PROGRESS,
    /** Device asks for its shadow: payload {}. */
    SHADOW_GET;

    public String topicSuffix() {
        return name().toLowerCase().replace('_', '-');
    }
}
