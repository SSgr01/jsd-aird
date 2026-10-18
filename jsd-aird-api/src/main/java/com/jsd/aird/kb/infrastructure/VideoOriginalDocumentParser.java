package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.jsd.aird.kb.domain.DocumentParser;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Keeps supported video originals available for playback without inventing a transcript. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class VideoOriginalDocumentParser implements DocumentParser {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mp4", "m4v", "mov", "webm");

    @Override
    public boolean supports(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        var dot = name.lastIndexOf('.');
        var extension = dot < 0 ? "" : name.substring(dot + 1);
        return type.startsWith("video/") && SUPPORTED_EXTENSIONS.contains(extension);
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName) {
        return new ParsedDocument(List.of(), "video-original-v1", null,
                Map.of("mediaType", "VIDEO", "textExtraction", "NOT_REQUESTED"));
    }
}
