package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;

import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
import org.springframework.stereotype.Component;

@Component
public class NoopOcrProvider implements MediaExtractionProvider {

    @Override
    public boolean supports(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        return type.startsWith("image/") || name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".gif");
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public DocumentParser.ParsedDocument extract(InputStream source, String fileName) {
        throw new IllegalStateException("OCR provider 尚未配置");
    }
}
