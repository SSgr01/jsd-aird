package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;
import java.util.ArrayList;

import com.jsd.aird.kb.domain.DocumentParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName, String contentType) {
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                || "application/pdf".equalsIgnoreCase(contentType);
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName) {
        var blocks = new ArrayList<TextBlock>();
        try (var document = Loader.loadPDF(source.readAllBytes())) {
            var stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                var text = stripper.getText(document).strip();
                if (!text.isBlank()) blocks.add(new TextBlock(page, "page", text));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("PDF 文件解析失败", exception);
        }
        return new ParsedDocument(blocks, "pdf-v1");
    }
}
