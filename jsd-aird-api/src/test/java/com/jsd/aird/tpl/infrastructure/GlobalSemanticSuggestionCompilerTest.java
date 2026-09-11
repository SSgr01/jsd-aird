package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GlobalSemanticSuggestionCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completesFormRegionFromPhysicalLabelsAndKeepsUnboundFieldsVisible() throws Exception {
        var context = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"光引发剂对比测试","semanticCells":[
                  {"sheetId":"s1","address":"A1","value":"不同光引发剂耐黄变对比测试","mergedRange":"A1:H1"},
                  {"sheetId":"s1","address":"A2","value":"目的"},
                  {"sheetId":"s1","address":"B2","value":"测试不同光引发剂对黄变性的影响","mergedRange":"B2:D2"},
                  {"sheetId":"s1","address":"E2","value":"时间"},
                  {"sheetId":"s1","address":"F2","inputCandidate":true},
                  {"sheetId":"s1","address":"G2","value":"测试人"},
                  {"sheetId":"s1","address":"H2","inputCandidate":true},
                  {"sheetId":"s1","address":"A3","value":"固化条件"},
                  {"sheetId":"s1","address":"B3","inputCandidate":true,"mergedRange":"B3:E3"},
                  {"sheetId":"s1","address":"F3","value":"膜厚："},
                  {"sheetId":"s1","address":"G3","value":"素材：","mergedRange":"G3:H3"}
                ]}],"semanticRegions":[{"regionId":"form-1","sheetId":"s1","range":"A1:H3",
                  "type":"FORM_REGION","candidateRef":"form-candidate","structure":{}}]}
                """);
        var response = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"recognitionProtocolVersion":3,"regions":[{"regionId":"form-1","businessName":"基本信息区域",
                  "fieldRelations":[],"qualityIssues":[]}],"qualityIssues":[]}
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compileRegionBatch(response, context);
        var fields = compiled.suggestions().stream()
                .filter(item -> "SCALAR_FIELD".equals(item.suggestionType())).toList();

        assertThat(fields).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactlyInAnyOrder("目的", "时间", "测试人", "固化条件", "膜厚", "素材");
        var purpose = fields.stream().filter(item -> "目的".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow().payload();
        assertThat(purpose.path("locator").path("address").asText()).isEqualTo("B2:D2");
        assertThat(purpose.path("editability").asText()).isEqualTo("READ_ONLY");
        var tester = fields.stream().filter(item -> "测试人".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow().payload();
        assertThat(tester.path("locator").path("address").asText()).isEqualTo("H2");
        var thickness = fields.stream().filter(item -> "膜厚".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow().payload();
        assertThat(thickness.path("positionPending").asBoolean()).isTrue();
        assertThat(thickness.path("locator").path("address").asText()).isBlank();
        assertThat(thickness.path("pendingReason").asText()).isEqualTo("FIELD_POSITION_REQUIRED");
    }

    @Test
    void columnTableUsesOnlyPhysicalCandidatesWhenSemanticRelationsAreEmpty() throws Exception {
        var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"recognitionProtocolVersion":3,
                  "regions":[{"regionId":"r1","businessName":"光引发剂对比测试",
                    "fieldRelations":[],"qualityIssues":[]}],"qualityIssues":[]}
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"r1","sheetId":"s1","range":"A4:H19",
                  "type":"COLUMN_TABLE","candidateRef":"physical-1",
                  "structure":{"headerRange":"A4:H4","dataRange":"A5:H19","recordAxis":"COLUMN"},
                  "fieldCandidates":[
                    {"candidateRef":"f1","fieldName":"引发剂","labelRange":"C4:C4","valueRange":"C5:C19","valueType":"string"},
                    {"candidateRef":"f2","fieldName":"耐黄变","labelRange":"A5:B5","valueRange":"A6:B19","valueType":"number"}
                  ]}]}
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compileRegionBatch(normalized, context);
        var table = compiled.suggestions().stream()
                .filter(item -> "COLUMN_TABLE".equals(item.suggestionType())).findFirst().orElseThrow();

        assertThat(table.payload().path("columns")).hasSize(2);
        assertThat(table.payload().path("columns")).extracting(column -> column.path("name").asText())
                .containsExactly("耐黄变", "引发剂");
        assertThat(table.payload().path("columns")).extracting(column -> column.path("candidateRef").asText())
                .containsExactly("f2", "f1");
        assertThat(table.payload().has("recordProjection")).isFalse();
        assertThat(table.payload().has("rowAttributes")).isFalse();
        assertThat(compiled.suggestions()).filteredOn(item -> "TABLE_CHILD_FIELD".equals(item.suggestionType()))
                .hasSize(2);
    }

    @Test
    void columnSemanticPatchCannotChangePhysicalLocationOrCreateCandidate() throws Exception {
        var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"recognitionProtocolVersion":3,
                  "regions":[{"regionId":"r1","businessName":"测试区域","fieldRelations":[
                    {"candidateRef":"f1","businessName":"模型补充名称","labelRange":"Z99","valueRange":"Z100",
                     "valueType":"number","unit":"mPa·s","groupName":"基础性能"},
                    {"candidateRef":"unknown","businessName":"模型发明字段","labelRange":"A1","valueRange":"B1"}
                  ],"qualityIssues":[]}],"qualityIssues":[]}
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"r1","sheetId":"s1","range":"A4:H19",
                  "type":"COLUMN_TABLE","candidateRef":"physical-1",
                  "structure":{"headerRange":"A4:H4","dataRange":"A5:H19","recordAxis":"COLUMN"},
                  "fieldCandidates":[{"candidateRef":"f1","fieldName":"粘度","labelRange":"C4:C4",
                    "valueRange":"C5:C19","valueType":"string"}]}]}
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compileRegionBatch(normalized, context);
        var child = compiled.suggestions().stream()
                .filter(item -> "TABLE_CHILD_FIELD".equals(item.suggestionType())).findFirst().orElseThrow();
        assertThat(child.payload().path("fieldName").asText()).isEqualTo("模型补充名称");
        assertThat(child.payload().path("unit").asText()).isEqualTo("mPa·s");
        assertThat(child.payload().path("locator").path("labelRange").asText()).isEqualTo("C4:C4");
        assertThat(child.payload().path("locator").path("address").asText()).isEqualTo("C5:C19");
        assertThat(compiled.qualityIssues()).extracting(issue -> issue.issueType())
                .contains("UNKNOWN_FIELD_CANDIDATE");
    }

    @Test
    void carriesAutoConfirmedV4SemanticsAndFormulaProjectionIntoExistingSuggestions() throws Exception {
        var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"recognitionProtocolVersion":4,
                  "experimentTemplateSuggestion":{"templateUsage":"EXPERIMENT_DATA","recordMode":"BY_IDENTITY",
                    "confidence":0.97,"alternatives":[],"experimentSemanticStatus":"AUTO_CONFIRMED","autoAccept":true},
                  "regions":[{"regionId":"r1","businessName":"应用测试报告","fieldRelations":[
                    {"candidateRef":"f1","fieldName":"实验编号","businessName":"实验编号","valueType":"string","unit":"","groupName":"实验配方",
                     "experimentField":{"domain":"BASIC","field":"SOURCE_IDENTITY"},"experimentItemLabel":"实验编号",
                     "experimentSemanticConfidence":0.99,"experimentSemanticStatus":"AUTO_CONFIRMED","experimentSemanticSource":"FIELD_RULE","autoAccept":true}
                  ],"matrixRelations":[{"candidateRef":"matrix-1","matrixType":"FORMULA_MATRIX","confidence":0.97,
                    "componentId":"r1","recordAxis":"COLUMN","itemAxis":"ROW","labelRange":"C17:C25","valueRange":"D17:I25",
                    "labelSemantic":"MATERIAL_NAME","valueSemantic":"RATIO","experimentSemanticStatus":"AUTO_CONFIRMED","autoAccept":true}],
                    "qualityIssues":[]}],"qualityIssues":[]}
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"r1","sheetId":"s1","range":"A16:I30",
                  "type":"COLUMN_TABLE","candidateRef":"physical-1","canonicalStatus":"CONFIRMED","structureStatus":"CONFIRMED",
                  "structure":{"headerRange":"A16:I16","dataRange":"A17:I30","recordAxis":"COLUMN"},
                  "fieldCandidates":[{"candidateRef":"f1","fieldName":"实验编号","labelRange":"C16","valueRange":"D16:I16",
                    "valueType":"string","labelPathSegments":["实验配方","实验编号"]}]}]}
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compileRegionBatch(normalized, context);
        var table = compiled.suggestions().stream()
                .filter(item -> "COLUMN_TABLE".equals(item.suggestionType())).findFirst().orElseThrow();
        var child = compiled.suggestions().stream()
                .filter(item -> "TABLE_CHILD_FIELD".equals(item.suggestionType())).findFirst().orElseThrow();

        assertThat(table.payload().path("autoAccept").asBoolean()).isTrue();
        assertThat(table.payload().path("listProjections")).singleElement().satisfies(projection -> {
            assertThat(projection.path("labelRange").asText()).isEqualTo("C17:C25");
            assertThat(projection.path("valueRange").asText()).isEqualTo("D17:I25");
        });
        assertThat(child.payload().path("autoAccept").asBoolean()).isTrue();
        assertThat(child.payload().path("experimentField").path("field").asText())
                .isEqualTo("SOURCE_IDENTITY");
    }

}
