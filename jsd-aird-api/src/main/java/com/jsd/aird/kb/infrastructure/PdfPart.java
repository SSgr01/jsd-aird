package com.jsd.aird.kb.infrastructure;

import java.nio.file.Path;

/** A temporary PDF slice and its mapping back to the original document. */
record PdfPart(Path path, int index, int sourceStartPage, int sourceEndPage,
               int logicalStartPage, int logicalEndPage, boolean temporary) {

    int pageOffset() {
        return sourceStartPage - 1;
    }

    boolean overlapsPreviousPart() {
        return sourceStartPage < logicalStartPage;
    }
}
