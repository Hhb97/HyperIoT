package io.hyperiot.access;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.common.Json;
import io.hyperiot.config.HyperIotProperties;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceCredentials;
import io.hyperiot.device.DeviceRepository;
import io.hyperiot.device.DeviceService;
import io.hyperiot.device.DeviceStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the online state in the database in line with what EMQX says. Connect / disconnect events can be lost (the
 * platform's own MQTT connection was down, the platform was restarting, ...), and a lost event would otherwise leave a
 * device "online" or "offline" forever. EMQX's REST API lists the clients that are really connected; this component
 * compares that list with the database and repairs the differences.
 *
 * <p>Needs an EMQX API key ({@code hyperiot.mqtt.emqx-api.*}); without it nothing happens.
 */
@Component
@ConditionalOnProperty(name = "hyperiot.mqtt.mode", havingValue = "external")
public class EmqxClientReconciler {

    private static final Logger log = LoggerFactory.getLogger(EmqxClientReconciler.class);
    private static final int PAGE_SIZE = 5000;
    /** Devices that came online in the last few seconds may not be in the broker's list yet / their event is in flight. */
    private static final long GRACE_MS = 30_000;

    private final HyperIotProperties.Mqtt.EmqxApi cfg;
    private final DeviceRepository repo;
    private final DeviceService devices;
    private final DeviceStatusService status;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public EmqxClientReconciler(HyperIotProperties props, DeviceRepository repo, DeviceService devices, DeviceStatusService status) {
        this.cfg = props.getMqtt().getEmqxApi();
        this.repo = repo;
        this.devices = devices;
        this.status = status;
    }

    /** Result of one comparison, mostly for logs and tests. */
    public record Result(int connectedAtBroker, int markedOnline, int markedOffline) {
    }

    private volatile long lastRun = System.currentTimeMillis();

    /** Ticks every 10 seconds, runs a comparison once the configured interval has passed. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    void scheduled() {
        if (System.currentTimeMillis() - lastRun >= cfg.getInterval().toMillis()) {
            run();
        }
    }

    @EventListener(ExternalMqttGateway.Reconnected.class)
    void onReconnected() {
        run();
    }

    private void run() {
        if (!cfg.isConfigured()) {
            return;
        }
        lastRun = System.currentTimeMillis();
        try {
            Result r = reconcile();
            if (r.markedOnline() > 0 || r.markedOffline() > 0) {
                log.warn("Reconciled device state with EMQX: {} marked online, {} marked offline ({} clients connected)",
                        r.markedOnline(), r.markedOffline(), r.connectedAtBroker());
            }
        } catch (Exception e) {
            // never touch the state when EMQX could not be asked: "unknown" must not turn into "everything offline"
            log.warn("Could not reconcile device state with EMQX: {}", e.getMessage());
        }
    }

    public Result reconcile() throws Exception {
        Map<String, String> connected = fetchConnected(); // "productKey/deviceName" -> ip
        long now = System.currentTimeMillis();
        int online = 0;
        int offline = 0;

        // devices EMQX says are connected, but the database does not know as online
        for (Map.Entry<String, String> e : connected.entrySet()) {
            int slash = e.getKey().indexOf('/');
            Optional<Device> d = devices.resolve(e.getKey().substring(0, slash), e.getKey().substring(slash + 1));
            if (d.isEmpty() || !d.get().enabled()) {
                continue;
            }
            Device fresh = repo.findById(d.get().id()).orElse(null);
            if (fresh != null && !"ONLINE".equals(fresh.status())) {
                status.online(fresh, e.getValue());
                online++;
            }
        }
        // devices the database knows as online (MQTT only), but EMQX does not list
        List<Device> believed = repo.findOnlineMqtt();
        for (Device d : believed) {
            boolean listed = connected.containsKey(d.productKey() + "/" + d.deviceName());
            boolean recent = d.lastOnlineAt() != null && now - d.lastOnlineAt() < GRACE_MS;
            if (!listed && !recent) {
                status.offline(d);
                offline++;
            }
        }
        return new Result(connected.size(), online, offline);
    }

    /** All clients EMQX reports as connected, restricted to device client ids. */
    private Map<String, String> fetchConnected() throws Exception {
        Map<String, String> out = new HashMap<>();
        String base = cfg.getUrl().replaceAll("/+$", "");
        String auth = "Basic " + Base64.getEncoder().encodeToString((cfg.getKey() + ":" + cfg.getSecret()).getBytes(StandardCharsets.UTF_8));
        for (int page = 1; page < 1000; page++) {
            String url = base + "/api/v5/clients?conn_state=connected&limit=" + PAGE_SIZE + "&page=" + page
                    + "&fields=" + URLEncoder.encode("clientid,ip_address", StandardCharsets.UTF_8);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("EMQX API answered HTTP " + resp.statusCode());
            }
            JsonNode root = Json.tree(resp.body());
            for (JsonNode c : root.path("data")) {
                DeviceCredentials.Identity id = DeviceCredentials.parseClientId(c.path("clientid").asText(null));
                if (id != null) {
                    out.put(id.productKey() + "/" + id.deviceName(), c.path("ip_address").asText(null));
                }
            }
            if (!root.path("meta").path("hasnext").asBoolean(false)) {
                break;
            }
        }
        return out;
    }
}
