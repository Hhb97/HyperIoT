package io.hyperiot.rule;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEvaluatorTest {

    private static Rule rule(String logic, Rule.Condition... conditions) {
        return new Rule(1L, "r", null, "pk", null, logic, List.of(conditions), List.of(), "WARN", true, 0L);
    }

    @Test
    void numericComparisons() {
        Rule gt = rule("AND", new Rule.Condition("t", "GT", 80));
        assertThat(RuleEvaluator.evaluate(gt, Map.of("t", 80.1)).matched()).isTrue();
        assertThat(RuleEvaluator.evaluate(gt, Map.of("t", 80)).matched()).isFalse();
        assertThat(RuleEvaluator.evaluate(gt, Map.of("t", 79L)).matched()).isFalse();
        assertThat(RuleEvaluator.evaluate(rule("AND", new Rule.Condition("t", "LE", 80)), Map.of("t", 80)).matched()).isTrue();
        assertThat(RuleEvaluator.evaluate(rule("AND", new Rule.Condition("t", "EQ", 5)), Map.of("t", 5.0)).matched()).isTrue();
        assertThat(RuleEvaluator.evaluate(rule("AND", new Rule.Condition("t", "NE", 5)), Map.of("t", 5.0)).matched()).isFalse();
    }

    @Test
    void andRequiresAllOrRequiresOne() {
        var c1 = new Rule.Condition("t", "GT", 80);
        var c2 = new Rule.Condition("h", "LT", 20);
        Map<String, Object> onlyFirst = Map.of("t", 90, "h", 50);
        assertThat(RuleEvaluator.evaluate(rule("AND", c1, c2), onlyFirst).matched()).isFalse();
        assertThat(RuleEvaluator.evaluate(rule("OR", c1, c2), onlyFirst).matched()).isTrue();
        assertThat(RuleEvaluator.evaluate(rule("AND", c1, c2), Map.of("t", 90, "h", 10)).matched()).isTrue();
        assertThat(RuleEvaluator.evaluate(rule("OR", c1, c2), Map.of("t", 10, "h", 50)).matched()).isFalse();
    }

    @Test
    void missingValuesNeverMatch() {
        assertThat(RuleEvaluator.evaluate(rule("AND", new Rule.Condition("t", "GT", 80)), Map.of()).matched()).isFalse();
        assertThat(RuleEvaluator.evaluate(rule("AND", new Rule.Condition("t", "NE", 80)), Map.of()).matched()).isFalse();
        assertThat(RuleEvaluator.evaluate(rule("AND"), Map.of("t", 1)).matched()).as("no conditions").isFalse();
    }

    @Test
    void nonNumericValuesOnlySupportEquality() {
        assertThat(RuleEvaluator.evaluate(rule("AND", new Rule.Condition("mode", "EQ", "auto")), Map.of("mode", "auto")).matched()).isTrue();
        assertThat(RuleEvaluator.evaluate(rule("AND", new Rule.Condition("mode", "NE", "auto")), Map.of("mode", "manual")).matched()).isTrue();
        assertThat(RuleEvaluator.evaluate(rule("AND", new Rule.Condition("mode", "GT", "a")), Map.of("mode", "b")).matched()).isFalse();
        assertThat(RuleEvaluator.evaluate(rule("AND", new Rule.Condition("fan", "EQ", true)), Map.of("fan", true)).matched()).isTrue();
    }

    @Test
    void summaryDescribesTheMatchedConditions() {
        var r = RuleEvaluator.evaluate(rule("AND", new Rule.Condition("t", "GT", 80)), Map.of("t", 85.5));
        assertThat(r.summary()).isEqualTo("t=85.5 > 80");
        assertThat(r.values()).containsEntry("t", 85.5);
    }

    @Test
    void ruleScope() {
        Rule all = rule("AND", new Rule.Condition("t", "GT", 1));
        assertThat(all.appliesTo("anything")).isTrue();
        Rule one = new Rule(1L, "r", null, "pk", "dev1", "AND", all.conditions(), List.of(), "WARN", true, 0L);
        assertThat(one.appliesTo("dev1")).isTrue();
        assertThat(one.appliesTo("dev2")).isFalse();
    }
}
