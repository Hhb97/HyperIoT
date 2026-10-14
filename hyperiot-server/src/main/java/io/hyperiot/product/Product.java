package io.hyperiot.product;

public record Product(long id, String productKey, String name, String description, String nodeType,
                      String protocol, long deviceCount, long createdAt, long updatedAt) {
}
