package io.hyperiot.bus;

import io.hyperiot.common.Json;
import io.hyperiot.config.HyperIotProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Runs the Kafka bus against an in-JVM Kafka broker (KRaft). */
class KafkaEventBusTest {

    private static EmbeddedKafkaKraftBroker broker;
    private static KafkaEventBus bus;

    @BeforeAll
    static void start() throws Exception {
        broker = new EmbeddedKafkaKraftBroker(1, 1);
        broker.afterPropertiesSet();
        HyperIotProperties props = new HyperIotProperties();
        props.getBus().setBootstrapServers(broker.getBrokersAsString());
        props.getBus().setTopicPrefix("test." + System.nanoTime() + ".");
        bus = new KafkaEventBus(props);
        bus.init();
    }

    @AfterAll
    static void stop() {
        bus.shutdown();
        broker.destroy();
    }

    private static DeviceMessage message(MessageKind kind, long deviceId, int seq) {
        return DeviceMessage.of(kind, deviceId, "pk", "dev" + deviceId, 1000L + seq, Json.object().put("seq", seq));
    }

    @Test
    void deliversMessagesToEachGroupInOrderPerDevice() {
        List<Integer> groupA = new CopyOnWriteArrayList<>();
        List<Integer> groupB = new CopyOnWriteArrayList<>();
        bus.subscribe("a", Set.of(MessageKind.PROPERTY), m -> groupA.add(m.payload().get("seq").asInt()));
        bus.subscribe("b", Set.of(MessageKind.PROPERTY, MessageKind.EVENT), m -> groupB.add(m.payload().get("seq").asInt()));

        // consumers start at "latest": keep publishing a marker until both groups are assigned and see it
        await().atMost(java.time.Duration.ofSeconds(30)).pollInterval(java.time.Duration.ofMillis(500)).until(() -> {
            bus.publish(message(MessageKind.PROPERTY, 7, -1));
            return groupA.contains(-1) && groupB.contains(-1);
        });
        groupA.clear();
        groupB.clear();

        for (int i = 0; i < 20; i++) {
            bus.publish(message(MessageKind.PROPERTY, 7, i));
        }
        bus.publish(message(MessageKind.EVENT, 7, 100));
        bus.publish(message(MessageKind.STATUS, 7, 200)); // nobody subscribed to STATUS

        await().atMost(java.time.Duration.ofSeconds(15)).until(() -> groupA.contains(19) && groupB.contains(100));
        // all messages of one device go through one partition, so they arrive in order
        assertThat(groupA.stream().filter(i -> i >= 0 && i < 100).toList()).startsWith(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
        assertThat(groupA).doesNotContain(100, 200);
        assertThat(groupB).contains(100).doesNotContain(200);
    }
}
