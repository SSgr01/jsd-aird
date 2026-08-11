package com.jsd.aird.kb.domain;

import java.io.InputStream;
import java.util.UUID;

/**
 * OCR/ASR provider boundary. Concrete providers can be added without changing
 * the knowledge ingestion workflow.
 */
public interface MediaExtractionProvider {

    boolean supports(String fileName, String contentType);

    boolean isConfigured();

    default String unavailableReason() {
        return "媒体解析服务尚未配置";
    }

    default DocumentParser.ParsedDocument extract(InputStream source, String fileName) {
        throw new IllegalStateException("媒体解析 Provider 未实现");
    }

    default DocumentParser.ParsedDocument extract(InputStream source, String fileName, ExtractionContext context) {
        return extract(source, fileName);
    }

    record ExtractionContext(UUID fileId, String contentType, long size, String publicUrl) {
    }
}
