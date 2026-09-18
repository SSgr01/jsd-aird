package com.jsd.aird.rnd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class ExperimentEditModelNormalizerTest {

    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExperimentEditModelNormalizer normalizer = new ExperimentEditModelNormalizer(mapper);

    @Test
    void upgradesLegacyModelsWithoutDroppingExtensionFields() throws Exception {
        var legacy = mapper.readTree("""
                {
                  "title": "UV实验",
                  "futureRoot": {"enabled": true},
                  "formulaItems": [{
                    "materialName": "树脂A",
                    "ratio": "60",
                    "futureFormulaField": "keep-me",
                    "sourceRefs": [{"sheetId": "Sheet1", "cellRange": "B5:E5", "extra": 1}]
                  }],
                  "processSteps": [{"operation": "搅拌"}],
                  "testResults": [{"testItem": "附着力", "value": "5B"}]
                }
                """);

        var first = normalizer.normalize(legacy, VERSION_ID);
        var second = normalizer.normalize(legacy, VERSION_ID);

        assertThat(first.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(first.path("futureRoot").path("enabled").asBoolean()).isTrue();
        assertThat(first.path("formulaItems").path(0).path("futureFormulaField").asText()).isEqualTo("keep-me");
        assertThat(first.path("formulaItems").path(0).path("sourceRefs").path(0).path("extra").asInt())
                .isEqualTo(1);
        assertThat(first.path("formulaItems").path(0).path("materialId").isNull()).isTrue();
        assertThat(first.path("formulaItems").path(0).path("materialCode").asText()).isEmpty();
        assertThat(first.path("formulaItems").path(0).path("rawValue").isNull()).isTrue();
        assertThat(first.path("formulaItems").path(0).path("rawUnit").asText()).isEmpty();
        assertThat(first.path("formulaItems").path(0).path("sampleKey").asText()).isEmpty();
        assertThat(first.path("formulaItems").path(0).path("sourceIdentity").asText()).isEmpty();
        assertThat(first.path("formulaItems").path(0).path("sourceRecordKey").asText()).isEmpty();
        assertThat(first.path("testResults").path(0).path("testMethod").asText()).isEmpty();
        assertThat(first.path("testResults").path(0).path("testCondition").asText()).isEmpty();
        assertThat(first.path("testResults").path(0).path("substrate").asText()).isEmpty();
        assertThat(first.path("formulaItems").path(0).path("itemId").asText())
                .isEqualTo(second.path("formulaItems").path(0).path("itemId").asText())
                .isNotBlank();
        assertThat(first.path("processSteps").path(0).path("itemId").asText()).isNotBlank();
        assertThat(first.path("testResults").path(0).path("itemId").asText()).isNotBlank();
    }

    @Test
    void keepsExistingStableIdsAndAcceptsNullLegacyIds() throws Exception {
        var model = mapper.readTree("""
                {
                  "schemaVersion": 2,
                  "formulaItems": [{"itemId": " formula-1 ", "sourceRefs": []}],
                  "processSteps": [{"itemId": null}],
                  "testResults": []
                }
                """);

        var normalized = normalizer.normalize(model, VERSION_ID);

        assertThat(normalized.path("formulaItems").path(0).path("itemId").asText()).isEqualTo("formula-1");
        assertThat(normalized.path("processSteps").path(0).path("itemId").asText()).isNotBlank();
    }

    @Test
    void rejectsDuplicateItemIdsAndUnsupportedFutureSchemas() throws Exception {
        var duplicate = mapper.readTree("""
                {
                  "formulaItems": [{"itemId": "same"}, {"itemId": "same"}],
                  "processSteps": [],
                  "testResults": []
                }
                """);
        assertThatThrownBy(() -> normalizer.normalize(duplicate, VERSION_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("重复itemId");

        var future = mapper.createObjectNode().put("schemaVersion", 3);
        assertThatThrownBy(() -> normalizer.normalize(future, VERSION_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("暂不支持");
    }
}
