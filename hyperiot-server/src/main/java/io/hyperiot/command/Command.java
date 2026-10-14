package io.hyperiot.command;

import com.fasterxml.jackson.databind.JsonNode;

public record Command(long id, String requestId, long deviceId, String productKey, String deviceName, String identifier,
                      JsonNode params, String status, JsonNode response, long createdAt, Long sentAt, Long repliedAt,
                      long expireAt) {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String TIMEOUT = "TIMEOUT";
}
