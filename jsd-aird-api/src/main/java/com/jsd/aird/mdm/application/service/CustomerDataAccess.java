package com.jsd.aird.mdm.application.service;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.DataScopeResolver.ResolvedDataScope;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CustomerDataAccess {
    private final AuthorizationService authorization;
    private final JdbcTemplate jdbc;

    public CustomerDataAccess(AuthorizationService authorization, JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.jdbc = jdbc;
    }

    public ResolvedDataScope scope(String permissionCode) {
        var actor = ActorContext.required();
        var scope = authorization.resolveScope(new PermissionCheck(actor.organizationId(), actor.userId(),
                permissionCode, "CUSTOMER", null, "READ"));
        if (!scope.allowed()) throw denied();
        return scope;
    }

    public ResolvedDataScope optionalScope(String permissionCode) {
        try {
            return scope(permissionCode);
        } catch (ApiException denied) {
            return ResolvedDataScope.allow("SELECTED");
        }
    }

    public void require(UUID customerId, String permissionCode) {
        var actor = ActorContext.required();
        Boolean owned = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM mdm.business_partner
                WHERE id = ? AND organization_id = ? AND deleted = false)
                """, Boolean.class, customerId, actor.organizationId());
        if (!Boolean.TRUE.equals(owned)) throw denied();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), permissionCode,
                "CUSTOMER", customerId, "READ"));
    }

    public boolean mayReadProject(UUID projectId) {
        var actor = ActorContext.required();
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM mdm.project WHERE id = ? AND organization_id = ? AND deleted = false)",
                Boolean.class, projectId, actor.organizationId());
        return Boolean.TRUE.equals(exists) && authorization.check(new PermissionCheck(actor.organizationId(),
                actor.userId(), "project.view", "PROJECT", projectId, "READ")).allowed();
    }

    public void requireProjectVisible(UUID projectId) {
        if (!mayReadProject(projectId))
            throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "无权关联该项目");
    }

    public void requireProjectAction(UUID projectId, String permissionCode) {
        var actor = ActorContext.required();
        if (!mayReadProject(projectId)) throw denied();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(),
                permissionCode, "PROJECT", projectId, permissionCode.endsWith(".view") ? "READ" : "WRITE"));
    }

    private static ApiException denied() {
        return new ApiException(ApiErrorCode.PERMISSION_DENIED, "无权访问该客户");
    }
}
