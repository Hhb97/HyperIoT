package io.hyperiot.rule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.hyperiot.common.ApiException;
import io.hyperiot.common.Json;
import io.hyperiot.common.PageResult;
import io.hyperiot.device.DeviceService;
import io.hyperiot.model.ThingModel;
import io.hyperiot.model.ThingModelValidator;
import io.hyperiot.product.ProductService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@Service
public class RuleService {

    private static final Set<String> LEVELS = Set.of("INFO", "WARN", "CRITICAL");
    private static final String COLUMNS = "id, name, description, product_key, device_name, logic, conditions, actions, level, enabled, created_at";

    private final JdbcClient jdbc;
    private final ProductService products;
    private final DeviceService devices;
    private final WebhookClient webhook;
    /** Enabled rules per product, refreshed on change and after 30s (other nodes may have changed them). */
    private final Cache<String, List<Rule>> byProduct = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).build();

    public RuleService(JdbcClient jdbc, ProductService products, DeviceService devices, WebhookClient webhook) {
        this.jdbc = jdbc;
        this.products = products;
        this.devices = devices;
        this.webhook = webhook;
    }

    private static Rule map(ResultSet rs, int i) throws SQLException {
        return new Rule(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getString("product_key"),
                rs.getString("device_name"), rs.getString("logic"),
                Json.read(rs.getString("conditions"), new TypeReference<List<Rule.Condition>>() { }),
                Json.read(rs.getString("actions"), new TypeReference<List<Rule.Action>>() { }),
                rs.getString("level"), rs.getBoolean("enabled"), rs.getLong("created_at"));
    }

    public List<Rule> enabledFor(String productKey) {
        return byProduct.get(productKey, k -> jdbc.sql("SELECT " + COLUMNS + " FROM rule WHERE product_key = :pk AND enabled = TRUE")
                .param("pk", k).query(RuleService::map).list());
    }

    public PageResult<Rule> page(int page, int size) {
        long total = jdbc.sql("SELECT COUNT(*) FROM rule").query(Long.class).single();
        List<Rule> items = jdbc.sql("SELECT " + COLUMNS + " FROM rule ORDER BY id DESC LIMIT :size OFFSET :offset")
                .param("size", PageResult.normalizeSize(size)).param("offset", PageResult.offset(page, size))
                .query(RuleService::map).list();
        return new PageResult<>(total, items);
    }

    public Rule get(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM rule WHERE id = :id").param("id", id).query(RuleService::map).optional()
                .orElseThrow(() -> ApiException.notFound("规则不存在"));
    }

    public Rule create(Rule in) {
        Rule r = normalize(in);
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO rule(name, description, product_key, device_name, logic, conditions, actions, level, enabled, created_at) "
                        + "VALUES (:n, :d, :pk, :dn, :lg, :c, :a, :lv, :en, :now)")
                .param("n", r.name()).param("d", r.description()).param("pk", r.productKey()).param("dn", r.deviceName())
                .param("lg", r.logic()).param("c", Json.write(r.conditions())).param("a", Json.write(r.actions()))
                .param("lv", r.level()).param("en", r.enabled()).param("now", System.currentTimeMillis()).update(kh, "id");
        byProduct.invalidate(r.productKey());
        return get(kh.getKey().longValue());
    }

    public Rule update(long id, Rule in) {
        Rule old = get(id);
        Rule r = normalize(in);
        jdbc.sql("UPDATE rule SET name = :n, description = :d, product_key = :pk, device_name = :dn, logic = :lg, "
                        + "conditions = :c, actions = :a, level = :lv, enabled = :en WHERE id = :id")
                .param("n", r.name()).param("d", r.description()).param("pk", r.productKey()).param("dn", r.deviceName())
                .param("lg", r.logic()).param("c", Json.write(r.conditions())).param("a", Json.write(r.actions()))
                .param("lv", r.level()).param("en", r.enabled()).param("id", id).update();
        byProduct.invalidate(old.productKey());
        byProduct.invalidate(r.productKey());
        return get(id);
    }

    public void setEnabled(long id, boolean enabled) {
        Rule r = get(id);
        jdbc.sql("UPDATE rule SET enabled = :en WHERE id = :id").param("en", enabled).param("id", id).update();
        byProduct.invalidate(r.productKey());
    }

    public void delete(long id) {
        Rule r = get(id);
        jdbc.sql("DELETE FROM rule WHERE id = :id").param("id", id).update();
        byProduct.invalidate(r.productKey());
    }

    // ---------------------------------------------------------------- validation

    private Rule normalize(Rule in) {
        if (in.name() == null || in.name().isBlank() || in.name().length() > 128) {
            throw ApiException.badRequest("规则名称不能为空且不超过 128 字符");
        }
        if (in.productKey() == null || in.productKey().isBlank()) {
            throw ApiException.badRequest("必须指定 productKey");
        }
        ThingModel model = products.thingModel(in.productKey());
        String deviceName = in.deviceName() == null || in.deviceName().isBlank() ? null : in.deviceName();
        if (deviceName != null && devices.resolve(in.productKey(), deviceName).isEmpty()) {
            throw ApiException.badRequest("设备不存在：" + deviceName);
        }
        String logic = in.logic() == null ? "AND" : in.logic().toUpperCase();
        if (!logic.equals("AND") && !logic.equals("OR")) {
            throw ApiException.badRequest("logic 只能是 AND 或 OR");
        }
        String level = in.level() == null ? "WARN" : in.level().toUpperCase();
        if (!LEVELS.contains(level)) {
            throw ApiException.badRequest("level 只能是 INFO、WARN 或 CRITICAL");
        }
        List<Rule.Condition> conditions = in.conditions() == null ? List.of() : in.conditions();
        if (conditions.isEmpty() || conditions.size() > 10) {
            throw ApiException.badRequest("条件数量必须在 1 到 10 之间");
        }
        List<Rule.Condition> normalized = conditions.stream().map(c -> checkCondition(model, c)).toList();
        List<Rule.Action> actions = in.actions() == null ? List.of() : in.actions();
        if (actions.size() > 5) {
            throw ApiException.badRequest("动作最多 5 个");
        }
        List<Rule.Action> checkedActions = actions.stream().map(a -> checkAction(model, a)).toList();
        return new Rule(null, in.name().trim(), in.description(), in.productKey(), deviceName, logic, normalized, checkedActions,
                level, in.enabled() == null || in.enabled(), null);
    }

    private Rule.Condition checkCondition(ThingModel model, Rule.Condition c) {
        var def = model.property(c.identifier()).orElseThrow(() -> ApiException.badRequest("条件中的属性未定义：" + c.identifier()));
        String op = c.op() == null ? "" : c.op().toUpperCase();
        if (!RuleEvaluator.OPS.contains(op)) {
            throw ApiException.badRequest("不支持的比较符：" + c.op());
        }
        Object v = c.value();
        switch (def.type()) {
            case "int", "long", "float", "double" -> {
                if (!(v instanceof Number)) {
                    throw ApiException.badRequest("属性 " + c.identifier() + " 是数值类型，阈值必须是数字");
                }
            }
            case "bool" -> {
                if (!(v instanceof Boolean)) {
                    throw ApiException.badRequest("属性 " + c.identifier() + " 是布尔类型，阈值必须是 true/false");
                }
                requireEquality(c, op);
            }
            default -> {
                if (!(v instanceof String)) {
                    throw ApiException.badRequest("属性 " + c.identifier() + " 的阈值必须是字符串");
                }
                requireEquality(c, op);
            }
        }
        return new Rule.Condition(c.identifier(), op, v);
    }

    private static void requireEquality(Rule.Condition c, String op) {
        if (!op.equals("EQ") && !op.equals("NE")) {
            throw ApiException.badRequest("属性 " + c.identifier() + " 只支持 EQ / NE");
        }
    }

    private Rule.Action checkAction(ThingModel model, Rule.Action a) {
        String type = a.type() == null ? "" : a.type().toUpperCase();
        switch (type) {
            case "COMMAND" -> {
                var svc = model.service(a.identifier())
                        .orElseThrow(() -> ApiException.badRequest("动作中的服务未定义：" + a.identifier()));
                ThingModelValidator.checkParams(svc.input(), Json.toTree(a.params() == null ? java.util.Map.of() : a.params()), true);
                return new Rule.Action(type, a.identifier(), a.params() == null ? java.util.Map.of() : a.params(), null);
            }
            case "WEBHOOK" -> {
                if (a.url() == null || a.url().length() > 500) {
                    throw ApiException.badRequest("WEBHOOK 动作需要 url（不超过 500 字符）");
                }
                String problem = webhook.validate(a.url());
                if (problem != null) {
                    throw ApiException.badRequest("WEBHOOK 地址不可用：" + problem);
                }
                return new Rule.Action(type, null, null, a.url());
            }
            default -> throw ApiException.badRequest("不支持的动作类型：" + a.type());
        }
    }
}
