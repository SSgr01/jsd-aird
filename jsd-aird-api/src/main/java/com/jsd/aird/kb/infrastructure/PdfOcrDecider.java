package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;

import com.jsd.aird.kb.domain.OcrMode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

final class PdfOcrDecider {

    Decision decide(Path pdf, OcrMode mode) {
        if (mode == OcrMode.ON) return new Decision(true, "REQUESTED_ON", 0, 0, 0);
        if (mode == OcrMode.OFF) return new Decision(false, "REQUESTED_OFF", 0, 0, 0);
        try (var document = Loader.loadPDF(pdf.toFile())) {
            var pageCount = document.getNumberOfPages();
            if (pageCount <= 0) return new Decision(true, "AUTO_EMPTY_PDF", 0, 0, 0);
            var sampled = samplePages(pageCount);
            var qualified = 0;
            var aggregate = 0;
            var stripper = new PDFTextStripper();
            for (var page : sampled) {
                stripper.setStartPage(page + 1);
                stripper.setEndPage(page + 1);
                var count = validCharacterCount(stripper.getText(document));
                aggregate += count;
                if (count >= 32) qualified++;
            }
            var enoughPages = qualified * 10 >= sampled.size() * 7;
            var useOcr = !(enoughPages && aggregate >= 64);
            return new Decision(useOcr, useOcr ? "AUTO_INSUFFICIENT_TEXT" : "AUTO_TEXT_SUFFICIENT",
                    sampled.size(), qualified, aggregate);
        } catch (Exception exception) {
            return new Decision(true, "AUTO_DETECTION_FAILED", 0, 0, 0);
        }
    }

    private LinkedHashSet<Integer> samplePages(int pageCount) {
        var sampleCount = Math.min(5, pageCount);
        var result = new LinkedHashSet<Integer>();
        if (sampleCount == 1) {
            result.add(0);
            return result;
        }
        for (var i = 0; i < sampleCount; i++) {
            result.add((int) Math.round((double) i * (pageCount - 1) / (sampleCount - 1)));
        }
        return result;
    }

    private int validCharacterCount(String text) throws IOException {
        if (text == null) return 0;
        return (int) text.codePoints().filter(codePoint -> Character.isLetterOrDigit(codePoint)
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN).count();
    }

    record Decision(boolean useOcr, String reason, int sampledPages, int qualifiedPages, int validCharacters) { }
}
