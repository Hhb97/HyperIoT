package io.hyperiot.alarm;

public record Alarm(long id, long ruleId, String ruleName, long deviceId, String productKey, String deviceName,
                    String level, String status, String message, String triggerValue, long triggeredAt,
                    Long recoveredAt, Long ackedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String ACKED = "ACKED";
    public static final String RECOVERED = "RECOVERED";
}
