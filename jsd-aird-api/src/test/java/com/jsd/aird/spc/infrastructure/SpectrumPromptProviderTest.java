package com.jsd.aird.spc.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.spc.application.port.SpectrumPromptPort.SpectrumAnalysisPromptContext;
import org.junit.jupiter.api.Test;

class SpectrumPromptProviderTest {

    private final SpectrumPromptProvider provider = new SpectrumPromptProvider(new ObjectMapper());

    @Test
    void promptUsesServerFactsAndAllowsEvidenceInsufficiencyToEndTheAnswer() {
        var prompt = provider.build(new SpectrumAnalysisPromptContext(
                "哪些峰可能对应参考？", "图谱ID=sample-a", "sample-a -> [1]",
                "COMPETITOR_DECOMPOSITION", List.of("IR"), false, List.of(), List.of(),
                List.of("sample-a/page-1"), List.of("UA-338")));

        assertThat(prompt).contains("必须返回空 peakMappings");
        assertThat(prompt).contains("是否存在单峰参考图谱：false");
        assertThat(prompt).contains("证据不足是合法的结束状态");
        assertThat(prompt).contains("answerMarkdown 必须直接回答用户问题");
        assertThat(prompt).contains("最多 2 句话");
        assertThat(prompt).contains("suggestedValidationExperiments 最多 3 条");
        assertThat(prompt).contains("只有用户明确询问验证、实验、建议、复测或确认方法时");
        assertThat(prompt).contains("JSON 的第一个字段必须是 answerMarkdown");
        assertThat(prompt).contains("所有面向用户的自然语言内容必须使用简体中文");
        assertThat(prompt).contains("confidence: \"LOW\" | \"MEDIUM\" | \"HIGH\"");
        assertThat(prompt).contains("suggestedValidationExperiments: {experiment:string,purpose:string,evidenceIds:string[]}[]");
        assertThat(prompt).doesNotContain("professionalReviewOpinion");
    }
}
