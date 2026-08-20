package com.jsd.aird.ops.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Worker-facing boundary for conversation titles. Business modules expose only
 * the minimum data needed by the shared title job and keep their tables private.
 */
public interface ConversationTitleTarget {

    String targetType();

    Optional<Target> find(UUID organizationId, UUID conversationId);

    /** Updates only automatic titles; a user-owned title must never be replaced. */
    boolean updateAutomaticTitle(UUID organizationId, UUID conversationId, String title);

    record Target(UUID organizationId, UUID actorId, UUID conversationId, String title,
                  String titleSource, String firstQuestion) { }
}
