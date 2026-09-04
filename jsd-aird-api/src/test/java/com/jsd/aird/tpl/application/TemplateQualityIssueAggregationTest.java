package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import org.junit.jupiter.api.Test;

class TemplateQualityIssueAggregationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesDirectionIssueAndItsWordTableEvidence() {
        var service = new TemplateImportService(
                null, null, null, List.of(), objectMapper, null, null, null,
                null, null, null, null);
        var evidence = objectMapper.createObjectNode()
                .put("nodeId", "table-a")
                .put("sourcePath", "/document[1]/body[1]/tbl[2]")
                .put("rowCount", 3)
                .put("columnCount", 3);
        var issue = new RecognitionModelClient.QualityIssueSuggestion(
                "STRUCTURE_DIRECTION_UNCLEAR", "ERROR", "", "", "table-a",
                "方向不明", "无法唯一判断", "需要人工确认", 0.99, false,
                null, null, evidence, "DETECTED", "table-a", null);

        var aggregated = service.aggregateQualityIssues(List.of(issue));

        assertThat(aggregated).singleElement().satisfies(result -> {
            assertThat(result.issueType()).isEqualTo("STRUCTURE_DIRECTION_UNCLEAR");
            assertThat(result.evidence().get(0).path("nodeId").asText()).isEqualTo("table-a");
            assertThat(result.evidence().get(0).path("sourcePath").asText())
                    .isEqualTo("/document[1]/body[1]/tbl[2]");
            assertThat(result.evidence().get(0).path("rowCount").asInt()).isEqualTo(3);
            assertThat(result.evidence().get(0).path("columnCount").asInt()).isEqualTo(3);
        });
        assertThat(TemplateQualityIssueCategory.fromIssueType("STRUCTURE_DIRECTION_UNCLEAR"))
                .isEqualTo("TABLE_STRUCTURE_UNCLEAR");
    }
}
