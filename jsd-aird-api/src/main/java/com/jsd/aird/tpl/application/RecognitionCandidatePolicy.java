package com.jsd.aird.tpl.application;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One defensive policy for deciding whether a recognition candidate may cross
 * the review boundary into the formal template model.
 */
public final class RecognitionCandidatePolicy {

    private RecognitionCandidatePolicy() {
    }

    public static boolean isFormallyConfirmable(JsonNode payload) {
        if (payload == null || !payload.isObject()) return false;
        var hasExplicitStructureStatus = payload.has("canonicalStatus") || payload.has("structureStatus");
        return !payload.path("candidateOnly").asBoolean(false)
                && !payload.path("reviewRequired").asBoolean(false)
                && !payload.path("physicalStructureOnly").asBoolean(false)
                && !payload.path("structureConflict").asBoolean(false)
                && !payload.path("semanticConflict").asBoolean(false)
                && !payload.path("requiresStandardConfirmation").asBoolean(false)
                && !isProtocolRejected(payload)
                && (!isStructural(payload) || !hasExplicitStructureStatus
                    || ("CONFIRMED".equals(payload.path("canonicalStatus").asText())
                        && "CONFIRMED".equals(payload.path("structureStatus").asText())));
    }

    public static boolean isFormalCandidate(JsonNode payload) {
        return isFormallyConfirmable(payload)
                && !"STATIC".equals(payload.path("valueSource").asText())
                && !"STATIC_REFERENCE".equals(payload.path("blockType").asText());
    }

    public static boolean isStructural(JsonNode payload) {
        var kind = payload == null ? "" : payload.path("kind")
                .asText(payload.path("tableKind").asText(""));
        return "MATRIX".equals(kind) || "ROW_TABLE".equals(kind)
                || "COLUMN_TABLE".equals(kind) || "FORM_REGION".equals(kind)
                || "TABLE_REGION".equals(kind)
                || "TABLE_REGION".equals(payload.path("locatorType").asText(""));
    }

    public static boolean isProtocolRejected(JsonNode payload) {
        if (payload == null) return true;
        return "RETAINED_REJECTED_CANDIDATE".equals(payload.path("protocolRecovery").asText())
                || "PROTOCOL_REVIEW_REQUIRED".equals(payload.path("pendingReason").asText())
                || "RETAINED_REJECTED_CANDIDATE".equals(payload.path("pendingReason").asText());
    }
}
