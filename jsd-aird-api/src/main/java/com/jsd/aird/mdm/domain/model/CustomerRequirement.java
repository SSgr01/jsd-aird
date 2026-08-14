package com.jsd.aird.mdm.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerRequirement(
    UUID id, String requirementCode, UUID partnerId, String title,
    String rawRequirement, String urgency, LocalDate raisedAt,
    LocalDate deliveryDate, RequirementStatus status, String customStatusName, UUID projectId,
    JsonNode customFields,
    long version, Instant createdAt, Instant updatedAt
) {
    public enum RequirementStatus {DRAFT, CONFIRMED, IN_PROJECT, COMPLETED, CANCELLED}
}
