package com.jsd.aird.mdm.domain.model;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

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
    /** Association rows are removed through the explicit unlink command. */
    public List<String> getAllowedActions() {
        return List.of();
    }
}
