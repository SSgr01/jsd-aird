package com.jsd.aird.spc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.spc.application.port.SpectrumPromptPort.SpectrumAnalysisPromptContext;
import org.junit.jupiter.api.Test;

class SpectrumResultValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpectrumResultValidator validator = new SpectrumResultValidator(objectMapper);

    @Test
    void acceptsObservedAbsorbanceAndApplicableControlledKnowledgeRule() throws Exception {
        var result = uvResult(3.271);

        var validated = validator.validate(result, context("AUTO", "CONCENTRATION_CHECK",
                "HIGH_CONFIDENCE_RULE", List.of("UV")));

        var methodClaim = validated.result().path("claims").path(1);
        assertThat(methodClaim.path("status").asText()).isEqualTo("VALID");
        assertThat(methodClaim.path("validationAction").asText()).isEqualTo("ACCEPT");
        assertThat(methodClaim.path("originalLevel").asText()).isEqualTo("L2");
        assertThat(methodClaim.path("effectiveLevel").asText()).isEqualTo("L2");
        assertThat(methodClaim.path("effectiveRequiredEvidenceTypes").toString())
                .contains("DOMAIN_KNOWLEDGE_OR_COMPANY_SOP");
    }

    @Test
    void rejectsKnowledgeRuleWhenObservedMetricDoesNotMeetApplicability() throws Exception {
        var validated = validator.validate(uvResult(0.2), context("AUTO", "CONCENTRATION_CHECK",
                "HIGH_CONFIDENCE_RULE", List.of("UV")));

        var methodClaim = validated.result().path("claims").path(1);
        assertThat(methodClaim.path("status").asText()).isEqualTo("REJECTED_TO_LIMITATION");
        assertThat(methodClaim.path("validationReasonCodes").toString())
                .contains("KNOWLEDGE_RULE_NOT_APPLICABLE");
    }

    @Test
    void keepsUvMeasurementCautionApplicableDuringCurveComparison() throws Exception {
        var validated = validator.validate(uvResult(2.4), context("AUTO", "SAMPLE_COMPARISON",
                "HIGH_CONFIDENCE_RULE", List.of("UV")));

        var methodClaim = validated.result().path("claims").path(1);
        assertThat(methodClaim.path("status").asText()).isEqualTo("VALID");
        assertThat(methodClaim.path("effectiveLevel").asText()).isEqualTo("L2");
    }

    @Test
    void keepsUvMeasurementCautionWhenPeakHeightIsEncodedAsUvPeak() throws Exception {
        var result = uvResult(2.4);
        var peakFact = (ObjectNode) result.path("claims").path(0).path("structuredFacts").path(0);
        peakFact.put("factType", "UV_PEAK");

        var validated = validator.validate(result, context("AUTO", "SAMPLE_COMPARISON",
                "HIGH_CONFIDENCE_RULE", List.of("UV")));

        var methodClaim = validated.result().path("claims").path(1);
        assertThat(methodClaim.path("status").asText()).isEqualTo("VALID");
        assertThat(methodClaim.path("effectiveLevel").asText()).isEqualTo("L2");
    }

    @Test
    void keepsUvMeasurementCautionWhenPeakHeightIsAnEstimatedRangeAboveThreshold() throws Exception {
        var result = uvResult(2.4);
        var absorbance = (ObjectNode) result.path("claims").path(0).path("structuredFacts")
                .path(0).path("absorbance");
        absorbance.remove("value");
        absorbance.put("range", "约2.4–2.5");

        var validated = validator.validate(result, context("AUTO", "SAMPLE_COMPARISON",
                "HIGH_CONFIDENCE_RULE", List.of("UV")));

        var methodClaim = validated.result().path("claims").path(1);
        assertThat(methodClaim.path("status").asText()).isEqualTo("VALID");
        assertThat(methodClaim.path("effectiveLevel").asText()).isEqualTo("L2");
    }

    @Test
    void doesNotApplyUvHighAbsorbanceCautionWhenEstimatedRangeCrossesThreshold() throws Exception {
        var result = uvResult(2.4);
        var absorbance = (ObjectNode) result.path("claims").path(0).path("structuredFacts")
                .path(0).path("absorbance");
        absorbance.remove("value");
        absorbance.put("range", "约1.8–2.4");

        var validated = validator.validate(result, context("AUTO", "SAMPLE_COMPARISON",
                "HIGH_CONFIDENCE_RULE", List.of("UV")));

        var methodClaim = validated.result().path("claims").path(1);
        assertThat(methodClaim.path("status").asText()).isEqualTo("REJECTED_TO_LIMITATION");
        assertThat(methodClaim.path("validationReasonCodes").toString())
                .contains("KNOWLEDGE_RULE_NOT_APPLICABLE");
    }

    @Test
    void keepsSingleStringNextStepAndPresentsItToTheUser() throws Exception {
        var source = (ObjectNode) objectMapper.readTree("""
                {"claims":[{"claimId":"C1","claimType":"OBSERVATION","statement":"新旧曲线谱形接近。",
                  "level":"L1","confidence":"MEDIUM","dependsOnClaimIds":[],
                  "evidenceRefs":[{"sourceType":"OBSERVED_DATA","evidenceId":"chart:a:page:1"}]}],
                  "limitations":[{"type":"缺少原始数据","statement":"图片不能支持精确读数。",
                    "relatedClaimIds":["C1"],"missingEvidence":["仪器原始数据"],
                    "nextSteps":"导出原始波长-吸光度数据并比较峰位和峰高。"}],
                  "assessment":{"type":"SAMPLE_COMPARISON","focus":"新旧曲线整体接近，精确峰高仍需复核。",
                    "supportingClaimIds":["C1"]},"confidence":"MEDIUM"}
                """);

        var validated = validator.validate(source, context("AUTO", "SAMPLE_COMPARISON",
                "EXPLICIT_COMPARISON_QUESTION", List.of("UV")));
        var publicResult = new SpectrumResultPresenter(objectMapper).present(validated.result(), "比较新旧曲线");

        assertThat(validated.result().path("limitations").path(0).path("nextSteps").path(0).asText())
                .contains("导出原始波长-吸光度数据");
        assertThat(publicResult.path("answer").path("nextStep").path(0).asText())
                .contains("导出原始波长-吸光度数据");
        assertThat(publicResult.path("answer").path("summary").asText())
                .contains("新旧曲线整体接近");
    }

    @Test
    void rejectsUnsupportedConcentrationConfirmationInsteadOfTurningItIntoPossiblyFiftyPpm() throws Exception {
        var source = (ObjectNode) objectMapper.readTree("""
                {"claims":[{
                  "claimId":"C1","claimType":"CONCENTRATION_CONFIRMATION",
                  "statement":"实测浓度就是50 ppm。","level":"L4","confidence":"HIGH",
                  "dependsOnClaimIds":[],"evidenceRefs":[],"knowledgeRuleIds":[],
                  "limitationStatement":"当前不能确认实际浓度是否为50 ppm。"
                }],"limitations":[],"assessment":{"type":"CONCENTRATION_CHECK",
                  "nominalConcentration":{"value":"50 ppm","sourceType":"DOCUMENT_METADATA"}},
                  "confidence":"HIGH"}
                """);

        var validated = validator.validate(source, context("AUTO", "CONCENTRATION_CHECK",
                "HIGH_CONFIDENCE_RULE", List.of("UV")));
        var claim = validated.result().path("claims").path(0);

        assertThat(claim.path("originalLevel").asText()).isEqualTo("L4");
        assertThat(claim.path("effectiveLevel").asText()).isEmpty();
        assertThat(claim.path("validationAction").asText()).isEqualTo("REJECT_TO_LIMITATION");
        assertThat(claim.path("validationReasonCodes").toString())
                .contains("MISSING_STRONG_EVIDENCE", "MISSING_CALIBRATION_CURVE");
        assertThat(validated.result().path("limitations").toString())
                .contains("当前不能确认实际浓度是否为50 ppm");
    }

    @Test
    void recordsCandidateStatementWhenAnOverstatedCauseClaimIsDemoted() throws Exception {
        var source = (ObjectNode) objectMapper.readTree("""
                {"claims":[
                  {"claimId":"C1","claimType":"OBSERVATION","statement":"报告显示吸光度为3.271。",
                   "level":"L1","confidence":"HIGH","dependsOnClaimIds":[],"structuredFacts":[],
                   "evidenceRefs":[{"sourceType":"OBSERVED_DATA","evidenceId":"chart:a:page:1"}]},
                  {"claimId":"C2","claimType":"CAUSE_HYPOTHESIS","statement":"高吸光度一定由浓度过高导致。",
                   "candidateStatement":"样品浓度较高是候选原因之一。","level":"L4","confidence":"HIGH",
                   "dependsOnClaimIds":["C1"],"uncertainty":"还可能与物质本身吸收有关。","evidenceRefs":[]}
                ],"limitations":[],"assessment":{"type":"ANOMALY_DIAGNOSIS"},"confidence":"MEDIUM"}
                """);

        var validated = validator.validate(source, context("AUTO", "ANOMALY_DIAGNOSIS",
                "HIGH_CONFIDENCE_RULE", List.of("UV")));
        var claim = validated.result().path("claims").path(1);

        assertThat(claim.path("status").asText()).isEqualTo("DEMOTED");
        assertThat(claim.path("originalLevel").asText()).isEqualTo("L4");
        assertThat(claim.path("effectiveLevel").asText()).isEqualTo("L3");
        assertThat(claim.path("effectiveStatement").asText()).contains("候选原因之一");
        assertThat(claim.path("validationReasonCodes").toString()).contains("CANDIDATE_STATEMENT_USED");
    }

    @Test
    void keepsExplicitPurityIntentAndReturnsUndeterminedForMechanicalEvidence() {
        var source = objectMapper.createObjectNode();
        source.putArray("claims");
        source.putArray("limitations");
        source.putObject("assessment").put("type", "PURITY_CHECK");
        source.put("confidence", "LOW");

        var validated = validator.validate(source, context("PURITY_CHECK", "PURITY_CHECK",
                "USER_SELECTED", List.of("MECHANICAL")));

        assertThat(validated.result().path("analysisIntent").asText()).isEqualTo("PURITY_CHECK");
        assertThat(validated.result().path("routing").path("resolution").asText()).isEqualTo("USER_SELECTED");
        assertThat(validated.result().path("assessment").path("purityAssessment").asText())
                .isEqualTo("UNDETERMINED");
        assertThat(validated.result().path("limitations").toString()).contains("当前图谱类型不适合");
    }

    @Test
    void dropsAssessmentFocusWithoutAValidSupportingClaim() throws Exception {
        var source = (ObjectNode) objectMapper.readTree("""
                {"claims":[{"claimId":"C1","claimType":"OBSERVATION","statement":"图上可见一个峰。",
                  "level":"L1","confidence":"HIGH","dependsOnClaimIds":[],
                  "evidenceRefs":[{"sourceType":"OBSERVED_DATA","evidenceId":"chart:a:page:1"}]}],
                  "limitations":[],"assessment":{"type":"OVERVIEW","focus":"样品完全一致。",
                  "supportingClaimIds":[]},"confidence":"HIGH"}
                """);

        var validated = validator.validate(source, context("AUTO", "OVERVIEW",
                "FALLBACK_OVERVIEW", List.of("UV")));

        assertThat(validated.result().path("assessment").has("focus")).isFalse();
        assertThat(validated.result().path("analysisStatus").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void modelInferenceIsNotAcceptedAsEvidence() throws Exception {
        var source = (ObjectNode) objectMapper.readTree("""
                {"claims":[{"claimId":"C1","claimType":"OBSERVATION","statement":"存在异常峰。",
                 "level":"L1","confidence":"HIGH","dependsOnClaimIds":[],
                 "evidenceRefs":[{"sourceType":"MODEL_INFERENCE","evidenceId":"self"}]}],
                 "limitations":[],"assessment":{"type":"OVERVIEW"},"confidence":"LOW"}
                """);

        var validated = validator.validate(source, context("AUTO", "OVERVIEW",
                "FALLBACK_OVERVIEW", List.of("UV")));
        var claim = validated.result().path("claims").path(0);
        assertThat(claim.path("evidenceRefs")).isEmpty();
        assertThat(claim.path("validationReasonCodes").toString()).contains("INVALID_EVIDENCE_REFERENCE");
        assertThat(claim.path("status").asText()).isEqualTo("REJECTED_TO_LIMITATION");
    }

    @Test
    void ignoresModelDeclaredEvidenceRequirementsAndComputesEffectiveRequirements() throws Exception {
        var source = (ObjectNode) objectMapper.readTree("""
                {"claims":[{
                  "claimId":"C1","claimType":"OBSERVATION","statement":"报告显示一个吸收峰。",
                  "level":"L1","confidence":"HIGH","dependsOnClaimIds":[],
                  "requiredEvidenceTypes":["MODEL_SAYS_ENOUGH"],
                  "effectiveRequiredEvidenceTypes":["MODEL_SAYS_ENOUGH"],
                  "evidenceRefs":[{"sourceType":"OBSERVED_DATA","evidenceId":"chart:a:page:1"}]
                }],"limitations":[],"assessment":{"type":"OVERVIEW"},"confidence":"HIGH"}
                """);

        var validated = validator.validate(source, context("AUTO", "OVERVIEW",
                "FALLBACK_OVERVIEW", List.of("UV")));
        var claim = validated.result().path("claims").path(0);

        assertThat(claim.path("status").asText()).isEqualTo("VALID");
        assertThat(claim.path("effectiveRequiredEvidenceTypes").toString())
                .isEqualTo("[\"OBSERVED_DATA_OR_DOCUMENT_METADATA\"]");
        assertThat(claim.toString()).doesNotContain("MODEL_SAYS_ENOUGH", "requiredEvidenceTypes");
    }

    @Test
    void removesNominalConcentrationWithoutVerifiedDocumentMetadataReference() throws Exception {
        var source = (ObjectNode) objectMapper.readTree("""
                {"claims":[],"limitations":[],"assessment":{"type":"CONCENTRATION_CHECK",
                  "nominalConcentration":{"value":"50 ppm","sourceType":"DOCUMENT_METADATA"},
                  "actualConcentrationAssessment":"VERIFIED","measurementSuitability":"SUITABLE"},
                  "confidence":"HIGH"}
                """);

        var validated = validator.validate(source, context("AUTO", "CONCENTRATION_CHECK",
                "HIGH_CONFIDENCE_RULE", List.of("UV")));
        var assessment = validated.result().path("assessment");

        assertThat(assessment.has("nominalConcentration")).isFalse();
        assertThat(assessment.path("actualConcentrationAssessment").asText()).isEqualTo("UNVERIFIED");
        assertThat(assessment.path("measurementSuitability").asText()).isEqualTo("UNDETERMINED");
    }

    private ObjectNode uvResult(double absorbance) throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {"claims":[
                  {"claimId":"C1","claimType":"OBSERVATION","statement":"报告显示280 nm处吸光度为%s。",
                   "level":"L1","confidence":"HIGH","dependsOnClaimIds":[],
                   "structuredFacts":[{"factType":"UV_ABSORBANCE_POINT","wavelength":{"value":280,"unit":"nm"},
                     "absorbance":{"value":%s,"unit":"AU"},"valueOrigin":"REPORTED"}],
                   "evidenceRefs":[{"sourceType":"OBSERVED_DATA","evidenceId":"chart:a:page:1"}]},
                  {"claimId":"C2","claimType":"METHOD_ASSESSMENT","statement":"当前吸光度需要关注定量可靠性。",
                   "level":"L2","confidence":"MEDIUM","dependsOnClaimIds":["C1"],
                   "uncertainty":"仍需结合具体方法判断。","knowledgeRuleIds":["UV_METHOD_001"],
                   "evidenceRefs":[{"sourceType":"DOMAIN_KNOWLEDGE","knowledgeRuleId":"UV_METHOD_001"}]}
                ],"limitations":[],"assessment":{"type":"CONCENTRATION_CHECK"},"confidence":"MEDIUM"}
                """.formatted(absorbance, absorbance));
    }

    private SpectrumAnalysisPromptContext context(String requested, String resolved, String resolution,
                                                  List<String> protocols) {
        return new SpectrumAnalysisPromptContext("问题", "", "", requested, resolved, resolution, "TEST",
                protocols, List.of(), Map.of(
                "chart:a:page:1", "OBSERVED_DATA",
                "chart:a:metadata:title", "DOCUMENT_METADATA"),
                List.of(), List.of(), List.of("样品A"));
    }
}
