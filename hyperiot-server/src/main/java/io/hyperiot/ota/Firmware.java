package io.hyperiot.ota;

public record Firmware(long id, String productKey, String version, String name, String description, long size,
                       String sha256, long createdAt) {
}
