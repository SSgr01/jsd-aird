package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import org.junit.jupiter.api.Test;

class RecognitionCoverageValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsARegionThatReturnsOnlyOneScalarFieldInsteadOfTableStructure() {
        var validator = new RecognitionCoverageValidator(objectMapper);
        var region = region("b1", "ROW_TABLE", "A4:D12");
        var scalar = objectMapper.createObjectNode()
                .put("blockId", "b1")
                .put("kind", "SCALAR")
                .put("fieldName", "序号");

        var assessment = validator.assess(
                objectMapper.createObjectNode(),
                List.of(region),
                Map.of("s1|A4:D12|ROW_TABLE", "SUCCEEDED"),
                List.of(new RecognitionModelClient.ModelSuggestion(
                        "SCALAR_FIELD", scalar, 0.9, objectMapper.createArrayNode()
                )),
                true,
                false
        );

        assertThat(assessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(assessment.coveredRegionCount()).isZero();
        assertThat(assessment.unresolvedRegionCount()).isEqualTo(1);
        assertThat(assessment.report().path("coverageRatio").asDouble()).isZero();
    }

    @Test
    void acceptsARegionOnlyWhenTheReturnedTableMatchesItsPhysicalKindAndRegion() {
        var validator = new RecognitionCoverageValidator(objectMapper);
        var region = region("b1", "MATRIX", "A4:N100");
        var table = objectMapper.createObjectNode()
                .put("blockId", "b1")
                .put("kind", "MATRIX")
                .set("locator", objectMapper.createObjectNode().put("range", "A4:N100"));

        var assessment = validator.assess(
                objectMapper.createObjectNode(),
                List.of(region),
                Map.of("s1|A4:N100|MATRIX", "SUCCEEDED"),
                List.of(new RecognitionModelClient.ModelSuggestion(
                        "TABLE_REGION", table, 0.9, objectMapper.createArrayNode()
                )),
                true,
                false
        );

        assertThat(assessment.status()).isEqualTo("COMPLETE");
        assertThat(assessment.coveredRegionCount()).isEqualTo(1);
        assertThat(assessment.unresolvedRegionCount()).isZero();
    }

    @Test
    void reportsConfirmedCoreRegionsAsCoveredWhileLeavingModelOnlyFootersUnresolved() {
        var validator = new RecognitionCoverageValidator(objectMapper);
        var form = region("form", "FORM_REGION", "A1:J5").put("structureStatus", "CONFIRMED");
        var rows = region("rows", "ROW_TABLE", "A6:J22").put("structureStatus", "CONFIRMED");
        var footer = region("footer", "FORM_REGION", "A23:J24")
                .put("canonicalStatus", "PROVISIONAL").put("structureStatus", "UNRESOLVED");
        var signatures = region("signatures", "FORM_REGION", "A25:J27")
                .put("canonicalStatus", "PROVISIONAL").put("structureStatus", "UNRESOLVED");
        var scalar = objectMapper.createObjectNode()
                .put("blockId", "form")
                .put("kind", "SCALAR")
                .set("locator", objectMapper.createObjectNode().put("range", "B2:C2"));
        var table = objectMapper.createObjectNode()
                .put("blockId", "rows")
                .put("kind", "ROW_TABLE")
                .set("locator", objectMapper.createObjectNode().put("range", "A6:J22"));

        var assessment = validator.assess(
                objectMapper.createObjectNode(),
                List.of(form, rows, footer, signatures),
                Map.of("s1|A1:J5|FORM_REGION", "SUCCEEDED", "s1|A6:J22|ROW_TABLE", "SUCCEEDED"),
                List.of(
                        new RecognitionModelClient.ModelSuggestion(
                                "SCALAR_FIELD", scalar, 0.9, objectMapper.createArrayNode()),
                        new RecognitionModelClient.ModelSuggestion(
                                "TABLE_REGION", table, 0.9, objectMapper.createArrayNode())
                ),
                true,
                false
        );

        assertThat(assessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(assessment.coveredRegionCount()).isEqualTo(2);
        assertThat(assessment.unresolvedRegionCount()).isEqualTo(2);
        assertThat(assessment.report().path("coverageRatio").asDouble()).isEqualTo(0.5);
    }

    private ObjectNode region(String blockId, String type, String range) {
        return objectMapper.createObjectNode()
                .put("blockId", blockId)
                .put("temporaryId", blockId)
                .put("sheetId", "s1")
                .put("range", range)
                .put("type", type)
                .put("businessName", "测试区域")
                .put("canonicalStatus", "CONFIRMED");
    }
}
