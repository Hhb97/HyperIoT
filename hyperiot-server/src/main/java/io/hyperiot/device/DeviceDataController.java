package io.hyperiot.device;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.command.Command;
import io.hyperiot.command.CommandService;
import io.hyperiot.common.ApiException;
import io.hyperiot.common.PageResult;
import io.hyperiot.data.DeviceLogService;
import io.hyperiot.data.EventStore;
import io.hyperiot.data.TelemetryRepository;
import io.hyperiot.model.ThingModel;
import io.hyperiot.product.ProductService;
import io.hyperiot.shadow.ShadowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the console shows about one device besides its master data. */
@RestController
@RequestMapping("/api/devices/{id}")
public class DeviceDataController {

    public record DesiredRequest(Map<String, Object> params) {
    }

    public record InvokeRequest(JsonNode params, Long ttlSeconds) {
    }

    public record TelemetryResponse(String identifier, List<TelemetryRepository.Point> points) {
    }

    private static final Set<String> AGGREGATIONS = Set.of("raw", "avg", "max", "min", "last");
    private static final int MAX_POINTS = 5000;

    private final DeviceService devices;
    private final ProductService products;
    private final ShadowService shadow;
    private final TelemetryRepository telemetry;
    private final EventStore events;
    private final DeviceLogService logs;
    private final CommandService commands;

    public DeviceDataController(DeviceService devices, ProductService products, ShadowService shadow, TelemetryRepository telemetry,
                                EventStore events, DeviceLogService logs, CommandService commands) {
        this.devices = devices;
        this.products = products;
        this.shadow = shadow;
        this.telemetry = telemetry;
        this.events = events;
        this.logs = logs;
        this.commands = commands;
    }

    @GetMapping("/shadow")
    public ShadowService.ShadowView shadow(@PathVariable long id) {
        devices.get(id);
        return shadow.view(id);
    }

    @PutMapping("/desired")
    public ShadowService.ShadowView desired(@PathVariable long id, @RequestBody DesiredRequest req) {
        return shadow.setDesired(id, req.params());
    }

    @GetMapping("/telemetry")
    public TelemetryResponse telemetry(@PathVariable long id, @RequestParam String identifier,
                                       @RequestParam(required = false) Long from, @RequestParam(required = false) Long to,
                                       @RequestParam(defaultValue = "raw") String agg,
                                       @RequestParam(required = false) Long interval,
                                       @RequestParam(defaultValue = "1000") int limit) {
        Device d = devices.get(id);
        ThingModel model = products.thingModel(d.productKey());
        if (model.property(identifier).isEmpty()) {
            throw ApiException.badRequest("属性未定义：" + identifier);
        }
        long end = to == null ? System.currentTimeMillis() : to;
        long start = from == null ? end - 3_600_000L : from;
        if (start > end) {
            throw ApiException.badRequest("from 不能大于 to");
        }
        String a = agg.toLowerCase();
        if (!AGGREGATIONS.contains(a)) {
            throw ApiException.badRequest("agg 只能是 raw、avg、max、min、last");
        }
        int max = Math.max(1, Math.min(limit, MAX_POINTS));
        if ("raw".equals(a)) {
            return new TelemetryResponse(identifier, telemetry.raw(id, identifier, start, end, max));
        }
        if (interval == null || interval < 1000) {
            throw ApiException.badRequest("聚合查询需要 interval（毫秒，至少 1000）");
        }
        if ((end - start) / interval > MAX_POINTS) {
            throw ApiException.badRequest("interval 太小：时间范围内会产生超过 " + MAX_POINTS + " 个点");
        }
        return new TelemetryResponse(identifier, telemetry.aggregate(id, identifier, start, end, a, interval, max));
    }

    @GetMapping("/events")
    public PageResult<EventStore.DeviceEvent> events(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        devices.get(id);
        return events.page(id, page, size);
    }

    @GetMapping("/logs")
    public PageResult<DeviceLogService.Entry> logs(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        devices.get(id);
        return logs.page(id, page, size);
    }

    @PostMapping("/services/{identifier}/invoke")
    public Command invoke(@PathVariable long id, @PathVariable String identifier, @RequestBody(required = false) InvokeRequest req,
                          @RequestParam(defaultValue = "false") boolean wait, @RequestParam(defaultValue = "5000") long timeoutMs) {
        JsonNode params = req == null ? null : req.params();
        Long ttl = req == null ? null : req.ttlSeconds();
        return wait ? commands.invokeAndWait(id, identifier, params, ttl, timeoutMs) : commands.invoke(id, identifier, params, ttl);
    }

    @GetMapping("/commands")
    public PageResult<Command> commands(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        devices.get(id);
        return commands.page(id, null, page, size);
    }
}
