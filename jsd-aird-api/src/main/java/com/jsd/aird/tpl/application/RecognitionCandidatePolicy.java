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
        if (isFormulaExpression(payload)) return false;
        var hasExplicitStructureStatus = payload.has("canonicalStatus") || payload.has("structureStatus");
        var deterministicSimpleLongTableField = "RULE_DETERMINISTIC".equals(
                payload.path("recognitionOrigin").asText())
                && "SIMPLE_LONG_TABLE_FIELD".equals(payload.path("reasonCode").asText())
                && "CHILD".equals(payload.path("suggestionLevel").asText())
                && "REPEAT_FIELD".equals(payload.path("mappingKind").asText())
                && "ROW".equals(payload.path("repeatAxis").asText());
        var stableDocxControl = "DOCX_CONTENT_CONTROL".equals(payload.path("source").asText())
                && !payload.path("markerId").asText(
                        payload.path("locator").path("markerId").asText("")).isBlank();
        return (deterministicSimpleLongTableField || !payload.path("candidateOnly").asBoolean(false))
                && (deterministicSimpleLongTableField
                    || !payload.path("reviewRequired").asBoolean(false) || stableDocxControl)
                && (deterministicSimpleLongTableField
                    || !payload.path("physicalStructureOnly").asBoolean(false))
                && !payload.path("structureConflict").asBoolean(false)
                && !payload.path("semanticConflict").asBoolean(false)
                && !(payload.path("standardRequired").asBoolean(false)
                    && payload.path("requiresStandardConfirmation").asBoolean(false))
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

    /**
     * Fields that may be confirmed after the user explicitly clicks the
     * one-click confirmation action.  This boundary is intentionally wider
     * than {@link #isFormallyConfirmable(JsonNode)}: {@code reviewRequired}
     * means that the user must confirm the field, not that the field can never
     * be confirmed.  Ambiguous structures and protocol-recovery candidates
     * remain outside this boundary.
     */
    public static boolean isOneClickFieldConfirmable(JsonNode payload) {
        if (payload == null || !payload.isObject() || isStructural(payload)) return false;
        if (isFormulaExpression(payload) || isProtocolRejected(payload)) return false;
        if (payload.path("candidateOnly").asBoolean(false)
                || payload.path("physicalStructureOnly").asBoolean(false)
                || payload.path("structureConflict").asBoolean(false)
                || payload.path("semanticConflict").asBoolean(false)) return false;
        if (payload.path("standardRequired").asBoolean(false)
                && payload.path("requiresStandardConfirmation").asBoolean(false)) return false;

        var hasExplicitStructureStatus = payload.has("canonicalStatus") || payload.has("structureStatus");
        if (hasExplicitStructureStatus
                && !("CONFIRMED".equals(payload.path("canonicalStatus").asText())
                && "CONFIRMED".equals(payload.path("structureStatus").asText()))) return false;

        var fieldName = payload.path("fieldName").asText(payload.path("label").asText("")).strip();
        var bindingId = payload.path("bindingId").asText("").strip();
        var mappingKind = payload.path("mappingKind").asText("").strip();
        var suggestionLevel = payload.path("suggestionLevel").asText("");
        return !fieldName.isBlank()
                && !bindingId.isBlank()
                && !mappingKind.isBlank()
                && ("ROOT".equals(suggestionLevel) || "CHILD".equals(suggestionLevel));
    }

    private static boolean isFormulaExpression(JsonNode payload) {
        if (payload.path("fieldName").asText("").strip().startsWith("=")) return true;
        if (payload.path("label").asText("").strip().startsWith("=")) return true;
        return "FORMULA".equals(payload.path("valueSource").asText(""))
                && payload.path("labelPath").asText("").strip().isBlank();
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
