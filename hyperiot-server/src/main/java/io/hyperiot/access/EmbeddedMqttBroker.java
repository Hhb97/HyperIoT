package io.hyperiot.access;

import io.hyperiot.config.HyperIotProperties;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceCredentials;
import io.hyperiot.device.DeviceService;
import io.hyperiot.device.DeviceStatusService;
import io.moquette.broker.Server;
import io.moquette.broker.ClientDescriptor;
import io.moquette.broker.config.MemoryConfig;
import io.moquette.broker.security.IAuthenticator;
import io.moquette.broker.security.IAuthorizatorPolicy;
import io.moquette.broker.subscriptions.Topic;
import io.moquette.interception.AbstractInterceptHandler;
import io.moquette.interception.InterceptHandler;
import io.moquette.interception.messages.InterceptConnectMessage;
import io.moquette.interception.messages.InterceptConnectionLostMessage;
import io.moquette.interception.messages.InterceptDisconnectMessage;
import io.moquette.interception.messages.InterceptPublishMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in MQTT 3.1.1 broker (Moquette) for standalone deployments and development. Devices authenticate with the
 * HMAC scheme from {@link DeviceCredentials} and can only touch their own topic namespace.
 * For production traffic use an external broker cluster (EMQX) via {@code hyperiot.mqtt.mode=external}.
 */
