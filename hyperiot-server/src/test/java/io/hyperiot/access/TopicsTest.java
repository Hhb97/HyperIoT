package io.hyperiot.access;

import io.hyperiot.device.DeviceCredentials;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopicsTest {

    private static final DeviceCredentials.Identity ME = new DeviceCredentials.Identity("pk1", "dev1");

    @Test
    void parsesAllUplinkTopics() {
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/thing/property/post").type()).isEqualTo(Topics.Type.PROPERTY_POST);
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/shadow/get").type()).isEqualTo(Topics.Type.SHADOW_GET);
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/ota/inform").type()).isEqualTo(Topics.Type.OTA_INFORM);
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/ota/progress").type()).isEqualTo(Topics.Type.OTA_PROGRESS);
        var event = Topics.parseUplink("hyperiot/pk1/dev1/thing/event/overheat/post");
        assertThat(event.type()).isEqualTo(Topics.Type.EVENT_POST);
        assertThat(event.identifier()).isEqualTo("overheat");
        var reply = Topics.parseUplink("hyperiot/pk1/dev1/thing/service/reboot/reply");
        assertThat(reply.type()).isEqualTo(Topics.Type.SERVICE_REPLY);
        assertThat(reply.identifier()).isEqualTo("reboot");
        assertThat(reply.productKey()).isEqualTo("pk1");
        assertThat(reply.deviceName()).isEqualTo("dev1");
    }

    @Test
    void downlinkTopicsAreNotUplink() {
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/thing/property/set")).isNull();
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/thing/service/reboot/invoke")).isNull();
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/ota/upgrade")).isNull();
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/shadow/get_reply")).isNull();
    }

    @Test
    void malformedTopicsAreRejected() {
        assertThat(Topics.parseUplink(null)).isNull();
        assertThat(Topics.parseUplink("")).isNull();
        assertThat(Topics.parseUplink("other/pk1/dev1/thing/property/post")).isNull();
        assertThat(Topics.parseUplink("hyperiot//dev1/thing/property/post")).isNull();
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/thing/event//post")).isNull();
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/thing/event/a.b/post")).isNull();
        assertThat(Topics.parseUplink("hyperiot/pk1/dev1/thing/property/post/extra")).isNull();
    }

    @Test
    void aclAllowsOnlyOwnNamespace() {
        assertThat(Topics.canPublish(ME, "hyperiot/pk1/dev1/thing/property/post")).isTrue();
        assertThat(Topics.canPublish(ME, "hyperiot/pk1/dev2/thing/property/post")).isFalse();
        assertThat(Topics.canPublish(ME, "hyperiot/pk2/dev1/thing/property/post")).isFalse();
        // a device must not be able to publish downlink messages (impersonating the platform)
        assertThat(Topics.canPublish(ME, "hyperiot/pk1/dev1/thing/property/set")).isFalse();
        assertThat(Topics.canPublish(ME, "hyperiot/pk1/dev1/thing/service/reboot/invoke")).isFalse();
        assertThat(Topics.canPublish(null, "hyperiot/pk1/dev1/thing/property/post")).isFalse();

        assertThat(Topics.canSubscribe(ME, "hyperiot/pk1/dev1/#")).isTrue();
        assertThat(Topics.canSubscribe(ME, "hyperiot/pk1/dev1/thing/service/+/invoke")).isTrue();
        assertThat(Topics.canSubscribe(ME, "hyperiot/pk1/dev2/#")).isFalse();
        assertThat(Topics.canSubscribe(ME, "hyperiot/#")).isFalse();
        assertThat(Topics.canSubscribe(ME, "#")).isFalse();
        assertThat(Topics.canSubscribe(ME, "hyperiot/pk1/+/#")).isFalse();
        // device name that is a prefix of another one must not leak: "dev1" vs "dev10"
        assertThat(Topics.canSubscribe(ME, "hyperiot/pk1/dev10/#")).isFalse();
    }

    @Test
    void credentialsRoundTrip() {
        assertThat(DeviceCredentials.parseClientId("pk1.dev1")).isEqualTo(ME);
        assertThat(DeviceCredentials.parseClientId("pk1.dev.with.dots").deviceName()).isEqualTo("dev.with.dots");
        assertThat(DeviceCredentials.parseClientId("nodot")).isNull();
        assertThat(DeviceCredentials.parseClientId(".dev")).isNull();
        assertThat(DeviceCredentials.parseClientId("pk.")).isNull();
        assertThat(DeviceCredentials.usernameMatches(ME, "dev1&pk1")).isTrue();
        assertThat(DeviceCredentials.usernameMatches(ME, "dev2&pk1")).isFalse();
        assertThat(DeviceCredentials.mqttPassword("secret", "pk1", "dev1"))
                .isEqualTo(DeviceCredentials.mqttPassword("secret", "pk1", "dev1"))
                .isNotEqualTo(DeviceCredentials.mqttPassword("secret2", "pk1", "dev1"))
                .isNotEqualTo(DeviceCredentials.mqttPassword("secret", "pk1", "dev2"))
                .hasSize(64);
    }

    @Test
    void passwordVerification() {
        String secret = "s3cretValue";
        String hmac = DeviceCredentials.mqttPassword(secret, "pk1", "dev1");
        // plain secret and HMAC signature are both accepted by default
        assertThat(DeviceCredentials.verifyPassword(secret, "pk1", "dev1", secret, true)).isTrue();
        assertThat(DeviceCredentials.verifyPassword(secret, "pk1", "dev1", hmac, true)).isTrue();
        // strict mode: only the signature
        assertThat(DeviceCredentials.verifyPassword(secret, "pk1", "dev1", secret, false)).isFalse();
        assertThat(DeviceCredentials.verifyPassword(secret, "pk1", "dev1", hmac, false)).isTrue();
        // wrong, empty, missing, and a signature computed for another device
        assertThat(DeviceCredentials.verifyPassword(secret, "pk1", "dev1", "wrong", true)).isFalse();
        assertThat(DeviceCredentials.verifyPassword(secret, "pk1", "dev1", "", true)).isFalse();
        assertThat(DeviceCredentials.verifyPassword(secret, "pk1", "dev1", null, true)).isFalse();
        assertThat(DeviceCredentials.verifyPassword(null, "pk1", "dev1", "x", true)).isFalse();
        assertThat(DeviceCredentials.verifyPassword(secret, "pk1", "dev1", DeviceCredentials.mqttPassword(secret, "pk1", "dev2"), true)).isFalse();
        // another device's plain secret never works
        assertThat(DeviceCredentials.verifyPassword(secret, "pk1", "dev1", "otherSecret", true)).isFalse();
    }
}
