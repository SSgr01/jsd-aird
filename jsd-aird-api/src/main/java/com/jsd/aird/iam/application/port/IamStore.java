package com.jsd.aird.iam.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.iam.api.PermissionDefinitionContributor.PermissionDefinition;

/**
 * Application port for IAM persistence.  The JDBC implementation stays behind
 * the IAM infrastructure adapter, while application services depend only on
 * this contract and its application-facing records.
 */
public interface IamStore {
    void lockAdministration(UUID organizationId);
    void lockUser(UUID organizationId,UUID userId);
    void lockRole(UUID organizationId,UUID roleId);
    void bumpUserVersion(UUID organizationId,UUID userId);


    Optional<Organization> defaultOrganization();

    Organization organizationById(UUID id);

    Organization ensureDefaultOrganization(String name);

    Optional<User> user(UUID userId);

    Optional<User> user(UUID organizationId, String username);

    List<User> users(UUID organizationId, String keyword, int page, int size);

    long countUsers(UUID organizationId, String keyword);

    long countUsersByStatus(UUID organizationId, String status);

    void insertUser(UUID id, UUID organizationId, String username, String displayName, String email,
                    String phone, String department, UUID roleId, String passwordHash);

    void updateUser(UUID organizationId, UUID userId, String displayName, String email, String phone,
                    String department, UUID roleId);

    void setStatus(UUID organizationId, UUID userId, String status);

    void resetPassword(UUID organizationId, UUID userId, String passwordHash);

    void changePassword(UUID organizationId, UUID userId, String passwordHash);

    void markLoginSuccess(UUID organizationId, UUID userId);

    void markLoginFailure(UUID organizationId, UUID userId, boolean locked);

    boolean hasSystemAdmin(UUID organizationId);

    long activeSystemAdmins(UUID organizationId);

    Optional<Role> role(UUID organizationId, UUID roleId);

    Optional<Role> roleByCode(UUID organizationId, String code);

    List<Role> roles(UUID organizationId);

    Role ensureRole(UUID organizationId, String code, String name, boolean builtin);

    void renameRole(UUID organizationId, UUID roleId, String name);

    void deleteRole(UUID organizationId, UUID roleId);

    List<PermissionDefinition> permissionDefinitions();

    void ensurePermission(PermissionDefinition definition);

    List<Binding> roleBindings(UUID organizationId, UUID roleId);

    List<Binding> userOverrides(UUID organizationId, UUID userId);

    void replaceRoleBindings(UUID organizationId, UUID roleId, long expectedVersion, List<Binding> bindings);

    void ensureRoleBinding(UUID organizationId, UUID roleId, Binding binding);

    long roleVersion(UUID organizationId, UUID roleId);

    void replaceUserOverrides(UUID organizationId, UUID userId, long expectedVersion, List<Binding> bindings);

    void deleteUserOverride(UUID organizationId, UUID userId, String permissionCode);

    Optional<Session> session(String token);

    /**
     * Looks up only a session whose user is still active and whose auth
     * version matches the session snapshot.  This keeps request authentication
     * to one database round trip without weakening revocation semantics.
     */
    Optional<Session> activeSession(String token);

    Session createSession(UUID organizationId, UUID userId, long authVersion, String tokenHash,
                          Instant expiresAt, Instant absoluteExpiresAt, String ip, String userAgent,
                          boolean rememberMe);

    void touchSession(UUID sessionId, Instant expiresAt);

    void revokeSession(UUID sessionId);

    List<SessionSummary> sessions(UUID organizationId, UUID userId);

    void revokeUserSessions(UUID organizationId, UUID userId);

    long recentFailures(UUID organizationId, String username, Instant since);

    long recentIpFailures(String ip, Instant since);

    void attempt(UUID organizationId, String username, String ip, boolean succeeded);

    void updateRole(UUID organizationId, UUID userId, UUID roleId);

    /** Permission model v2: business-role menu bindings and user assignments. */
    List<Role> userRoles(UUID organizationId, UUID userId);

    void replaceUserRoles(UUID organizationId, UUID userId, List<UUID> roleIds, UUID primaryRoleId);

    List<String> roleMenus(UUID organizationId, UUID roleId);

    void replaceRoleMenus(UUID organizationId, UUID roleId, long expectedVersion, List<String> menuKeys);

    List<PermissionLevel> permissionLevels(UUID organizationId);

    Optional<PermissionLevel> permissionLevel(UUID organizationId, UUID levelId);

    PermissionLevel ensurePermissionLevel(UUID organizationId, String code, String name, String description,
                                          boolean builtin, int tier);

    void renamePermissionLevel(UUID organizationId, UUID levelId, String name, String description);

    void deletePermissionLevel(UUID organizationId, UUID levelId);

    List<PermissionLevelBinding> permissionLevelBindings(UUID organizationId, UUID levelId);

    void replacePermissionLevelBindings(UUID organizationId, UUID levelId, long expectedVersion,
                                        List<PermissionLevelBinding> bindings);

    long permissionLevelVersion(UUID organizationId, UUID levelId);

    Optional<UUID> userPermissionLevel(UUID organizationId, UUID userId);

    void saveUserPermissionLevel(UUID organizationId, UUID userId, long expectedVersion, UUID levelId);

    List<MenuOverride> userMenuOverrides(UUID organizationId, UUID userId);

    void replaceUserMenuOverrides(UUID organizationId, UUID userId, List<MenuOverride> overrides);

    List<Binding> userOperationOverrides(UUID organizationId, UUID userId);

    List<Binding> userScopeOverrides(UUID organizationId, UUID userId);

    void replaceUserModelOverrides(UUID organizationId, UUID userId, List<MenuOverride> menus,
                                   List<Binding> operations, List<Binding> scopes);

    record Organization(UUID id, String name) { }

    record User(UUID id, UUID organizationId, String username, String displayName, String email, String phone,
                String departmentName, UUID roleId, String roleCode, String roleName, String status,
                boolean dictionaryAdmin, String passwordHash, long authVersion, Instant lastLoginAt,
                int failedLoginCount, Instant lockedUntil) { }

    record Role(UUID id, UUID organizationId, String code, String name, boolean builtin, boolean enabled,
                long policyVersion) { }

    record Binding(String menuKey, String permissionCode, String effect, String scopeType, List<UUID> targetIds) {
        public Binding(String permissionCode, String effect, String scopeType, List<UUID> targetIds) {
            this(null, permissionCode, effect, scopeType, targetIds);
        }
    }

    /**
     * A permission-level operation is scoped to the prototype row where it is
     * configured. A null menuKey is retained for legacy/global bindings.
     */
    record PermissionLevelBinding(String menuKey, String permissionCode, String effect,
                                  String scopeType, List<UUID> targetIds) { }

    record MenuOverride(String menuKey, String effect) { }

    record PermissionLevel(UUID id, UUID organizationId, String code, String name, String description,
                           boolean builtin, boolean enabled, int tier, long policyVersion) { }

    record Session(UUID id, UUID organizationId, UUID userId, long authVersion, Instant issuedAt, Instant lastSeenAt,
                   Instant expiresAt, Instant absoluteExpiresAt, String username, String status) { }

    record SessionSummary(UUID id, Instant issuedAt, Instant lastSeenAt, Instant expiresAt,
                          String ipAddress, String userAgent, Instant revokedAt) { }

    class PolicyVersionConflict extends RuntimeException { }
}
