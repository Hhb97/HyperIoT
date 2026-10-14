package io.hyperiot.model;

import io.hyperiot.common.ApiException;
import io.hyperiot.common.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThingModelValidatorTest {

    private static final ThingModel MODEL = Json.read("""
            {"properties":[
              {"identifier":"t","type":"double","min":-40,"max":125},
              {"identifier":"h","type":"int","min":0,"max":100},
              {"identifier":"big","type":"long"},
              {"identifier":"on","type":"bool"},
              {"identifier":"name","type":"string"},
              {"identifier":"mode","type":"enum","values":["auto","manual"]}
            ]}""", ThingModel.class);

    @Test
    void acceptsValidValues() {
        var r = ThingModelValidator.checkProperties(MODEL,
                Json.tree("{\"t\":21.5,\"h\":55,\"big\":9000000000,\"on\":true,\"name\":\"x\",\"mode\":\"auto\"}"), true);
        assertThat(r.rejected()).isEmpty();
        assertThat(r.accepted()).containsEntry("t", 21.5).containsEntry("h", 55L).containsEntry("big", 9_000_000_000L)
                .containsEntry("on", true).containsEntry("name", "x").containsEntry("mode", "auto");
    }

    @Test
    void integerPropertyAcceptsWholeNumbersForDouble() {
        var r = ThingModelValidator.checkProperties(MODEL, Json.tree("{\"t\":20}"), true);
        assertThat(r.accepted()).containsEntry("t", 20.0);
    }

    @Test
    void rejectsOutOfRangeWrongTypeAndUnknown() {
        var r = ThingModelValidator.checkProperties(MODEL,
                Json.tree("{\"t\":200,\"h\":1.5,\"on\":\"yes\",\"mode\":\"turbo\",\"nope\":1,\"name\":5}"), true);
        assertThat(r.accepted()).isEmpty();
        assertThat(r.rejected()).hasSize(6);
    }

    @Test
    void nonStrictModePassesUnknownScalars() {
        var r = ThingModelValidator.checkProperties(MODEL, Json.tree("{\"extra\":3,\"obj\":{\"a\":1}}"), false);
        assertThat(r.accepted()).containsEntry("extra", 3L);
        assertThat(r.rejected()).hasSize(1);
    }

    @Test
    void rejectsNonObjectParams() {
        assertThat(ThingModelValidator.checkProperties(MODEL, Json.tree("[1,2]"), true).rejected()).hasSize(1);
        assertThat(ThingModelValidator.checkProperties(MODEL, null, true).rejected()).hasSize(1);
    }

    @Test
    void nanAndInfinityAreNotNumbers() {
        // JSON has no NaN literal, but a double that overflows to infinity must still be refused
        var r = ThingModelValidator.checkProperties(MODEL, Json.tree("{\"t\":1e999}"), true);
        assertThat(r.accepted()).isEmpty();
    }

    @Test
    void checksServiceParameters() {
        var defs = List.of(new ThingModel.PropertyDef("delay", null, "int", null, 0.0, 60.0, null, null));
        assertThat(ThingModelValidator.checkParams(defs, Json.tree("{\"delay\":5}"), true)).containsEntry("delay", 5L);
        assertThatThrownBy(() -> ThingModelValidator.checkParams(defs, Json.tree("{}"), true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ThingModelValidator.checkParams(defs, Json.tree("{\"delay\":61}"), true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ThingModelValidator.checkParams(defs, Json.tree("{\"delay\":1,\"x\":2}"), true)).isInstanceOf(ApiException.class);
        assertThat(ThingModelValidator.checkParams(defs, null, false)).isEmpty();
    }

    @Test
    void definitionValidation() {
        assertThatThrownBy(() -> ThingModelValidator.validateDefinition(Json.read(
                "{\"properties\":[{\"identifier\":\"a\",\"type\":\"int\",\"min\":10,\"max\":1}]}", ThingModel.class)))
                .isInstanceOf(ApiException.class).hasMessageContaining("min");
        assertThatThrownBy(() -> ThingModelValidator.validateDefinition(Json.read(
                "{\"properties\":[{\"identifier\":\"a\",\"type\":\"int\",\"access\":\"w\"}]}", ThingModel.class)))
                .isInstanceOf(ApiException.class);
        ThingModelValidator.validateDefinition(ThingModel.empty());
        ThingModelValidator.validateDefinition(MODEL);
    }
}
