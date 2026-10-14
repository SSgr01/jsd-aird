package com.jsd.aird.tpl.api;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Public Office parsing boundary for modules that own a project document.
 * Template-specific parser implementations and recognition DTOs stay inside tpl.
 */
public interface TemplateOfficeFacade {

    ParseResult parseOffice(String format, InputStream input);

    ParseResult parseWorkbookSnapshot(InputStream input);

    RecognitionBatch recognize(String format, String sourceFileName, JsonNode structureSummary);

    /** Full template Excel recognition for an experiment-owned upload. */
    WorkbookRecognition recognizeExperimentWorkbook(
            UUID organizationId, UUID actorId, UUID fileId, UUID originalFileId);

    /** Same pipeline, but the stored file is a workbook snapshot JSON (image OCR import). */
    WorkbookRecognition recognizeExperimentWorkbookSnapshot(
            UUID organizationId, UUID actorId, UUID snapshotFileId, UUID originalFileId);

    /**
     * Rebuilds a DOCX artifact from an edited Univer document snapshot.  The
     * original package stays the archive and keeps its styles, headers, footers
     * and embedded media, so the exported body always matches what the user
     * edited.  Legacy {@code .doc} sources must be normalized to DOCX first.
     */
    byte[] exportWordSnapshot(byte[] source, JsonNode snapshot);

    record ParseResult(JsonNode structureSummary, JsonNode initialEditorSnapshot) {
    }

    record RecognitionBatch(List<Suggestion> suggestions) {
        public RecognitionBatch {
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }
    }

    record Suggestion(String suggestionType, double confidence, JsonNode payload, JsonNode evidence,
                      String decision) {
        public Suggestion(String suggestionType, double confidence, JsonNode payload, JsonNode evidence) {
            this(suggestionType, confidence, payload, evidence, "PENDING");
        }
    }

    record WorkbookRecognition(JsonNode snapshot, JsonNode result, RecognitionBatch batch) {
    }
}
