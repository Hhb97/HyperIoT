package io.hyperiot.rule;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperiot.alarm.Alarm;
import io.hyperiot.alarm.AlarmService;
import io.hyperiot.bus.DeviceMessage;
import io.hyperiot.bus.EventBus;
import io.hyperiot.bus.MessageKind;
import io.hyperiot.command.CommandService;
import io.hyperiot.common.Json;
import io.hyperiot.shadow.ShadowService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates the rules of a product whenever one of its devices reports properties. A rule whose conditions hold raises
 * an alarm (once per rule and device) and runs its actions; when the conditions stop holding the alarm recovers.
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final EventBus bus;
    private final RuleService rules;
    private final ShadowService shadow;
    private final AlarmService alarms;
    private final CommandService commands;
    private final WebhookClient webhook;

    public RuleEngine(EventBus bus, RuleService rules, ShadowService shadow, AlarmService alarms, CommandService commands,
                      WebhookClient webhook) {
        this.bus = bus;
        this.rules = rules;
        this.shadow = shadow;
        this.alarms = alarms;
        this.commands = commands;
        this.webhook = webhook;
    }

    @PostConstruct
    void start() {
        bus.subscribe("rule-engine", Set.of(MessageKind.PROPERTY), this::onProperty);
    }

    void onProperty(DeviceMessage m) {
        List<Rule> candidates = rules.enabledFor(m.productKey()).stream().filter(r -> r.appliesTo(m.deviceName())).toList();
        if (candidates.isEmpty()) {
            return;
        }
        JsonNode params = m.payload().get("params");
        if (params == null) {
            return;
        }
        // the shadow may not have merged this message yet, so overlay it on the last known state
        Map<String, Object> current = new LinkedHashMap<>(shadow.reportedValues(m.deviceId()));
        params.fields().forEachRemaining(e -> current.put(e.getKey(), plain(e.getValue())));

        for (Rule rule : candidates) {
            boolean touched = rule.conditions().stream().anyMatch(c -> params.has(c.identifier()));
            if (!touched) {
                continue; // nothing this rule looks at has changed
            }
            try {
                RuleEvaluator.Result result = RuleEvaluator.evaluate(rule, current);
                if (result.matched()) {
                    alarms.raise(rule.id(), rule.name(), m.deviceId(), m.productKey(), m.deviceName(), rule.level(),
                            rule.name() + "：" + result.summary(), Json.write(result.values()))
                            .ifPresent(a -> runActions(rule, a, m));
                } else {
                    alarms.recover(rule.id(), m.deviceId());
                }
            } catch (Exception e) {
                log.error("Rule {} failed for device {}", rule.id(), m.deviceName(), e);
            }
        }
    }

    private void runActions(Rule rule, Alarm alarm, DeviceMessage m) {
        for (Rule.Action a : rule.actions()) {
            try {
                if ("COMMAND".equals(a.type())) {
                    commands.invoke(m.deviceId(), a.identifier(), Json.toTree(a.params() == null ? Map.of() : a.params()), null);
                } else if ("WEBHOOK".equals(a.type())) {
                    webhook.post(a.url(), Json.write(Map.of("type", "alarm", "alarm", alarm)));
                }
            } catch (Exception e) {
                log.warn("Action {} of rule {} failed: {}", a.type(), rule.id(), e.toString());
            }
        }
    }

    private static Object plain(JsonNode n) {
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        return n.asText();
    }
}
