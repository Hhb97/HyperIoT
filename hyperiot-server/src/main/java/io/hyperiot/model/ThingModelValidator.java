package io.hyperiot.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.common.ApiException;
import io.hyperiot.model.ThingModel.PropertyDef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates thing model definitions (admin side) and device-reported values (device side).
 */
public final class ThingModelValidator {

    public static final Set<String> TYPES = Set.of("int", "long", "float", "double", "bool", "string", "enum");
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");
    private static final int MAX_STRING = 1024;
    private static final int MAX_ITEMS = 500;

    private ThingModelValidator() {
    }

    /** Result of checking reported values against the model. */
    public record Checked(Map<String, Object> accepted, List<String> rejected) {
    }

    // ----------------------------------------------------------------- definition

    public static void validateDefinition(ThingModel model) {
        if (model.properties().size() > MAX_ITEMS || model.events().size() > MAX_ITEMS || model.services().size() > MAX_ITEMS) {
            throw ApiException.badRequest("物模型条目过多（每类最多 " + MAX_ITEMS + "）");
        }
        Set<String> seen = new HashSet<>();
        for (PropertyDef p : model.properties()) {
            checkProperty(p, "属性 ");
            if (!seen.add(p.identifier())) {
                throw ApiException.badRequest("属性标识符重复：" + p.identifier());
            }
            if (p.access() != null && !p.access().equalsIgnoreCase("r") && !p.access().equalsIgnoreCase("rw")) {
                throw ApiException.badRequest("属性 " + p.identifier() + " 的 access 只能是 r 或 rw");
            }
        }
        seen.clear();
        for (ThingModel.EventDef e : model.events()) {
            checkIdentifier(e.identifier(), "事件");
            if (!seen.add(e.identifier())) {
                throw ApiException.badRequest("事件标识符重复：" + e.identifier());
            }
            for (PropertyDef p : e.params()) {
                checkProperty(p, "事件 " + e.identifier() + " 的参数 ");
            }
        }
        seen.clear();
        for (ThingModel.ServiceDef s : model.services()) {
            checkIdentifier(s.identifier(), "服务");
            if (!seen.add(s.identifier())) {
                throw ApiException.badRequest("服务标识符重复：" + s.identifier());
            }
            for (PropertyDef p : s.input()) {
                checkProperty(p, "服务 " + s.identifier() + " 的入参 ");
            }
            for (PropertyDef p : s.output()) {
                checkProperty(p, "服务 " + s.identifier() + " 的出参 ");
            }
        }
    }

    private static void checkIdentifier(String id, String what) {
        if (id == null || !IDENTIFIER.matcher(id).matches()) {
            throw ApiException.badRequest(what + "标识符必须以字母开头，只含字母、数字、下划线，最长 64 位：" + id);
        }
    }

    private static void checkProperty(PropertyDef p, String prefix) {
        checkIdentifier(p.identifier(), prefix);
        if (p.type() == null || !TYPES.contains(p.type())) {
            throw ApiException.badRequest(prefix + p.identifier() + " 的类型无效，可选：" + String.join("、", TYPES));
        }
        if ("enum".equals(p.type()) && (p.values() == null || p.values().isEmpty())) {
            throw ApiException.badRequest(prefix + p.identifier() + " 是枚举类型，必须提供 values");
        }
        if (p.min() != null && p.max() != null && p.min() > p.max()) {
            throw ApiException.badRequest(prefix + p.identifier() + " 的 min 不能大于 max");
        }
    }

    // ----------------------------------------------------------------- values

