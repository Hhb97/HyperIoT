package io.hyperiot.access;

import io.hyperiot.device.DeviceCredentials;

/**
 * MQTT topic layout. Every device owns the namespace {@code hyperiot/{productKey}/{deviceName}/...}.
 *
 * <pre>
 * device -> platform
 *   thing/property/post             property report
 *   thing/event/{identifier}/post   event report
 *   thing/service/{identifier}/reply  reply to a service invocation
 *   shadow/get                      ask for the full shadow
 *   ota/inform                      report the current firmware version
 *   ota/progress                    report upgrade progress
 * platform -> device
 *   thing/service/{identifier}/invoke  invoke a service
 *   thing/property/set              set properties (desired state)
 *   thing/property/post_reply       result of a property report that carried an "id"
 *   thing/event/{identifier}/post_reply
 *   shadow/get_reply                full shadow
 *   ota/upgrade                     firmware upgrade instruction
 * </pre>
 */
public final class Topics {

    public static final String ROOT = "hyperiot";

    private Topics() {
    }

    public enum Type {
        PROPERTY_POST, EVENT_POST, SERVICE_REPLY, SHADOW_GET, OTA_INFORM, OTA_PROGRESS
    }

    /** A parsed uplink topic. */
    public record Uplink(String productKey, String deviceName, Type type, String identifier) {
    }

    public static String base(String productKey, String deviceName) {
        return ROOT + "/" + productKey + "/" + deviceName + "/";
    }

    public static String serviceInvoke(String productKey, String deviceName, String identifier) {
        return base(productKey, deviceName) + "thing/service/" + identifier + "/invoke";
    }

    public static String propertySet(String productKey, String deviceName) {
        return base(productKey, deviceName) + "thing/property/set";
    }

    public static String propertyPostReply(String productKey, String deviceName) {
        return base(productKey, deviceName) + "thing/property/post_reply";
    }

    public static String eventPostReply(String productKey, String deviceName, String identifier) {
        return base(productKey, deviceName) + "thing/event/" + identifier + "/post_reply";
    }

    public static String shadowGetReply(String productKey, String deviceName) {
        return base(productKey, deviceName) + "shadow/get_reply";
    }

    public static String otaUpgrade(String productKey, String deviceName) {
        return base(productKey, deviceName) + "ota/upgrade";
    }

    /** Parses a topic published by a device; returns null if it is not a valid uplink topic. */
    public static Uplink parseUplink(String topic) {
        if (topic == null) {
            return null;
        }
        String[] p = topic.split("/", -1);
        if (p.length < 5 || !ROOT.equals(p[0]) || p[1].isEmpty() || p[2].isEmpty()) {
            return null;
        }
        String pk = p[1];
        String dn = p[2];
        String rest = String.join("/", java.util.Arrays.copyOfRange(p, 3, p.length));
        switch (rest) {
            case "thing/property/post":
                return new Uplink(pk, dn, Type.PROPERTY_POST, null);
            case "shadow/get":
                return new Uplink(pk, dn, Type.SHADOW_GET, null);
            case "ota/inform":
                return new Uplink(pk, dn, Type.OTA_INFORM, null);
            case "ota/progress":
                return new Uplink(pk, dn, Type.OTA_PROGRESS, null);
            default:
                break;
        }
        // thing/event/{id}/post and thing/service/{id}/reply
        if (p.length == 7 && "thing".equals(p[3]) && "event".equals(p[4]) && "post".equals(p[6]) && validId(p[5])) {
            return new Uplink(pk, dn, Type.EVENT_POST, p[5]);
        }
        if (p.length == 7 && "thing".equals(p[3]) && "service".equals(p[4]) && "reply".equals(p[6]) && validId(p[5])) {
            return new Uplink(pk, dn, Type.SERVICE_REPLY, p[5]);
        }
        return null;
    }

    private static boolean validId(String s) {
        return !s.isEmpty() && s.length() <= 64 && s.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }

    // ------------------------------------------------------------------ ACL

    /** A device may only publish uplink topics inside its own namespace. */
    public static boolean canPublish(DeviceCredentials.Identity who, String topic) {
        Uplink u = parseUplink(topic);
        return who != null && u != null && u.productKey().equals(who.productKey()) && u.deviceName().equals(who.deviceName());
    }

    /**
     * A device may only subscribe to filters inside its own namespace. Because product keys and device names can
     * not contain MQTT wildcards, a filter that starts with the device's base path can never match anything else.
     */
    public static boolean canSubscribe(DeviceCredentials.Identity who, String topicFilter) {
        return who != null && topicFilter != null && topicFilter.startsWith(base(who.productKey(), who.deviceName()));
    }
}
