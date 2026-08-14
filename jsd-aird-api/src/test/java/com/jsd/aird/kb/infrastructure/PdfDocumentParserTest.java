package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfDocumentParserTest {

    @Test
    void retainsNormalizedRegionsForNativePdfText() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Native PDF evidence");
                content.endText();
            }
            document.save(bytes);
        }

        var parsed = new PdfDocumentParser().parse(new ByteArrayInputStream(bytes.toByteArray()), "evidence.pdf");

        assertThat(parsed.parserVersion()).isEqualTo("pdf-native-v2");
        assertThat(parsed.blocks()).singleElement().satisfies(block -> {
            assertThat(block.pageNo()).isEqualTo(1);
            assertThat(block.content()).isEqualTo("Native PDF evidence");
            assertThat(block.bbox()).hasSize(8).allSatisfy(value -> assertThat(value).isBetween(0.0, 1.0));
        });
    }
}
