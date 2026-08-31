package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsd.aird.kb.domain.OcrMode;
import org.junit.jupiter.api.Test;

class SystemParsingPolicyTest {

    @Test
    void appliesConfiguredPolicyOnlyToPdfFiles() {
        var policy = new SystemParsingPolicy("ON", true);

        assertThat(policy.forFile("scan.pdf", "application/octet-stream"))
                .isEqualTo(new SystemParsingPolicy.Policy(OcrMode.ON, true));
        assertThat(policy.forFile("notes.txt", "text/plain"))
                .isEqualTo(new SystemParsingPolicy.Policy(OcrMode.AUTO, false));
    }

    @Test
    void defaultsToAutoWithoutAgentFallback() {
        var policy = new SystemParsingPolicy("AUTO", false);

        assertThat(policy.defaultOcrMode()).isEqualTo(OcrMode.AUTO);
        assertThat(policy.agentFallbackEnabled()).isFalse();
    }

    @Test
    void rejectsInvalidOcrModeAtConstructionTime() {
        assertThatThrownBy(() -> new SystemParsingPolicy("SMART", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTO、ON 或 OFF");
    }
}
