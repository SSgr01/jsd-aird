package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.jsd.aird.kb.domain.DocumentParser;
import org.junit.jupiter.api.Test;

class OcrFieldValueLinkerTest {

    @Test
    void createsACombinedEvidenceBlockWithoutRemovingOriginalOcrLines() {
        var blocks = OcrFieldValueLinker.link(List.of(
                new DocumentParser.TextBlock(1, "OCR-LINE", "密度"),
                new DocumentParser.TextBlock(1, "OCR-LINE", "1.05"),
                new DocumentParser.TextBlock(1, "OCR-LINE", "备注"),
                new DocumentParser.TextBlock(1, "OCR-LINE", "正常")));

        assertThat(blocks).extracting(DocumentParser.TextBlock::content)
                .containsExactly("密度", "1.05", "密度 = 1.05", "备注", "正常");
        assertThat(blocks.get(2).attributes())
                .containsEntry("fieldLabel", "密度")
                .containsEntry("fieldValue", "1.05");
    }

    @Test
    void doesNotTreatAProductTitleWithCodeAsAField() {
        var blocks = OcrFieldValueLinker.link(List.of(
                new DocumentParser.TextBlock(1, "paragraph", "准分子树脂 UA-3131"),
                new DocumentParser.TextBlock(1, "paragraph", "UA-3131")));

        assertThat(blocks).extracting(DocumentParser.TextBlock::content)
                .containsExactly("准分子树脂 UA-3131", "UA-3131");
    }
}
