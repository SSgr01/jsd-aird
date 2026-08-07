package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelSemanticViewBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsLowConfidenceInputCandidatesInTheModelView() throws Exception {
        var cell = objectMapper.createObjectNode()
                .put("address", "B5")
                .put("row", 5)
                .put("column", 2)
                .put("factType", "INPUT_CANDIDATE")
                .put("inputCandidate", true)
                .put("inputConfidence", 0.69)
                .put("inputEvidence", "REPEATED_DATA_REGION")
                .put("value", "")
                .put("mergeAnchorCell", true);
        var sheet = objectMapper.createObjectNode()
                .put("id", "sheet-1")
                .put("name", "Sheet1")
                .put("usedRange", "A1:B5");
        sheet.set("semanticCells", objectMapper.createArrayNode().add(cell));
        sheet.set("mergedRanges", objectMapper.createArrayNode());
        var structure = objectMapper.createObjectNode()
                .put("structureVersion", 6)
                .set("sheets", objectMapper.createArrayNode().add(sheet));

        var view = new ModelSemanticViewBuilder(objectMapper)
                .build(structure, "WORKBOOK", "", "");

        assertThat(view.path("sheets").get(0).path("semanticCells")).hasSize(1);
        assertThat(view.path("sheets").get(0).path("semanticCells").get(0)
                .path("inputCandidate").asBoolean()).isTrue();
    }
}
