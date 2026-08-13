package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeDuplicateDetectorTest {

    @Test
    void removesCommonVersionCopyAndDateNoise() {
        assertThat(KnowledgeDuplicateDetector.normalizedStem("产品说明书_V2_副本_20260813（1）.PDF"))
                .isEqualTo("产品说明书");
        assertThat(KnowledgeDuplicateDetector.similarity("COA-树脂A-2026.08.13-V1.pdf",
                "coa_树脂a_2026年08月13日_v2.PDF")).isEqualTo(1.0);
    }

    @Test
    void keepsDifferentBusinessNamesBelowVersionThreshold() {
        assertThat(KnowledgeDuplicateDetector.similarity("产品A实验报告.pdf", "产品B生产工艺.pdf"))
                .isLessThan(0.90);
    }
}
