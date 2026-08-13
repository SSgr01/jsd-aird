package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.domain.DocumentParser;
import org.junit.jupiter.api.Test;

class KnowledgeFieldExtractorTest {

    private final KnowledgeFieldExtractor extractor = new KnowledgeFieldExtractor(new ObjectMapper());

    @Test
    void extractsAndNormalizesCoaFieldsWithoutLlm() {
        var fields = extractor.extract("COA", List.of(new DocumentParser.TextBlock(1, "正文", """
                产品名称：水性树脂 A
                批号：LOT-20260813
                检测日期：2026年8月13日
                粘度：1250 mPa·s
                固含量：42.5%
                """)));

        assertThat(fields).extracting(field -> field.code())
                .contains("PRODUCT", "BATCH", "DATE", "VISCOSITY", "SOLIDS");
        assertThat(fields.stream().filter(field -> field.code().equals("DATE")).findFirst().orElseThrow()
                .normalizedValue()).isEqualTo("2026-08-13");
        assertThat(fields.stream().filter(field -> field.code().equals("PRODUCT")).findFirst().orElseThrow()
                .required()).isTrue();
    }

    @Test
    void exposesConflictingCandidatesForHumanConfirmation() {
        var fields = extractor.extract("COA", List.of(new DocumentParser.TextBlock(1, "正文",
                "批号：LOT-1\n批次：LOT-2\n产品：树脂A")));

        var batch = fields.stream().filter(field -> field.code().equals("BATCH")).findFirst().orElseThrow();
        assertThat(batch.conflict()).isTrue();
        assertThat(batch.confidence()).isLessThan(0.8);
        assertThat(batch.candidates()).hasSize(2);
    }
}
