package com.jsd.aird.tpl.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StandardFieldRepository {

    List<StandardField> search(String keyword, String valueType);

    Optional<StandardField> find(UUID id);

    Optional<StandardField> findActive(String fieldCode, int version, UUID id);

    boolean isDictionaryAdmin(UUID organizationId, UUID userId);

    boolean belongsToOrganization(UUID organizationId, UUID templateVersionId);

    Request insertRequest(RequestDraft request);

    Optional<Request> findRequest(UUID organizationId, UUID requestId);

    List<Request> listRequests(UUID organizationId, String status);

    StandardField approveRequest(
            UUID organizationId,
            UUID requestId,
            UUID reviewerId,
            String fieldCode,
            String reviewComment
    );

    void rejectRequest(UUID organizationId, UUID requestId, UUID reviewerId, String reviewComment);

    void backfillTemplateField(UUID organizationId, Request request, StandardField standardField);

    record StandardField(
            UUID id,
            String fieldCode,
            int version,
            String displayName,
            String valueType,
            String uiType,
            String groupCode,
            String defaultUnit,
            String description
    ) {
    }

    record RequestDraft(
            UUID id,
            UUID organizationId,
            UUID templateVersionId,
            String fieldId,
            String displayName,
            String valueType,
            String uiType,
            String groupCode,
            String description,
            UUID createdBy
    ) {
    }

    record Request(
            UUID id,
            UUID organizationId,
            UUID templateVersionId,
            String fieldId,
            String displayName,
            String valueType,
            String uiType,
            String groupCode,
            String description,
            String status,
            String proposedFieldCode,
            UUID approvedDictionaryId,
            String reviewComment,
            UUID createdBy,
            Instant createdAt,
            UUID reviewedBy,
            Instant reviewedAt
    ) {
    }
}
