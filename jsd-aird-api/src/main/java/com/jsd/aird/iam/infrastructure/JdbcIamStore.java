package com.jsd.aird.iam.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import com.jsd.aird.iam.application.port.IamStore.Organization;
import com.jsd.aird.iam.application.port.IamStore.Role;
import com.jsd.aird.iam.application.port.IamStore.Session;
import com.jsd.aird.iam.application.port.IamStore.SessionSummary;
import com.jsd.aird.iam.application.port.IamStore.User;
import com.jsd.aird.iam.api.PermissionDefinitionContributor.PermissionDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIamStore implements IamStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcIamStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<Organization> defaultOrganization() {
        return jdbc.query("SELECT id, name FROM iam.organization ORDER BY id LIMIT 1",
                        (rs, row) -> organization(rs, row))
                .stream().findFirst();
    }

    public Organization organizationById(UUID id) {
        return jdbc.query("SELECT id, name FROM iam.organization WHERE id = ?", (rs, row) -> organization(rs, row), id)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("组织不存在：" + id));
    }

    public Organization ensureDefaultOrganization(String name) {
        var found = defaultOrganization();
        if (found.isPresent()) return found.get();
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO iam.organization (id, name) VALUES (?, ?)", id, name);
        return new Organization(id, name);
    }

    public Optional<User> user(UUID userId) {
        return jdbc.query(userSql("u.id = ?"), (rs, row) -> user(rs, row), userId).stream().findFirst();
    }

    public Optional<User> user(UUID organizationId, String username) {
        return jdbc.query(userSql("u.organization_id = ? AND u.username = ?"), (rs, row) -> user(rs, row), organizationId, username)
                .stream().findFirst();
    }

    public List<User> users(UUID organizationId, String keyword, int page, int size) {
        var like = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        var offset = Math.max(0, (page - 1) * size);
        return jdbc.query(userSql("u.organization_id = ? AND (CAST(? AS text) IS NULL OR u.username ILIKE ? OR u.display_name ILIKE ? OR COALESCE(u.department_name, '') ILIKE ?)")
                        + " ORDER BY u.created_at DESC LIMIT ? OFFSET ?", (rs, row) -> user(rs, row),
                organizationId, like, like, like, like, size, offset);
    }

    public long countUsers(UUID organizationId, String keyword) {
        var like = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.queryForObject("""
                SELECT count(*) FROM iam.app_user u
                WHERE u.organization_id = ?
                  AND (CAST(? AS text) IS NULL OR u.username ILIKE ? OR u.display_name ILIKE ? OR COALESCE(u.department_name, '') ILIKE ?)
                """, Long.class, organizationId, like, like, like, like);
    }

    public long countUsersByStatus(UUID organizationId, String status) {
        return jdbc.queryForObject("SELECT count(*) FROM iam.app_user WHERE organization_id = ? AND status = ?",
                Long.class, organizationId, status);
    }

    public void insertUser(UUID id, UUID organizationId, String username, String displayName, String email,
                           String phone, String department, UUID roleId, String passwordHash) {
        jdbc.update("""
                INSERT INTO iam.app_user (id, organization_id, username, display_name, email, phone,
                    department_name, role_id, password_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, username, displayName, email, phone, department, roleId, passwordHash);
    }

    public void updateUser(UUID organizationId, UUID userId, String displayName, String email, String phone,
                           String department, UUID roleId) {
        jdbc.update("""
                UPDATE iam.app_user
                SET display_name = ?, email = ?, phone = ?, department_name = ?, role_id = ?, auth_version = auth_version + 1
                WHERE organization_id = ? AND id = ?
                """, displayName, email, phone, department, roleId, organizationId, userId);
    }

    public void setStatus(UUID organizationId, UUID userId, String status) {
        jdbc.update("""
                UPDATE iam.app_user SET status = ?, auth_version = auth_version + 1,
                    locked_until = NULL, failed_login_count = 0
                WHERE organization_id = ? AND id = ?
                """, status, organizationId, userId);
        if ("DISABLED".equals(status)) revokeUserSessions(organizationId, userId);
    }

    public void resetPassword(UUID organizationId, UUID userId, String passwordHash) {
        jdbc.update("""
                UPDATE iam.app_user SET password_hash = ?, auth_version = auth_version + 1,
                    failed_login_count = 0, locked_until = NULL
                WHERE organization_id = ? AND id = ?
                """, passwordHash, organizationId, userId);
        revokeUserSessions(organizationId, userId);
    }

    public void changePassword(UUID organizationId, UUID userId, String passwordHash) {
        jdbc.update("""
                UPDATE iam.app_user SET password_hash = ?, auth_version = auth_version + 1,
                    failed_login_count = 0, locked_until = NULL
                WHERE organization_id = ? AND id = ?
                """, passwordHash, organizationId, userId);
    }

    public void markLoginSuccess(UUID organizationId, UUID userId) {
        jdbc.update("""
                UPDATE iam.app_user SET last_login_at = now(), failed_login_count = 0, locked_until = NULL
                WHERE organization_id = ? AND id = ?
                """, organizationId, userId);
    }

    public void markLoginFailure(UUID organizationId, UUID userId, boolean locked) {
        jdbc.update("""
                UPDATE iam.app_user
                SET failed_login_count = failed_login_count + 1,
                    locked_until = CASE WHEN ? THEN now() + interval '15 minutes' ELSE locked_until END
                WHERE organization_id = ? AND id = ?
                """, locked, organizationId, userId);
    }

    public boolean hasSystemAdmin(UUID organizationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM iam.app_user u JOIN iam.role r ON r.id = u.role_id
                  WHERE u.organization_id = ? AND u.status = 'ACTIVE' AND r.code = 'SYSTEM_ADMIN' AND r.enabled
                )
                """, Boolean.class, organizationId));
    }

    public long activeSystemAdmins(UUID organizationId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM iam.app_user u JOIN iam.role r ON r.id = u.role_id
                WHERE u.organization_id = ? AND u.status = 'ACTIVE' AND r.code = 'SYSTEM_ADMIN' AND r.enabled
                """, Long.class, organizationId);
    }

    public Optional<Role> role(UUID organizationId, UUID roleId) {
        return jdbc.query(roleSql("organization_id = ? AND id = ?"), (rs, row) -> role(rs, row), organizationId, roleId).stream().findFirst();
    }

    public Optional<Role> roleByCode(UUID organizationId, String code) {
        return jdbc.query(roleSql("organization_id = ? AND code = ?"), (rs, row) -> role(rs, row), organizationId, code).stream().findFirst();
    }

    public List<Role> roles(UUID organizationId) {
        return jdbc.query(roleSql("organization_id = ? ORDER BY builtin DESC, name"), (rs, row) -> role(rs, row), organizationId);
    }

    public Role ensureRole(UUID organizationId, String code, String name, boolean builtin) {
        var existing = roleByCode(organizationId, code);
        if (existing.isPresent()) return existing.get();
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO iam.role (id, organization_id, code, name, builtin) VALUES (?, ?, ?, ?, ?)",
                id, organizationId, code, name, builtin);
        return new Role(id, organizationId, code, name, builtin, true, 0);
    }

    public void renameRole(UUID organizationId, UUID roleId, String name) {
        jdbc.update("UPDATE iam.role SET name = ?, updated_at = now(), policy_version = policy_version + 1 WHERE organization_id = ? AND id = ? AND NOT builtin",
                name, organizationId, roleId);
    }

    public void deleteRole(UUID organizationId, UUID roleId) {
        if (jdbc.queryForObject("SELECT builtin FROM iam.role WHERE organization_id = ? AND id = ?", Boolean.class, organizationId, roleId)) {
            throw new IllegalArgumentException("内置角色不可删除");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM iam.app_user WHERE organization_id = ? AND role_id = ?", Long.class, organizationId, roleId) > 0) {
            throw new IllegalArgumentException("仍有用户绑定该角色");
        }
        jdbc.update("DELETE FROM iam.role WHERE organization_id = ? AND id = ?", organizationId, roleId);
    }

    public List<PermissionDefinition> permissionDefinitions() {
        return jdbc.query("SELECT code, module, name, risk, default_scope FROM iam.permission_definition WHERE enabled ORDER BY module, code",
                (rs, row) -> new PermissionDefinition(rs.getString("code"), rs.getString("module"), rs.getString("name"),
                        rs.getString("risk"), rs.getString("default_scope")));
    }

    public void ensurePermission(PermissionDefinition definition) {
        jdbc.update("""
                INSERT INTO iam.permission_definition (code, module, name, risk, default_scope)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (code) DO UPDATE SET module = EXCLUDED.module, name = EXCLUDED.name,
                    risk = EXCLUDED.risk, default_scope = EXCLUDED.default_scope, enabled = TRUE,
                    updated_at = now(), definition_version = iam.permission_definition.definition_version + 1
                """, definition.code(), definition.module(), definition.name(), definition.risk(), definition.defaultScope());
    }

    public List<Binding> roleBindings(UUID organizationId, UUID roleId) {
        return jdbc.query("""
                SELECT permission_code, effect, scope_type, target_ids
                FROM iam.role_permission_binding WHERE organization_id = ? AND role_id = ? ORDER BY permission_code
                """, (rs, row) -> binding(rs), organizationId, roleId);
    }

    public List<Binding> userOverrides(UUID organizationId, UUID userId) {
        return jdbc.query("""
                SELECT permission_code, effect, scope_type, target_ids
                FROM iam.user_permission_override WHERE organization_id = ? AND user_id = ? ORDER BY permission_code
                """, (rs, row) -> binding(rs), organizationId, userId);
    }

    public void replaceRoleBindings(UUID organizationId, UUID roleId, long expectedVersion, List<Binding> bindings) {
        var updated = jdbc.update("""
                UPDATE iam.role SET policy_version = policy_version + 1, updated_at = now()
                WHERE organization_id = ? AND id = ? AND policy_version = ?
                """, organizationId, roleId, expectedVersion);
        if (updated != 1) throw new PolicyVersionConflict();
        jdbc.update("DELETE FROM iam.role_permission_binding WHERE organization_id = ? AND role_id = ?", organizationId, roleId);
        bindings.forEach(binding -> insertRoleBinding(organizationId, roleId, binding));
    }

    public void ensureRoleBinding(UUID organizationId, UUID roleId, Binding binding) {
        jdbc.update("""
                INSERT INTO iam.role_permission_binding (id, organization_id, role_id, permission_code, effect, scope_type, target_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (organization_id, role_id, permission_code) DO NOTHING
                """, UUID.randomUUID(), organizationId, roleId, binding.permissionCode(), binding.effect(), binding.scopeType(), json(binding.targetIds()));
    }

    public long roleVersion(UUID organizationId, UUID roleId) {
        return jdbc.queryForObject("SELECT policy_version FROM iam.role WHERE organization_id = ? AND id = ?", Long.class, organizationId, roleId);
    }

    public void replaceUserOverrides(UUID organizationId, UUID userId, long expectedVersion, List<Binding> bindings) {
        var updated = jdbc.update("UPDATE iam.app_user SET auth_version = auth_version + 1 WHERE organization_id = ? AND id = ? AND auth_version = ?",
                organizationId, userId, expectedVersion);
        if (updated != 1) throw new PolicyVersionConflict();
        jdbc.update("DELETE FROM iam.user_permission_override WHERE organization_id = ? AND user_id = ?", organizationId, userId);
        bindings.forEach(binding -> insertUserOverride(organizationId, userId, binding));
    }

    public void deleteUserOverride(UUID organizationId, UUID userId, String permissionCode) {
        jdbc.update("DELETE FROM iam.user_permission_override WHERE organization_id = ? AND user_id = ? AND permission_code = ?",
                organizationId, userId, permissionCode);
        jdbc.update("UPDATE iam.app_user SET auth_version = auth_version + 1 WHERE organization_id = ? AND id = ?", organizationId, userId);
    }

    public Optional<Session> session(String token) {
        return jdbc.query("""
                SELECT s.id, s.organization_id, s.user_id, s.auth_version, s.issued_at, s.last_seen_at,
                    s.expires_at, s.absolute_expires_at, u.username, u.status
                FROM iam.login_session s JOIN iam.app_user u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL
                """, (rs, row) -> session(rs, row), hash(token)).stream().findFirst();
    }

    public Session createSession(UUID organizationId, UUID userId, long authVersion, String tokenHash,
                                 Instant expiresAt, Instant absoluteExpiresAt, String ip, String userAgent,
                                 boolean rememberMe) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO iam.login_session (id, organization_id, user_id, token_hash, expires_at,
                    absolute_expires_at, auth_version, ip_address, user_agent, remember_me)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, userId, tokenHash, Timestamp.from(expiresAt), Timestamp.from(absoluteExpiresAt),
                authVersion, ip, userAgent, rememberMe);
        return new Session(id, organizationId, userId, authVersion, Instant.now(), Instant.now(), expiresAt,
                absoluteExpiresAt, null, null);
    }

    public void touchSession(UUID sessionId, Instant expiresAt) {
        jdbc.update("UPDATE iam.login_session SET last_seen_at = now(), expires_at = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(expiresAt), sessionId);
    }

    public void revokeSession(UUID sessionId) {
        jdbc.update("UPDATE iam.login_session SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", sessionId);
    }

    public List<SessionSummary> sessions(UUID organizationId, UUID userId) {
        return jdbc.query("""
                SELECT id, issued_at, last_seen_at, expires_at, ip_address, user_agent, revoked_at
                FROM iam.login_session WHERE organization_id = ? AND user_id = ?
                ORDER BY issued_at DESC
                """, (rs, row) -> new SessionSummary(rs.getObject("id", UUID.class), instant(rs, "issued_at"),
                instant(rs, "last_seen_at"), instant(rs, "expires_at"), rs.getString("ip_address"), rs.getString("user_agent"),
                instant(rs, "revoked_at")), organizationId, userId);
    }

    public void revokeUserSessions(UUID organizationId, UUID userId) {
        jdbc.update("UPDATE iam.login_session SET revoked_at = now() WHERE organization_id = ? AND user_id = ? AND revoked_at IS NULL",
                organizationId, userId);
    }

    public long recentFailures(UUID organizationId, String username, Instant since) {
        return jdbc.queryForObject("SELECT count(*) FROM iam.login_attempt WHERE organization_id = ? AND username = ? AND NOT succeeded AND created_at >= ?",
                Long.class, organizationId, username, Timestamp.from(since));
    }

    public long recentIpFailures(String ip, Instant since) {
        return jdbc.queryForObject("SELECT count(*) FROM iam.login_attempt WHERE ip_address = ? AND NOT succeeded AND created_at >= ?",
                Long.class, ip, Timestamp.from(since));
    }

    public void attempt(UUID organizationId, String username, String ip, boolean succeeded) {
        jdbc.update("INSERT INTO iam.login_attempt (id, organization_id, username, ip_address, succeeded) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), organizationId, username, ip, succeeded);
    }

    public void updateRole(UUID organizationId, UUID userId, UUID roleId) {
        jdbc.update("UPDATE iam.app_user SET role_id = ?, auth_version = auth_version + 1 WHERE organization_id = ? AND id = ?",
                roleId, organizationId, userId);
        revokeUserSessions(organizationId, userId);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算会话摘要", exception);
        }
    }

    private void insertRoleBinding(UUID organizationId, UUID roleId, Binding binding) {
        jdbc.update("""
                INSERT INTO iam.role_permission_binding (id, organization_id, role_id, permission_code, effect, scope_type, target_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), organizationId, roleId, binding.permissionCode(), binding.effect(), binding.scopeType(), json(binding.targetIds()));
    }

    private void insertUserOverride(UUID organizationId, UUID userId, Binding binding) {
        jdbc.update("""
                INSERT INTO iam.user_permission_override (id, organization_id, user_id, permission_code, effect, scope_type, target_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), organizationId, userId, binding.permissionCode(), binding.effect(), binding.scopeType(), json(binding.targetIds()));
    }

    private String json(List<UUID> ids) {
        try { return objectMapper.writeValueAsString(ids == null ? List.of() : ids); }
        catch (Exception exception) { throw new IllegalArgumentException("权限范围目标格式无效", exception); }
    }

    private Binding binding(ResultSet rs) throws java.sql.SQLException {
        try {
            var values = objectMapper.readValue(rs.getString("target_ids"), STRING_LIST);
            return new Binding(rs.getString("permission_code"), rs.getString("effect"), rs.getString("scope_type"),
                    values.stream().map(value -> { try { return UUID.fromString(value); } catch (Exception ignored) { return null; } }).filter(java.util.Objects::nonNull).toList());
        } catch (Exception exception) {
            return new Binding(rs.getString("permission_code"), rs.getString("effect"), rs.getString("scope_type"), List.of());
        }
    }

    private Organization organization(ResultSet rs, int ignored) throws java.sql.SQLException {
        return new Organization(rs.getObject("id", UUID.class), rs.getString("name"));
    }

    private User user(ResultSet rs, int ignored) throws java.sql.SQLException {
        return new User(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class), rs.getString("username"),
                rs.getString("display_name"), rs.getString("email"), rs.getString("phone"), rs.getString("department_name"),
                rs.getObject("role_id", UUID.class), rs.getString("role_code"), rs.getString("role_name"), rs.getString("status"),
                rs.getBoolean("dictionary_admin"), rs.getString("password_hash"), rs.getLong("auth_version"),
                instant(rs, "last_login_at"), rs.getInt("failed_login_count"), instant(rs, "locked_until"));
    }

    private Role role(ResultSet rs, int ignored) throws java.sql.SQLException {
        return new Role(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("builtin"), rs.getBoolean("enabled"), rs.getLong("policy_version"));
    }

    private Session session(ResultSet rs, int ignored) throws java.sql.SQLException {
        return new Session(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getLong("auth_version"), instant(rs, "issued_at"), instant(rs, "last_seen_at"), instant(rs, "expires_at"),
                instant(rs, "absolute_expires_at"), rs.getString("username"), rs.getString("status"));
    }

    private Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String userSql(String where) {
        return """
                SELECT u.id, u.organization_id, u.username, u.display_name, u.email, u.phone, u.department_name,
                    u.role_id, r.code AS role_code, r.name AS role_name, u.status, u.dictionary_admin, u.password_hash,
                    u.auth_version, u.last_login_at, u.failed_login_count, u.locked_until
                FROM iam.app_user u LEFT JOIN iam.role r ON r.id = u.role_id
                WHERE """ + " " + where;
    }

    private String roleSql(String where) {
        return "SELECT id, organization_id, code, name, builtin, enabled, policy_version FROM iam.role WHERE " + where;
    }

}
