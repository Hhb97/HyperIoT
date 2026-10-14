package io.hyperiot.device;

import io.hyperiot.common.Hmac;

/**
 * Device authentication. The secret never travels over the wire: devices send an HMAC-SHA256 signature and the
 * platform recomputes it.
 *
 * <pre>
 * MQTT clientId = productKey + "." + deviceName
 * MQTT username = deviceName + "&" + productKey
 * MQTT password = hex(HMAC-SHA256(key = deviceSecret, data = "clientId" + clientId + "deviceName" + deviceName + "productKey" + productKey))
 * HTTP header X-Signature = hex(HMAC-SHA256(key = deviceSecret, data = request body))
 * </pre>
 */
public final class DeviceCredentials {

    private DeviceCredentials() {
    }

    public static String clientId(String productKey, String deviceName) {
        return productKey + "." + deviceName;
    }

    public static String username(String productKey, String deviceName) {
        return deviceName + "&" + productKey;
    }

    public static String mqttPassword(String secret, String productKey, String deviceName) {
        String data = "clientId" + clientId(productKey, deviceName) + "deviceName" + deviceName + "productKey" + productKey;
        return Hmac.sha256Hex(secret, data);
    }

    /** Parsed identity of an MQTT connection; null parts mean the client id / username were malformed. */
    public record Identity(String productKey, String deviceName) {
        public String clientId() {
            return DeviceCredentials.clientId(productKey, deviceName);
        }
    }

    /** Splits "productKey.deviceName" (the product key never contains a dot, the device name may). */
    public static Identity parseClientId(String clientId) {
        if (clientId == null) {
            return null;
        }
        int dot = clientId.indexOf('.');
        if (dot <= 0 || dot == clientId.length() - 1) {
            return null;
        }
        return new Identity(clientId.substring(0, dot), clientId.substring(dot + 1));
    }

    /** True when username == deviceName&productKey for the identity in the client id. */
    public static boolean usernameMatches(Identity id, String username) {
        return id != null && username != null && username.equals(username(id.productKey(), id.deviceName()));
    }
}
