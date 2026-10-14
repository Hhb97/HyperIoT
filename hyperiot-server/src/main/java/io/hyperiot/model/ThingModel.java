package io.hyperiot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/**
 * The thing model describes what a product can report (properties, events) and what it can do (services).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThingModel(List<PropertyDef> properties, List<EventDef> events, List<ServiceDef> services) {

    public ThingModel {
        properties = properties == null ? List.of() : List.copyOf(properties);
        events = events == null ? List.of() : List.copyOf(events);
        services = services == null ? List.of() : List.copyOf(services);
    }

    public static ThingModel empty() {
        return new ThingModel(List.of(), List.of(), List.of());
    }

    public Optional<PropertyDef> property(String identifier) {
        return properties.stream().filter(p -> p.identifier().equals(identifier)).findFirst();
    }

    public Optional<EventDef> event(String identifier) {
        return events.stream().filter(e -> e.identifier().equals(identifier)).findFirst();
    }

    public Optional<ServiceDef> service(String identifier) {
        return services.stream().filter(s -> s.identifier().equals(identifier)).findFirst();
    }

    /** A typed value definition, shared by properties, event parameters and service parameters. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PropertyDef(String identifier, String name, String type, String unit,
                              Double min, Double max, List<String> values, String access) {

        public boolean writable() {
            return "rw".equalsIgnoreCase(access);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventDef(String identifier, String name, String level, List<PropertyDef> params) {

        public EventDef {
            params = params == null ? List.of() : List.copyOf(params);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceDef(String identifier, String name, List<PropertyDef> input, List<PropertyDef> output) {

        public ServiceDef {
            input = input == null ? List.of() : List.copyOf(input);
            output = output == null ? List.of() : List.copyOf(output);
        }
    }
}
