package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.util.UUID;

public record MaterialRow(
        UUID id,
        String code,
        String name,
        String category,
        String sourceCategory,
        String sourceModule,
        String stage,
        String contactPerson,
        String status,
        String description,
        long version,
        Instant createdAt,
        Instant updatedAt,
        boolean linked
) {
}