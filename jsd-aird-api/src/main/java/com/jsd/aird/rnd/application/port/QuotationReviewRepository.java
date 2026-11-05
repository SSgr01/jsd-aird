package com.jsd.aird.rnd.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface QuotationReviewRepository {
    Review ensure(UUID organizationId, UUID quotationId);

    Review transition(UUID organizationId, UUID quotationId, long expectedVersion,
                      String fromStatus, String toStatus, int quotationVersion,
                      UUID actorId, String comment);

    List<Event> events(UUID organizationId, UUID quotationId);

    record Review(String status, UUID submittedBy, Instant submittedAt, UUID reviewedBy,
                  Instant reviewedAt, String comment, long lockVersion) { }

    record Event(UUID id, String fromStatus, String toStatus, int quotationVersion,
                 UUID actorId, String actorName, String comment, Instant createdAt) { }
}
