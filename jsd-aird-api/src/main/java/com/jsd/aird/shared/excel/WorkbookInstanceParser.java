package com.jsd.aird.shared.excel;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Stateless XLSX parsing contract shared by template and production-instance flows. */
public interface WorkbookInstanceParser {

    ParsedWorkbook parseInstance(InputStream input);

    /** Read an editable workbook without building the template-recognition summary. */
    default JsonNode parseSnapshot(InputStream input) { return parseInstance(input).snapshot(); }

    record ParsedWorkbook(JsonNode structureSummary, JsonNode snapshot, List<Issue> issues) {
    }

    record Issue(String severity, String code, String message, JsonNode location) {
    }
}
