package io.hyperiot.access;

import io.hyperiot.data.DeviceLogService;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Sends messages from the platform to devices over whichever channel the device is connected on. */
@Service
public class DownlinkService {

    private static final Logger log = LoggerFactory.getLogger(DownlinkService.class);

    // ObjectProvider: the MQTT channel itself depends on the uplink pipeline which depends on this service
    private final ObjectProvider<DownlinkChannel> channels;
    private final DeviceLogService deviceLog;

    public DownlinkService(ObjectProvider<DownlinkChannel> channels, DeviceLogService deviceLog) {
        this.channels = channels;
        this.deviceLog = deviceLog;
    }

    /** @return true if a channel accepted the message (the device was reachable). */
    public boolean send(Device device, String topic, String payload) {
        return send(device.id(), device.productKey(), device.deviceName(), topic, payload);
    }

    public boolean send(long deviceId, String productKey, String deviceName, String topic, String payload) {
        for (DownlinkChannel c : channels) {
            try {
                if (c.send(productKey, deviceName, topic, payload)) {
                    deviceLog.down(deviceId, topic, payload);
                    return true;
                }
            } catch (Exception e) {
                log.warn("Downlink via {} failed for {}/{}: {}", c.getClass().getSimpleName(), productKey, deviceName, e.toString());
            }
        }
        return false;
    }

    @EventListener
    void onDeviceChanged(DeviceChangedEvent e) {
        if (e.type() == DeviceChangedEvent.Type.DISABLED || e.type() == DeviceChangedEvent.Type.DELETED
                || e.type() == DeviceChangedEvent.Type.SECRET_RESET) {
            for (DownlinkChannel c : channels) {
                c.kick(e.productKey(), e.deviceName());
            }
        }
    }
}
