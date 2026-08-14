package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.jsd.aird.kb.domain.DocumentParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

/** Reads a native PDF text layer and retains normalized page-region evidence anchors. */
@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName, String contentType) {
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                || "application/pdf".equalsIgnoreCase(contentType);
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName) {
        try (var document = Loader.loadPDF(source.readAllBytes())) {
            var stripper = new PositionedTextStripper(document);
            stripper.setSortByPosition(true);
            stripper.getText(document);
            return new ParsedDocument(stripper.blocks(), "pdf-native-v2");
        } catch (Exception exception) {
            throw new IllegalStateException("PDF 文件解析失败", exception);
        }
    }

    private static final class PositionedTextStripper extends PDFTextStripper {
        private final PDDocument document;
        private final List<TextBlock> blocks = new ArrayList<>();

        private PositionedTextStripper(PDDocument document) throws IOException {
            this.document = document;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            var normalized = text == null ? "" : text.strip();
            if (normalized.isBlank() || positions == null || positions.isEmpty()) return;
            var pageNo = getCurrentPageNo();
            var pageBox = document.getPage(pageNo - 1).getCropBox();
            var minX = Double.POSITIVE_INFINITY;
            var minY = Double.POSITIVE_INFINITY;
            var maxX = 0.0;
            var maxY = 0.0;
            for (var position : positions) {
                minX = Math.min(minX, position.getXDirAdj());
                minY = Math.min(minY, position.getYDirAdj() - position.getHeightDir());
                maxX = Math.max(maxX, position.getXDirAdj() + position.getWidthDirAdj());
                maxY = Math.max(maxY, position.getYDirAdj());
            }
            var left = clamp(minX / pageBox.getWidth());
            var top = clamp(minY / pageBox.getHeight());
            var right = clamp(maxX / pageBox.getWidth());
            var bottom = clamp(maxY / pageBox.getHeight());
            blocks.add(new TextBlock(pageNo, "paragraph", normalized, null, null, null,
                    List.of(left, top, right, top, right, bottom, left, bottom), null, null, null));
        }

        private List<TextBlock> blocks() {
            return List.copyOf(blocks);
        }

        private double clamp(double value) {
            return Math.max(0.0, Math.min(1.0, value));
        }
    }
}
