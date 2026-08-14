package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.util.UUID;

public record PartnerContactRow(UUID id, UUID partnerId, String name, String department, String title, String phone,
                                String email, String status, String assignedProjectIds, String members, String wechat,
                                String customFields, long version, Instant createdAt, Instant updatedAt) {
}
