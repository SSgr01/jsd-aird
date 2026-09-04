package com.jsd.aird.shared.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AllowedActionsTest {

    @Test
    void knowledgeDraftCanBeDeletedButPublishedCanOnlyBeDisabled() {
        assertEquals(java.util.List.of("DELETE"), AllowedActions.knowledge("ACTIVE", "PENDING_REVIEW", false));
        assertEquals(java.util.List.of("DISABLE"), AllowedActions.knowledge("ACTIVE", "PUBLISHED", true));
        assertEquals(java.util.List.of("RESTORE"), AllowedActions.knowledge("DISABLED", "PUBLISHED", true));
    }

    @Test
    void referencesAndLifecycleBlockProjectResourceDeletion() {
        assertEquals(java.util.List.of("DELETE"), AllowedActions.projectResource("IN_PROGRESS", false, false));
        assertEquals(java.util.List.of(), AllowedActions.projectResource("IN_PROGRESS", false, true));
        assertEquals(java.util.List.of(), AllowedActions.projectResource("COMPLETED", true, false));
    }

    @Test
    void importsCanOnlyBeRemovedBeforeGeneratedBusinessDataExists() {
        assertEquals(java.util.List.of("DELETE"), AllowedActions.templateImport("PARSED", false));
        assertEquals(java.util.List.of(), AllowedActions.templateImport("PARSED", true));
        assertEquals(java.util.List.of("DELETE"), AllowedActions.experimentImport("FAILED", false));
        assertEquals(java.util.List.of(), AllowedActions.experimentImport("FAILED", true));
    }
}