    /**
     * Checks reported properties. In strict mode undeclared properties are rejected; otherwise they are
     * passed through as-is (numbers, booleans and short strings only).
     */
    public static Checked checkProperties(ThingModel model, JsonNode params, boolean strict) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        if (params == null || !params.isObject()) {
            rejected.add("params 必须是对象");
            return new Checked(accepted, rejected);
        }
        params.fields().forEachRemaining(entry -> {
            String id = entry.getKey();
            JsonNode value = entry.getValue();
            var def = model.property(id);
            if (def.isEmpty()) {
                if (strict) {
                    rejected.add(id + ": 未在物模型中定义");
                } else {
                    Object any = plain(value);
                    if (any == null) {
                        rejected.add(id + ": 仅支持数字、布尔、字符串");
                    } else {
                        accepted.put(id, any);
                    }
                }
                return;
            }
            try {
                accepted.put(id, coerce(def.get(), value));
            } catch (IllegalArgumentException e) {
                rejected.add(id + ": " + e.getMessage());
            }
        });
        return new Checked(accepted, rejected);
    }

    /** Checks a map of values against a list of parameter definitions (service input / event params). */
    public static Map<String, Object> checkParams(List<PropertyDef> defs, JsonNode params, boolean requireAll) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode obj = params == null || params.isNull() || params.isMissingNode() ? null : params;
        if (obj != null && !obj.isObject()) {
            throw ApiException.badRequest("params 必须是对象");
        }
        for (PropertyDef def : defs) {
            JsonNode v = obj == null ? null : obj.get(def.identifier());
            if (v == null || v.isNull()) {
                if (requireAll) {
                    throw ApiException.badRequest("缺少参数：" + def.identifier());
                }
                continue;
            }
            try {
                out.put(def.identifier(), coerce(def, v));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("参数 " + def.identifier() + "：" + e.getMessage());
            }
        }
        if (obj != null) {
            obj.fieldNames().forEachRemaining(name -> {
                if (defs.stream().noneMatch(d -> d.identifier().equals(name))) {
                    throw ApiException.badRequest("未定义的参数：" + name);
                }
            });
        }
        return out;
    }

    /** Converts a JSON value to the Java value of the declared type, or throws IllegalArgumentException. */
    public static Object coerce(PropertyDef def, JsonNode v) {
        switch (def.type()) {
            case "int", "long" -> {
                if (!v.isIntegralNumber()) {
                    throw new IllegalArgumentException("需要整数");
                }
                long n = v.asLong();
                if ("int".equals(def.type()) && (n > Integer.MAX_VALUE || n < Integer.MIN_VALUE)) {
                    throw new IllegalArgumentException("超出 int 范围");
                }
                range(def, n);
                return n;
            }
            case "float", "double" -> {
                if (!v.isNumber()) {
                    throw new IllegalArgumentException("需要数字");
                }
                double d = v.asDouble();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IllegalArgumentException("数值无效");
                }
                range(def, d);
                return d;
            }
            case "bool" -> {
                if (!v.isBoolean()) {
                    throw new IllegalArgumentException("需要布尔值");
                }
                return v.asBoolean();
            }
            case "string" -> {
                if (!v.isTextual()) {
                    throw new IllegalArgumentException("需要字符串");
                }
                if (v.asText().length() > MAX_STRING) {
                    throw new IllegalArgumentException("字符串过长（最大 " + MAX_STRING + "）");
                }
                return v.asText();
            }
            case "enum" -> {
                if (!v.isTextual() || def.values() == null || !def.values().contains(v.asText())) {
                    throw new IllegalArgumentException("必须是 " + def.values() + " 之一");
                }
                return v.asText();
            }
            default -> throw new IllegalArgumentException("未知类型 " + def.type());
        }
    }

    private static void range(PropertyDef def, double n) {
        if (def.min() != null && n < def.min()) {
            throw new IllegalArgumentException("小于最小值 " + def.min());
        }
        if (def.max() != null && n > def.max()) {
            throw new IllegalArgumentException("大于最大值 " + def.max());
        }
    }

    private static Object plain(JsonNode v) {
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            double d = v.asDouble();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isTextual() && v.asText().length() <= MAX_STRING) {
            return v.asText();
        }
        return null;
    }
}
