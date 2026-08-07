package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CanonicalMatrixCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compilesColumnRecordsWithRegionScopedSlotsAndRuntimeTrainingPolicy() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"sheet-1","semanticCells":[
                  {"address":"A5","value":"物性测试"},
                  {"address":"B5","value":"外观"},
                  {"address":"C5","value":"目测"},
                  {"address":"E4","value":"样品A"},
                  {"address":"E5","value":"合格"},
                  {"address":"F5","value":""}
                ]}]}
                """);
        var compiler = new CanonicalMatrixCompiler(objectMapper);
        var projection = compiler.recordProjection(4, 5, 6, 4, 5, 5, "COLUMN");
        var slots = compiler.columnSlots("sheet-1", "region-1", "A4:F5", 5, 6, 4, 5);
        var artifacts = compiler.compileMatrixArtifacts(facts, "sheet-1", "region-1", "A4:F5",
                "A4:D4", "A5:D5", "E4:F4", "E5:F5", objectMapper.createArrayNode(),
                projection, slots, objectMapper.createArrayNode(), "CONFIRMED");

        assertThat(artifacts.path("matrixModel").path("semanticMode").asText()).isEqualTo("CROSS_TAB");
        assertThat(artifacts.path("matrixModel").path("recordAxis").asText()).isEqualTo("COLUMN");
        assertThat(artifacts.path("columnSlots")).hasSize(2);
        assertThat(artifacts.path("columnSlots").get(0).path("slotId").asText()).contains("region-1|COLUMN|E");
        assertThat(artifacts.path("longTableModel").path("records")).hasSize(2);
        assertThat(artifacts.path("longTableModel").path("records").get(0)
                .path("entityRecordId").asText()).contains("sheet-1|region-1|COLUMN|E");
        assertThat(artifacts.path("longTableModel").path("records").get(0)
                .path("trainingEligible").asBoolean()).isTrue();
        assertThat(artifacts.path("longTableModel").path("records").get(1)
                .path("trainingEligible").asBoolean()).isFalse();
    }

    @Test
    void compilesRowProjectionWithoutPretendingItIsColumnRecords() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"sheet-1","semanticCells":[
                  {"address":"A5","value":"样品一"}, {"address":"A6","value":"样品二"},
                  {"address":"B4","value":"结果"}, {"address":"C4","value":"备注"},
                  {"address":"B5","value":12}, {"address":"B6","value":13},
                  {"address":"C5","value":"正常"}
                ]}]}
                """);
        var compiler = new CanonicalMatrixCompiler(objectMapper);
        var projection = compiler.recordProjection(4, 2, 3, 4, 5, 6, "ROW");
        var rows = compiler.rowSlots("sheet-1", "region-2", "A4:C6", 5, 6, 1, 3);
        var artifacts = compiler.compileMatrixArtifacts(facts, "sheet-1", "region-2", "A4:C6",
                "A4:A4", "A5:A6", "B4:C4", "B5:C6", objectMapper.createArrayNode(),
                projection, objectMapper.createArrayNode(), rows, "CONFIRMED");

        assertThat(artifacts.path("matrixModel").path("recordAxis").asText()).isEqualTo("ROW");
        assertThat(artifacts.path("longTableModel").path("projectionStatus").asText()).isEqualTo("ROW_RECORDS");
        assertThat(artifacts.path("longTableModel").path("records")).hasSize(2);
        assertThat(artifacts.path("longTableModel").path("records").get(0)
                .path("entityRecordId").asText()).contains("sheet-1|region-2|ROW|row-5");
        assertThat(artifacts.path("longTableModel").path("records").get(0)
                .path("trainingEligible").asBoolean()).isTrue();
        assertThat(artifacts.path("longTableModel").path("records").get(0)
                .path("measures")).hasSize(2);
    }
}
