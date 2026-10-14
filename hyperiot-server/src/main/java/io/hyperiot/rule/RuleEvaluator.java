package io.hyperiot.rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure evaluation of rule conditions against a set of property values. */
public final class RuleEvaluator {

    public static final Set<String> OPS = Set.of("GT", "GE", "LT", "LE", "EQ", "NE");

    private RuleEvaluator() {
    }

    /**
     * @param matched whether the whole rule (all/any conditions, depending on logic) holds
     * @param summary human-readable description of the conditions that held, e.g. "temperature=85.5 &gt; 80"
     * @param values  the values of the properties that took part in the decision
     */
    public record Result(boolean matched, String summary, Map<String, Object> values) {
    }

    public static Result evaluate(Rule rule, Map<String, Object> current) {
        boolean and = !"OR".equalsIgnoreCase(rule.logic());
        boolean matched = and;
        List<String> held = new ArrayList<>();
        Map<String, Object> used = new LinkedHashMap<>();
        for (Rule.Condition c : rule.conditions()) {
            Object actual = current.get(c.identifier());
            boolean ok = actual != null && compare(actual, c.op(), c.value());
            if (actual != null) {
                used.put(c.identifier(), actual);
            }
            if (ok) {
                held.add(c.identifier() + "=" + actual + " " + symbol(c.op()) + " " + c.value());
            }
            if (and) {
                matched &= ok;
            } else {
                matched |= ok;
            }
        }
        if (rule.conditions().isEmpty()) {
            matched = false;
        }
        return new Result(matched, String.join("，", held), used);
    }

    static boolean compare(Object actual, String op, Object expected) {
        if (expected == null) {
            return false;
        }
        if (actual instanceof Number a && expected instanceof Number e) {
            int c = Double.compare(a.doubleValue(), e.doubleValue());
            return switch (op) {
                case "GT" -> c > 0;
                case "GE" -> c >= 0;
                case "LT" -> c < 0;
                case "LE" -> c <= 0;
                case "EQ" -> c == 0;
                case "NE" -> c != 0;
                default -> false;
            };
        }
        // booleans, strings and enums only support equality
        boolean eq = String.valueOf(actual).equals(String.valueOf(expected));
        return switch (op) {
            case "EQ" -> eq;
            case "NE" -> !eq;
            default -> false;
        };
    }

    static String symbol(String op) {
        return switch (op) {
            case "GT" -> ">";
            case "GE" -> ">=";
            case "LT" -> "<";
            case "LE" -> "<=";
            case "EQ" -> "==";
            case "NE" -> "!=";
            default -> op;
        };
    }
}
