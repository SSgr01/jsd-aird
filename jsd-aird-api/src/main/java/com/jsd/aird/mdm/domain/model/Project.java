package com.jsd.aird.mdm.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import com.jsd.aird.shared.api.AllowedActions;

public record Project(UUID id, String projectCode, String name, UUID partnerId, String partnerName,
                      String owner, LocalDate startDate, LocalDate endDate, ProjectPriority priority,
                      ProjectStatus status, int teamSize, String background, JsonNode customFields,
                      JsonNode teamMembers, long version, Instant createdAt, Instant updatedAt, UUID ownerUserId,
                      String customerRequirements) {
    public Project(UUID id, String projectCode, String name, UUID partnerId, String partnerName,
                   String owner, LocalDate startDate, LocalDate endDate, ProjectPriority priority,
                   ProjectStatus status, int teamSize, String background, JsonNode customFields,
                   JsonNode teamMembers, long version, Instant createdAt, Instant updatedAt, UUID ownerUserId) {
        this(id, projectCode, name, partnerId, partnerName, owner, startDate, endDate, priority,
            status, teamSize, background, customFields, teamMembers, version, createdAt, updatedAt,
            ownerUserId, null);
    }
    public Project(UUID id, String projectCode, String name, UUID partnerId, String partnerName,
                   String owner, LocalDate startDate, LocalDate endDate, ProjectPriority priority,
                   ProjectStatus status, int teamSize, String background, JsonNode customFields,
                   JsonNode teamMembers, long version, Instant createdAt, Instant updatedAt) {
        this(id, projectCode, name, partnerId, partnerName, owner, startDate, endDate, priority,
            status, teamSize, background, customFields, teamMembers, version, createdAt, updatedAt, null);
    }
    public List<String> getAllowedActions() {
        return AllowedActions.project(status == null ? null : status.name());
    }
}
