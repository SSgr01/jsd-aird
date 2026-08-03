package com.jsd.aird.tpl.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.tpl.domain.TemplateFormat;

public interface RecognitionModelClient {

    boolean isConfigured();

    RecognitionBatch recognize(RecognitionRequest request);

    record RecognitionRequest(
            UUID importJobId,
            UUID recognitionRunId,
            TemplateFormat format,
            String sourceFileName,
            String regionId,
            JsonNode structureSummary,
            String callPhase
    ) {
        public RecognitionRequest(
                UUID importJobId, UUID recognitionRunId, TemplateFormat format,
                String sourceFileName, String regionId, JsonNode structureSummary
        ) {
            this(importJobId, recognitionRunId, format, sourceFileName, regionId,
                    structureSummary, "REGION_INFERENCE");
        }

        public RecognitionRequest(UUID importJobId, TemplateFormat format, String sourceFileName, JsonNode structureSummary) {
            this(importJobId, null, format, sourceFileName, "", structureSummary, "REGION_INFERENCE");
        }
    }

    record RecognitionBatch(
            List<ModelSuggestion> suggestions,
            List<QualityIssueSuggestion> qualityIssues,
            String provider,
            String model,
            String promptVersion,
            String requestHash,
            String responseHash,
            CallTrace callTrace
    ) {
        public RecognitionBatch(
                List<ModelSuggestion> suggestions, List<QualityIssueSuggestion> qualityIssues,
                String provider, String model, String promptVersion,
                String requestHash, String responseHash
        ) {
            this(suggestions, qualityIssues, provider, model, promptVersion,
                    requestHash, responseHash, null);
        }

        public RecognitionBatch(
                List<ModelSuggestion> suggestions, String provider, String model,
                String promptVersion, String requestHash, String responseHash, CallTrace callTrace
        ) {
            this(suggestions, List.of(), provider, model, promptVersion, requestHash, responseHash, callTrace);
        }

        public RecognitionBatch(
                List<ModelSuggestion> suggestions, String provider, String model,
                String promptVersion, String requestHash, String responseHash
        ) {
            this(suggestions, List.of(), provider, model, promptVersion, requestHash, responseHash, null);
        }
    }

    record ModelSuggestion(
            String suggestionType,
            JsonNode payload,
            double confidence,
            JsonNode evidence
    ) {
    }

    record QualityIssueSuggestion(
            String issueType,
            String severity,
            String sheetId,
            String sheetName,
            String address,
            String title,
            String description,
            String businessImpact,
            double confidence,
            boolean autoFixable,
            JsonNode suggestedPatch,
            JsonNode inversePatch,
            JsonNode evidence,
            String status,
            String regionId,
            UUID recognitionCallId
    ) {
    }

    record CallTrace(
            UUID callId,
            String regionId,
            int attempt,
            String provider,
            String model,
            String promptVersion,
            String status,
            Integer httpStatus,
            Instant startedAt,
            Instant finishedAt,
            long durationMs,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            JsonNode requestPayload,
            JsonNode responsePayload,
            String requestHash,
            String responseHash,
            String errorType,
            String errorMessage,
            String phase,
            UUID parentCallId
    ) {
        public CallTrace(
                UUID callId, String regionId, int attempt, String provider, String model,
                String promptVersion, String status, Integer httpStatus, Instant startedAt,
                Instant finishedAt, long durationMs, int promptTokens, int completionTokens,
                int totalTokens, JsonNode requestPayload, JsonNode responsePayload,
                String requestHash, String responseHash, String errorType, String errorMessage
        ) {
            this(callId, regionId, attempt, provider, model, promptVersion, status, httpStatus,
                    startedAt, finishedAt, durationMs, promptTokens, completionTokens, totalTokens,
                    requestPayload, responsePayload, requestHash, responseHash, errorType,
                    errorMessage, "REGION_INFERENCE", null);
        }
    }

    final class RecognitionCallException extends RuntimeException {
        private final CallTrace trace;

        public RecognitionCallException(String message, Throwable cause, CallTrace trace) {
            super(message, cause);
            this.trace = trace;
        }

        public CallTrace trace() {
            return trace;
        }
    }
}
