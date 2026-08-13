package com.jsd.aird.kb.domain;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface DocumentParser {

    boolean supports(String fileName, String contentType);

    ParsedDocument parse(InputStream source, String fileName);

    record ParsedDocument(List<TextBlock> blocks, String parserVersion, String providerTaskId,
                          Map<String, Object> metadata) {
        public ParsedDocument {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public ParsedDocument(List<TextBlock> blocks, String parserVersion) {
            this(blocks, parserVersion, null, Map.of());
        }
    }

    record TextBlock(Integer pageNo, String section, String content, String sheetName, String cellRange,
                     String paragraphId, List<Double> bbox, Long startTimeMs, Long endTimeMs,
                     Double confidence) {
        public TextBlock(Integer pageNo, String section, String content) {
            this(pageNo, section, content, null, null, null, List.of(), null, null, null);
        }

        public TextBlock {
            bbox = bbox == null ? List.of() : List.copyOf(bbox);
        }
    }
}
