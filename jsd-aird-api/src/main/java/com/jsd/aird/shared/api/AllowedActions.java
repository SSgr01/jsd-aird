package com.jsd.aird.shared.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Common lifecycle action projection used by business read models.
 *
 * <p>The returned values describe actions which are valid for the current
 * lifecycle state. Route-level permission filters and the command service
 * remain the final authorization boundary.</p>
 */
public final class AllowedActions {
    private AllowedActions() { }

    public static List<String> knowledge(String lifecycleStatus, String reviewStatus, boolean hasPublication) {
        if ("DISABLED".equalsIgnoreCase(lifecycleStatus)) return List.of("RESTORE");
        if (hasPublication || "PUBLISHED".equalsIgnoreCase(reviewStatus)) return List.of("DISABLE");
        return List.of("DELETE");
    }

    public static List<String> template(String status, boolean hasDraft, boolean hasPublished) {
        var actions = new ArrayList<String>();
        if (hasPublished || "PUBLISHED".equalsIgnoreCase(status)) actions.add("RETIRE");
        if (hasDraft || "DRAFT".equalsIgnoreCase(status)) actions.add("DELETE");
        return List.copyOf(actions);
    }

    /** Template recognition jobs are removable only after parsing finished and
     * before a template version has been generated from the job. */
    public static List<String> templateImport(String status, boolean generatedVersion) {
        if (generatedVersion) return List.of();
        return "PARSED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)
                ? List.of("DELETE") : List.of();
    }

    public static List<String> experiment(String status) {
        if (status == null) return List.of();
        if ("COMPLETED".equalsIgnoreCase(status)) return List.of("VOID");
        if ("VOIDED".equalsIgnoreCase(status)) return List.of();
        return List.of("DELETE");
    }

    public static List<String> experimentImport(String status, boolean generatedExperiment) {
        if (generatedExperiment) return List.of();
        return "COMPLETED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)
                ? List.of("DELETE") : List.of();
    }

    public static List<String> production(String status) {
        if (status == null) return List.of();
        if ("CANCELLED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status)) {
            return List.of("DELETE");
        }
        if ("DRAFT".equalsIgnoreCase(status)) return List.of("DELETE", "CANCEL");
        if ("SUBMITTED".equalsIgnoreCase(status) || "IN_PROGRESS".equalsIgnoreCase(status)) {
            return List.of("CANCEL");
        }
        return List.of();
    }

    public static List<String> productionUpload(String status) {
        if (status == null) return List.of();
        if ("DELETED".equalsIgnoreCase(status)) return List.of();
        if ("QUEUED".equalsIgnoreCase(status) || "PARSING".equalsIgnoreCase(status)
                || "REVIEW_REQUIRED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
            return List.of("DELETE");
        }
        if ("SAVED".equalsIgnoreCase(status)) return List.of("DELETE", "CANCEL");
        return List.of("CANCEL");
    }

    public static List<String> disabled(String status) {
        if ("DISABLED".equalsIgnoreCase(status) || "INACTIVE".equalsIgnoreCase(status)) {
            return List.of("RESTORE");
        }
        return List.of("DISABLE");
    }

    public static List<String> chart(String status) {
        return "DELETED".equalsIgnoreCase(status) ? List.of() : List.of("DELETE");
    }

    public static List<String> project(String status) {
        if ("CANCELLED".equalsIgnoreCase(status)) return List.of("DELETE");
        if ("COMPLETED".equalsIgnoreCase(status)) return List.of("ARCHIVE");
        return List.of("DELETE", "CANCEL");
    }

    public static List<String> projectResource(String status, boolean completed) {
        return projectResource(status, completed, false);
    }

    public static List<String> projectResource(String status, boolean completed, boolean referenced) {
        if (referenced || completed || "COMPLETED".equalsIgnoreCase(status)) return List.of();
        return List.of("DELETE");
    }

    public static List<String> meeting(boolean archivedToKnowledgeBase) {
        return archivedToKnowledgeBase ? List.of() : List.of("DELETE", "ARCHIVE");
    }

    public static List<String> material(boolean linked) {
        return linked ? List.of() : List.of("DELETE");
    }

    public static List<String> projectDocument(String status) {
        return "DRAFT".equalsIgnoreCase(status) ? List.of("DELETE") : List.of();
    }

    public static List<String> category(boolean systemDefault) {
        return systemDefault ? List.of() : List.of("DELETE");
    }

}
