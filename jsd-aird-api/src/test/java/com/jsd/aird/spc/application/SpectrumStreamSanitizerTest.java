package com.jsd.aird.spc.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpectrumStreamSanitizerTest {

    @Test
    void removesInternalEvidenceLabelsUuidsAndPageIdentifiers() {
        var sanitized = SpectrumChatService.sanitizeStreamText(
                "先比较两条曲线。Evidence Ids: 4d51dc2d-2124-4ab4-9208-5bc52c8e783c/page-1；再核对条件。");

        assertThat(sanitized).isEqualTo("先比较两条曲线。再核对条件。");
        assertThat(sanitized).doesNotContain("Evidence", "4d51dc2d", "page-1");
    }
}
