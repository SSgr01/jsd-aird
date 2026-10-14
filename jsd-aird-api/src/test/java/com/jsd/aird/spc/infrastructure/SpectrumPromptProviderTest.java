package com.jsd.aird.spc.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.spc.application.port.SpectrumPromptPort;
import com.jsd.aird.spc.application.port.SpectrumPromptPort.SpectrumAnalysisPromptContext;
import org.junit.jupiter.api.Test;

class SpectrumPromptProviderTest {

    private final SpectrumPromptProvider provider = new SpectrumPromptProvider(new ObjectMapper());

    @Test
    void buildsAnAdaptiveNaturalLanguagePromptWithoutAQuestionTypeSchema() {
        assertThat(SpectrumPromptPort.currentVersion()).isEqualTo("spectrum-chat-v4-natural-language");
        var prompt = provider.build(new SpectrumAnalysisPromptContext(
                "这个50 ppm配得对不对", "图谱ID=sample-a", "sample-a -> [1]",
                "AUTO", "CONCENTRATION_CHECK", "HIGH_CONFIDENCE_RULE",
                "CONCENTRATION_VALUE_AND_VERIFICATION_QUESTION", List.of("UV"), List.of("关注吸光度"),
                Map.of("chart:sample-a:page:1", "OBSERVED_DATA"), List.of(), List.of(), List.of("样品A")));

        assertThat(prompt).contains("用户问题可能是任意类型", "不要套用固定问题分类或固定报告模板",
                "直接回答用户的问题", "需要时使用 Markdown 标题、表格或列表",
                "这个50 ppm配得对不对", "图谱ID=sample-a", "UV：");
        assertThat(prompt).doesNotContain("当前主意图", "Claim 等级", "输出 JSON", "assessment.focus",
                "supportingClaimIds", "schemaVersion", "requiredObservedMetrics");
    }

    @Test
    void keepsAnalysisGuidanceFlexibleForAOneImageMultiCurveQuestion() {
        var prompt = provider.build(new SpectrumAnalysisPromptContext(
                "分析这张图里的新旧曲线异同", "图谱ID=sample-a", "sample-a -> [1]",
                "AUTO", "SAMPLE_COMPARISON", "HIGH_CONFIDENCE_RULE",
                "EXPLICIT_COMPARISON_QUESTION", List.of("UV"), List.of(),
                Map.of("chart:sample-a:page:1", "OBSERVED_DATA"), List.of(), List.of(), List.of()));

        assertThat(prompt).contains("用户问题可能是任意类型", "单张图可能包含多条曲线",
                "按图像分辨率给出合理范围", "用户问题：", "分析这张图里的新旧曲线异同");
        assertThat(prompt).doesNotContain("SAMPLE_COMPARISON", "4～6条", "每项最多", "claims 为数组",
                "主要峰位/峰高、谷值与基线、共同点、主要差异");
    }

    @Test
    void includesRecentConversationAsContextWithoutTreatingItAsCurrentGraphEvidence() {
        var context = new SpectrumAnalysisPromptContext(
                "第二个峰差多少？", "当前图谱=USP-096", "USP-096 -> [1]",
                "AUTO", "SAMPLE_COMPARISON", "AI_CHAT", "FOLLOW_UP",
                List.of("UV"), List.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(
                        new SpectrumPromptPort.ConversationMessage("USER", "请比较新旧样品的主峰"),
                        new SpectrumPromptPort.ConversationMessage("ASSISTANT", "主峰约在 294 nm")));

        var prompt = provider.build(context);

        assertThat(prompt).contains("【本会话近期问答】", "用户：请比较新旧样品的主峰",
                "助手：主峰约在 294 nm", "本次分析结论仍须依据当前问题和本次图谱证据",
                "用户问题：\n第二个峰差多少？");
    }
}
