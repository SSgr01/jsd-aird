package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RequirementRow(UUID id, String requirementCode, UUID partnerId, String title,
                             String rawRequirement, String urgency, LocalDate raisedAt,
                             LocalDate deliveryDate, String status, String customStatusName, String assignedProjectIds,
                             String customFields,
                             long version, Instant createdAt, Instant updatedAt) {
}
