package com.jsd.aird.rnd.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.rnd.domain.ExperimentModels.*;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import java.time.LocalDate;
import java.util.*;

public interface ExperimentRepository {
    record Search(String keyword, String status, String sourceType, UUID projectId, UUID stageId, UUID taskId,
                  UUID categoryId, String ownerName, LocalDate dateFrom, LocalDate dateTo, int page, int size) {}
    record Create(UUID id, UUID organizationId, String experimentNo, String title, UUID categoryId, String categoryName,
                  String sourceType, ExperimentStatus status, UUID projectId, UUID stageId, UUID taskId,
                  UUID ownerId, String ownerName, LocalDate experimentDate, UUID versionId, UUID templateVersionId,
                  String templateHash, JsonNode templateSnapshot, JsonNode editModel, UUID actorId,
                  String actorName) {}
    record Draft(String experimentNo, String title, UUID categoryId, String categoryName, UUID projectId, UUID stageId, UUID taskId,
                 String ownerName, LocalDate experimentDate, UUID templateVersionId, String templateHash,
                 JsonNode templateSnapshot, JsonNode editModel) {}
    record CompletedFactsSearch(Set<UUID> experimentIds, UUID projectId, UUID categoryId,
                                int page, int size, DataScopeFilter scope) {
        public CompletedFactsSearch {
            experimentIds = experimentIds == null ? Set.of() : Set.copyOf(experimentIds);
            scope = scope == null ? DataScopeFilter.all() : scope;
        }
    }
    record CompletedFactsRow(UUID experimentId, UUID experimentVersionId, String experimentNo, String title,
                             String sourceType, UUID projectId, UUID stageId, UUID taskId, UUID categoryId,
                             String categoryName, LocalDate experimentDate, JsonNode editModel) {}
    record DataScopeFilter(String type, UUID actorId, Set<UUID> targetIds) {
        public DataScopeFilter {
            type = type == null || type.isBlank() ? "ALL" : type.toUpperCase(Locale.ROOT);
            targetIds = targetIds == null ? Set.of() : Set.copyOf(targetIds);
        }
        public static DataScopeFilter all() { return new DataScopeFilter("ALL", null, Set.of()); }
    }
    List<Summary> search(UUID organizationId, Search search);
    long count(UUID organizationId, Search search);
    Optional<Detail> detail(UUID organizationId, UUID id);
    List<CompletedFactsRow> completedFacts(UUID organizationId, CompletedFactsSearch search);
    long countCompletedFacts(UUID organizationId, CompletedFactsSearch search);
    Summary create(Create create);
    Summary copy(UUID organizationId, UUID sourceId, UUID actorId, String actorName);
    Detail saveDraft(UUID organizationId, UUID id, long revision, Draft draft, UUID actorId, String actorName);
    void delete(UUID organizationId, UUID id, long revision, UUID actorId, String actorName);
    Detail publish(UUID organizationId, UUID id, long revision, UUID actorId, String actorName);
    Detail transition(UUID organizationId, UUID id, long revision, ExperimentStatus target, String comment,
                      UUID actorId, String actorName);
    List<Version> versions(UUID organizationId, UUID id);
    Detail createRevision(UUID organizationId, UUID id, long revision, String reason, UUID actorId, String actorName);
    Detail rollback(UUID organizationId, UUID id, long revision, int targetVersion, String reason, UUID actorId, String actorName);
    JsonNode compare(UUID organizationId, UUID id, int from, int to);
    List<Audit> audits(UUID organizationId, UUID id);
    List<Category> categories(UUID organizationId, boolean includeInactive);
    Category createCategory(UUID organizationId, String code, String name, String description, UUID actorId);
    Category updateCategory(UUID organizationId, UUID id, long revision, String name, String description, UUID actorId);
    Category setCategoryActive(UUID organizationId, UUID id, long revision, boolean active);
}
