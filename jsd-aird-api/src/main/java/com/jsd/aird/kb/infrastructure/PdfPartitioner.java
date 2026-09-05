package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Splits large PDFs into MinerU-safe parts while retaining a small page overlap.
 * The source PDF is never modified; generated parts are temporary and owned by
 * the returned PartitionedPdf.
 */
final class PdfPartitioner {

    static final int MAX_PAGES_PER_PART = 190;
    static final int OVERLAP_PAGES = 2;

    PartitionedPdf partition(Path source) throws IOException {
        try (var document = Loader.loadPDF(source.toFile())) {
            var pageCount = document.getNumberOfPages();
            if (pageCount <= 0) {
                return new PartitionedPdf(pageCount,
                        List.of(new PdfPart(source, 1, 1, 0, 1, 0, false)));
            }
            if (pageCount <= MAX_PAGES_PER_PART) {
                return new PartitionedPdf(pageCount,
                        List.of(new PdfPart(source, 1, 1, pageCount, 1, pageCount, false)));
            }
            var parts = new ArrayList<PdfPart>();
            var logicalStart = 1;
            var partIndex = 1;
            while (logicalStart <= pageCount) {
                var sourceStart = partIndex == 1 ? 1 : Math.max(1, logicalStart - OVERLAP_PAGES);
                var sourceEnd = Math.min(pageCount, sourceStart + MAX_PAGES_PER_PART - 1);
                var target = Files.createTempFile("mineru-part-", ".pdf");
                try (var part = new PDDocument()) {
                    for (var page = sourceStart - 1; page < sourceEnd; page++) {
                        part.importPage(document.getPage(page));
                    }
                    part.save(target.toFile());
                } catch (Exception exception) {
                    Files.deleteIfExists(target);
                    throw exception;
                }
                parts.add(new PdfPart(target, partIndex, sourceStart, sourceEnd,
                        logicalStart, Math.min(pageCount, sourceEnd), true));
                if (sourceEnd >= pageCount) break;
                logicalStart = sourceEnd + 1;
                partIndex++;
            }
            return new PartitionedPdf(pageCount, List.copyOf(parts));
        }
    }

    static final class PartitionedPdf implements AutoCloseable {
        private final int originalPageCount;
        private final List<PdfPart> parts;

        PartitionedPdf(int originalPageCount, List<PdfPart> parts) {
            this.originalPageCount = originalPageCount;
            this.parts = List.copyOf(parts);
        }

        int originalPageCount() { return originalPageCount; }
        List<PdfPart> parts() { return parts; }
        boolean split() { return parts.size() > 1; }

        @Override
        public void close() {
            for (var part : parts) {
                if (!part.temporary()) continue;
                try { Files.deleteIfExists(part.path()); }
                catch (IOException ignored) { }
            }
        }
    }
}
