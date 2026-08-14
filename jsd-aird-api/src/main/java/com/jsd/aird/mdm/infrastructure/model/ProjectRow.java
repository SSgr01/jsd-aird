package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProjectRow(UUID id, String projectCode, String name, UUID partnerId, String partnerName,
                         String owner, LocalDate startDate, LocalDate endDate, String priority, String status,
                         int teamSize, String background, String customFields, String teamMembers, long version,
                         Instant createdAt, Instant updatedAt) {
}
