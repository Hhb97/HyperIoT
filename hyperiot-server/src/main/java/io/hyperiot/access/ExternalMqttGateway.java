package io.hyperiot.access;

import io.hyperiot.config.HyperIotProperties;
import io.hyperiot.device.DeviceCredentials;
import io.hyperiot.device.DeviceService;
import io.hyperiot.device.DeviceStatusService;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connects the platform to an external MQTT broker cluster (EMQX). Devices authenticate against the broker, which
 * calls back {@code /api/mqtt/auth} and {@code /api/mqtt/acl}; the platform subscribes with a shared subscription
 * so several nodes split the uplink traffic, and publishes downlink messages through the same connection.
 *
 * <p>The Paho callback does nothing but hand a message to a worker and return. Paho has one callback thread and an
 * inbound queue of only 10 messages: once it is full the thread that reads the socket waits for the callback thread, and
 * a callback that itself waits for the broker (the acknowledgement of a reply it publishes arrives through the very same
 * socket) is a deadlock. The connection then looks dead, Paho reconnects after the keep-alive timeout and every device
 * connect / disconnect event of that time is lost. Messages of one device always go to the same worker, so their order
 * (connected, data, disconnected) is kept.
 */
@Component
@ConditionalOnProperty(name = "hyperiot.mqtt.mode", havingValue = "external")
public class ExternalMqttGateway implements DownlinkChannel {

    private static final Logger log = LoggerFactory.getLogger(ExternalMqttGateway.class);

    private final HyperIotProperties.Mqtt cfg;
    private static final String SYS_CONNECTED = "$SYS/brokers/+/clients/+/connected";
    private static final String SYS_DISCONNECTED = "$SYS/brokers/+/clients/+/disconnected";

    private final UplinkProcessor uplink;
    private final DeviceService devices;
    private final DeviceStatusService status;
    private final ApplicationEventPublisher events;
    private volatile MqttClient client;
    private volatile boolean stopping;
    private final ThreadPoolExecutor[] workers;
    private final AtomicLong dropped = new AtomicLong();

