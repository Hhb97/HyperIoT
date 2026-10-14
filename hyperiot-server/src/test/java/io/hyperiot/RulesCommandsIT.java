package io.hyperiot;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.common.Json;
import io.hyperiot.support.IntegrationTestBase;
import io.hyperiot.support.TestPlatform;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RulesCommandsIT extends IntegrationTestBase {

    private JsonNode createRule(Fixture f, String level) {
        String rule = """
                {"name":"high temperature","productKey":"%s","logic":"AND","level":"%s",
                 "conditions":[{"identifier":"temperature","op":"GT","value":80}],
                 "actions":[{"type":"COMMAND","identifier":"fanOn","params":{}}]}
                """.formatted(f.productKey(), level);
        TestPlatform.Response r = api.post("/api/rules", rule);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    @Test
    void ruleRaisesAlarmRunsActionAndRecovers() throws Exception {
        Fixture f = createFixture();
        createRule(f, "CRITICAL");
        try (TestPlatform.MqttDevice d = connect(f)) {
            d.publish("thing/property/post", "{\"params\":{\"temperature\":85.5}}");

            // alarm raised
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode alarms = api.get("/api/alarms?status=ACTIVE").json();
                assertThat(alarms.path("items").toString()).contains(f.deviceName());
            });
            JsonNode alarm = null;
            for (JsonNode a : api.get("/api/alarms?status=ACTIVE").json().path("items")) {
                if (a.path("deviceName").asText().equals(f.deviceName())) {
                    alarm = a;
                }
            }
            assertThat(alarm).isNotNull();
            assertThat(alarm.path("level").asText()).isEqualTo("CRITICAL");
            assertThat(alarm.path("message").asText()).contains("temperature=85.5");

            // rule action: the device received the fanOn command and answers it
            var invoke = d.await("thing/service/fanOn/invoke", 5000);
            assertThat(invoke).as("fanOn command delivered to the device").isNotNull();
            String requestId = invoke.payload().path("id").asText();
            d.publish("thing/service/fanOn/reply", "{\"id\":\"" + requestId + "\",\"code\":200,\"data\":{}}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode cmds = api.get("/api/devices/" + f.deviceId() + "/commands").json();
                assertThat(cmds.path("items").path(0).path("status").asText()).isEqualTo("SUCCESS");
            });

            // a second hot report must not raise a second alarm
            d.publish("thing/property/post", "{\"params\":{\"temperature\":90.0}}");
            Thread.sleep(500);
            int active = 0;
            for (JsonNode a : api.get("/api/alarms?status=ACTIVE").json().path("items")) {
                if (a.path("deviceName").asText().equals(f.deviceName())) {
                    active++;
                }
            }
            assertThat(active).isEqualTo(1);

            // acknowledge, then cool down: the alarm recovers
            long alarmId = alarm.path("id").asLong();
            assertThat(api.post("/api/alarms/" + alarmId + "/ack", null).status()).isEqualTo(204);
            d.publish("thing/property/post", "{\"params\":{\"temperature\":50.0}}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode all = api.get("/api/alarms?status=RECOVERED").json();
                assertThat(all.path("items").toString()).contains("\"id\":" + alarmId);
            });
        }
    }

    @Test
    void ruleValidationRejectsUnknownPropertiesAndBadOperators() {
        Fixture f = createFixture();
        String unknown = """
                {"name":"x","productKey":"%s","conditions":[{"identifier":"nope","op":"GT","value":1}]}""".formatted(f.productKey());
        assertThat(api.post("/api/rules", unknown).status()).isEqualTo(400);
        String badOp = """
                {"name":"x","productKey":"%s","conditions":[{"identifier":"temperature","op":"LIKE","value":1}]}""".formatted(f.productKey());
        assertThat(api.post("/api/rules", badOp).status()).isEqualTo(400);
        String stringThreshold = """
                {"name":"x","productKey":"%s","conditions":[{"identifier":"temperature","op":"GT","value":"hot"}]}""".formatted(f.productKey());
        assertThat(api.post("/api/rules", stringThreshold).status()).isEqualTo(400);
        String badAction = """
                {"name":"x","productKey":"%s","conditions":[{"identifier":"temperature","op":"GT","value":1}],
                 "actions":[{"type":"COMMAND","identifier":"missingService","params":{}}]}""".formatted(f.productKey());
        assertThat(api.post("/api/rules", badAction).status()).isEqualTo(400);
    }

    @Test
    void invokeAndWaitReturnsDeviceReply() throws Exception {
        Fixture f = createFixture();
        try (TestPlatform.MqttDevice d = connect(f)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> "ONLINE".equals(device(f).path("status").asText()));
            // the device answers asynchronously while the API call is blocking
            Thread responder = new Thread(() -> {
                try {
                    var invoke = d.await("thing/service/reboot/invoke", 5000);
                    assertThat(invoke.payload().path("params").path("delaySeconds").asInt()).isEqualTo(5);
                    d.publish("thing/service/reboot/reply", "{\"id\":\"" + invoke.payload().path("id").asText()
                            + "\",\"code\":200,\"data\":{\"ok\":true}}");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            responder.start();
            TestPlatform.Response r = api.post("/api/devices/" + f.deviceId() + "/services/reboot/invoke?wait=true&timeoutMs=5000",
                    "{\"params\":{\"delaySeconds\":5}}");
            responder.join();
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.json().path("status").asText()).isEqualTo("SUCCESS");
            assertThat(r.json().path("response").path("data").path("ok").asBoolean()).isTrue();
        }
    }

    @Test
    void deviceErrorReplyMarksCommandFailed() throws Exception {
        Fixture f = createFixture();
        try (TestPlatform.MqttDevice d = connect(f)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> "ONLINE".equals(device(f).path("status").asText()));
            JsonNode cmd = api.post("/api/devices/" + f.deviceId() + "/services/reboot/invoke", "{\"params\":{\"delaySeconds\":1}}").json();
            assertThat(cmd.path("status").asText()).isEqualTo("SENT");
            var invoke = d.await("thing/service/reboot/invoke", 5000);
            d.publish("thing/service/reboot/reply", "{\"id\":\"" + invoke.payload().path("id").asText() + "\",\"code\":500,\"msg\":\"busy\"}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(api.get("/api/commands?status=FAILED").body()).contains(cmd.path("requestId").asText()));
        }
    }

    @Test
    void invalidServiceParametersAreRejected() {
        Fixture f = createFixture();
        assertThat(api.post("/api/devices/" + f.deviceId() + "/services/reboot/invoke", "{\"params\":{\"delaySeconds\":999}}").status()).isEqualTo(400);
        assertThat(api.post("/api/devices/" + f.deviceId() + "/services/reboot/invoke", "{\"params\":{}}").status()).isEqualTo(400);
        assertThat(api.post("/api/devices/" + f.deviceId() + "/services/unknown/invoke", "{}").status()).isEqualTo(400);
    }

    @Test
    void commandToOfflineDeviceIsQueuedAndDeliveredOnReconnect() throws Exception {
        Fixture f = createFixture();
        JsonNode cmd = api.post("/api/devices/" + f.deviceId() + "/services/fanOn/invoke", "{\"ttlSeconds\":120}").json();
        assertThat(cmd.path("status").asText()).isEqualTo("PENDING");

        try (TestPlatform.MqttDevice d = connect(f)) {
            var invoke = d.await("thing/service/fanOn/invoke", 5000);
            assertThat(invoke).as("queued command delivered after the device came online").isNotNull();
            assertThat(invoke.payload().path("id").asText()).isEqualTo(cmd.path("requestId").asText());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(api.get("/api/devices/" + f.deviceId() + "/commands").json().path("items").path(0).path("status").asText())
                            .isEqualTo("SENT"));
        }
    }

    @Test
    void commandsExpireWhenNoReplyComesBack() throws Exception {
        Fixture f = createFixture();
        // 1 second TTL, device never connects
        JsonNode cmd = api.post("/api/devices/" + f.deviceId() + "/services/fanOn/invoke", "{\"ttlSeconds\":1}").json();
        assertThat(cmd.path("status").asText()).isEqualTo("PENDING");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(api.get("/api/commands?status=TIMEOUT").body()).contains(cmd.path("requestId").asText()));
    }

    @Test
    void desiredStateIsPushedAndClearedWhenDeviceApplesIt() throws Exception {
        Fixture f = createFixture();
        try (TestPlatform.MqttDevice d = connect(f)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> "ONLINE".equals(device(f).path("status").asText()));

            // read-only properties cannot be set
            assertThat(api.put("/api/devices/" + f.deviceId() + "/desired", Map.of("params", Map.of("temperature", 20))).status()).isEqualTo(400);

            assertThat(api.put("/api/devices/" + f.deviceId() + "/desired", Map.of("params", Map.of("mode", "manual", "fan", true))).status())
                    .isEqualTo(200);
            var set = d.await("thing/property/set", 5000);
            assertThat(set).isNotNull();
            assertThat(set.payload().path("params").path("mode").asText()).isEqualTo("manual");
            assertThat(set.payload().path("params").path("fan").asBoolean()).isTrue();
            assertThat(shadow(f).path("desired").path("mode").asText()).isEqualTo("manual");

            // device applies and reports: desired entries are cleared
            d.publish("thing/property/post", "{\"params\":{\"mode\":\"manual\",\"fan\":true}}");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode s = shadow(f);
                assertThat(s.path("reported").path("mode").path("value").asText()).isEqualTo("manual");
                assertThat(s.path("desired").size()).isZero();
            });
        }
    }

    @Test
    void desiredStateIsDeliveredWhenDeviceComesOnline() throws Exception {
        Fixture f = createFixture();
        assertThat(api.put("/api/devices/" + f.deviceId() + "/desired", Map.of("params", Map.of("mode", "auto"))).status()).isEqualTo(200);
        try (TestPlatform.MqttDevice d = connect(f)) {
            var set = d.await("thing/property/set", 5000);
            assertThat(set).isNotNull();
            assertThat(set.payload().path("params").path("mode").asText()).isEqualTo("auto");
        }
    }

    @Test
    void thingModelValidationRejectsBadDefinitions() {
        Fixture f = createFixture();
        String duplicate = """
                {"properties":[{"identifier":"a","type":"int"},{"identifier":"a","type":"int"}]}""";
        assertThat(api.put("/api/products/" + f.productId() + "/thing-model", duplicate).status()).isEqualTo(400);
        String badType = """
                {"properties":[{"identifier":"a","type":"decimal"}]}""";
        assertThat(api.put("/api/products/" + f.productId() + "/thing-model", badType).status()).isEqualTo(400);
        String badIdentifier = """
                {"properties":[{"identifier":"1bad name","type":"int"}]}""";
        assertThat(api.put("/api/products/" + f.productId() + "/thing-model", badIdentifier).status()).isEqualTo(400);
        String enumWithoutValues = """
                {"properties":[{"identifier":"a","type":"enum"}]}""";
        assertThat(api.put("/api/products/" + f.productId() + "/thing-model", enumWithoutValues).status()).isEqualTo(400);
        assertThat(Json.write(api.get("/api/products/" + f.productId() + "/thing-model").json()))
                .as("the previous valid model is untouched").contains("temperature");
    }
}
