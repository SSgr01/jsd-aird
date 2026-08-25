package com.jsd.aird.rnd.application;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;

import java.util.Locale;
import java.util.Set;

/** Centralized authorization rules for the ELN endpoints. */
public final class ExperimentAccessPolicy {
    private static final Set<String> READ_ROLES = Set.of(
            "ADMIN", "SYSTEM_ADMIN", "RND_MANAGER", "RND_ENGINEER", "QUALITY_MANAGER",
            "RESEARCHER", "REVIEWER", "VIEWER", "READ_ONLY", "研发人员", "审核员", "只读"
    );
    private static final Set<String> WRITE_ROLES = Set.of(
            "ADMIN", "SYSTEM_ADMIN", "RND_MANAGER", "RND_ENGINEER", "RESEARCHER", "研发人员"
    );
    private static final Set<String> REVIEW_ROLES = Set.of(
            "ADMIN", "SYSTEM_ADMIN", "RND_MANAGER", "QUALITY_MANAGER", "REVIEWER", "审核员"
    );

    private ExperimentAccessPolicy() {}

    public static void requireRead() {
        require(READ_ROLES, "没有实验查看权限");
    }

    public static void requireWrite() {
        require(WRITE_ROLES, "没有实验新增或编辑权限");
    }

    public static void requireReview() {
        require(REVIEW_ROLES, "没有实验审核权限");
    }

    public static void requireDelete() {
        require(Set.of("ADMIN", "SYSTEM_ADMIN"), "没有实验删除权限");
    }

    private static void require(Set<String> allowed, String message) {
        Actor actor = ActorContext.required();
        String role = actor.role() == null ? "" : actor.role().trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(role) && !allowed.contains(actor.role())) {
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, message);
        }
    }
}
