package io.hyperiot;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.support.IntegrationTestBase;
import io.hyperiot.support.TestPlatform;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class MqttAccessIT extends IntegrationTestBase {

    @Test
    void deviceConnectsAndGoesOnlineAndOffline() throws Exception {
        Fixture f = createFixture();
        assertThat(device(f).path("status").asText()).isEqualTo("INACTIVE");

        try (TestPlatform.MqttDevice d = connect(f)) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(device(f).path("status").asText()).isEqualTo("ONLINE"));
            assertThat(device(f).path("lastOnlineAt").asLong()).isPositive();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(device(f).path("status").asText()).isEqualTo("OFFLINE"));
    }

    @Test
    void wrongPasswordIsRejected() {
        Fixture f = createFixture();
        assertThatThrownBy(() -> TestPlatform.MqttDevice.connectWithPassword(MQTT_PORT, f.productKey(), f.deviceName(), "not-the-signature"))
                .isInstanceOf(MqttException.class);
    }

    @Test
    void unknownDeviceIsRejected() {
        assertThatThrownBy(() -> TestPlatform.MqttDevice.connect(MQTT_PORT, "nosuchproduct", "ghost", "whatever"))
                .isInstanceOf(MqttException.class);
    }

    @Test
    void disabledDeviceCannotConnectAndIsKickedWhenDisabled() throws Exception {
        Fixture f = createFixture();
        try (TestPlatform.MqttDevice d = connect(f)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> "ONLINE".equals(device(f).path("status").asText()));
            assertThat(api.put("/api/devices/" + f.deviceId(), "{\"enabled\":false}").status()).isEqualTo(200);
            await().atMost(Duration.ofSeconds(5)).until(() -> !d.isConnected());
        }
        assertThatThrownBy(() -> TestPlatform.MqttDevice.connect(MQTT_PORT, f.productKey(), f.deviceName(), f.secret()))
                .isInstanceOf(MqttException.class);
    }

    @Test
    void deviceCannotSubscribeToAnotherDevicesTopics() throws Exception {
        Fixture a = createFixture();
        Fixture b = createFixture();
        try (TestPlatform.MqttDevice da = TestPlatform.MqttDevice.connect(MQTT_PORT, a.productKey(), a.deviceName(), a.secret())) {
            var token = da.client().subscribeWithResponse("hyperiot/" + b.productKey() + "/" + b.deviceName() + "/#", 1);
            assertThat(token.getGrantedQos()[0]).as("subscription to a foreign namespace must be refused (0x80)").isEqualTo(128);
            var own = da.client().subscribeWithResponse("hyperiot/" + a.productKey() + "/" + a.deviceName() + "/#", 1);
            assertThat(own.getGrantedQos()[0]).isNotEqualTo(128);
        }
    }

    @Test
    void devicePublishingToAnotherDevicesTopicIsIgnored() throws Exception {
        Fixture a = createFixture();
        Fixture b = createFixture();
        try (TestPlatform.MqttDevice da = TestPlatform.MqttDevice.connect(MQTT_PORT, a.productKey(), a.deviceName(), a.secret())) {
            var msg = new org.eclipse.paho.client.mqttv3.MqttMessage("{\"params\":{\"temperature\":1.5}}".getBytes());
            msg.setQos(0);
            da.client().publish("hyperiot/" + b.productKey() + "/" + b.deviceName() + "/thing/property/post", msg);
            Thread.sleep(500);
            assertThat(shadow(b).path("reported").size()).isZero();
        }
    }

    @Test
    void propertyReportFlowsToShadowTelemetryAndReplies() throws Exception {
        Fixture f = createFixture();
        try (TestPlatform.MqttDevice d = connect(f)) {
            long ts = System.currentTimeMillis() - 1000;
            d.publish("thing/property/post",
                    "{\"id\":\"42\",\"ts\":" + ts + ",\"params\":{\"temperature\":25.5,\"humidity\":60}}");

            var reply = d.await("thing/property/post_reply", 5000);
            assertThat(reply).isNotNull();
            assertThat(reply.payload().path("id").asText()).isEqualTo("42");
            assertThat(reply.payload().path("code").asInt()).isEqualTo(200);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode reported = shadow(f).path("reported");
                assertThat(reported.path("temperature").path("value").asDouble()).isEqualTo(25.5);
                assertThat(reported.path("humidity").path("value").asLong()).isEqualTo(60);
                assertThat(reported.path("temperature").path("ts").asLong()).isEqualTo(ts);
            });

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode t = api.get("/api/devices/" + f.deviceId() + "/telemetry?identifier=temperature").json();
                assertThat(t.path("points")).hasSize(1);
                assertThat(t.path("points").path(0).path("value").asDouble()).isEqualTo(25.5);
            });
        }
    }

    @Test
    void invalidValuesAreRejectedAgainstTheThingModel() throws Exception {
        Fixture f = createFixture();
        try (TestPlatform.MqttDevice d = connect(f)) {
            // humidity out of range, undeclared property, wrong type; only temperature is valid
            d.publish("thing/property/post",
                    "{\"id\":\"7\",\"params\":{\"temperature\":20.0,\"humidity\":500,\"bogus\":1,\"mode\":\"turbo\"}}");
            var reply = d.await("thing/property/post_reply", 5000);
            assertThat(reply.payload().path("code").asInt()).isEqualTo(400);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode reported = shadow(f).path("reported");
                assertThat(reported.has("temperature")).isTrue();
                assertThat(reported.has("humidity")).isFalse();
                assertThat(reported.has("bogus")).isFalse();
                assertThat(reported.has("mode")).isFalse();
            });
            // and the rejection is visible in the device log
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(api.get("/api/devices/" + f.deviceId() + "/logs").body()).contains("被拒绝"));
        }
    }

    @Test
    void eventsAreStored() throws Exception {
        Fixture f = createFixture();
        try (TestPlatform.MqttDevice d = connect(f)) {
            d.publish("thing/event/overheat/post", "{\"params\":{\"temp\":99.5}}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode events = api.get("/api/devices/" + f.deviceId() + "/events").json();
                assertThat(events.path("total").asInt()).isEqualTo(1);
                assertThat(events.path("items").path(0).path("identifier").asText()).isEqualTo("overheat");
                assertThat(events.path("items").path(0).path("level").asText()).isEqualTo("WARN");
                assertThat(events.path("items").path(0).path("params").path("temp").asDouble()).isEqualTo(99.5);
            });
        }
    }

    @Test
    void shadowGetReturnsFullState() throws Exception {
        Fixture f = createFixture();
        try (TestPlatform.MqttDevice d = connect(f)) {
            d.publish("thing/property/post", "{\"params\":{\"temperature\":30.0}}");
            await().atMost(Duration.ofSeconds(5)).until(() -> shadow(f).path("reported").has("temperature"));
            d.publish("shadow/get", "{}");
            var reply = d.await("shadow/get_reply", 5000);
            assertThat(reply).isNotNull();
            assertThat(reply.payload().path("state").path("reported").path("temperature").asDouble()).isEqualTo(30.0);
        }
    }

    @Test
    void aggregatedTelemetryQueryBucketsValues() throws Exception {
        Fixture f = createFixture();
        long base = System.currentTimeMillis() / 60_000 * 60_000 - 10 * 60_000;
        try (TestPlatform.MqttDevice d = connect(f)) {
            // two samples in one minute bucket, one in the next
            d.publish("thing/property/post", "{\"ts\":" + (base + 1000) + ",\"params\":{\"temperature\":10.0}}");
            d.publish("thing/property/post", "{\"ts\":" + (base + 2000) + ",\"params\":{\"temperature\":20.0}}");
            d.publish("thing/property/post", "{\"ts\":" + (base + 61_000) + ",\"params\":{\"temperature\":40.0}}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode raw = api.get("/api/devices/" + f.deviceId() + "/telemetry?identifier=temperature&from=" + base + "&to=" + (base + 120_000)).json();
                assertThat(raw.path("points")).hasSize(3);
            });
            JsonNode avg = api.get("/api/devices/" + f.deviceId() + "/telemetry?identifier=temperature&agg=avg&interval=60000&from="
                    + base + "&to=" + (base + 120_000)).json();
            assertThat(avg.path("points")).hasSize(2);
            assertThat(avg.path("points").path(0).path("value").asDouble()).isEqualTo(15.0);
            assertThat(avg.path("points").path(1).path("value").asDouble()).isEqualTo(40.0);
            JsonNode max = api.get("/api/devices/" + f.deviceId() + "/telemetry?identifier=temperature&agg=max&interval=60000&from="
                    + base + "&to=" + (base + 120_000)).json();
            assertThat(max.path("points").path(0).path("value").asDouble()).isEqualTo(20.0);
            JsonNode last = api.get("/api/devices/" + f.deviceId() + "/telemetry?identifier=temperature&agg=last&interval=60000&from="
                    + base + "&to=" + (base + 120_000)).json();
            assertThat(last.path("points").path(0).path("value").asDouble()).isEqualTo(20.0);
        }
    }
}
