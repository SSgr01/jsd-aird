package com.jsd.aird.kb.domain;

import java.io.InputStream;
import java.util.List;

public interface DocumentParser {

    boolean supports(String fileName, String contentType);

    ParsedDocument parse(InputStream source, String fileName);

    record ParsedDocument(List<TextBlock> blocks, String parserVersion) {
        public ParsedDocument {
            blocks = List.copyOf(blocks);
        }
    }

    record TextBlock(Integer pageNo, String section, String content) {
    }
}
