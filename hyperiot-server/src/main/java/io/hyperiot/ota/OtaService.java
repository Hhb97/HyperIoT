package io.hyperiot.ota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hyperiot.access.DownlinkService;
import io.hyperiot.access.Topics;
import io.hyperiot.bus.DeviceMessage;
import io.hyperiot.bus.EventBus;
import io.hyperiot.bus.MessageKind;
import io.hyperiot.common.ApiException;
import io.hyperiot.common.Hmac;
import io.hyperiot.common.Json;
import io.hyperiot.common.PageResult;
import io.hyperiot.config.HyperIotProperties;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Firmware upgrade tasks. Devices are told where to download (signed URL), then report progress; the platform tracks
 * every device of a task individually and closes the task when nobody is pending any more.
 */
@Service
public class OtaService {

    private static final Logger log = LoggerFactory.getLogger(OtaService.class);
    private static final long STUCK_AFTER_MS = 30L * 60 * 1000;
    private static final String ACTIVE = "('PENDING', 'NOTIFIED', 'DOWNLOADING')";

    public record OtaTask(long id, String name, long firmwareId, String firmwareVersion, String productKey, String status,
                          long total, long success, long failed, long createdAt, List<TaskDevice> devices) {
    }

    public record TaskDevice(long deviceId, String deviceName, String status, int progress, String message, long updatedAt) {
    }

    private final JdbcClient jdbc;
    private final FirmwareService firmwares;
    private final DeviceService devices;
    private final DownlinkService downlink;
    private final EventBus bus;
    private final HyperIotProperties props;

    public OtaService(JdbcClient jdbc, FirmwareService firmwares, DeviceService devices, DownlinkService downlink,
                      EventBus bus, HyperIotProperties props) {
        this.jdbc = jdbc;
        this.firmwares = firmwares;
        this.devices = devices;
        this.downlink = downlink;
        this.bus = bus;
        this.props = props;
    }

    @PostConstruct
    void start() {
        bus.subscribe("ota", Set.of(MessageKind.STATUS, MessageKind.OTA_PROGRESS), this::onMessage);
    }

    // ---------------------------------------------------------------- download signature

    public static String signDownload(String secret, long firmwareId, String deviceName) {
        return Hmac.sha256Hex(secret, "ota" + firmwareId + deviceName);
    }

    private String downloadUrl(Firmware f, Device d) {
        String base = props.getPublicBaseUrl().replaceAll("/+$", "");
        return base + "/device-api/ota/" + f.id() + "/download?dn=" + java.net.URLEncoder.encode(d.deviceName(), java.nio.charset.StandardCharsets.UTF_8)
                + "&sig=" + signDownload(d.deviceSecret(), f.id(), d.deviceName());
    }

    // ---------------------------------------------------------------- tasks

    @Transactional
    public OtaTask create(String name, long firmwareId, List<Long> deviceIds) {
        Firmware fw = firmwares.get(firmwareId);
        List<Device> targets;
        if (deviceIds == null || deviceIds.isEmpty()) {
            targets = devices.listByProduct(fw.productKey());
        } else {
            targets = devices.getAll(deviceIds);
            if (targets.size() != deviceIds.stream().distinct().count()) {
                throw ApiException.badRequest("部分设备不存在");
            }
            for (Device d : targets) {
                if (!d.productKey().equals(fw.productKey())) {
                    throw ApiException.badRequest("设备 " + d.deviceName() + " 不属于固件所属产品");
                }
            }
        }
        targets = targets.stream().filter(d -> d.enabled() && !fw.version().equals(d.firmwareVersion())).toList();
        if (targets.isEmpty()) {
            throw ApiException.badRequest("没有需要升级的设备（可能都已是该版本或已禁用）");
        }
        long now = System.currentTimeMillis();
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO ota_task(name, firmware_id, product_key, status, created_at) VALUES (:n, :f, :pk, 'RUNNING', :now)")
                .param("n", name == null || name.isBlank() ? "升级到 " + fw.version() : name).param("f", firmwareId)
                .param("pk", fw.productKey()).param("now", now).update(kh, "id");
        long taskId = kh.getKey().longValue();
        for (Device d : targets) {
            // a device only follows one upgrade at a time: the newest task wins
            jdbc.sql("UPDATE ota_task_device SET status = 'CANCELED', message = '被新任务取代', updated_at = :now "
                            + "WHERE device_id = :d AND status IN " + ACTIVE)
                    .param("now", now).param("d", d.id()).update();
            jdbc.sql("INSERT INTO ota_task_device(task_id, device_id, status, progress, updated_at) VALUES (:t, :d, 'PENDING', 0, :now)")
                    .param("t", taskId).param("d", d.id()).param("now", now).update();
        }
        for (Device d : targets) {
            if ("ONLINE".equals(devices.get(d.id()).status())) {
                notifyDevice(taskId, fw, devices.get(d.id()));
            }
        }
        return detail(taskId);
    }

