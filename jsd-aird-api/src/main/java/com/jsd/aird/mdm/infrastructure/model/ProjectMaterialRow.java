package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectMaterialRow(
        UUID id,
        UUID projectId,
        UUID materialId
) {
}