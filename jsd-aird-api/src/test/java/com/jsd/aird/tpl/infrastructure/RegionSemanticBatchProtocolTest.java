package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RegionSemanticBatchProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsRemovedV3ProtocolInsteadOfRewritingHistoricalResults() throws Exception {
        var response = objectMapper.readTree("""
                {"recognitionProtocolVersion":3,"regions":[],"qualityIssues":[]}
                """);

        assertThatThrownBy(() -> new RegionSemanticBatchProtocol(objectMapper).validate(
                response, objectMapper.readTree("{\"semanticRegions\":[]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须为 4");
    }

    @Test
    void resolvesGroupFirstSemanticsAndRejectsHallucinatedCandidates() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":4,
                  "experimentTemplateSuggestion":{"templateUsage":"EXPERIMENT_DATA","recordMode":"BY_IDENTITY","confidence":0.97,"alternatives":[]},
                  "regions":[{
                    "regionId":"region-1","businessName":"应用测试报告",
                    "fieldRelations":[
                      {"candidateRef":"experiment-no","fieldName":"实验编号","businessName":"实验编号","valueType":"string","unit":"","groupName":"实验配方",
                       "experimentFieldSuggestion":{"domain":"BASIC","field":"SOURCE_IDENTITY","itemLabel":"实验编号","confidence":0.99,"alternatives":[]}},
                      {"candidateRef":"warping","fieldName":"初始翘曲","businessName":"初始翘曲","valueType":"string","unit":"cm","groupName":"性能测试",
                       "experimentFieldSuggestion":{"domain":"TEST","field":"VALUE","itemLabel":"初始翘曲","confidence":0.96,"alternatives":[]}},
                      {"candidateRef":"missing","fieldName":"虚构字段","businessName":"虚构字段","valueType":"string","unit":"","groupName":"性能测试",
                       "experimentFieldSuggestion":{"domain":"TEST","field":"VALUE","itemLabel":"虚构字段","confidence":0.99,"alternatives":[]}}
                    ],"matrixRelations":[],"qualityIssues":[]
                  }],"qualityIssues":[]
                }
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{
                  "regionId":"region-1","type":"COLUMN_TABLE","canonicalStatus":"CONFIRMED","structureStatus":"CONFIRMED",
                  "fieldCandidates":[
                    {"candidateRef":"experiment-no","labelRange":"C16","valueRange":"D16:I16","labelPathSegments":["实验配方","实验编号"]},
                    {"candidateRef":"warping","labelRange":"C30","valueRange":"D30:I30","labelPathSegments":["性能测试","初始翘曲"]}
                  ],"matrixCandidates":[]
                }]}
                """);

        var normalized = new RegionSemanticBatchProtocol(objectMapper).validate(response, context);

        assertThat(normalized.path("regions").get(0).path("fieldRelations")).hasSize(2);
        var identity = normalized.path("regions").get(0).path("fieldRelations").get(0);
        assertThat(identity.path("experimentField").path("domain").asText()).isEqualTo("BASIC");
        assertThat(identity.path("experimentField").path("field").asText()).isEqualTo("SOURCE_IDENTITY");
        assertThat(identity.path("experimentSemanticStatus").asText()).isEqualTo("AUTO_CONFIRMED");
        var warping = normalized.path("regions").get(0).path("fieldRelations").get(1);
        assertThat(warping.path("experimentField").path("domain").asText()).isEqualTo("TEST");
        assertThat(warping.path("experimentItemLabel").asText()).isEqualTo("初始翘曲");
        assertThat(warping.path("labelPathSegments")).extracting(node -> node.asText())
                .containsExactly("性能测试", "初始翘曲");
        assertThat(normalized.path("qualityIssues"))
                .extracting(node -> node.path("issueType").asText())
                .contains("INVALID_FIELD_RELATION");
    }

    @Test
    void keepsAmbiguousFieldForReviewEvenWhenTheModelIsConfident() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":4,
                  "experimentTemplateSuggestion":{"templateUsage":"EXPERIMENT_DATA","recordMode":"BY_IDENTITY","confidence":0.95,"alternatives":[]},
                  "regions":[{
                    "regionId":"region-1","businessName":"结果",
                    "fieldRelations":[
                      {"candidateRef":"status","fieldName":"状态","businessName":"状态","valueType":"string","unit":"","groupName":"结果",
                       "experimentFieldSuggestion":{"domain":"TEST","field":"JUDGEMENT","itemLabel":"状态","confidence":0.96,"alternatives":[{"domain":"CONCLUSION","field":"RESULT_STATUS"}]}}
                    ],"matrixRelations":[],"qualityIssues":[]
                  }],"qualityIssues":[]
                }
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"region-1","type":"FORM_REGION","canonicalStatus":"CONFIRMED","structureStatus":"CONFIRMED",
                  "fieldCandidates":[{"candidateRef":"status","labelRange":"A2","valueRange":"B2","labelPathSegments":["结果","状态"]}],
                  "matrixCandidates":[]}]}
                """);

        var normalized = new RegionSemanticBatchProtocol(objectMapper).validate(response, context);
        var field = normalized.path("regions").get(0).path("fieldRelations").get(0);

        assertThat(field.path("experimentSemanticStatus").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(field.path("autoAccept").asBoolean()).isFalse();
        assertThat(field.path("experimentSemanticAlternatives")).hasSize(2);
    }

    @Test
    void compilesFormulaMatrixOnlyFromDeterministicCandidateGeometry() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":4,
                  "experimentTemplateSuggestion":{"templateUsage":"EXPERIMENT_DATA","recordMode":"BY_IDENTITY","confidence":0.98,"alternatives":[]},
                  "regions":[{
                    "regionId":"region-1","businessName":"实验配方","fieldRelations":[],
                    "matrixRelations":[{"candidateRef":"matrix-1","matrixType":"FORMULA_MATRIX","confidence":0.97,"alternatives":[]}],
                    "qualityIssues":[]
                  }],"qualityIssues":[]
                }
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"region-1","type":"COLUMN_TABLE","canonicalStatus":"CONFIRMED","structureStatus":"CONFIRMED",
                  "fieldCandidates":[],"matrixCandidates":[{
                    "candidateRef":"matrix-1","componentId":"region-1","sheetId":"sheet-1","groupName":"实验配方",
                    "recordAxis":"COLUMN","itemAxis":"ROW","labelRange":"C17:C25","valueRange":"D17:I25",
                    "identityLabelRange":"C16","identityValueRange":"D16:I16","totalRange":"C26:I26",
                    "labelSemantic":"MATERIAL_NAME","valueSemantic":"RATIO","geometryConfidence":0.98
                  }]}]}
                """);

        var normalized = new RegionSemanticBatchProtocol(objectMapper).validate(response, context);
        var matrix = normalized.path("regions").get(0).path("matrixRelations").get(0);

        assertThat(matrix.path("labelRange").asText()).isEqualTo("C17:C25");
        assertThat(matrix.path("valueRange").asText()).isEqualTo("D17:I25");
        assertThat(matrix.path("totalRange").asText()).isEqualTo("C26:I26");
        assertThat(matrix.path("experimentSemanticStatus").asText()).isEqualTo("AUTO_CONFIRMED");
    }

    @Test
    void rejectsModelAuthoredGeometryProperties() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":4,
                  "experimentTemplateSuggestion":{"templateUsage":"GENERAL_DATA","recordMode":"UNKNOWN","confidence":0.5,"alternatives":[]},
                  "regions":[{
                    "regionId":"region-1","businessName":"测试表",
                    "fieldRelations":[{"candidateRef":"field-1","fieldName":"名称","businessName":"名称","valueType":"string","unit":"","groupName":"测试","labelRange":"Z99",
                      "experimentFieldSuggestion":{"domain":"TEST","field":"VALUE","itemLabel":"名称","confidence":0.96,"alternatives":[]}}],
                    "matrixRelations":[],"qualityIssues":[]
                  }],"qualityIssues":[]
                }
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"region-1","type":"FORM_REGION","canonicalStatus":"CONFIRMED","structureStatus":"CONFIRMED",
                  "fieldCandidates":[{"candidateRef":"field-1","labelRange":"A1","valueRange":"B1"}],"matrixCandidates":[]}]}
                """);

        var normalized = new RegionSemanticBatchProtocol(objectMapper).validate(response, context);

        assertThat(normalized.path("regions").get(0).path("fieldRelations")).hasSize(1);
        var recovered = normalized.path("regions").get(0).path("fieldRelations").get(0);
        assertThat(recovered.path("labelRange").asText()).isEqualTo("A1");
        assertThat(recovered.path("valueRange").asText()).isEqualTo("B1");
        assertThat(recovered.has("targetPath")).isFalse();
        assertThat(normalized.path("qualityIssues"))
                .extracting(node -> node.path("issueType").asText())
                .contains("INVALID_FIELD_RELATION");
    }

    @Test
    void recoversGroupSemanticsWhenModelSuggestionUsesUnsupportedRole() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":4,
                  "experimentTemplateSuggestion":{"templateUsage":"EXPERIMENT_DATA","recordMode":"BY_IDENTITY","confidence":0.92,"alternatives":[]},
                  "regions":[{
                    "regionId":"region-1","businessName":"应用测试报告",
                    "fieldRelations":[
                      {"candidateRef":"experiment-no","fieldName":"实验编号","businessName":"实验编号","valueType":"string","unit":"","groupName":"实验配方",
                       "experimentFieldSuggestion":{"domain":"BASIC","field":"SOURCE_IDENTITY","itemLabel":"实验编号","confidence":0.99,"alternatives":[]}},
                      {"candidateRef":"solid-content","fieldName":"实测固含","businessName":"实测固含","valueType":"string","unit":"","groupName":"树脂物性",
                       "experimentFieldSuggestion":{"domain":"PROCESS","field":"MATERIAL_PROPERTY","itemLabel":"实测固含","confidence":0.95,"alternatives":[]}}
                    ],"matrixRelations":[],"qualityIssues":[]
                  }],"qualityIssues":[]
                }
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{
                  "regionId":"region-1","type":"COLUMN_TABLE","canonicalStatus":"CONFIRMED","structureStatus":"CONFIRMED",
                  "fieldCandidates":[
                    {"candidateRef":"experiment-no","fieldName":"实验编号","labelRange":"C16","valueRange":"D16:I16","labelPathSegments":["实验配方","实验编号"]},
                    {"candidateRef":"solid-content","fieldName":"实测固含","labelRange":"C11","valueRange":"D11:I11","labelPathSegments":["树脂物性","实测固含（120度烘烤1小时）"]}
                  ],"matrixCandidates":[]
                }]}
                """);

        var normalized = new RegionSemanticBatchProtocol(objectMapper).validate(response, context);

        assertThat(normalized.path("regions").get(0).path("fieldRelations")).hasSize(2);
        var solidContent = normalized.path("regions").get(0).path("fieldRelations").get(1);
        assertThat(solidContent.path("experimentField").path("domain").asText()).isEqualTo("TEST");
        assertThat(solidContent.path("experimentField").path("field").asText()).isEqualTo("VALUE");
        assertThat(normalized.path("experimentTemplateSuggestion").path("templateUsage").asText())
                .isEqualTo("EXPERIMENT_DATA");
        assertThat(normalized.path("experimentTemplateSuggestion").path("recordMode").asText())
                .isEqualTo("SINGLE_FILE");
    }

    @Test
    void deterministicExperimentEvidenceWinsOverModelGeneralDataGuess() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":4,
                  "experimentTemplateSuggestion":{"templateUsage":"GENERAL_DATA","recordMode":"BY_IDENTITY","confidence":0.96,"alternatives":["EXPERIMENT_DATA"]},
                  "regions":[{
                    "regionId":"region-1","businessName":"应用测试报告",
                    "fieldRelations":[
                      {"candidateRef":"experiment-no","fieldName":"实验编号","businessName":"实验编号","valueType":"string","unit":"","groupName":"实验配方",
                       "experimentFieldSuggestion":{"domain":"BASIC","field":"SOURCE_IDENTITY","itemLabel":"实验编号","confidence":0.99,"alternatives":[]}},
                      {"candidateRef":"warping","fieldName":"初始翘曲","businessName":"初始翘曲","valueType":"string","unit":"cm","groupName":"性能测试",
                       "experimentFieldSuggestion":{"domain":"TEST","field":"VALUE","itemLabel":"初始翘曲","confidence":0.96,"alternatives":[]}}
                    ],"matrixRelations":[],"qualityIssues":[]
                  }],"qualityIssues":[]
                }
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{
                  "regionId":"region-1","type":"COLUMN_TABLE","canonicalStatus":"CONFIRMED","structureStatus":"CONFIRMED",
                  "fieldCandidates":[
                    {"candidateRef":"experiment-no","fieldName":"实验编号","labelRange":"C16","valueRange":"D16:I16","labelPathSegments":["实验配方","实验编号"]},
                    {"candidateRef":"warping","fieldName":"初始翘曲","labelRange":"C30","valueRange":"D30:I30","labelPathSegments":["性能测试","初始翘曲"]}
                  ],"matrixCandidates":[]
                }]}
                """);

        var normalized = new RegionSemanticBatchProtocol(objectMapper).validate(response, context);

        assertThat(normalized.path("experimentTemplateSuggestion").path("templateUsage").asText())
                .isEqualTo("EXPERIMENT_DATA");
        assertThat(normalized.path("experimentTemplateSuggestion").path("experimentSemanticStatus").asText())
                .isEqualTo("AUTO_CONFIRMED");
        assertThat(normalized.path("experimentTemplateSuggestion").path("autoAccept").asBoolean()).isTrue();
    }
}
