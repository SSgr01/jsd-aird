package com.jsd.aird.tpl.application.port;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.tpl.domain.TemplateFormat;

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
