package io.hyperiot.rule;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

public record Rule(Long id, String name, String description, String productKey, String deviceName, String logic,
                   List<Condition> conditions, List<Action> actions, String level, Boolean enabled, Long createdAt) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Condition(String identifier, String op, Object value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(String type, String identifier, Map<String, Object> params, String url) {
    }

    public boolean appliesTo(String deviceName) {
        return this.deviceName == null || this.deviceName.isBlank() || this.deviceName.equals(deviceName);
    }
}
