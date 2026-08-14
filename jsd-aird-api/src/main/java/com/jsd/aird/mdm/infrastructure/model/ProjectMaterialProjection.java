package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectMaterialProjection(
        UUID id,
        UUID projectId,
        UUID materialId,
        Instant createdAt,
        String mCode,
        String mName,
        String mCategory,
        String mSourceCategory,
        String mSourceModule,
        String mStage,
        String mContactPerson,
        String mStatus
) {
}