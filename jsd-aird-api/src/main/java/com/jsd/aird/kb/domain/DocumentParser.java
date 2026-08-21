package com.jsd.aird.kb.domain;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DocumentParser {

    boolean supports(String fileName, String contentType);

    /** Returns false when the configured provider cannot accept work yet. */
    default boolean isConfigured() { return true; }

    default String unavailableReason() { return "文档解析服务尚未配置"; }

    ParsedDocument parse(InputStream source, String fileName);

    default ParsedDocument parse(InputStream source, String fileName, ParseContext context) {
        return parse(source, fileName);
    }

    record ParseContext(UUID organizationId, UUID actorId, UUID sourceFileId,
                        String contentType, long size) { }

    record ParsedDocument(List<TextBlock> blocks, String parserVersion, String providerTaskId,
                          Map<String, Object> metadata, List<SourceTable> sourceTables) {
        public ParsedDocument {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
        }

        public ParsedDocument(List<TextBlock> blocks, String parserVersion) {
            this(blocks, parserVersion, null, Map.of(), List.of());
        }

        public ParsedDocument(List<TextBlock> blocks, String parserVersion, String providerTaskId,
                              Map<String, Object> metadata) {
            this(blocks, parserVersion, providerTaskId, metadata, List.of());
        }
    }

    record TextBlock(Integer pageNo, String section, String content, String sheetName, String cellRange,
                     String paragraphId, List<Double> bbox, Long startTimeMs, Long endTimeMs,
                     Double confidence, Map<String, Object> attributes) {
        public TextBlock(Integer pageNo, String section, String content) {
            this(pageNo, section, content, null, null, null, List.of(), null, null, null, Map.of());
        }

        public TextBlock(Integer pageNo, String section, String content, String sheetName, String cellRange,
                         String paragraphId, List<Double> bbox, Long startTimeMs, Long endTimeMs,
                         Double confidence) {
            this(pageNo, section, content, sheetName, cellRange, paragraphId, bbox, startTimeMs,
                    endTimeMs, confidence, Map.of());
        }

        public TextBlock {
            bbox = bbox == null ? List.of() : List.copyOf(bbox);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    record SourceTable(int sourceBlockNo, String sheetKey, String sheetName, int rowCount,
                       int columnCount, int nonEmptyCount, List<TableCell> cells) {
        public SourceTable {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    record TableCell(int rowNo, int columnNo, String displayValue, String cellRange) { }
}
