package io.hyperiot.device;

import io.hyperiot.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    public record CreateRequest(@NotBlank String productKey, @NotBlank @Size(max = 64) String deviceName,
                                @Size(max = 128) String nickname, Map<String, String> tags) {
    }

    public record BatchRequest(@NotBlank String productKey, @Size(max = 40) String prefix, @Min(1) @Max(500) int count) {
    }

    public record UpdateRequest(@Size(max = 128) String nickname, Map<String, String> tags, Boolean enabled) {
    }

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<Device> list(@RequestParam(required = false) String productKey, @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return service.page(productKey, status, keyword, page, size);
    }

    @PostMapping
    public Device create(@Valid @RequestBody CreateRequest req) {
        return service.create(req.productKey(), req.deviceName(), req.nickname(), req.tags());
    }

    @PostMapping("/batch")
    public List<Device> batch(@Valid @RequestBody BatchRequest req) {
        return service.createBatch(req.productKey(), req.prefix(), req.count());
    }

    @GetMapping("/{id}")
    public Device get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public Device update(@PathVariable long id, @Valid @RequestBody UpdateRequest req) {
        return service.update(id, req.nickname(), req.tags(), req.enabled()).withoutSecret();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-secret")
    public Device resetSecret(@PathVariable long id) {
        return service.resetSecret(id);
    }
}
