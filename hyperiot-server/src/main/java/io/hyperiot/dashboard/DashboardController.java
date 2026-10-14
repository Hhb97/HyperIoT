package io.hyperiot.dashboard;

import io.hyperiot.alarm.Alarm;
import io.hyperiot.alarm.AlarmService;
import io.hyperiot.device.DeviceService;
import io.hyperiot.product.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    public record Dashboard(long products, long devices, long online, long activeAlarms,
                            List<UplinkMeter.Bucket> uplinkPerMinute, List<Alarm> recentAlarms) {
    }

    private final ProductService products;
    private final DeviceService devices;
    private final AlarmService alarms;
    private final UplinkMeter meter;

    public DashboardController(ProductService products, DeviceService devices, AlarmService alarms, UplinkMeter meter) {
        this.products = products;
        this.devices = devices;
        this.alarms = alarms;
        this.meter = meter;
    }

    @GetMapping
    public Dashboard get() {
        return new Dashboard(products.page(null, 1, 1).total(), devices.countAll(), devices.countOnline(), alarms.countActive(),
                meter.last(30), alarms.recent(8));
    }
}
