package com.jsd.aird.rnd.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.rnd.domain.ExperimentModels.*;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import java.time.LocalDate;
import java.util.*;

public interface ExperimentRepository {
    record Search(String keyword, String status, String sourceType, UUID projectId, UUID categoryId, int page, int size) {}
    record Create(UUID id, UUID organizationId, String experimentNo, String title, UUID categoryId, String categoryName,
                  String sourceType, ExperimentStatus status, UUID projectId, UUID stageId, UUID taskId,
                  UUID ownerId, String ownerName, LocalDate experimentDate, UUID versionId, UUID templateVersionId,
                  String templateHash, JsonNode templateSnapshot, JsonNode editModel, UUID actorId) {}
    record Draft(String title, UUID categoryId, String categoryName, UUID projectId, UUID stageId, UUID taskId,
                 String ownerName, LocalDate experimentDate, UUID templateVersionId, String templateHash,
                 JsonNode templateSnapshot, JsonNode editModel) {}
    List<Summary> search(UUID organizationId, Search search);
    long count(UUID organizationId, Search search);
    Optional<Detail> detail(UUID organizationId, UUID id);
    Summary create(Create create);
    Detail saveDraft(UUID organizationId, UUID id, long revision, Draft draft, UUID actorId, String actorName);
    Detail transition(UUID organizationId, UUID id, long revision, ExperimentStatus target, String comment,
                      UUID actorId, String actorName);
    List<Version> versions(UUID organizationId, UUID id);
    Detail createRevision(UUID organizationId, UUID id, long revision, String reason, UUID actorId, String actorName);
    JsonNode compare(UUID organizationId, UUID id, int from, int to);
    List<Audit> audits(UUID organizationId, UUID id);
    List<Category> categories(UUID organizationId, boolean includeInactive);
    Category createCategory(UUID organizationId, String code, String name, String description, UUID actorId);
    Category setCategoryActive(UUID organizationId, UUID id, long revision, boolean active);
}
