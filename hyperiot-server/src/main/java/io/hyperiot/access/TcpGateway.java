package io.hyperiot.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hyperiot.common.Hmac;
import io.hyperiot.common.Json;
import io.hyperiot.config.HyperIotProperties;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceService;
import io.hyperiot.device.DeviceStatusService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Example of a private-protocol gateway built on Netty: newline-delimited JSON over plain TCP.
 *
 * <pre>
 * device -> platform
 *   {"type":"auth","productKey":"pk","deviceName":"dn","sign":"hex(HMAC-SHA256(secret, 'productKey'+pk+'deviceName'+dn))"}
 *   {"type":"property","id":"1","params":{...}}        {"type":"event","identifier":"x","params":{...}}
 *   {"type":"reply","identifier":"svc","id":"req","code":200,"data":{...}}
 *   {"type":"shadow_get"}   {"type":"ota_inform","version":"1.0"}   {"type":"ota_progress","version":"1.0","step":50}
 *   {"type":"ping"}
 * platform -> device
 *   {"type":"auth_reply","code":200}   {"type":"pong"}
 *   {"type":"downlink","topic":"hyperiot/pk/dn/thing/service/reboot/invoke","payload":{...}}
 * </pre>
 *
 * Use TLS termination (stunnel, HAProxy, cloud LB) in front of it for anything that leaves a trusted network.
 */
@Component
@ConditionalOnProperty(name = "hyperiot.tcp.enabled", havingValue = "true")
public class TcpGateway implements DownlinkChannel {

    private static final Logger log = LoggerFactory.getLogger(TcpGateway.class);
    private static final int MAX_FRAME = 65536;
    private static final int AUTH_TIMEOUT_SECONDS = 10;

    private final HyperIotProperties.Tcp cfg;
    private final DeviceService devices;
    private final DeviceStatusService status;
    private final UplinkProcessor uplink;
    private final Map<String, Channel> sessions = new ConcurrentHashMap<>();
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel server;

    public TcpGateway(HyperIotProperties props, DeviceService devices, DeviceStatusService status, UplinkProcessor uplink) {
        this.cfg = props.getTcp();
        this.devices = devices;
        this.status = status;
        this.uplink = uplink;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        server = new ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new ReadTimeoutHandler(cfg.getIdleTimeout().toSeconds(), TimeUnit.SECONDS))
                                .addLast(new LineBasedFrameDecoder(MAX_FRAME))
                                .addLast(new StringDecoder(StandardCharsets.UTF_8))
                                .addLast(new StringEncoder(StandardCharsets.UTF_8))
                                .addLast(new Handler());
                    }
                }).bind(cfg.getPort()).sync().channel();
        log.info("TCP gateway listening on port {}", ((InetSocketAddress) server.localAddress()).getPort());
    }

    public int port() {
        return server == null ? -1 : ((InetSocketAddress) server.localAddress()).getPort();
    }

    @PreDestroy
    void stop() {
        if (server != null) {
            server.close();
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        if (workers != null) {
            workers.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------- downlink

    @Override
    public boolean send(String productKey, String deviceName, String topic, String payload) {
        Channel ch = sessions.get(productKey + "/" + deviceName);
        if (ch == null || !ch.isActive()) {
            return false;
        }
        ObjectNode frame = Json.object().put("type", "downlink").put("topic", topic);
        frame.set("payload", Json.tree(payload));
        ch.writeAndFlush(frame + "\n");
        return true;
    }

    @Override
    public void kick(String productKey, String deviceName) {
        Channel ch = sessions.get(productKey + "/" + deviceName);
        if (ch != null) {
            ch.close();
        }
    }

    // -------------------------------------------------------------- connection handler

    private final class Handler extends SimpleChannelInboundHandler<String> {

        private Device device;
        private String key;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            ctx.executor().schedule(() -> {
                if (device == null) {
                    ctx.close();
                }
            }, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String line) {
            JsonNode msg;
            try {
                msg = Json.tree(line);
            } catch (IllegalArgumentException e) {
                ctx.close();
                return;
            }
            String type = msg.path("type").asText("");
            if (device == null) {
                if (!"auth".equals(type) || !authenticate(ctx, msg)) {
                    ctx.writeAndFlush("{\"type\":\"auth_reply\",\"code\":401}\n").addListener(ChannelFutureListener.CLOSE);
                }
                return;
            }
            switch (type) {
                case "ping" -> ctx.writeAndFlush("{\"type\":\"pong\"}\n");
                case "property" -> forward(Topics.Type.PROPERTY_POST, null, msg, "thing/property/post");
                case "event" -> {
                    String id = msg.path("identifier").asText("");
                    forward(Topics.Type.EVENT_POST, id, msg, "thing/event/" + id + "/post");
                }
                case "reply" -> {
                    String id = msg.path("identifier").asText("");
                    forward(Topics.Type.SERVICE_REPLY, id, msg, "thing/service/" + id + "/reply");
                }
                case "shadow_get" -> forward(Topics.Type.SHADOW_GET, null, msg, "shadow/get");
                case "ota_inform" -> forward(Topics.Type.OTA_INFORM, null, msg, "ota/inform");
                case "ota_progress" -> forward(Topics.Type.OTA_PROGRESS, null, msg, "ota/progress");
                default -> log.debug("Unknown TCP frame type {}", type);
            }
        }

        private boolean authenticate(ChannelHandlerContext ctx, JsonNode msg) {
            String pk = msg.path("productKey").asText("");
            String dn = msg.path("deviceName").asText("");
            String sign = msg.path("sign").asText("");
            Optional<Device> d = devices.resolve(pk, dn).filter(Device::enabled);
            if (d.isEmpty()) {
                return false;
            }
            String expected = Hmac.sha256Hex(d.get().deviceSecret(), "productKey" + pk + "deviceName" + dn);
            if (!Hmac.safeEquals(expected, sign.toLowerCase())) {
                return false;
            }
            device = d.get();
            key = pk + "/" + dn;
            Channel previous = sessions.put(key, ctx.channel());
            if (previous != null && previous != ctx.channel()) {
                previous.close();
            }
            ctx.writeAndFlush("{\"type\":\"auth_reply\",\"code\":200}\n");
            String ip = ctx.channel().remoteAddress() instanceof InetSocketAddress a ? a.getAddress().getHostAddress() : null;
            status.online(device, ip);
            return true;
        }

        private void forward(Topics.Type t, String identifier, JsonNode msg, String suffix) {
            ObjectNode body = ((ObjectNode) msg).deepCopy();
            body.remove("type");
            uplink.process(device, t, identifier, body.toString().getBytes(StandardCharsets.UTF_8),
                    Topics.base(device.productKey(), device.deviceName()) + suffix);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (key != null && sessions.remove(key, ctx.channel())) {
                status.offline(device);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("TCP connection error: {}", cause.toString());
            ctx.close();
        }
    }
}
