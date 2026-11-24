package com.jsd.aird.kb.api;
public interface PreciseDocumentParser extends com.jsd.aird.kb.api.DocumentParser {
    default ParsedDocument adaptArchive(java.nio.file.Path zip, String fileName, java.util.UUID resultFileId,
                                       ParseContext context, java.util.Map<String, Object> provenance) {
        throw new UnsupportedOperationException("Archive adaptation not supported");
    }
}
