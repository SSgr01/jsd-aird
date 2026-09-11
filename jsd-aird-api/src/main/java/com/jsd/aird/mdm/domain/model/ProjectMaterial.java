package com.jsd.aird.mdm.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectMaterial(
        UUID id,
        UUID projectId,
        UUID materialId,
        String materialCode,
        String materialName,
        String materialCategory,
        String sourceCategory,
        String sourceModule,
        String stage,
        String contactPerson,
        String status,
        Instant createdAt
) {
}