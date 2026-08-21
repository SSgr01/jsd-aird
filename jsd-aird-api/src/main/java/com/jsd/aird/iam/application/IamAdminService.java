package com.jsd.aird.iam.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.iam.api.PermissionDefinitionContributor.PermissionDefinition;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IamAdminService {

    private final IamStore store;
    private final AuthorizationService authorization;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogFacade audit;

    public IamAdminService(IamStore store, AuthorizationService authorization, PasswordEncoder passwordEncoder,
                           AuditLogFacade audit) {
        this.store = store;
        this.authorization = authorization;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    public PageResponse<UserView> users(Actor actor, String keyword, int page, int size) {
        require(actor, "system.user.view");
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        var items = store.users(actor.organizationId(), keyword, safePage, safeSize).stream().map(this::userView).toList();
        var total = store.countUsers(actor.organizationId(), keyword);
        return new PageResponse<>(items, safePage, safeSize, total, (total + safeSize - 1) / safeSize);
    }

    public UserView user(Actor actor, UUID userId) {
        require(actor, "system.user.view");
        return store.user(userId).filter(user -> actor.organizationId().equals(user.organizationId()))
                .map(this::userView).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "用户不存在"));
    }

    @Transactional
    public CreateUserResult create(Actor actor, CreateUserCommand command) {
        if (command.password() == null || command.password().length() < 6 || command.password().length() > 200) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "密码长度为 6-200 位");
        }
        require(actor, "system.user.manage");
        var role = role(actor, command.roleId());
        var id = UUID.randomUUID();
        store.insertUser(id, actor.organizationId(), command.username(), command.displayName(), command.email(), command.phone(),
                command.departmentName(), role.id(), passwordEncoder.encode(command.password()));
        audit(actor, "IAM_USER_CREATED", "USER", id);
        return new CreateUserResult(user(actor, id));
    }

    @Transactional
    public UserView update(Actor actor, UUID userId, UpdateUserCommand command) {
        require(actor, "system.user.manage");
        var existing = requireUser(actor, userId);
        role(actor, command.roleId());
        store.updateUser(actor.organizationId(), userId, command.displayName(), command.email(), command.phone(),
                command.departmentName(), command.roleId());
        audit(actor, "IAM_USER_UPDATED", "USER", userId);
        return user(actor, existing.id());
    }

    @Transactional
    public void setStatus(Actor actor, UUID userId, boolean enabled) {
        require(actor, "system.user.manage");
        var existing = requireUser(actor, userId);
        if (!enabled && "SYSTEM_ADMIN".equals(existing.roleCode()) && store.activeSystemAdmins(actor.organizationId()) <= 1) {
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能停用最后一个系统管理员");
        }
        store.setStatus(actor.organizationId(), userId, enabled ? "ACTIVE" : "DISABLED");
        audit(actor, enabled ? "IAM_USER_ENABLED" : "IAM_USER_DISABLED", "USER", userId);
    }

    @Transactional
    public void resetPassword(Actor actor, UUID userId, String password) {
        if (password == null || password.length() < 6 || password.length() > 200) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "密码长度为 6-200 位");
        }
        require(actor, "system.user.manage");
        requireUser(actor, userId);
        store.resetPassword(actor.organizationId(), userId, passwordEncoder.encode(password));
        audit(actor, "IAM_PASSWORD_RESET", "USER", userId);
    }

    @Transactional
    public void forceLogout(Actor actor, UUID userId) {
        require(actor, "system.user.manage");
        requireUser(actor, userId);
        store.revokeUserSessions(actor.organizationId(), userId);
        audit(actor, "IAM_FORCE_LOGOUT", "USER", userId);
    }

    public List<RoleView> roles(Actor actor) {
        require(actor, "system.role.view");
        return store.roles(actor.organizationId()).stream().map(role ->
                new RoleView(role.id(), role.code(), role.name(), role.builtin(), role.enabled(), role.policyVersion())).toList();
    }

    @Transactional
    public RoleView createRole(Actor actor, String code, String name) {
        require(actor, "system.role.create");
        if (!code.matches("[A-Z][A-Z0-9_]{2,79}")) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "角色编码必须使用大写字母、数字和下划线");
        var role = store.roleByCode(actor.organizationId(), code);
        if (role.isPresent()) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "角色编码已存在");
        var created = store.ensureRole(actor.organizationId(), code, name, false);
        audit(actor, "IAM_ROLE_CREATED", "ROLE", created.id());
        return new RoleView(created.id(), created.code(), created.name(), created.builtin(), created.enabled(), created.policyVersion());
    }

    @Transactional
    public void renameRole(Actor actor, UUID roleId, String name) {
        require(actor, "system.role.manage");
        var role = role(actor, roleId);
        if (role.builtin()) throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "内置角色不可重命名");
        store.renameRole(actor.organizationId(), roleId, name);
        audit(actor, "IAM_ROLE_RENAMED", "ROLE", roleId);
    }

    @Transactional
    public void deleteRole(Actor actor, UUID roleId) {
        require(actor, "system.role.manage");
        role(actor, roleId);
        try { store.deleteRole(actor.organizationId(), roleId); }
        catch (IllegalArgumentException exception) { throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, exception.getMessage()); }
        audit(actor, "IAM_ROLE_DELETED", "ROLE", roleId);
    }

    public List<Binding> rolePermissions(Actor actor, UUID roleId) {
        require(actor, "system.permission.manage");
        role(actor, roleId);
        return store.roleBindings(actor.organizationId(), roleId);
    }

    @Transactional
    public long saveRolePermissions(Actor actor, UUID roleId, long expectedVersion, List<Binding> bindings) {
        require(actor, "system.permission.manage");
        var role = role(actor, roleId);
        validateBindings(bindings);
        try { store.replaceRoleBindings(actor.organizationId(), roleId, expectedVersion, bindings); }
        catch (IamStore.PolicyVersionConflict conflict) { throw new ApiException(ApiErrorCode.POLICY_VERSION_CONFLICT); }
        audit(actor, "IAM_ROLE_PERMISSIONS_UPDATED", "ROLE", roleId);
        return store.roleVersion(actor.organizationId(), role.id());
    }

    public PermissionOverrides userPermissions(Actor actor, UUID userId) {
        require(actor, "system.permission.manage");
        var user = requireUser(actor, userId);
        return new PermissionOverrides(user.authVersion(), store.userOverrides(actor.organizationId(), userId));
    }

    @Transactional
    public long saveUserPermissions(Actor actor, UUID userId, long expectedVersion, List<Binding> bindings) {
        require(actor, "system.permission.manage");
        requireUser(actor, userId);
        validateBindings(bindings);
        try { store.replaceUserOverrides(actor.organizationId(), userId, expectedVersion, bindings); }
        catch (IamStore.PolicyVersionConflict conflict) { throw new ApiException(ApiErrorCode.POLICY_VERSION_CONFLICT); }
        audit(actor, "IAM_USER_PERMISSIONS_UPDATED", "USER", userId);
        return store.user(userId).orElseThrow().authVersion();
    }

    @Transactional
    public void deleteUserOverride(Actor actor, UUID userId, String permissionCode) {
        require(actor, "system.permission.manage");
        requireUser(actor, userId);
        store.deleteUserOverride(actor.organizationId(), userId, permissionCode);
        audit(actor, "IAM_USER_PERMISSION_RESTORED", "USER", userId);
    }

    public List<PermissionDefinition> permissionDefinitions(Actor actor) {
        require(actor, "system.permission.manage");
        return store.permissionDefinitions();
    }

    public List<AuditLogFacade.AuditEntry> auditLogs(Actor actor, UUID actorId, String action, Instant from, Instant to, int limit) {
        require(actor, "system.audit.view");
        return audit.search(actor.organizationId(), actorId, action, from, to, limit);
    }

    private void require(Actor actor, String permission) {
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), permission, "IAM", null, null));
    }

    private IamStore.User requireUser(Actor actor, UUID userId) {
        return store.user(userId).filter(user -> actor.organizationId().equals(user.organizationId()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "用户不存在"));
    }

    private IamStore.Role role(Actor actor, UUID roleId) {
        return store.role(actor.organizationId(), roleId).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "角色不存在"));
    }

    private void validateBindings(List<Binding> bindings) {
        var known = store.permissionDefinitions().stream().map(PermissionDefinition::code).collect(java.util.stream.Collectors.toSet());
        for (var binding : bindings) {
            if (!known.contains(binding.permissionCode())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "未知权限编码：" + binding.permissionCode());
            if (!List.of("ALLOW", "DENY").contains(binding.effect())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "权限效果无效");
            if (!List.of("ALL", "SELF", "ASSIGNED", "PROJECT", "CATEGORY", "SELECTED").contains(binding.scopeType())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据范围无效");
            }
            if ("DENY".equals(binding.effect()) && binding.targetIds() != null && !binding.targetIds().isEmpty()) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "DENY 不允许配置数据目标");
            }
            if ("ALLOW".equals(binding.effect())
                    && List.of("SELECTED", "PROJECT", "CATEGORY").contains(binding.scopeType())
                    && (binding.targetIds() == null || binding.targetIds().isEmpty())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, binding.scopeType() + " 范围必须选择目标");
            }
        }
    }

    private UserView userView(IamStore.User user) {
        return new UserView(user.id(), user.username(), user.displayName(), user.email(), user.phone(), user.departmentName(),
                user.roleId(), user.roleCode(), user.roleName(), user.status(), user.lastLoginAt(), user.authVersion());
    }

    private void audit(Actor actor, String action, String type, UUID id) {
        audit.append(actor.organizationId(), actor.userId(), action, type, id, JsonNodeFactory.instance.objectNode().put("source", "iam"));
    }

    public record CreateUserCommand(String username, String displayName, String email, String phone, String departmentName,
                                    UUID roleId, String password) { }
    public record UpdateUserCommand(String displayName, String email, String phone, String departmentName, UUID roleId) { }
    public record UserView(UUID id, String username, String displayName, String email, String phone, String departmentName,
                           UUID roleId, String roleCode, String roleName, String status, Instant lastLoginAt,
                           long authVersion) { }
    public record CreateUserResult(UserView user) { }
    public record RoleView(UUID id, String code, String name, boolean builtin, boolean enabled, long policyVersion) { }
    public record PermissionOverrides(long version, List<Binding> bindings) { }
}
