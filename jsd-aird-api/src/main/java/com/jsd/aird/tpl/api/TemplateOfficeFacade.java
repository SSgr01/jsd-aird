package com.jsd.aird.tpl.api;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Public Office parsing boundary for modules that own a project document.
 * Template-specific parser implementations and recognition DTOs stay inside tpl.
 */
public interface TemplateOfficeFacade {

    ParseResult parseOffice(String format, InputStream input);

    ParseResult parseWorkbookSnapshot(InputStream input);

    RecognitionBatch recognize(String format, String sourceFileName, JsonNode structureSummary);

    record ParseResult(JsonNode structureSummary, JsonNode initialEditorSnapshot) {
    }

    record RecognitionBatch(List<Suggestion> suggestions) {
        public RecognitionBatch {
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }
    }

    record Suggestion(String suggestionType, double confidence, JsonNode payload, JsonNode evidence) {
    }
}
