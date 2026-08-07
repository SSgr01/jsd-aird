package com.jsd.aird.tpl.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;

/** Rejects transport/protocol-successful responses that contain no usable semantics. */
final class SemanticResultValidator {

    void validateMeaningfulResult(JsonNode result, JsonNode physicalFacts) {
        var annotations = result.path("semanticAnnotations").size();
        var blocks = result.path("businessBlocks").size();
        var relations = result.path("fieldRelations").size();
        var tables = result.path("tables").size();
        var issues = result.path("qualityIssues").size();
        var total = annotations + blocks + relations + tables + issues;
        if (total == 0 && countPhysicalValueCells(physicalFacts) > 0) {
            throw new EmptySemanticResultException(
                    "工作簿包含有效业务内容，但模型返回了空语义结果"
            );
        }
    }

    int countPhysicalValueCells(JsonNode physicalFacts) {
        var count = 0;
        for (var sheet : physicalFacts.path("sheets")) {
            for (var cell : sheet.path("semanticCells")) {
                var factType = cell.path("factType").asText("");
                if ("VALUE".equals(factType) || "FORMULA".equals(factType)
                        || "INPUT_CANDIDATE".equals(factType)) {
                    count++;
                }
            }
        }
        return count;
    }

    static final class EmptySemanticResultException extends IllegalStateException {
        EmptySemanticResultException(String message) {
            super(message);
        }
    }
}
