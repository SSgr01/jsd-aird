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
            CallTrace callTrace,
            List<CallTrace> callTraces
    ) {
        public RecognitionBatch {
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            qualityIssues = qualityIssues == null ? List.of() : List.copyOf(qualityIssues);
            callTraces = callTraces == null ? callTrace == null ? List.of() : List.of(callTrace)
                    : List.copyOf(callTraces);
            if (callTrace == null && !callTraces.isEmpty()) {
                callTrace = callTraces.get(callTraces.size() - 1);
            }
        }

        public RecognitionBatch(
                List<ModelSuggestion> suggestions, List<QualityIssueSuggestion> qualityIssues,
                String provider, String model, String promptVersion,
                String requestHash, String responseHash, CallTrace callTrace
        ) {
            this(suggestions, qualityIssues, provider, model, promptVersion,
                    requestHash, responseHash, callTrace, null);
        }

        public RecognitionBatch(
                List<ModelSuggestion> suggestions, List<QualityIssueSuggestion> qualityIssues,
                String provider, String model, String promptVersion,
                String requestHash, String responseHash
        ) {
            this(suggestions, qualityIssues, provider, model, promptVersion,
                    requestHash, responseHash, null, null);
        }

        public RecognitionBatch(
                List<ModelSuggestion> suggestions, String provider, String model,
                String promptVersion, String requestHash, String responseHash, CallTrace callTrace
        ) {
            this(suggestions, List.of(), provider, model, promptVersion,
                    requestHash, responseHash, callTrace, null);
        }

        public RecognitionBatch(
                List<ModelSuggestion> suggestions, String provider, String model,
                String promptVersion, String requestHash, String responseHash
        ) {
            this(suggestions, List.of(), provider, model, promptVersion,
                    requestHash, responseHash, null, null);
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
        private final List<CallTrace> traces;

        public RecognitionCallException(String message, Throwable cause, CallTrace trace) {
            this(message, cause, trace == null ? List.of() : List.of(trace));
        }

        public RecognitionCallException(String message, Throwable cause, List<CallTrace> traces) {
            super(message, cause);
            this.traces = traces == null ? List.of() : List.copyOf(traces);
        }

        public CallTrace trace() {
            return traces.isEmpty() ? null : traces.get(traces.size() - 1);
        }

        public List<CallTrace> traces() {
            return traces;
        }
    }
}
