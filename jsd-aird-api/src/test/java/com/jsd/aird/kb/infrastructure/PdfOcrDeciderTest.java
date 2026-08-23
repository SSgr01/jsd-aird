package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;

import com.jsd.aird.kb.domain.OcrMode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfOcrDeciderTest {

    @Test
    void obeysExplicitModesAndSafelyEnablesOcrForBlankPdf() throws Exception {
        var path = Files.createTempFile("ocr-decision-", ".pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(path.toFile());
        }
        try {
            var decider = new PdfOcrDecider();
            assertThat(decider.decide(path, OcrMode.ON).useOcr()).isTrue();
            assertThat(decider.decide(path, OcrMode.OFF).useOcr()).isFalse();
            assertThat(decider.decide(path, OcrMode.AUTO).useOcr()).isTrue();
        } finally { Files.deleteIfExists(path); }
    }

    @Test
    void disablesForcedOcrWhenMostSampledPagesHaveEnoughText() throws Exception {
        var path = Files.createTempFile("ocr-text-", ".pdf");
        try (var document = new PDDocument()) {
            for (var index = 0; index < 5; index++) {
                var page = new PDPage(); document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.newLineAtOffset(30, 700); content.showText("A".repeat(80)); content.endText();
                }
            }
            document.save(path.toFile());
        }
        try {
            var decision = new PdfOcrDecider().decide(path, OcrMode.AUTO);
            assertThat(decision.useOcr()).isFalse();
            assertThat(decision.sampledPages()).isEqualTo(5);
            assertThat(decision.qualifiedPages()).isEqualTo(5);
        } finally { Files.deleteIfExists(path); }
    }
}
