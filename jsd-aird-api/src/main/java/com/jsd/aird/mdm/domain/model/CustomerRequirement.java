package com.jsd.aird.mdm.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CustomerRequirement(
    UUID id, String requirementCode, UUID partnerId, String title,
    String rawRequirement, String urgency, LocalDate raisedAt,
    LocalDate deliveryDate, RequirementStatus status, String customStatusName, UUID projectId,
    List<UUID> projectIds,
    JsonNode customFields,
    long version, Instant createdAt, Instant updatedAt
) {
    /** 删除需求为逻辑删除（置 deleted=true），不会修改状态为 CANCELLED，也不会物理删除。 */
    public List<String> getAllowedActions() {
        return List.of();
    }
    public enum RequirementStatus {DRAFT, CONFIRMED, IN_PROJECT, COMPLETED, CANCELLED}
}
