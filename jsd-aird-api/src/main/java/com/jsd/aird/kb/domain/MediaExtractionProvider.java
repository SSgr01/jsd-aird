package com.jsd.aird.kb.domain;

import java.io.InputStream;

/**
 * OCR/ASR provider boundary. Concrete providers can be added without changing
 * the knowledge ingestion workflow.
 */
public interface MediaExtractionProvider {

    boolean supports(String fileName, String contentType);

    boolean isConfigured();

    DocumentParser.ParsedDocument extract(InputStream source, String fileName);
}