    public ExternalMqttGateway(HyperIotProperties props, UplinkProcessor uplink, DeviceService devices, DeviceStatusService status,
                               ApplicationEventPublisher events) {
        this.cfg = props.getMqtt();
        this.uplink = uplink;
        this.devices = devices;
        this.status = status;
        this.events = events;
        this.workers = new ThreadPoolExecutor[Math.max(1, cfg.getWorkers())];
        for (int i = 0; i < workers.length; i++) {
            AtomicInteger n = new AtomicInteger();
            int id = i;
            workers[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(cfg.getWorkerQueue()), r -> {
                Thread t = new Thread(r, "mqtt-uplink-" + id);
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
        }
    }

    /** Published when the connection to the broker came back after a loss: connect / disconnect events may have been missed. */
    public record Reconnected() {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread t = new Thread(this::connectWithRetry, "mqtt-external-connect");
        t.setDaemon(true);
        t.start();
    }

    private void connectWithRetry() {
        while (!stopping && (client == null || !client.isConnected())) {
            try {
                MqttClient c = new MqttClient(cfg.getBrokerUrl(), cfg.getClientId(), new MemoryPersistence());
                c.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverUri) {
                        subscribe(c);
                        if (reconnect) {
                            log.warn("Reconnected to the MQTT broker; device connect / disconnect events during the outage were missed");
                            events.publishEvent(new Reconnected());
                        }
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        log.warn("Lost connection to MQTT broker: {}", cause == null ? "" : cause.toString());
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        dispatch(topic, message.getPayload()); // must return at once, see the class comment
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                    }
                });
                MqttConnectOptions o = new MqttConnectOptions();
                o.setAutomaticReconnect(true);
                o.setCleanSession(true);
                o.setKeepAliveInterval(30);
                c.setTimeToWait(30_000); // a blocking call gives up instead of hanging for ever
                o.setUserName(cfg.getUsername());
                o.setPassword(cfg.getPassword().toCharArray());
                c.connect(o);
                client = c;
                log.info("Connected to external MQTT broker {}", cfg.getBrokerUrl());
                return;
            } catch (MqttException e) {
                log.warn("Cannot connect to MQTT broker {} ({}), retrying in 5s", cfg.getBrokerUrl(), e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Hands the message to the worker that owns its device; never blocks, a full queue drops the message and says so. */
    private void dispatch(String topic, byte[] payload) {
        ThreadPoolExecutor worker = workers[Math.floorMod(routingKey(topic).hashCode(), workers.length)];
        try {
            worker.execute(() -> handle(topic, payload));
        } catch (RejectedExecutionException e) {
            if (!stopping && dropped.getAndIncrement() % 1000 == 0) {
                log.warn("MQTT message queue is full, {} messages dropped so far (the platform is not keeping up)", dropped.get());
            }
        }
    }

    private void handle(String topic, byte[] payload) {
        try {
            if (topic.startsWith("$SYS/")) {
                onSystemEvent(topic, payload);
                return;
            }
            uplink.onMqtt(topic, payload);
        } catch (Exception e) {
            log.warn("Failed to process message on {}: {}", topic, e.toString());
        }
    }

    /** The client id of the device a message is about: {@code productKey.deviceName}, for device topics and for $SYS events alike. */
    static String routingKey(String topic) {
        String[] p = topic.split("/", 7);
        if (topic.startsWith("$SYS/") && p.length >= 6) { // $SYS/brokers/<node>/clients/<clientid>/connected
            return p[4];
        }
        if (p.length >= 3 && "hyperiot".equals(p[0])) { // hyperiot/<productKey>/<deviceName>/...
            return p[1] + "." + p[2];
        }
        return topic;
    }

    private void subscribe(MqttClient c) {
        try {
            c.subscribe(cfg.getSubscription(), 1);
            log.info("Subscribed to {}", cfg.getSubscription());
            // EMQX publishes client connect / disconnect events on $SYS topics; they drive the online state of devices
            c.subscribe(new String[]{SYS_CONNECTED, SYS_DISCONNECTED}, new int[]{1, 1});
        } catch (MqttException e) {
            log.error("Subscribe to {} failed", cfg.getSubscription(), e);
        }
    }

    /** {@code $SYS/brokers/<node>/clients/<clientid>/connected|disconnected}, payload is JSON with the client details. */
    private void onSystemEvent(String topic, byte[] payload) {
        boolean connected = topic.endsWith("/connected");
        if (!connected && !topic.endsWith("/disconnected")) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode n = io.hyperiot.common.Json.tree(payload);
        DeviceCredentials.Identity id = DeviceCredentials.parseClientId(n.path("clientid").asText(null));
        if (id == null) {
            return;
        }
        devices.resolve(id.productKey(), id.deviceName()).ifPresent(d -> {
            if (connected) {
                status.online(d, n.path("ipaddress").asText(null));
            } else {
                status.offline(d);
            }
        });
    }

    @Override
    public boolean send(String productKey, String deviceName, String topic, String payload) {
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            return false;
        }
        try {
            MqttMessage m = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            m.setQos(1);
            c.publish(topic, m);
            return true;
        } catch (MqttException e) {
            log.warn("Publish to {} failed: {}", topic, e.getMessage());
            return false;
        }
    }

    @PreDestroy
    void stop() {
        stopping = true;
        for (ThreadPoolExecutor w : workers) {
            w.shutdownNow();
        }
        MqttClient c = client;
        if (c != null) {
            try {
                // no quiesce wait and no DISCONNECT packet: the broker may already be gone and must not stall shutdown
                c.disconnectForcibly(0, 500, false);
                c.close(true);
            } catch (MqttException ignored) {
                // shutting down
            }
        }
    }
}
