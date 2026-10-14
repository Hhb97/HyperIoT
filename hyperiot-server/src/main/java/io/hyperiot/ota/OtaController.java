package io.hyperiot.ota;

import io.hyperiot.common.ApiException;
import io.hyperiot.common.Hmac;
import io.hyperiot.common.PageResult;
import io.hyperiot.device.Device;
import io.hyperiot.device.DeviceService;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.util.List;

@RestController
public class OtaController {

    public record CreateTaskRequest(String name, @NotNull Long firmwareId, List<Long> deviceIds) {
    }

    private final FirmwareService firmwares;
    private final OtaService ota;
    private final DeviceService devices;

    public OtaController(FirmwareService firmwares, OtaService ota, DeviceService devices) {
        this.firmwares = firmwares;
        this.ota = ota;
        this.devices = devices;
    }

    // ---------------------------------------------------------------- console

    @GetMapping("/api/firmwares")
    public PageResult<Firmware> listFirmwares(@RequestParam(required = false) String productKey,
                                              @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return firmwares.page(productKey, page, size);
    }

    @PostMapping("/api/firmwares")
    public Firmware upload(@RequestParam MultipartFile file, @RequestParam String productKey, @RequestParam String version,
                           @RequestParam(required = false) String name, @RequestParam(required = false) String description) {
        return firmwares.upload(productKey, version, name, description, file);
    }

    @DeleteMapping("/api/firmwares/{id}")
    public ResponseEntity<Void> deleteFirmware(@PathVariable long id) {
        firmwares.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/ota/tasks")
    public PageResult<OtaService.OtaTask> tasks(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ota.page(page, size);
    }

    @PostMapping("/api/ota/tasks")
    public OtaService.OtaTask createTask(@RequestBody CreateTaskRequest req) {
        if (req.firmwareId() == null) {
            throw ApiException.badRequest("firmwareId 不能为空");
        }
        return ota.create(req.name(), req.firmwareId(), req.deviceIds());
    }

    @GetMapping("/api/ota/tasks/{id}")
    public OtaService.OtaTask task(@PathVariable long id) {
        return ota.detail(id);
    }

    @PostMapping("/api/ota/tasks/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable long id) {
        ota.cancel(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- device download (signed, no JWT)

    @GetMapping("/device-api/ota/{firmwareId}/download")
    public ResponseEntity<Resource> download(@PathVariable long firmwareId, @RequestParam("dn") String deviceName,
                                             @RequestParam("sig") String sig) throws java.io.IOException {
        Firmware fw = firmwares.get(firmwareId);
        Device d = devices.resolve(fw.productKey(), deviceName).filter(Device::enabled)
                .orElseThrow(() -> ApiException.unauthorized("signature verification failed"));
        if (!Hmac.safeEquals(OtaService.signDownload(d.deviceSecret(), firmwareId, deviceName), sig.toLowerCase())) {
            throw ApiException.unauthorized("signature verification failed");
        }
        var path = firmwares.file(fw);
        if (!Files.exists(path)) {
            throw ApiException.notFound("固件文件不存在");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fw.productKey() + "-" + fw.version() + ".bin\"")
                .header("X-Firmware-SHA256", fw.sha256())
                .body(new FileSystemResource(path));
    }
}
