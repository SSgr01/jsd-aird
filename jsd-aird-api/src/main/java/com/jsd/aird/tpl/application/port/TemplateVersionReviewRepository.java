package com.jsd.aird.tpl.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateVersionReviewRepository {

    Optional<Review> find(UUID organizationId, UUID versionId);

    Review ensure(UUID organizationId, UUID versionId);

    Review transition(UUID organizationId, UUID versionId, String expectedStatus, String nextStatus,
                      UUID actorId, String comment);

    List<ReviewEvent> events(UUID organizationId, UUID versionId);

    record Review(UUID versionId, String status, UUID submittedBy, Instant submittedAt,
                  UUID reviewedBy, Instant reviewedAt, String comment, long lockVersion) { }

    record ReviewEvent(UUID id, UUID versionId, String fromStatus, String toStatus,
                       UUID actorId, String comment, Instant createdAt) { }
}
