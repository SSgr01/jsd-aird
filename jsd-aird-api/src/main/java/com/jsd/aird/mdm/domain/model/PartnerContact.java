package com.jsd.aird.mdm.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PartnerContact(UUID id, UUID partnerId, String name, String department, String title,
                             String phone, String email, PartnerStatus status,
                             List<String> assignedProjectIds, String members, String wechat,
                             long version, JsonNode customFields, Instant createdAt, Instant updatedAt) {
}
