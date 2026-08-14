package com.jsd.aird.mdm.application.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.mdm.domain.model.ProjectPriority;
import com.jsd.aird.mdm.domain.model.ProjectStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ProjectCommands {

    private ProjectCommands() {
    }

    public record SaveProject(String projectCode, String name, UUID partnerId, String partnerName, String owner,
                              LocalDate startDate, LocalDate endDate, ProjectPriority priority, ProjectStatus status,
                              Integer teamSize, String background, JsonNode customFields, JsonNode teamMembers,
                              Long version) {
    }

    public record Created(UUID id, long version) {
    }

    public record CopyProjects(List<UUID> ids) {
    }
}
