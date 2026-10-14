package io.hyperiot;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.access.UplinkProcessor;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceRepository;
import io.hyperiot.device.DeviceService;
import io.hyperiot.device.DeviceStatusService;
import io.hyperiot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** A device that sends data is online, even if the platform never saw it connect (restart, missed event). */
class StatusSelfHealIT extends IntegrationTestBase {

    @Autowired
    UplinkProcessor uplink;
    @Autowired
    DeviceService devices;
    @Autowired
    DeviceRepository repo;
    @Autowired
    DeviceStatusService status;

    private void send(Fixture f, String json) {
        uplink.onMqtt("hyperiot/" + f.productKey() + "/" + f.deviceName() + "/thing/property/post", json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aDeviceThatSendsDataIsMarkedOnline() {
        Fixture f = createFixture();
        assertThat(device(f).path("status").asText()).isEqualTo("INACTIVE");

        send(f, "{\"params\":{\"temperature\":21.5}}");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode d = device(f);
            assertThat(d.path("status").asText()).isEqualTo("ONLINE");
            assertThat(d.path("lastOnlineAt").asLong()).isPositive();
        });
    }

    @Test
    void aDeviceThatWasMarkedOfflineEarlierComesBackOnItsNextMessage() {
        Fixture f = createFixture();
        Device d = devices.get(f.deviceId());
        // it went offline a while ago (and this node has no memory of it being online, like after a restart)
        repo.markOffline(f.deviceId(), System.currentTimeMillis() - 60_000);
        assertThat(device(f).path("status").asText()).isEqualTo("OFFLINE");

        send(f, "{\"params\":{\"temperature\":22.5}}");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(device(f).path("status").asText()).isEqualTo("ONLINE"));
    }

    @Test
    void aLateMessageOfAConnectionThatJustClosedDoesNotResurrectTheDevice() throws Exception {
        Fixture f = createFixture();
        Device d = devices.get(f.deviceId());
        status.onlineNow(d, "10.0.0.1");
        assertThat(device(f).path("status").asText()).isEqualTo("ONLINE");

        status.offline(d); // the broker reported the disconnect
        send(f, "{\"params\":{\"temperature\":23.5}}"); // a straggler that was still in flight
        Thread.sleep(500);

        assertThat(device(f).path("status").asText()).as("a message right after the disconnect must not flip it back").isEqualTo("OFFLINE");
    }
}
