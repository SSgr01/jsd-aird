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
        assertThat(prompt).doesNotContain("professionalReviewOpinion");
    }
}
