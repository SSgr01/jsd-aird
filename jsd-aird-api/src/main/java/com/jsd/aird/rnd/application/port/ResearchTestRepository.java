package com.jsd.aird.rnd.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.rnd.domain.ResearchTestModels.*;
import com.jsd.aird.shared.api.PageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResearchTestRepository {
    record Search(Type type,String keyword,String category,String status,String ownerName,UUID projectId,
                  LocalDate dateFrom,LocalDate dateTo,int page,int size) {}
    record Create(UUID organizationId,UUID actorId,String actorName,Type type,String businessNo,String name,
                  String category,String scope,String ownerName,LocalDate date,String format,String sourceType,
                  String visibility,UUID projectId,UUID stageId,UUID taskId,UUID sourceFileId,
                  UUID templateVersionId,String templateHash,JsonNode templateSnapshot,JsonNode editModel,
                  JsonNode memberSnapshot,LocalDate effectiveFrom,LocalDate effectiveTo) {}
    record Draft(long lockVersion,String businessNo,String name,String category,String scope,String ownerName,
                 LocalDate date,String visibility,UUID projectId,UUID stageId,UUID taskId,
                 UUID templateVersionId,String templateHash,JsonNode templateSnapshot,JsonNode editModel,
                 JsonNode memberSnapshot,LocalDate effectiveFrom,LocalDate effectiveTo) {}

    PageResponse<Summary> search(UUID organizationId, Search search);
    Optional<Detail> detail(UUID organizationId, UUID id);
    Detail create(Create command);
    Detail save(UUID organizationId,UUID id,Draft draft,UUID actor,String actorName);
    Detail rename(UUID organizationId, UUID id, String name, UUID projectId, UUID stageId, UUID taskId,
                  long lockVersion, UUID actor, String actorName);
    Detail transition(UUID organizationId,UUID id,long lockVersion,Status status,String comment,UUID actor,String actorName);
    Detail createRevision(UUID organizationId,UUID id,long lockVersion,String reason,UUID actor,String actorName);
    Detail copy(UUID organizationId,UUID id,UUID actor,String actorName);
    void delete(UUID organizationId,UUID id,long lockVersion,UUID actor,String actorName);
    List<Version> versions(UUID organizationId,UUID id);
    List<Audit> audits(UUID organizationId,UUID id);
    Upload addUpload(UUID organizationId,UUID actor,UUID recordId,UUID fileId,String name,String contentType,long size,String sha256);
    Optional<Upload> findUpload(UUID organizationId, UUID id);
    Upload retryUpload(UUID organizationId, UUID id);
    PageResponse<Upload> uploads(UUID organizationId,String keyword,int page,int size);
    void deleteUpload(UUID organizationId,UUID id);
}
