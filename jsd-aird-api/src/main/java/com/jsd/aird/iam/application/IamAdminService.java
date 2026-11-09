package com.jsd.aird.iam.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.iam.api.RecordDataScope;
import com.jsd.aird.iam.api.PermissionDefinitionContributor.PermissionDefinition;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import com.jsd.aird.iam.application.port.IamStore.PermissionLevelBinding;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IamAdminService {

    private final IamStore store;
    private final AuthorizationService authorization;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogFacade audit;
    private final AuditLogPresentationService auditPresentation;
    private final EffectivePermissionResolver effectivePermissions;

    public IamAdminService(IamStore store, AuthorizationService authorization, PasswordEncoder passwordEncoder,
                           AuditLogFacade audit, AuditLogPresentationService auditPresentation) {
        this(store, authorization, passwordEncoder, audit, auditPresentation, null);
    }

    @Autowired
    public IamAdminService(IamStore store, AuthorizationService authorization, PasswordEncoder passwordEncoder,
                           AuditLogFacade audit, AuditLogPresentationService auditPresentation,
                           EffectivePermissionResolver effectivePermissions) {
        this.store = store;
        this.authorization = authorization;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.auditPresentation = auditPresentation;
        this.effectivePermissions = effectivePermissions;
    }

    public PageResponse<UserView> users(Actor actor, String keyword, int page, int size) {
        return users(actor, keyword, page, size, null);
    }

    /**
     * 用户管理 / 用户权限配置的数据范围按「用户」维度限制。permissionView 区分两个页面，
     * 避免其中一行的配置意外收窄另一行的用户列表。
     */
    public PageResponse<UserView> users(Actor actor, String keyword, int page, int size, String permissionView) {
        requireAny(actor, "system.user.view", "system.permissions.view");
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        var code = "permissions".equalsIgnoreCase(permissionView) ? "system.permissions.view" : "system.users.view";
        var dataScope = userDataScope(actor, code);
        var items = store.users(actor.organizationId(), keyword, safePage, safeSize, dataScope).stream().map(this::userView).toList();
        var total = store.countUsers(actor.organizationId(), keyword, dataScope);
        return new PageResponse<>(items, safePage, safeSize, total, (total + safeSize - 1) / safeSize);
    }

    private RecordDataScope userDataScope(Actor actor, String permissionCode) {
        var resolved = authorization.resolveScope(new PermissionCheck(actor.organizationId(), actor.userId(),
                permissionCode, "USER", null, "READ"));
        if (!resolved.allowed()) {
            // 未配置 v2 权限（旧角色）时保持原有全量口径；空「指定」则返回空数据集。
            return "PERMISSION_DENIED".equals(resolved.reason())
                    ? RecordDataScope.all()
                    : new RecordDataScope(resolved.scopeType(), actor.userId(), java.util.Set.of());
        }
        return new RecordDataScope(resolved.scopeType(), actor.userId(), resolved.targetIds());
    }

    public UserView user(Actor actor, UUID userId) {
        require(actor, "system.user.view");
        requireUserInScope(actor, userId, "system.users.view");
        return store.user(userId).filter(user -> actor.organizationId().equals(user.organizationId()))
                .map(this::userView).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "用户不存在"));
    }

    private void requireUserInScope(Actor actor, UUID userId, String permissionCode) {
        if (!store.userInScope(actor.organizationId(), userId, userDataScope(actor, permissionCode)))
            throw new ApiException(ApiErrorCode.NOT_FOUND, "用户不存在");
    }

    @Transactional
    public CreateUserResult create(Actor actor, CreateUserCommand command) {
        store.lockAdministration(actor.organizationId());

        if (command.password() == null || command.password().length() < 6 || command.password().length() > 200) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "密码长度为 6-200 位");
        }
        requireAny(actor, "system.users.create", "system.user.manage");
        var role = role(actor, command.roleId());
        var id = UUID.randomUUID();
        store.insertUser(id, actor.organizationId(), command.username(), command.displayName(), command.email(), command.phone(),
                command.departmentName(), role.id(), passwordEncoder.encode(command.password()), actor.userId());
        audit(actor, "IAM_USER_CREATED", "USER", id);
        return new CreateUserResult(user(actor, id));
    }

    @Transactional
    public UserView update(Actor actor, UUID userId, UpdateUserCommand command) {
        store.lockAdministration(actor.organizationId());
        requireUserVersion(actor,userId,command.expectedVersion());
        requireAny(actor, "system.users.update", "system.user.manage");
        requireUserInScope(actor, userId, "system.users.view");
        var existing = requireUser(actor, userId);
        if (actor.userId().equals(userId) && !Objects.equals(existing.roleId(), command.roleId()))
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能修改自己的业务角色");
        var targetRole=role(actor, command.roleId());
        if("SYSTEM_ADMIN".equals(existing.roleCode()) && "ACTIVE".equals(existing.status()) && !"SYSTEM_ADMIN".equals(targetRole.code()) && store.activeSystemAdmins(actor.organizationId())<=1)
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN,"不能移除最后一个系统管理员");
        store.updateUser(actor.organizationId(), userId, command.displayName(), command.email(), command.phone(),
                command.departmentName(), command.roleId());
        audit(actor, "IAM_USER_UPDATED", "USER", userId);
        return user(actor, existing.id());
    }

    @Transactional
    public void setStatus(Actor actor, UUID userId, boolean enabled,long expectedVersion) {
        store.lockAdministration(actor.organizationId());
        requireUserVersion(actor,userId,expectedVersion);
        requireAny(actor, "system.users.status", "system.user.manage");
        requireUserInScope(actor, userId, "system.users.view");
        var existing = requireUser(actor, userId);
        if (!enabled && "SYSTEM_ADMIN".equals(existing.roleCode()) && store.activeSystemAdmins(actor.organizationId()) <= 1) {
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能停用最后一个系统管理员");
        }
        store.setStatus(actor.organizationId(), userId, enabled ? "ACTIVE" : "DISABLED");
        audit(actor, enabled ? "IAM_USER_ENABLED" : "IAM_USER_DISABLED", "USER", userId);
    }

    @Transactional
    public void resetPassword(Actor actor, UUID userId, String password,long expectedVersion) {
        store.lockAdministration(actor.organizationId());
        requireUserVersion(actor,userId,expectedVersion);
        if (password == null || password.length() < 6 || password.length() > 200) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "密码长度为 6-200 位");
        }
        requireAny(actor, "system.users.reset", "system.user.manage");
        requireUserInScope(actor, userId, "system.users.view");
        requireUser(actor, userId);
        store.resetPassword(actor.organizationId(), userId, passwordEncoder.encode(password));
        audit(actor, "IAM_PASSWORD_RESET", "USER", userId);
    }

    @Transactional
    public void forceLogout(Actor actor, UUID userId,long expectedVersion) {
        store.lockAdministration(actor.organizationId());
        requireUserVersion(actor,userId,expectedVersion);
        requireAny(actor, "system.users.logout", "system.user.manage");
        requireUserInScope(actor, userId, "system.users.view");
        requireUser(actor, userId);
        store.bumpUserVersion(actor.organizationId(),userId);
        store.revokeUserSessions(actor.organizationId(), userId);
        audit(actor, "IAM_FORCE_LOGOUT", "USER", userId);
    }

    public List<RoleView> roles(Actor actor) {
        requireAny(actor, "system.role.view", "system.permissions.view");
        return store.roles(actor.organizationId()).stream().map(role ->
                new RoleView(role.id(), role.code(), role.name(), role.builtin(), role.enabled(), role.policyVersion())).toList();
    }

    public PermissionModelView permissionModel(Actor actor) {
        requireAny(actor, "system.permissions.view", "system.permission.manage");
        var effective = effective(actor);
        var assignedRoles = store.userRoles(actor.organizationId(), actor.userId());
        var ownRoleIds = assignedRoles == null || assignedRoles.isEmpty()
                ? store.user(actor.userId()).map(IamStore.User::roleId).stream().filter(Objects::nonNull).toList()
                : assignedRoles.stream().map(IamStore.Role::id).toList();
        var assignedLevel = store.userPermissionLevel(actor.organizationId(), actor.userId());
        var ownLevelId = assignedLevel == null ? null : assignedLevel.orElse(null);
        var menus = PermissionModelCatalog.menus().stream()
                .filter(menu -> effective == null || effective.unrestricted() || effective.menuKeys().contains(menu.key()))
                .map(menu -> new MenuView(menu.key(), menu.label(), menu.parent(), menu.route())).toList();
        var levels = store.permissionLevels(actor.organizationId()).stream().map(this::levelView).toList();
        var definitions = store.permissionDefinitions().stream()
                .filter(definition -> effective == null || effective.unrestricted()
                        || permitted(effective, definition.code()))
                .toList();
        var scopes = effective == null ? Map.<String, DelegableScope>of() : effective.bindings().entrySet().stream()
                .filter(entry -> "ALLOW".equals(entry.getValue().effect()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> new DelegableScope(entry.getValue().scopeType(), entry.getValue().targetIds())));
        return new PermissionModelView(menus, levels, definitions, scopes,
                effective == null || effective.unrestricted(), ownRoleIds, ownLevelId);
    }

    private EffectivePermissionResolver.EffectivePermissions effective(Actor actor) {
        return effectivePermissions == null ? null : effectivePermissions.resolve(actor.organizationId(), actor.userId());
    }

    private boolean permitted(EffectivePermissionResolver.EffectivePermissions effective, String code) {
        var binding = effective.bindings().get(code);
        return binding != null && "ALLOW".equals(binding.effect());
    }

    public MenuPermissionsView roleMenus(Actor actor, UUID roleId) {
        requireAny(actor, "system.permissions.view", "system.permission.manage");
        var effective = effective(actor);
        var role = role(actor, roleId);
        return new MenuPermissionsView(role.policyVersion(), store.roleMenus(actor.organizationId(), roleId).stream()
                .filter(key -> effective == null || effective.unrestricted() || effective.menuKeys().contains(key)).toList());
    }

    @Transactional
    public long saveRoleMenus(Actor actor, UUID roleId, long expectedVersion, List<String> menuKeys) {
        requireAny(actor, "system.permissions.update", "system.permission.manage");
        requireNotOwnRole(actor, roleId);
        var role = role(actor, roleId);
        if (role.builtin() && "SYSTEM_ADMIN".equals(role.code())) {
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "系统管理员菜单不可修改");
        }
        var valid = PermissionModelCatalog.allMenuKeys();
        if (menuKeys == null || menuKeys.stream().anyMatch(key -> key == null || !valid.contains(key))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "包含未知菜单权限");
        }
        var effective = effective(actor);
        if (effective != null && !effective.unrestricted()) {
            if (!effective.menuKeys().containsAll(menuKeys))
                throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能分配自己未拥有的功能权限");
            var merged = new ArrayList<>(menuKeys);
            store.roleMenus(actor.organizationId(), roleId).stream()
                    .filter(key -> !effective.menuKeys().contains(key)).forEach(merged::add);
            menuKeys = merged;
        }
        try { store.replaceRoleMenus(actor.organizationId(), roleId, expectedVersion, menuKeys); }
        catch (IamStore.PolicyVersionConflict conflict) { throw new ApiException(ApiErrorCode.POLICY_VERSION_CONFLICT); }
        audit(actor, "IAM_ROLE_MENUS_UPDATED", "ROLE", roleId);
        return store.roleVersion(actor.organizationId(), roleId);
    }

    public List<PermissionLevelView> permissionLevels(Actor actor) {
        requireAny(actor, "system.permissions.view", "system.permission.manage");
        return store.permissionLevels(actor.organizationId()).stream().map(this::levelView).toList();
    }

    @Transactional
    public PermissionLevelView createPermissionLevel(Actor actor, String code, String name, String description, int tier) {
        requireAny(actor, "system.permissions.create", "system.permission.manage");
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,79}")) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "权限等级编码必须使用大写字母、数字和下划线");
        }
        if (store.permissionLevels(actor.organizationId()).stream().anyMatch(level -> code.equals(level.code()))) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "权限等级编码已存在");
        }
        var created = store.ensurePermissionLevel(actor.organizationId(), code, name, description, false, Math.max(0, tier));
        audit(actor, "IAM_PERMISSION_LEVEL_CREATED", "PERMISSION_LEVEL", created.id());
        return levelView(created);
    }

    @Transactional
    public void renamePermissionLevel(Actor actor, UUID levelId, String name, String description) {
        requireAny(actor, "system.permissions.update", "system.permission.manage");
        requireNotOwnLevel(actor, levelId);
        var level = store.permissionLevel(actor.organizationId(), levelId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "权限等级不存在"));
        if (level.builtin()) throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "内置权限等级不可重命名");
        store.renamePermissionLevel(actor.organizationId(), levelId, name, description);
        audit(actor, "IAM_PERMISSION_LEVEL_RENAMED", "PERMISSION_LEVEL", levelId);
    }

    @Transactional
    public void deletePermissionLevel(Actor actor, UUID levelId) {
        requireAny(actor, "system.permissions.delete", "system.permission.manage");
        requireNotOwnLevel(actor, levelId);
        var level = store.permissionLevel(actor.organizationId(), levelId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "权限等级不存在"));
        if (level.builtin()) throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "内置权限等级不可删除");
        try { store.deletePermissionLevel(actor.organizationId(), levelId); }
        catch (IllegalArgumentException exception) { throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, exception.getMessage()); }
        audit(actor, "IAM_PERMISSION_LEVEL_DELETED", "PERMISSION_LEVEL", levelId);
    }

    public PermissionLevelView permissionLevel(Actor actor, UUID levelId) {
        requireAny(actor, "system.permissions.view", "system.permission.manage");
        return levelView(store.permissionLevel(actor.organizationId(), levelId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "权限等级不存在")));
    }

    public PermissionLevelPayload permissionLevelBindings(Actor actor, UUID levelId) {
        requireAny(actor, "system.permissions.view", "system.permission.manage");
        var effective = effective(actor);
        var level = store.permissionLevel(actor.organizationId(), levelId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "权限等级不存在"));
        return new PermissionLevelPayload(level.policyVersion(), store.permissionLevelBindings(actor.organizationId(), levelId).stream()
                .filter(binding -> effective == null || effective.unrestricted() || permitted(effective, binding.permissionCode()))
                .map(binding -> {
                    if (effective == null || effective.unrestricted()) return binding;
                    var limited = limitScope(effective.bindings().get(binding.permissionCode()), binding.scopeType(), binding.targetIds());
                    return new PermissionLevelBinding(binding.menuKey(), binding.permissionCode(), binding.effect(), limited.scopeType(), limited.targetIds());
                })
                .toList());
    }

    @Transactional
    public long savePermissionLevelBindings(Actor actor, UUID levelId, long expectedVersion, List<PermissionLevelBinding> bindings) {
        requireAny(actor, "system.permissions.update", "system.permission.manage");
        requireNotOwnLevel(actor, levelId);
        var level = store.permissionLevel(actor.organizationId(), levelId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "权限等级不存在"));
        if (level.builtin() && "L3_ADMIN".equals(level.code())) {
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "L3 管理级不可直接修改");
        }
        validateLevelBindings(bindings);
        var effective = effective(actor);
        if (effective != null && !effective.unrestricted()) {
            for (var binding : bindings) {
                var owned = effective.bindings().get(binding.permissionCode());
                if (owned == null || !"ALLOW".equals(owned.effect())
                        || !withinScope(owned, new Binding(binding.permissionCode(), "ALLOW", binding.scopeType(), binding.targetIds())))
                    throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能分配超出自身权限的操作或数据范围");
            }
            var merged = new ArrayList<>(bindings);
            store.permissionLevelBindings(actor.organizationId(), levelId).stream()
                    .filter(item -> !permitted(effective, item.permissionCode())).forEach(merged::add);
            bindings = merged;
        }
        try { store.replacePermissionLevelBindings(actor.organizationId(), levelId, expectedVersion, bindings); }
        catch (IamStore.PolicyVersionConflict conflict) { throw new ApiException(ApiErrorCode.POLICY_VERSION_CONFLICT); }
        audit(actor, "IAM_PERMISSION_LEVEL_UPDATED", "PERMISSION_LEVEL", levelId);
        return store.permissionLevelVersion(actor.organizationId(), levelId);
    }

    public UserModelView userModel(Actor actor, UUID userId) {
        requireAny(actor, "system.permissions.view", "system.permission.manage");
        requireUserInScope(actor, userId, "system.permissions.view");
        var user = requireUser(actor, userId);
        var roles = store.userRoles(actor.organizationId(), userId);
        if ((roles == null || roles.isEmpty()) && user.roleId() != null) {
            roles = store.role(actor.organizationId(), user.roleId()).map(List::of).orElseGet(List::of);
        }
        var levelRef = store.userPermissionLevel(actor.organizationId(), userId);
        var levelId = levelRef == null ? null : levelRef.orElse(null);
        var level = levelId == null ? null : store.permissionLevel(actor.organizationId(), levelId).orElse(null);
        var effective = effective(actor);
        return new UserModelView(user.id(), roles == null ? List.of() : roles.stream().map(this::roleView).toList(),
                level == null ? null : levelView(level), store.userMenuOverrides(actor.organizationId(), userId).stream()
                    .filter(item -> effective == null || effective.unrestricted() || effective.menuKeys().contains(item.menuKey())).toList(),
                store.userOperationOverrides(actor.organizationId(), userId).stream()
                    .filter(item -> effective == null || effective.unrestricted() || permitted(effective, item.permissionCode()))
                    .map(item -> limitBinding(effective, item)).toList(),
                store.userScopeOverrides(actor.organizationId(), userId).stream()
                    .filter(item -> effective == null || effective.unrestricted() || permitted(effective, item.permissionCode()))
                    .map(item -> limitBinding(effective, item)).toList(),
                user.authVersion());
    }

    @Transactional
    public long saveUserModel(Actor actor, UUID userId, SaveUserModelCommand command) {
        requireAny(actor, "system.permissions.update", "system.permission.manage");
        requireNotSelf(actor, userId);
        requireUserInScope(actor, userId, "system.permissions.view");
        var user = requireUser(actor, userId);
        if (command.expectedVersion() != user.authVersion()) {
            throw new ApiException(ApiErrorCode.POLICY_VERSION_CONFLICT);
        }
        var roleIds = command.roleIds() == null ? List.<UUID>of() : command.roleIds().stream().filter(Objects::nonNull).distinct().toList();
        if (command.primaryRoleId() != null && !roleIds.contains(command.primaryRoleId())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "主角色必须属于已选择的业务角色");
        }
        for (var roleId : roleIds) role(actor, roleId);
        if (command.permissionLevelId() != null) {
            store.permissionLevel(actor.organizationId(), command.permissionLevelId())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "权限等级不存在"));
        }
        var systemAdmin = roleIds.stream().map(id -> role(actor, id)).anyMatch(role -> "SYSTEM_ADMIN".equals(role.code()));
        if (systemAdmin && !roleIds.contains(user.roleId())) {
            // A system administrator is never left without the legacy primary
            // pointer because old session/bootstrap checks still read it.
            command = new SaveUserModelCommand(roleIds, roleIds.get(0), command.permissionLevelId(), command.menus(),
                    command.operations(), command.scopes(), command.expectedVersion());
        }
        validateModelOverrides(command.menus(), command.operations(), command.scopes());
        var effective = effective(actor);
        if (effective != null && !effective.unrestricted()) {
            var assignedRoles = store.userRoles(actor.organizationId(), userId);
            var currentRoles = (assignedRoles == null ? List.<IamStore.Role>of() : assignedRoles).stream()
                    .map(IamStore.Role::id).collect(java.util.stream.Collectors.toSet());
            if (currentRoles.isEmpty() && user.roleId() != null) currentRoles = Set.of(user.roleId());
            var levelRef = store.userPermissionLevel(actor.organizationId(), userId);
            var currentLevel = levelRef == null ? null : levelRef.orElse(null);
            for (var roleId : roleIds) {
                if (!currentRoles.contains(roleId)) validateAssignableRole(actor, effective, roleId);
            }
            if (command.permissionLevelId() != null && !Objects.equals(currentLevel, command.permissionLevelId()))
                validateAssignableLevel(actor, effective, command.permissionLevelId());
            validateDelegatedModel(effective, command);
            var visibleMenus = new ArrayList<>(command.menus() == null ? List.<IamStore.MenuOverride>of() : command.menus());
            store.userMenuOverrides(actor.organizationId(), userId).stream()
                    .filter(item -> !effective.menuKeys().contains(item.menuKey())).forEach(visibleMenus::add);
            var visibleOperations = new ArrayList<>(command.operations() == null ? List.<Binding>of() : command.operations());
            store.userOperationOverrides(actor.organizationId(), userId).stream()
                    .filter(item -> !permitted(effective, item.permissionCode())).forEach(visibleOperations::add);
            var visibleScopes = new ArrayList<>(command.scopes() == null ? List.<Binding>of() : command.scopes());
            store.userScopeOverrides(actor.organizationId(), userId).stream()
                    .filter(item -> !permitted(effective, item.permissionCode())).forEach(visibleScopes::add);
            command = new SaveUserModelCommand(roleIds, command.primaryRoleId(), command.permissionLevelId(),
                    visibleMenus, visibleOperations, visibleScopes, command.expectedVersion());
        }
        try {
            store.saveUserPermissionLevel(actor.organizationId(), userId, user.authVersion(), command.permissionLevelId());
            store.replaceUserRoles(actor.organizationId(), userId, roleIds, command.primaryRoleId());
            store.replaceUserModelOverrides(actor.organizationId(), userId, command.menus(), command.operations(), command.scopes());
        } catch (IamStore.PolicyVersionConflict conflict) {
            throw new ApiException(ApiErrorCode.POLICY_VERSION_CONFLICT);
        }
        audit(actor, "IAM_USER_PERMISSION_MODEL_UPDATED", "USER", userId);
        return store.user(userId).orElseThrow().authVersion();
    }

    private void validateAssignableRole(Actor actor, EffectivePermissionResolver.EffectivePermissions effective, UUID roleId) {
        var selectedRole = role(actor, roleId);
        if ("SYSTEM_ADMIN".equals(selectedRole.code())
                || !effective.menuKeys().containsAll(store.roleMenus(actor.organizationId(), roleId)))
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能分配超出自身权限的业务角色");
        for (var binding : store.roleBindings(actor.organizationId(), roleId))
            if ("ALLOW".equals(binding.effect())) validateDelegatedBinding(effective, binding);
    }

    private void validateAssignableLevel(Actor actor, EffectivePermissionResolver.EffectivePermissions effective, UUID levelId) {
        for (var binding : store.permissionLevelBindings(actor.organizationId(), levelId))
            validateDelegatedBinding(effective, new Binding(binding.permissionCode(), binding.effect(),
                    binding.scopeType(), binding.targetIds()));
    }

    private void validateDelegatedBinding(EffectivePermissionResolver.EffectivePermissions effective, Binding binding) {
        var owned = effective.bindings().get(binding.permissionCode());
        if (owned == null || !"ALLOW".equals(owned.effect()) || !withinScope(owned, binding))
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能分配超出自身权限的操作或数据范围");
    }

    private void validateDelegatedModel(EffectivePermissionResolver.EffectivePermissions effective,
                                        SaveUserModelCommand command) {
        for (var menu : command.menus() == null ? List.<IamStore.MenuOverride>of() : command.menus()) {
            if (!effective.menuKeys().contains(menu.menuKey()))
                throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能分配自己未拥有的功能权限");
        }
        for (var operation : command.operations() == null ? List.<Binding>of() : command.operations()) {
            if (!permitted(effective, operation.permissionCode()))
                throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能分配自己未拥有的操作权限");
            if ("ALLOW".equals(operation.effect())
                    && !withinScope(effective.bindings().get(operation.permissionCode()), operation))
                throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能分配超出自身权限的数据范围");
        }
        for (var scope : command.scopes() == null ? List.<Binding>of() : command.scopes()) {
            var owned = effective.bindings().get(scope.permissionCode());
            if (owned == null || !"ALLOW".equals(owned.effect()) || !withinScope(owned, scope))
                throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能分配超出自身权限的数据范围");
        }
    }

    private Binding limitBinding(EffectivePermissionResolver.EffectivePermissions effective, Binding item) {
        if (effective == null || effective.unrestricted()) return item;
        var limited = limitScope(effective.bindings().get(item.permissionCode()), item.scopeType(), item.targetIds());
        return new Binding(item.menuKey(), item.permissionCode(), item.effect(), limited.scopeType(), limited.targetIds());
    }

    private DelegableScope limitScope(Binding owned, String scopeType, List<UUID> targetIds) {
        var requested = new Binding("", "ALLOW", scopeType, targetIds == null ? List.of() : targetIds);
        if (withinScope(owned, requested)) return new DelegableScope(scopeType, requested.targetIds());
        if ("SELECTED".equals(owned.scopeType()) && "SELECTED".equals(scopeType))
            return new DelegableScope("SELECTED", requested.targetIds().stream()
                    .filter(owned.targetIds()::contains).toList());
        return new DelegableScope(owned.scopeType(), owned.targetIds());
    }

    private boolean withinScope(Binding owned, Binding requested) {
        var ownerType = owned.scopeType();
        var requestedType = requested.scopeType();
        if (Set.of("ALL", "ORG").contains(ownerType)) return true;
        if (Set.of("SELF", "CREATOR").contains(ownerType))
            return Set.of("SELF", "CREATOR").contains(requestedType);
        if ("SELECTED".equals(ownerType) && "SELECTED".equals(requestedType))
            return owned.targetIds().containsAll(requested.targetIds());
        return ownerType.equals(requestedType);
    }

    @Transactional
    public RoleView createRole(Actor actor, String code, String name) {
        store.lockAdministration(actor.organizationId());

        requireAny(actor, "system.permissions.create", "system.role.create");
        if (!code.matches("[A-Z][A-Z0-9_]{2,79}")) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "角色编码必须使用大写字母、数字和下划线");
        var role = store.roleByCode(actor.organizationId(), code);
        if (role.isPresent()) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "角色编码已存在");
        var created = store.ensureRole(actor.organizationId(), code, name, false);
        audit(actor, "IAM_ROLE_CREATED", "ROLE", created.id());
        return new RoleView(created.id(), created.code(), created.name(), created.builtin(), created.enabled(), created.policyVersion());
    }

    @Transactional
    public void renameRole(Actor actor, UUID roleId, String name,long expectedVersion) {
        store.lockAdministration(actor.organizationId());
        requireRoleVersion(actor,roleId,expectedVersion);
        requireAny(actor, "system.permissions.update", "system.role.manage");
        requireNotOwnRole(actor, roleId);
        var role = role(actor, roleId);
        if (role.builtin()) throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "内置角色不可重命名");
        store.renameRole(actor.organizationId(), roleId, name);
        audit(actor, "IAM_ROLE_RENAMED", "ROLE", roleId);
    }

    @Transactional
    public void deleteRole(Actor actor, UUID roleId,long expectedVersion) {
        store.lockAdministration(actor.organizationId());
        requireRoleVersion(actor,roleId,expectedVersion);
        requireAny(actor, "system.permissions.delete", "system.role.manage");
        requireNotOwnRole(actor, roleId);
        role(actor, roleId);
        try { store.deleteRole(actor.organizationId(), roleId); }
        catch (IllegalArgumentException exception) { throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, exception.getMessage()); }
        audit(actor, "IAM_ROLE_DELETED", "ROLE", roleId);
    }

    public List<Binding> rolePermissions(Actor actor, UUID roleId) {
        requireAny(actor, "system.permissions.view", "system.permission.manage");
        role(actor, roleId);
        var effective = effective(actor);
        return store.roleBindings(actor.organizationId(), roleId).stream()
                .filter(item -> effective == null || effective.unrestricted() || permitted(effective, item.permissionCode()))
                .map(item -> limitBinding(effective, item)).toList();
    }

    @Transactional
    public long saveRolePermissions(Actor actor, UUID roleId, long expectedVersion, List<Binding> bindings) {
        store.lockAdministration(actor.organizationId());
        requireRoleVersion(actor,roleId,expectedVersion);
        requireAny(actor, "system.permissions.update", "system.permission.manage");
        requireNotOwnRole(actor, roleId);
        var role = role(actor, roleId);
        validateBindings(bindings);
        var effective = effective(actor);
        if (effective != null && !effective.unrestricted()) {
            for (var binding : bindings)
                if ("ALLOW".equals(binding.effect())) validateDelegatedBinding(effective, binding);
            var merged = new ArrayList<>(bindings);
            store.roleBindings(actor.organizationId(), roleId).stream()
                    .filter(item -> !permitted(effective, item.permissionCode())).forEach(merged::add);
            bindings = merged;
        }
        try { store.replaceRoleBindings(actor.organizationId(), roleId, expectedVersion, bindings); }
        catch (IamStore.PolicyVersionConflict conflict) { throw new ApiException(ApiErrorCode.POLICY_VERSION_CONFLICT); }
        audit(actor, "IAM_ROLE_PERMISSIONS_UPDATED", "ROLE", roleId);
        return store.roleVersion(actor.organizationId(), role.id());
    }

    public PermissionOverrides userPermissions(Actor actor, UUID userId) {
        requireAny(actor, "system.permissions.view", "system.permission.manage");
        requireUserInScope(actor, userId, "system.permissions.view");
        var user = requireUser(actor, userId);
        var effective = effective(actor);
        return new PermissionOverrides(user.authVersion(), store.userOverrides(actor.organizationId(), userId).stream()
                .filter(item -> effective == null || effective.unrestricted() || permitted(effective, item.permissionCode()))
                .map(item -> limitBinding(effective, item)).toList());
    }

    @Transactional
    public long saveUserPermissions(Actor actor, UUID userId, long expectedVersion, List<Binding> bindings) {
        store.lockAdministration(actor.organizationId());
        requireUserVersion(actor,userId,expectedVersion);
        requireAny(actor, "system.permissions.update", "system.permission.manage");
        requireNotSelf(actor, userId);
        requireUserInScope(actor, userId, "system.permissions.view");
        requireUser(actor, userId);
        validateBindings(bindings);
        var effective = effective(actor);
        if (effective != null && !effective.unrestricted()) {
            for (var binding : bindings)
                if ("ALLOW".equals(binding.effect())) validateDelegatedBinding(effective, binding);
            var merged = new ArrayList<>(bindings);
            store.userOverrides(actor.organizationId(), userId).stream()
                    .filter(item -> !permitted(effective, item.permissionCode())).forEach(merged::add);
            bindings = merged;
        }
        try { store.replaceUserOverrides(actor.organizationId(), userId, expectedVersion, bindings); }
        catch (IamStore.PolicyVersionConflict conflict) { throw new ApiException(ApiErrorCode.POLICY_VERSION_CONFLICT); }
        audit(actor, "IAM_USER_PERMISSIONS_UPDATED", "USER", userId);
        return store.user(userId).orElseThrow().authVersion();
    }

    @Transactional
    public void deleteUserOverride(Actor actor, UUID userId, String permissionCode,long expectedVersion) {
        store.lockAdministration(actor.organizationId());
        requireUserVersion(actor,userId,expectedVersion);
        requireAny(actor, "system.permissions.update", "system.permission.manage");
        requireNotSelf(actor, userId);
        requireUserInScope(actor, userId, "system.permissions.view");
        var effective = effective(actor);
        if (effective != null && !effective.unrestricted() && !permitted(effective, permissionCode))
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能删除自己未拥有的权限配置");
        requireUser(actor, userId);
        store.deleteUserOverride(actor.organizationId(), userId, permissionCode);
        audit(actor, "IAM_USER_PERMISSION_RESTORED", "USER", userId);
    }

    public List<PermissionDefinition> permissionDefinitions(Actor actor) {
        requireAny(actor, "system.permissions.view", "system.permission.manage");
        var effective = effective(actor);
        return store.permissionDefinitions().stream()
                .filter(item -> effective == null || effective.unrestricted() || permitted(effective, item.code())).toList();
    }

    public PageResponse<AuditLogPresentationService.AuditLogView> presentedAuditLogs(
            Actor actor, AuditLogPresentationService.AuditLogQuery query) {
        require(actor, "system.audit.view");
        return auditPresentation.page(actor.organizationId(), query);
    }

    private void require(Actor actor, String permission) {
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), permission, "IAM", null, null));
    }

    private void requireAny(Actor actor, String first, String second) {
        if (authorization.check(new PermissionCheck(actor.organizationId(), actor.userId(), first, "IAM", null, null)).allowed()) return;
        require(actor, second);
    }

    private IamStore.User requireUser(Actor actor, UUID userId) {
        return store.user(userId).filter(user -> actor.organizationId().equals(user.organizationId()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "用户不存在"));
    }

    private void requireNotSelf(Actor actor, UUID userId) {
        if (actor.userId().equals(userId))
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能修改自己的权限");
    }

    private void requireNotOwnRole(Actor actor, UUID roleId) {
        var assigned = store.userRoles(actor.organizationId(), actor.userId());
        if (assigned != null && assigned.stream().anyMatch(role -> role.id().equals(roleId)))
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能修改自己使用的业务角色");
        if ((assigned == null || assigned.isEmpty()) && store.user(actor.userId())
                .map(IamStore.User::roleId).filter(roleId::equals).isPresent())
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能修改自己使用的业务角色");
    }

    private void requireNotOwnLevel(Actor actor, UUID levelId) {
        var assigned = store.userPermissionLevel(actor.organizationId(), actor.userId());
        if (assigned != null && assigned.filter(levelId::equals).isPresent())
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "不能修改自己使用的权限等级");
    }

    private IamStore.Role role(Actor actor, UUID roleId) {
        return store.role(actor.organizationId(), roleId).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "角色不存在"));
    }

    private void validateBindings(List<Binding> bindings) {
        var known = store.permissionDefinitions().stream().map(PermissionDefinition::code).collect(java.util.stream.Collectors.toSet());
        for (var binding : bindings) {
            if (!known.contains(binding.permissionCode())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "未知权限编码：" + binding.permissionCode());
            if (!List.of("ALLOW", "DENY").contains(binding.effect())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "权限效果无效");
            if (!List.of("ALL", "ORG", "SELF", "DEPARTMENT", "ASSIGNED", "PROJECT", "CATEGORY", "SELECTED").contains(binding.scopeType())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据范围无效");
            }
            if ("DENY".equals(binding.effect()) && binding.targetIds() != null && !binding.targetIds().isEmpty()) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "DENY 不允许配置数据目标");
            }
            if ("ALLOW".equals(binding.effect())
                    && List.of("SELECTED", "PROJECT", "CATEGORY").contains(binding.scopeType())
                    && (binding.targetIds() == null || binding.targetIds().isEmpty())
                    && !usesProjectListTargets(binding.permissionCode())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, binding.scopeType() + " 范围必须选择目标");
            }
        }
    }

    private void validateLevelBindings(List<PermissionLevelBinding> bindings) {
        var known = store.permissionDefinitions().stream().map(PermissionDefinition::code).collect(java.util.stream.Collectors.toSet());
        for (var binding : bindings == null ? List.<PermissionLevelBinding>of() : bindings) {
            if (binding == null || !known.contains(binding.permissionCode())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "未知权限编码：" + (binding == null ? "null" : binding.permissionCode()));
            if (binding.menuKey() != null && !PermissionModelCatalog.allMenuKeys().contains(binding.menuKey()))
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "未知功能/区域：" + binding.menuKey());
            if (!"ALLOW".equals(binding.effect())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "权限等级不支持拒绝规则");
            if (binding.scopeType() == null || binding.scopeType().isBlank()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据范围不能为空");
            if (List.of("SELECTED", "PROJECT", "CATEGORY").contains(binding.scopeType())
                    && (binding.targetIds() == null || binding.targetIds().isEmpty())
                    && !usesProjectListTargets(binding.permissionCode())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "指定范围必须选择目标");
            }
        }
    }

    private void validateModelOverrides(List<IamStore.MenuOverride> menus, List<Binding> operations, List<Binding> scopes) {
        var known = store.permissionDefinitions().stream().map(PermissionDefinition::code).collect(java.util.stream.Collectors.toSet());
        var knownMenus = PermissionModelCatalog.allMenuKeys();
        for (var menu : menus == null ? List.<IamStore.MenuOverride>of() : menus) {
            if (menu == null || !knownMenus.contains(menu.menuKey()) || !List.of("ALLOW", "DENY").contains(menu.effect()))
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "个人菜单覆盖无效");
        }
        for (var binding : operations == null ? List.<Binding>of() : operations) {
            if (!known.contains(binding.permissionCode()) || !List.of("ALLOW", "DENY").contains(binding.effect()))
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "个人操作覆盖无效");
        }
        for (var binding : scopes == null ? List.<Binding>of() : scopes) {
            if (!known.contains(binding.permissionCode()) || !"ALLOW".equals(binding.effect())
                    || binding.scopeType() == null || binding.scopeType().isBlank())
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "个人数据范围覆盖无效");
            if ("SELECTED".equals(binding.scopeType()) && (binding.targetIds() == null || binding.targetIds().isEmpty())
                    && !usesProjectListTargets(binding.permissionCode()))
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "指定对象范围必须选择目标");
        }
    }

    private boolean usesProjectListTargets(String permissionCode) {
        return java.util.stream.Stream.of("eln.view.", "research-test.report.view.",
                "quality.view.", "production.view.").anyMatch(permissionCode::startsWith);
    }

    private RoleView roleView(IamStore.Role role) {
        return new RoleView(role.id(), role.code(), role.name(), role.builtin(), role.enabled(), role.policyVersion());
    }

    private PermissionLevelView levelView(IamStore.PermissionLevel level) {
        return new PermissionLevelView(level.id(), level.code(), level.name(), level.description(), level.builtin(),
                level.enabled(), level.tier(), level.policyVersion());
    }

    private UserView userView(IamStore.User user) {
        return new UserView(user.id(), user.username(), user.displayName(), user.email(), user.phone(), user.departmentName(),
                user.roleId(), user.roleCode(), user.roleName(), user.status(), user.lastLoginAt(), user.authVersion());
    }

    private void requireUserVersion(Actor actor,UUID id,long expected){
        store.lockUser(actor.organizationId(),id);
        if(expected<0||requireUser(actor,id).authVersion()!=expected)throw new ApiException(ApiErrorCode.POLICY_VERSION_CONFLICT);
    }
    private void requireRoleVersion(Actor actor,UUID id,long expected){
        store.lockRole(actor.organizationId(),id);
        if(expected<0||role(actor,id).policyVersion()!=expected)throw new ApiException(ApiErrorCode.POLICY_VERSION_CONFLICT);
    }

    private void audit(Actor actor, String action, String type, UUID id) {
        audit.append(actor.organizationId(), actor.userId(), action, type, id, JsonNodeFactory.instance.objectNode().put("source", "iam"));
    }

    public record CreateUserCommand(String username, String displayName, String email, String phone, String departmentName,
                                    UUID roleId, String password) { }
    public record UpdateUserCommand(long expectedVersion,String displayName, String email, String phone, String departmentName, UUID roleId) { }
    public record UserView(UUID id, String username, String displayName, String email, String phone, String departmentName,
                           UUID roleId, String roleCode, String roleName, String status, Instant lastLoginAt,
                           long authVersion) { }
    public record CreateUserResult(UserView user) { }
    public record RoleView(UUID id, String code, String name, boolean builtin, boolean enabled, long policyVersion) { }
    public record PermissionOverrides(long version, List<Binding> bindings) { }
    public record PermissionPayload(long version, List<Binding> bindings) { }
    public record PermissionLevelPayload(long version, List<PermissionLevelBinding> bindings) { }
    public record MenuView(String key, String label, String parent, String route) { }
    public record DelegableScope(String scopeType, List<UUID> targetIds) { }
    public record PermissionModelView(List<MenuView> menus, List<PermissionLevelView> levels,
                                      List<PermissionDefinition> operations,
                                      Map<String, DelegableScope> delegableScopes, boolean unrestricted,
                                      List<UUID> ownRoleIds, UUID ownLevelId) { }
    public record MenuPermissionsView(long version, List<String> menuKeys) { }
    public record PermissionLevelView(UUID id, String code, String name, String description, boolean builtin,
                                      boolean enabled, int tier, long policyVersion) { }
    public record UserModelView(UUID userId, List<RoleView> roles, PermissionLevelView level,
                                List<IamStore.MenuOverride> menuOverrides, List<Binding> operationOverrides,
                                List<Binding> scopeOverrides, long authVersion) { }
    public record SaveUserModelCommand(List<UUID> roleIds, UUID primaryRoleId, UUID permissionLevelId,
                                       List<IamStore.MenuOverride> menus, List<Binding> operations,
                                       List<Binding> scopes, long expectedVersion) { }
}
