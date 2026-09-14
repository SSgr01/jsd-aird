package com.jsd.aird.spc.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SpectrumResultPresenterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpectrumResultPresenter presenter = new SpectrumResultPresenter(objectMapper);

    @Test
    void buildsCompactAttributionAnswerWithoutChangingStructuredAnalysis() {
        var result = objectMapper.createObjectNode();
        result.put("answerMarkdown", "图片展示了新旧批次曲线。两条曲线趋势一致。当前证据不足以归因。");
        result.put("evidenceSufficiency", "INSUFFICIENT_FOR_MAPPING");
        result.putArray("comparisons")
                .add("新旧批次峰位基本一致，主要差异位于约 295-300 nm。")
                .add("约 295-300 nm 处吸光度相差约 0.2 Abs。")
                .add("新旧批次峰位基本一致，主要差异位于约 295-300 nm。");
        result.putArray("observations").add("320 nm 后两条曲线均接近基线。");
        result.putArray("suggestedValidationExperiments")
                .add("在相同测试条件下进行平行复测。")
                .add("使用标准品进行对照测试。")
                .add("通过 HPLC 或 LC-MS 比较杂质谱。")
                .add("核对原始称量记录。");
        result.putArray("evidence").add("chart-a/page-1");
        var original = result.deepCopy();

        var presented = presenter.present(result, "当前图片是否足以支持明确归因？请给出建议验证实验。");

        assertThat(presented.path("presentation").path("version").asInt()).isEqualTo(2);
        assertThat(presented.path("presentation").has("reasoningSummary")).isFalse();
        assertThat(presented.path("presentation").path("primaryIntent").asText()).isEqualTo("ATTRIBUTION");
        assertThat(presented.path("presentation").path("conclusion").asText()).contains("不足以支持明确归因");
        assertThat(presented.path("presentation").path("keyFindings")).hasSize(3);
        assertThat(presented.path("presentation").path("validationSteps")).hasSize(3);
        var withoutPresentation = presented.deepCopy();
        withoutPresentation.remove("presentation");
        assertThat(withoutPresentation).isEqualTo(original);
    }

    @Test
    void acceptsObjectShapedValidationExperimentsFromResponsesModel() {
        var result = objectMapper.createObjectNode();
        result.put("answerMarkdown", "当前差异需要复测确认。");
        result.putArray("suggestedValidationExperiments").addObject()
                .put("experiment", "在同一条件下进行三次平行复测。")
                .put("purpose", "确认强度差是否可重复。");

        var presented = presenter.present(result, "建议做什么验证实验");

        assertThat(presented.path("presentation").path("validationSteps").path(0).asText())
                .isEqualTo("在同一条件下进行三次平行复测。");
    }

    @Test
    void hidesValidationStepsWhenTheQuestionDidNotAskForThem() {
        var result = objectMapper.createObjectNode();
        result.put("answerMarkdown", "两个批次的主峰位置一致，峰高略有差异。");
        result.putArray("comparisons").add("差异集中在主峰区域。");
        result.putArray("suggestedValidationExperiments").add("在相同条件下进行平行复测。");

        var presented = presenter.present(result, "请比较两个批次的差异");

        assertThat(presented.path("presentation").path("primaryIntent").asText()).isEqualTo("COMPARISON");
        assertThat(presented.path("presentation").path("keyFindings")).hasSize(1);
        assertThat(presented.path("presentation").path("validationSteps")).isEmpty();
        assertThat(presented.path("suggestedValidationExperiments")).hasSize(1);
    }

    @Test
    void detectsAllSupportedAnswerIntents() {
        assertIntent("请比较两个批次的差异", "COMPARISON");
        assertIntent("请解释 1720 cm-1 峰位", "FEATURE_INTERPRETATION");
        assertIntent("建议做什么验证实验", "VALIDATION");
        assertIntent("总结这张图谱", "OVERVIEW");
        assertIntent("请简要概览主要峰形和保留时间", "OVERVIEW");
    }

    @Test
    void removesFindingsAlreadyContainedInTheConclusion() {
        var result = objectMapper.createObjectNode();
        result.put("answerMarkdown", "两条曲线峰位基本一致，但峰高略有差异。");
        result.putArray("comparisons")
                .add("两条曲线峰位基本一致，但峰高略有差异。")
                .add("差异主要位于主峰区域。");

        var presented = presenter.present(result, "请比较两条曲线");

        assertThat(presented.path("presentation").path("keyFindings")).hasSize(1);
        assertThat(presented.path("presentation").path("keyFindings").get(0).asText())
                .isEqualTo("差异主要位于主峰区域。");
    }

    private void assertIntent(String question, String expected) {
        var result = objectMapper.createObjectNode();
        result.put("answerMarkdown", "已完成分析。");
        var presented = presenter.present(result, question);
        assertThat(presented.path("presentation").path("primaryIntent").asText()).isEqualTo(expected);
    }
}
