package com.jsd.aird.mdm.domain.model;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.jsd.aird.shared.api.AllowedActions;

public record Material(
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
    public List<String> getAllowedActions() {
        return AllowedActions.material(linked);
    }
}