    private void notifyDevice(long taskId, Firmware fw, Device d) {
        ObjectNode msg = Json.object();
        msg.put("id", Long.toString(taskId));
        msg.put("version", fw.version());
        msg.put("url", downloadUrl(fw, d));
        msg.put("size", fw.size());
        msg.put("sha256", fw.sha256());
        if (downlink.send(d, Topics.otaUpgrade(d.productKey(), d.deviceName()), msg.toString())) {
            jdbc.sql("UPDATE ota_task_device SET status = 'NOTIFIED', updated_at = :now WHERE task_id = :t AND device_id = :d AND status = 'PENDING'")
                    .param("now", System.currentTimeMillis()).param("t", taskId).param("d", d.id()).update();
        }
    }

    public void cancel(long taskId) {
        detail(taskId);
        long now = System.currentTimeMillis();
        jdbc.sql("UPDATE ota_task_device SET status = 'CANCELED', message = '任务已取消', updated_at = :now WHERE task_id = :t AND status IN " + ACTIVE)
                .param("now", now).param("t", taskId).update();
        jdbc.sql("UPDATE ota_task SET status = 'CANCELED' WHERE id = :t AND status = 'RUNNING'").param("t", taskId).update();
    }

    public PageResult<OtaTask> page(int page, int size) {
        long total = jdbc.sql("SELECT COUNT(*) FROM ota_task").query(Long.class).single();
        List<OtaTask> items = jdbc.sql(taskSelect() + " ORDER BY t.id DESC LIMIT :size OFFSET :offset")
                .param("size", PageResult.normalizeSize(size)).param("offset", PageResult.offset(page, size))
                .query((rs, n) -> mapTask(rs, null)).list();
        return new PageResult<>(total, items);
    }

    public OtaTask detail(long id) {
        OtaTask t = jdbc.sql(taskSelect() + " WHERE t.id = :id").param("id", id).query((rs, n) -> mapTask(rs, null)).optional()
                .orElseThrow(() -> ApiException.notFound("升级任务不存在"));
        List<TaskDevice> rows = jdbc.sql("SELECT d.device_name, x.device_id, x.status, x.progress, x.message, x.updated_at "
                        + "FROM ota_task_device x JOIN device d ON d.id = x.device_id WHERE x.task_id = :t ORDER BY x.id")
                .param("t", id).query((rs, n) -> new TaskDevice(rs.getLong("device_id"), rs.getString("device_name"),
                        rs.getString("status"), rs.getInt("progress"), rs.getString("message"), rs.getLong("updated_at"))).list();
        return new OtaTask(t.id(), t.name(), t.firmwareId(), t.firmwareVersion(), t.productKey(), t.status(), t.total(),
                t.success(), t.failed(), t.createdAt(), rows);
    }

    private static String taskSelect() {
        return "SELECT t.id, t.name, t.firmware_id, f.version AS fw_version, t.product_key, t.status, t.created_at, "
                + "(SELECT COUNT(*) FROM ota_task_device x WHERE x.task_id = t.id) AS total, "
                + "(SELECT COUNT(*) FROM ota_task_device x WHERE x.task_id = t.id AND x.status = 'SUCCESS') AS success, "
                + "(SELECT COUNT(*) FROM ota_task_device x WHERE x.task_id = t.id AND x.status = 'FAILED') AS failed "
                + "FROM ota_task t JOIN firmware f ON f.id = t.firmware_id";
    }

    private static OtaTask mapTask(java.sql.ResultSet rs, List<TaskDevice> devices) throws java.sql.SQLException {
        return new OtaTask(rs.getLong("id"), rs.getString("name"), rs.getLong("firmware_id"), rs.getString("fw_version"),
                rs.getString("product_key"), rs.getString("status"), rs.getLong("total"), rs.getLong("success"),
                rs.getLong("failed"), rs.getLong("created_at"), devices);
    }

    // ---------------------------------------------------------------- bus

    private void onMessage(DeviceMessage m) {
        if (m.kind() == MessageKind.STATUS) {
            if ("ONLINE".equals(m.payload().path("status").asText())) {
                pushPending(m.deviceId());
            }
            return;
        }
        onProgress(m);
    }

