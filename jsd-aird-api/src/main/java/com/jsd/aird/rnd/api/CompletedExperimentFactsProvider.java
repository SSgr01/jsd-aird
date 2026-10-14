package com.jsd.aird.rnd.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only cross-module boundary for current, completed experiment facts.
 *
 * <p>The RND context owns lifecycle, access and source facts. It deliberately
 * does not expose model-task concepts such as X/Y, target keys or eligibility.</p>
 */
public interface CompletedExperimentFactsProvider {

    CompletedExperimentFactsPage query(CompletedExperimentFactsQuery query);

    record CompletedExperimentFactsQuery(
            Set<UUID> experimentIds,
            UUID projectId,
            UUID categoryId,
            int page,
            int size
    ) {
        public CompletedExperimentFactsQuery {
            experimentIds = experimentIds == null ? Set.of() : Set.copyOf(experimentIds);
            page = Math.max(1, page);
            size = Math.min(200, Math.max(1, size));
        }

        public static CompletedExperimentFactsQuery all(int page, int size) {
            return new CompletedExperimentFactsQuery(Set.of(), null, null, page, size);
        }
    }

    record CompletedExperimentFactsPage(
            List<CompletedExperimentFacts> items,
            int page,
            int size,
            long total,
            long totalPages
    ) {
        public CompletedExperimentFactsPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record CompletedExperimentFacts(
            UUID experimentId,
            UUID experimentVersionId,
            String experimentNo,
            String title,
            String sourceType,
            UUID projectId,
            UUID stageId,
            UUID taskId,
            UUID categoryId,
            String categoryName,
            LocalDate experimentDate,
            JsonNode sourceGroups,
            JsonNode sourceContexts,
            JsonNode formulaItems,
            JsonNode processSteps,
            JsonNode testResults,
            JsonNode dynamicValues
    ) {
    }
}
