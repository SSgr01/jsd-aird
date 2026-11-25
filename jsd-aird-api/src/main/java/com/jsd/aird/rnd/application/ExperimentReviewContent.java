package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.excel.WorkbookReviewContent;

/** Compatibility entry point; import and experiment review use the same content rules. */
final class ExperimentReviewContent {
    private ExperimentReviewContent() {}
    static JsonNode workbook(ObjectMapper json, JsonNode snapshot) {
        return WorkbookReviewContent.workbook(json, snapshot);
    }
    static JsonNode instance(ObjectMapper json, JsonNode source) {
        return WorkbookReviewContent.instance(json, source);
    }
}
