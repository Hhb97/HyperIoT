package io.hyperiot.device;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.hyperiot.common.ApiException;
import io.hyperiot.common.Hmac;
import io.hyperiot.common.PageResult;
import io.hyperiot.data.TelemetryStore;
import io.hyperiot.product.ProductService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class DeviceService {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_\\-.:@]{1,64}$");
    private static final int MAX_BATCH = 500;

    private final DeviceRepository repo;
    private final ProductService products;
    private final ApplicationEventPublisher events;
    private final TelemetryStore telemetry;
    /** Hot path lookup used for every message; unknown devices are cached briefly too (negative cache). */
    private final Cache<String, Optional<Device>> cache = Caffeine.newBuilder()
            .maximumSize(100_000).expireAfterWrite(Duration.ofSeconds(30)).build();

    public DeviceService(DeviceRepository repo, ProductService products, ApplicationEventPublisher events, TelemetryStore telemetry) {
        this.repo = repo;
        this.products = products;
        this.events = events;
        this.telemetry = telemetry;
    }

    private static String cacheKey(String productKey, String deviceName) {
        return productKey + "\u0000" + deviceName;
    }

    /** Resolves a device for the access layer, including its secret. Cached. */
    public Optional<Device> resolve(String productKey, String deviceName) {
        return cache.get(cacheKey(productKey, deviceName), k -> repo.find(productKey, deviceName));
    }

    private void evict(Device d) {
        cache.invalidate(cacheKey(d.productKey(), d.deviceName()));
    }

    public Device create(String productKey, String deviceName, String nickname, Map<String, String> tags) {
        products.getByKey(productKey);
        validateName(deviceName);
        if (repo.find(productKey, deviceName).isPresent()) {
            throw ApiException.conflict("设备名已存在：" + deviceName);
        }
        long id = repo.insert(productKey, deviceName, nickname, Hmac.randomString(32), tags, System.currentTimeMillis());
        cache.invalidate(cacheKey(productKey, deviceName));
        return get(id);
    }

    @Transactional
    public List<Device> createBatch(String productKey, String prefix, int count) {
        if (count < 1 || count > MAX_BATCH) {
            throw ApiException.badRequest("count 必须在 1 到 " + MAX_BATCH + " 之间");
        }
        products.getByKey(productKey);
        String p = prefix == null || prefix.isBlank() ? "dev" : prefix.trim();
        validateName(p + "-" + String.format("%04d", count));
        List<Device> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 1; i <= count; i++) {
            String name = p + "-" + String.format("%04d", i);
            if (repo.find(productKey, name).isPresent()) {
                throw ApiException.conflict("设备名已存在：" + name);
            }
            long id = repo.insert(productKey, name, null, Hmac.randomString(32), null, now);
            out.add(repo.findById(id).orElseThrow());
        }
        return out;
    }

    private static void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw ApiException.badRequest("设备名只能包含字母、数字和 _ - . : @，长度 1~64");
        }
    }

    public Device get(long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("设备不存在"));
    }

    public PageResult<Device> page(String productKey, String status, String keyword, int page, int size) {
        PageResult<Device> r = repo.page(productKey, status, keyword, page, size);
        return new PageResult<>(r.total(), r.items().stream().map(Device::withoutSecret).toList());
    }

    public List<Device> listByProduct(String productKey) {
        return repo.findByProduct(productKey);
    }

    public List<Device> getAll(List<Long> ids) {
        return repo.findByIds(ids);
    }

    public Device update(long id, String nickname, Map<String, String> tags, Boolean enabled) {
        Device d = get(id);
        boolean en = enabled == null ? d.enabled() : enabled;
        repo.update(id, nickname == null ? d.nickname() : nickname, tags == null ? d.tags() : tags, en);
        evict(d);
        if (d.enabled() && !en) {
            events.publishEvent(new DeviceChangedEvent(DeviceChangedEvent.Type.DISABLED, id, d.productKey(), d.deviceName()));
        }
        return get(id);
    }

    public Device resetSecret(long id) {
        Device d = get(id);
        repo.updateSecret(id, Hmac.randomString(32));
        evict(d);
        events.publishEvent(new DeviceChangedEvent(DeviceChangedEvent.Type.SECRET_RESET, id, d.productKey(), d.deviceName()));
        return get(id);
    }

    public void delete(long id) {
        Device d = get(id);
        events.publishEvent(new DeviceChangedEvent(DeviceChangedEvent.Type.DELETED, id, d.productKey(), d.deviceName()));
        repo.delete(id);
        telemetry.deleteDevice(id);
        evict(d);
    }

    public void updateFirmwareVersion(long id, String version) {
        repo.updateFirmwareVersion(id, version);
        // cache holds no firmware info that matters for access decisions, nothing to evict
    }

    public long countAll() {
        return repo.countAll();
    }

    public long countOnline() {
        return repo.countByStatus("ONLINE");
    }
}