@Component
@ConditionalOnProperty(name = "hyperiot.mqtt.mode", havingValue = "embedded", matchIfMissing = true)
public class EmbeddedMqttBroker implements DownlinkChannel {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedMqttBroker.class);
    private static final String PLATFORM_CLIENT = "hyperiot-platform";

    private final HyperIotProperties props;
    private final DeviceService devices;
    private final DeviceStatusService status;
    private final UplinkProcessor uplink;
    private final Set<String> connected = ConcurrentHashMap.newKeySet();
    private volatile Server server;

    public EmbeddedMqttBroker(HyperIotProperties props, DeviceService devices, DeviceStatusService status, UplinkProcessor uplink) {
        this.props = props;
        this.devices = devices;
        this.status = status;
        this.uplink = uplink;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws IOException {
        Properties p = new Properties();
        p.setProperty("host", props.getMqtt().getHost());
        p.setProperty("port", Integer.toString(props.getMqtt().getPort()));
        p.setProperty("allow_anonymous", "false");
        p.setProperty("persistence_enabled", "false");
        p.setProperty("telemetry_enabled", "false");
        p.setProperty("use_bugsnag", "false");
        p.setProperty("netty.mqtt.message_size", Integer.toString(props.getMqtt().getMaxMessageBytes()));
        p.setProperty("websocket_port", "disabled");
        Server s = new Server();
        s.startServer(new MemoryConfig(p), List.<InterceptHandler>of(new Interceptor()), null, new Authenticator(), new Acl());
        server = s;
        log.info("Embedded MQTT broker listening on {}:{}", props.getMqtt().getHost(), s.getPort());
    }

    @PreDestroy
    public void stop() {
        Server s = server;
        if (s != null) {
            s.stopServer();
            server = null;
        }
    }

    public int port() {
        Server s = server;
        return s == null ? -1 : s.getPort();
    }

    // ---------------------------------------------------------------- downlink

    @Override
    public boolean send(String productKey, String deviceName, String topic, String payload) {
        Server s = server;
        String clientId = DeviceCredentials.clientId(productKey, deviceName);
        if (s == null || !connected.contains(clientId)) {
            return false;
        }
        MqttPublishMessage msg = MqttMessageBuilders.publish()
                .topicName(topic)
                .retained(false)
                .qos(MqttQoS.AT_LEAST_ONCE)
                .payload(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8)))
                .build();
        s.internalPublish(msg, PLATFORM_CLIENT);
        return true;
    }

    @Override
    public void kick(String productKey, String deviceName) {
        Server s = server;
        if (s != null) {
            s.disconnectClient(DeviceCredentials.clientId(productKey, deviceName));
        }
    }

    // ---------------------------------------------------------------- auth

    private final class Authenticator implements IAuthenticator {
        @Override
        public boolean checkValid(String clientId, String username, byte[] password) {
            DeviceCredentials.Identity id = DeviceCredentials.parseClientId(clientId);
            if (id == null || !DeviceCredentials.usernameMatches(id, username) || password == null) {
                return false;
            }
            Optional<Device> d = devices.resolve(id.productKey(), id.deviceName());
            if (d.isEmpty() || !d.get().enabled()) {
                return false;
            }
            return DeviceCredentials.verifyPassword(d.get().deviceSecret(), id.productKey(), id.deviceName(),
                    new String(password, StandardCharsets.UTF_8), props.getSecurity().isAllowPlainDevicePassword());
        }
    }

    private static final class Acl implements IAuthorizatorPolicy {
        @Override
        public boolean canWrite(Topic topic, String user, String client) {
            return Topics.canPublish(DeviceCredentials.parseClientId(client), topic.toString());
        }

        @Override
        public boolean canRead(Topic topic, String user, String client) {
            return Topics.canSubscribe(DeviceCredentials.parseClientId(client), topic.toString());
        }
    }

    // ---------------------------------------------------------------- events

    private final class Interceptor extends AbstractInterceptHandler {
        @Override
        public String getID() {
            return "hyperiot-interceptor";
        }

        @Override
        public Class<?>[] getInterceptedMessageTypes() {
            return new Class<?>[]{InterceptConnectMessage.class, InterceptDisconnectMessage.class,
                    InterceptConnectionLostMessage.class, InterceptPublishMessage.class};
        }

        @Override
        public void onConnect(InterceptConnectMessage msg) {
            try {
                DeviceCredentials.Identity id = DeviceCredentials.parseClientId(msg.getClientID());
                if (id == null) {
                    return;
                }
                connected.add(msg.getClientID());
                devices.resolve(id.productKey(), id.deviceName()).ifPresent(d -> status.online(d, remoteAddress(msg.getClientID())));
            } catch (Exception e) {
                log.warn("onConnect failed for {}: {}", msg.getClientID(), e.toString());
            }
        }

        @Override
        public void onSessionLoopError(Throwable error) {
            log.warn("MQTT session loop error: {}", error.toString());
        }

        @Override
        public void onDisconnect(InterceptDisconnectMessage msg) {
            offline(msg.getClientID());
        }

        @Override
        public void onConnectionLost(InterceptConnectionLostMessage msg) {
            offline(msg.getClientID());
        }

        @Override
        public void onPublish(InterceptPublishMessage msg) {
            try {
                ByteBuf buf = msg.getPayload();
                byte[] bytes = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), bytes);
                uplink.onMqtt(msg.getTopicName(), bytes);
            } catch (Exception e) {
                log.warn("onPublish failed for topic {}: {}", msg.getTopicName(), e.toString());
            }
        }
    }

    private void offline(String clientId) {
        try {
            DeviceCredentials.Identity id = DeviceCredentials.parseClientId(clientId);
            if (id == null) {
                return;
            }
            Server s = server;
            // a reconnect may have registered the new connection before the old one reports its disconnect
            if (s != null && s.listConnectedClients().stream().anyMatch(c -> clientId.equals(c.getClientID()))) {
                return;
            }
            connected.remove(clientId);
            devices.resolve(id.productKey(), id.deviceName()).ifPresent(status::offline);
        } catch (Exception e) {
            log.warn("offline handling failed for {}: {}", clientId, e.toString());
        }
    }

    private String remoteAddress(String clientId) {
        Server s = server;
        if (s == null) {
            return null;
        }
        return s.listConnectedClients().stream().filter(c -> clientId.equals(c.getClientID()))
                .map(ClientDescriptor::getAddress).findFirst().orElse(null);
    }
}
