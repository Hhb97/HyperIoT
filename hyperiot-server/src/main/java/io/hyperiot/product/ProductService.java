package io.hyperiot.product;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.hyperiot.common.ApiException;
import io.hyperiot.common.Hmac;
import io.hyperiot.common.Json;
import io.hyperiot.common.PageResult;
import io.hyperiot.model.ThingModel;
import io.hyperiot.model.ThingModelValidator;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

@Service
public class ProductService {

    private static final Set<String> NODE_TYPES = Set.of("DEVICE", "GATEWAY");
    private static final Set<String> PROTOCOLS = Set.of("MQTT", "HTTP", "TCP");

    private final ProductRepository repo;
    private final Cache<String, ThingModel> modelCache = Caffeine.newBuilder()
            .maximumSize(2000).expireAfterWrite(Duration.ofMinutes(10)).build();

    public ProductService(ProductRepository repo) {
        this.repo = repo;
    }

    public Product create(String name, String description, String nodeType, String protocol) {
        String nt = nodeType == null ? "DEVICE" : nodeType.toUpperCase();
        String proto = protocol == null ? "MQTT" : protocol.toUpperCase();
        if (!NODE_TYPES.contains(nt)) {
            throw ApiException.badRequest("nodeType 只能是 DEVICE 或 GATEWAY");
        }
        if (!PROTOCOLS.contains(proto)) {
            throw ApiException.badRequest("protocol 只能是 MQTT、HTTP 或 TCP");
        }
        String key = Hmac.randomString(12).toLowerCase();
        long id = repo.insert(key, name.trim(), description, nt, proto, Json.write(ThingModel.empty()), System.currentTimeMillis());
        return get(id);
    }

    public Product get(long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("产品不存在"));
    }

    public Product getByKey(String productKey) {
        return repo.findByKey(productKey).orElseThrow(() -> ApiException.notFound("产品不存在"));
    }

    public PageResult<Product> page(String keyword, int page, int size) {
        return repo.page(keyword, page, size);
    }

    public Product update(long id, String name, String description) {
        get(id);
        repo.update(id, name.trim(), description, System.currentTimeMillis());
        return get(id);
    }

    public void delete(long id) {
        Product p = get(id);
        if (p.deviceCount() > 0) {
            throw ApiException.conflict("该产品下仍有 " + p.deviceCount() + " 个设备，请先删除设备");
        }
        repo.delete(id);
        modelCache.invalidate(p.productKey());
    }

    public ThingModel thingModel(long id) {
        return thingModel(get(id).productKey());
    }

    public ThingModel thingModel(String productKey) {
        ThingModel cached = modelCache.getIfPresent(productKey);
        if (cached != null) {
            return cached;
        }
        String json = repo.findThingModel(productKey).orElseThrow(() -> ApiException.notFound("产品不存在"));
        ThingModel model = Json.read(json, ThingModel.class);
        modelCache.put(productKey, model);
        return model;
    }

    public ThingModel updateThingModel(long id, ThingModel model) {
        Product p = get(id);
        ThingModelValidator.validateDefinition(model);
        repo.updateThingModel(id, Json.write(model), System.currentTimeMillis());
        modelCache.invalidate(p.productKey());
        return model;
    }
}
