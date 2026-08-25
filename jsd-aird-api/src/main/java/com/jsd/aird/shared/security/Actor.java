package com.jsd.aird.shared.security;

import java.util.UUID;

public record Actor(UUID organizationId, UUID userId, String username, String role) {
    public Actor(UUID organizationId, UUID userId, String username) {
        this(organizationId, userId, username, "ADMIN");
    }
}
