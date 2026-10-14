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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Connects the platform to an external MQTT broker cluster (EMQX). Devices authenticate against the broker, which
 * calls back {@code /api/mqtt/auth} and {@code /api/mqtt/acl}; the platform subscribes with a shared subscription
 * so several nodes split the uplink traffic, and publishes downlink messages through the same connection.
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
    private volatile MqttClient client;
    private volatile boolean stopping;

    public ExternalMqttGateway(HyperIotProperties props, UplinkProcessor uplink, DeviceService devices, DeviceStatusService status) {
        this.cfg = props.getMqtt();
        this.uplink = uplink;
        this.devices = devices;
        this.status = status;
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
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        log.warn("Lost connection to MQTT broker: {}", cause == null ? "" : cause.toString());
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        try {
                            if (topic.startsWith("$SYS/")) {
                                onSystemEvent(topic, message.getPayload());
                                return;
                            }
                            uplink.onMqtt(topic, message.getPayload());
                        } catch (Exception e) {
                            log.warn("Failed to process message on {}: {}", topic, e.toString());
                        }
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                    }
                });
                MqttConnectOptions o = new MqttConnectOptions();
                o.setAutomaticReconnect(true);
                o.setCleanSession(true);
                o.setKeepAliveInterval(30);
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
