package com.jsd.aird.spc.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SpectrumResultPresenterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpectrumResultPresenter presenter = new SpectrumResultPresenter(objectMapper);

    @Test
    void preservesTheModelAuthoredMarkdownReportWithoutRestructuringIt() {
        var report = "## 任意主题\n\n先给结论。\n\n| 名称 | 数值 |\n|---|---:|\n| A | 1.2 |";
        var internal = objectMapper.createObjectNode()
                .put("schemaVersion", 4)
                .put("analysisStatus", "SUCCEEDED")
                .put("answerMarkdown", report);

        var result = presenter.present(internal, "任意问题");

        assertThat(result.path("answerMarkdown").asText()).isEqualTo(report);
        assertThat(result.has("presentation")).isFalse();
        assertThat(result.has("claims")).isFalse();
    }

    @Test
    void mapsValidatedClaimsToFiveUserSectionsWithoutInternalFields() throws Exception {
        var internal = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"schemaVersion":3,"analysisStatus":"PARTIAL","confidence":"MEDIUM","claims":[
                  {"claimId":"C1","claimType":"OBSERVATION","effectiveStatement":"报告显示280 nm处吸光度约为3.271。",
                   "effectiveLevel":"L1","status":"VALID","evidenceRefs":[{"sourceType":"OBSERVED_DATA","evidenceId":"chart:a:page:1"}]},
                  {"claimId":"C2","claimType":"METHOD_ASSESSMENT","effectiveStatement":"当前吸光度水平需要关注定量可靠性。",
                   "effectiveLevel":"L2","status":"VALID","validationReasonCodes":[],"knowledgeRuleIds":["UV_METHOD_001"]},
                  {"claimId":"C3","claimType":"CONCENTRATION_CONFIRMATION","effectiveStatement":"实际浓度为50 ppm。",
                   "effectiveLevel":"","status":"REJECTED_TO_LIMITATION","validationReasonCodes":["MISSING_STRONG_EVIDENCE"]}
                ],"limitations":[{"statement":"当前不能确认实际浓度是否为50 ppm。",
                  "missingEvidence":[{"label":"配液记录"}],"nextSteps":["核对配液记录并稀释复测。"]}],
                  "assessment":{"type":"CONCENTRATION_CHECK","nominalConcentration":{"value":"50 ppm"},
                  "actualConcentrationAssessment":"UNVERIFIED","measurementSuitability":"CAUTION"}}
                """);

        var result = presenter.present(internal, "这个50 ppm配得对不对");

        assertThat(result.path("answer").path("confirmed").toString()).contains("3.271");
        assertThat(result.path("answer").path("summary").asText()).contains("3.271");
        assertThat(result.path("answer").path("reasonableInference").toString()).contains("定量可靠性");
        assertThat(result.path("answer").path("notConfirmed").toString()).contains("不能确认实际浓度");
        assertThat(result.path("answer").path("missingEvidence").toString()).contains("配液记录");
        assertThat(result.path("assessment").toString()).contains("标称浓度", "文档标记", "需要关注");
        assertThat(result.toString()).doesNotContain("claimId", "effectiveLevel", "validationReasonCodes",
                "UV_METHOD_001", "chart:a:page:1", "L1", "L2");
    }

    @Test
    void framesReportedMetadataAsDocumentLabelInsteadOfMeasuredFact() throws Exception {
        var internal = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"schemaVersion":3,"analysisStatus":"SUCCEEDED","confidence":"HIGH","claims":[{
                  "claimId":"C1","claimType":"REPORTED_METADATA","effectiveStatement":"浓度为50 ppm。",
                  "effectiveLevel":"L1","status":"VALID","evidenceRefs":[{"sourceType":"DOCUMENT_METADATA"}]
                }],"limitations":[],"assessment":{"type":"CONCENTRATION_CHECK",
                  "nominalConcentration":{"value":"50 ppm"}}}
                """);

        var result = presenter.present(internal, "浓度是多少");

        assertThat(result.path("answer").path("metadata").path(0).asText()).as(result.toPrettyString())
                .isEqualTo("文件或报告将样品标记为50 ppm。");
        assertThat(result.toString()).doesNotContain("浓度为50 ppm。\"");
    }

    @Test
    void usesValidatedCurveComparisonAsSummaryWhenAssessmentFocusOnlyDescribesScope() throws Exception {
        var internal = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"schemaVersion":3,"analysisStatus":"SUCCEEDED","confidence":"MEDIUM","claims":[{
                  "claimId":"C1","claimType":"OBSERVATION","effectiveStatement":"新旧曲线整体轮廓接近，旧样主峰略高。",
                  "effectiveLevel":"L1","status":"VALID","structuredFacts":[{"factType":"CURVE_COMPARISON"}],
                  "evidenceRefs":[{"sourceType":"OBSERVED_DATA","evidenceId":"chart:a:page:1"}]
                }],"limitations":[],"assessment":{"type":"SAMPLE_COMPARISON",
                "focus":"概览新旧曲线的主要特征和差异。","supportingClaimIds":["C1"]}}
                """);

        var result = presenter.present(internal, "比较新旧曲线");

        assertThat(result.path("answer").path("summary").asText())
                .isEqualTo("新旧曲线整体轮廓接近，旧样主峰略高。");
    }

    @Test
    void stillPresentsHistoricalV2Results() {
        var legacy = objectMapper.createObjectNode();
        legacy.put("answerMarkdown", "两条曲线趋势一致。差异需要复测。更多内容不应进入结论。");
        legacy.putArray("observations").add("主峰位置基本一致。");

        var result = presenter.present(legacy, "总结图谱");

        assertThat(result.path("presentation").path("version").asInt()).isEqualTo(2);
        assertThat(result.path("presentation").path("conclusion").asText()).contains("两条曲线趋势一致");
    }

    @Test
    void replacesInternalFailureDetailsWithPublicMessage() throws Exception {
        var internal = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"schemaVersion":3,"analysisStatus":"FAILED",
                 "errorMessage":"connection reset by upstream 10.0.0.8:443",
                 "claims":[],"limitations":[],"assessment":{"type":"OVERVIEW"},"confidence":"LOW"}
                """);

        var result = presenter.present(internal, "分析图谱");

        assertThat(result.path("errorMessage").asText()).isEqualTo("图谱分析未完成，请稍后重试。");
        assertThat(result.toString()).doesNotContain("connection reset", "10.0.0.8");
    }
}