    private void pushPending(long deviceId) {
        var pending = jdbc.sql("SELECT task_id FROM ota_task_device WHERE device_id = :d AND status = 'PENDING' ORDER BY id DESC")
                .param("d", deviceId).query(Long.class).list();
        if (pending.isEmpty()) {
            return;
        }
        Device d = devices.get(deviceId);
        long taskId = pending.get(0);
        long fwId = jdbc.sql("SELECT firmware_id FROM ota_task WHERE id = :t AND status = 'RUNNING'").param("t", taskId)
                .query(Long.class).optional().orElse(-1L);
        if (fwId > 0) {
            notifyDevice(taskId, firmwares.get(fwId), d);
        }
    }

    private void onProgress(DeviceMessage m) {
        JsonNode p = m.payload();
        String reported = p.hasNonNull("version") ? p.get("version").asText() : null;
        if (p.path("inform").asBoolean(false)) {
            if (reported != null) {
                devices.updateFirmwareVersion(m.deviceId(), reported);
                finishIfCurrent(m.deviceId(), reported);
            }
            return;
        }
        int step = p.path("step").asInt(0);
        String desc = p.hasNonNull("desc") ? p.get("desc").asText() : null;
        Optional<long[]> row = activeRow(m.deviceId());
        if (row.isEmpty()) {
            return;
        }
        long rowId = row.get()[0];
        long now = System.currentTimeMillis();
        if (step < 0) {
            update(rowId, "FAILED", 0, desc == null ? "设备上报升级失败" : desc, now);
        } else if (step >= 100) {
            update(rowId, "SUCCESS", 100, desc, now);
            if (reported != null) {
                devices.updateFirmwareVersion(m.deviceId(), reported);
            } else {
                taskVersion(row.get()[1]).ifPresent(v -> devices.updateFirmwareVersion(m.deviceId(), v));
            }
        } else {
            update(rowId, "DOWNLOADING", step, desc, now);
        }
        closeTaskIfDone(row.get()[1]);
    }

    private void finishIfCurrent(long deviceId, String version) {
        activeRow(deviceId).ifPresent(row -> {
            if (taskVersion(row[1]).filter(version::equals).isPresent()) {
                update(row[0], "SUCCESS", 100, "设备确认版本 " + version, System.currentTimeMillis());
                closeTaskIfDone(row[1]);
            }
        });
    }

    /** @return [rowId, taskId] of the device's newest unfinished upgrade */
    private Optional<long[]> activeRow(long deviceId) {
        return jdbc.sql("SELECT x.id, x.task_id FROM ota_task_device x JOIN ota_task t ON t.id = x.task_id "
                        + "WHERE x.device_id = :d AND x.status IN ('NOTIFIED', 'DOWNLOADING') AND t.status = 'RUNNING' "
                        + "ORDER BY x.id DESC LIMIT 1")
                .param("d", deviceId).query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).optional();
    }

    private Optional<String> taskVersion(long taskId) {
        return jdbc.sql("SELECT f.version FROM ota_task t JOIN firmware f ON f.id = t.firmware_id WHERE t.id = :t")
                .param("t", taskId).query(String.class).optional();
    }

    private void update(long rowId, String status, int progress, String message, long now) {
        jdbc.sql("UPDATE ota_task_device SET status = :s, progress = :p, message = :m, updated_at = :now WHERE id = :id")
                .param("s", status).param("p", progress).param("m", message).param("now", now).param("id", rowId).update();
    }

    private void closeTaskIfDone(long taskId) {
        long active = jdbc.sql("SELECT COUNT(*) FROM ota_task_device WHERE task_id = :t AND status IN " + ACTIVE)
                .param("t", taskId).query(Long.class).single();
        if (active == 0) {
            jdbc.sql("UPDATE ota_task SET status = 'COMPLETED' WHERE id = :t AND status = 'RUNNING'").param("t", taskId).update();
        }
    }

    /** Devices that were told to upgrade but went silent: fail them so the task can finish. */
    @Scheduled(fixedDelay = 60_000)
    void failStuck() {
        long now = System.currentTimeMillis();
        var stuck = jdbc.sql("SELECT id, task_id FROM ota_task_device WHERE status IN ('NOTIFIED', 'DOWNLOADING') AND updated_at < :c")
                .param("c", now - STUCK_AFTER_MS).query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).list();
        for (long[] s : stuck) {
            update(s[0], "FAILED", 0, "升级超时（30 分钟内无进展）", now);
            closeTaskIfDone(s[1]);
        }
        if (!stuck.isEmpty()) {
            log.info("Marked {} stuck OTA device entries as failed", stuck.size());
        }
    }
}
