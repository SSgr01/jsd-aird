package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class PdfPartitionerTest {

    @Test
    void keepsPdfExtensionOnMineruPartNames() {
        assertThat(MineruDocumentParser.splitPdfFileName("材料.pdf", 1))
                .isEqualTo("材料.part-001.pdf");
        assertThat(MineruDocumentParser.splitPdfFileName("材料.PDF", 12))
                .isEqualTo("材料.part-012.pdf");
    }

    @Test
    void keepsSmallPdfAsTheOriginalFile() throws Exception {
        var source = Files.createTempFile("mineru-small-", ".pdf");
        try (var document = new PDDocument()) {
            for (var index = 0; index < 190; index++) document.addPage(new PDPage());
            document.save(source.toFile());
        }
        try (var partitioned = new PdfPartitioner().partition(source)) {
            assertThat(partitioned.split()).isFalse();
            assertThat(partitioned.parts()).singleElement().satisfies(part -> {
                assertThat(part.path()).isEqualTo(source);
                assertThat(part.sourceStartPage()).isEqualTo(1);
                assertThat(part.sourceEndPage()).isEqualTo(190);
                assertThat(part.temporary()).isFalse();
            });
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void createsOverlappingPartsAndCleansTemporaryFiles() throws Exception {
        var source = Files.createTempFile("mineru-large-", ".pdf");
        try (var document = new PDDocument()) {
            for (var index = 0; index < 537; index++) document.addPage(new PDPage());
            document.save(source.toFile());
        }
        PdfPartitioner.PartitionedPdf partitioned = new PdfPartitioner().partition(source);
        var paths = partitioned.parts().stream().map(PdfPart::path).toList();
        try {
            assertThat(partitioned.originalPageCount()).isEqualTo(537);
            assertThat(partitioned.parts()).extracting(PdfPart::sourceStartPage)
                    .containsExactly(1, 189, 377);
            assertThat(partitioned.parts()).extracting(PdfPart::sourceEndPage)
                    .containsExactly(190, 378, 537);
            assertThat(partitioned.parts()).extracting(PdfPart::logicalStartPage)
                    .containsExactly(1, 191, 379);
            for (var part : partitioned.parts()) {
                try (var document = Loader.loadPDF(part.path().toFile())) {
                    assertThat(document.getNumberOfPages()).isLessThanOrEqualTo(PdfPartitioner.MAX_PAGES_PER_PART);
                }
            }
        } finally {
            partitioned.close();
            for (var path : paths) assertThat(Files.exists(path)).isFalse();
            Files.deleteIfExists(source);
        }
    }
}
