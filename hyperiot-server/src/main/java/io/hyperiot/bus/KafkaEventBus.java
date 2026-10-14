package io.hyperiot.bus;

import io.hyperiot.common.Json;
import io.hyperiot.config.HyperIotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Cluster bus: one Kafka topic per message kind, keyed by device id so that all messages of a device land in
 * the same partition (ordering). Subscribers are consumer groups, so several platform nodes share the load.
 * Enable with {@code hyperiot.bus.type=kafka}.
 */
@Component
@ConditionalOnProperty(name = "hyperiot.bus.type", havingValue = "kafka")
public class KafkaEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBus.class);
    private static final int TOPIC_PARTITIONS = 6;

    private final HyperIotProperties.Bus cfg;
    private KafkaProducer<String, String> producer;
    private final List<Poller> pollers = new CopyOnWriteArrayList<>();

    public KafkaEventBus(HyperIotProperties props) {
        this.cfg = props.getBus();
    }

    @PostConstruct
    void init() throws Exception {
        Properties admin = new Properties();
        admin.put("bootstrap.servers", cfg.getBootstrapServers());
        try (AdminClient client = AdminClient.create(admin)) {
            List<NewTopic> topics = new ArrayList<>();
            for (MessageKind kind : MessageKind.values()) {
                topics.add(new NewTopic(topic(kind), TOPIC_PARTITIONS, (short) 1));
            }
            try {
                client.createTopics(topics).all().get();
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    log.debug("createTopics: {}", e.getCause().toString());
                }
            }
        }
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "1");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        producer = new KafkaProducer<>(p);
    }

    private String topic(MessageKind kind) {
        return cfg.getTopicPrefix() + kind.topicSuffix();
    }

    @Override
    public void publish(DeviceMessage message) {
        producer.send(new ProducerRecord<>(topic(message.kind()), Long.toString(message.deviceId()), Json.write(message)),
                (meta, ex) -> {
                    if (ex != null) {
                        log.warn("Kafka publish failed for {}: {}", message.kind(), ex.toString());
                    }
                });
    }

    @Override
    public void subscribe(String group, Set<MessageKind> kinds, Consumer<DeviceMessage> handler) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, cfg.getGroupPrefix() + group);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p);
        consumer.subscribe(kinds.stream().map(this::topic).toList());
        Poller poller = new Poller(group, consumer, handler);
        pollers.add(poller);
        poller.start();
    }

    @PreDestroy
    void shutdown() {
        pollers.forEach(Poller::shutdown);
        if (producer != null) {
            producer.close(Duration.ofSeconds(2));
        }
    }

    private static final class Poller extends Thread {
        private final String group;
        private final KafkaConsumer<String, String> consumer;
        private final Consumer<DeviceMessage> handler;
        private volatile boolean running = true;

        Poller(String group, KafkaConsumer<String, String> consumer, Consumer<DeviceMessage> handler) {
            super("kafka-bus-" + group);
            setDaemon(true);
            this.group = group;
            this.consumer = consumer;
            this.handler = handler;
        }

        @Override
        public void run() {
            try {
                while (running) {
                    for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                        try {
                            handler.accept(Json.read(r.value(), DeviceMessage.class));
                        } catch (Exception e) {
                            log.error("Subscriber '{}' failed on record {}-{}@{}", group, r.topic(), r.partition(), r.offset(), e);
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Kafka poller '{}' stopped", group, e);
                }
            } finally {
                consumer.close(Duration.ofSeconds(2));
            }
        }

        void shutdown() {
            running = false;
            consumer.wakeup();
        }
    }
}
