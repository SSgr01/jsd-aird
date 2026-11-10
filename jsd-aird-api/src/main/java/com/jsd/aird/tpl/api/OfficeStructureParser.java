package com.jsd.aird.tpl.api;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public interface OfficeStructureParser {

    TemplateFormat format();

    ParseResult parse(InputStream input);

    record ParseIssue(String severity, String code, String message, JsonNode location) {
    }

    record ParseResult(
            JsonNode structureSummary,
            JsonNode initialEditorSnapshot,
            List<ParseIssue> issues
    ) {
    }
}
