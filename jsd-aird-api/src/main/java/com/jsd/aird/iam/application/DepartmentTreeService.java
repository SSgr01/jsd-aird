package com.jsd.aird.iam.application;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentTreeService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    public DepartmentTreeService(JdbcTemplate jdbc, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    public List<DepartmentNode> list(Actor actor) {
        requireAny(actor, "system.users.view", "system.user.view");
        return jdbc.query("""
                SELECT d.id, d.name, d.parent_id, d.sort_order,
                       (SELECT count(*) FROM iam.app_user u WHERE u.department_id = d.id) AS user_count
                FROM iam.department d WHERE d.organization_id = ? ORDER BY d.sort_order, d.name
                """, (rs, row) -> new DepartmentNode(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("parent_id", UUID.class), rs.getInt("sort_order"),
                rs.getLong("user_count")), actor.organizationId());
    }

    public List<DepartmentUser> users(Actor actor, UUID departmentId) {
        requireAny(actor, "system.users.view", "system.user.view");
        requireNode(actor, departmentId);
        return jdbc.query("""
                SELECT u.id, u.username, u.display_name, u.email, u.phone, u.role_id,
                       r.code AS role_code, r.name AS role_name, u.status,
                       u.last_login_at, u.auth_version
                FROM iam.app_user u LEFT JOIN iam.role r ON r.id = u.role_id
                WHERE u.organization_id = ? AND u.department_id = ?
                ORDER BY u.display_name, u.username
                """, (rs, row) -> new DepartmentUser(rs.getObject("id", UUID.class),
                rs.getString("username"), rs.getString("display_name"),
                rs.getString("email"), rs.getString("phone"), rs.getObject("role_id", UUID.class),
                rs.getString("role_code"), rs.getString("role_name"), rs.getString("status"),
                rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant(),
                rs.getLong("auth_version")),
                actor.organizationId(), departmentId);
    }

    @Transactional
    public DepartmentNode create(Actor actor, String name, UUID parentId, Integer sortOrder) {
        requireAny(actor, "system.users.create", "system.user.manage");
        lockOrganization(actor);
        var cleanName = validName(name);
        var order = validSortOrder(sortOrder);
        if (parentId != null) requireNode(actor, parentId);
        var id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO iam.department (id, organization_id, name, parent_id, sort_order) VALUES (?, ?, ?, ?, ?)",
                    id, actor.organizationId(), cleanName, parentId, order);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "机构名称已存在或上级机构无效");
        }
        return new DepartmentNode(id, cleanName, parentId, order, 0);
    }

    @Transactional
    public DepartmentNode update(Actor actor, UUID id, String name, UUID parentId, Integer sortOrder) {
        requireAny(actor, "system.users.update", "system.user.manage");
        lockOrganization(actor);
        requireNode(actor, id);
        if (parentId != null) requireNode(actor, parentId);
        if (parentId != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH RECURSIVE descendants AS (
                    SELECT id FROM iam.department WHERE id = ? AND organization_id = ?
                    UNION ALL
                    SELECT child.id FROM iam.department child JOIN descendants d ON child.parent_id = d.id
                ) SELECT EXISTS (SELECT 1 FROM descendants WHERE id = ?)
                """, Boolean.class, id, actor.organizationId(), parentId))) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "不能将机构移动到自身或下级机构");
        }
        var cleanName = validName(name);
        var order = sortOrder == null ? null : validSortOrder(sortOrder);
        try {
            jdbc.update("UPDATE iam.department SET name = ?, parent_id = ?, sort_order = COALESCE(?, sort_order) WHERE id = ? AND organization_id = ?",
                    cleanName, parentId, order, id, actor.organizationId());
            jdbc.update("UPDATE iam.app_user SET department_name = ?, auth_version = auth_version + 1 "
                    + "WHERE organization_id = ? AND department_id = ? AND department_name IS DISTINCT FROM ?",
                    cleanName, actor.organizationId(), id, cleanName);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "机构名称已存在或上级机构无效");
        }
        return jdbc.queryForObject("""
                SELECT d.id, d.name, d.parent_id, d.sort_order,
                       (SELECT count(*) FROM iam.app_user u WHERE u.department_id = d.id) AS user_count
                FROM iam.department d WHERE d.id = ? AND d.organization_id = ?
                """, (rs, row) -> new DepartmentNode(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("parent_id", UUID.class), rs.getInt("sort_order"),
                rs.getLong("user_count")), id, actor.organizationId());
    }

    @Transactional
    public void delete(Actor actor, UUID id) {
        requireAny(actor, "system.users.delete", "system.user.manage");
        lockOrganization(actor);
        requireNode(actor, id);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM iam.department WHERE parent_id = ?)",
                Boolean.class, id))) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "请先移动或删除下级机构");
        }
        try {
            jdbc.update("DELETE FROM iam.department WHERE id = ? AND organization_id = ?", id, actor.organizationId());
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "机构仍被用户或业务数据引用");
        }
    }

    private void lockOrganization(Actor actor) {
        jdbc.queryForObject("SELECT id FROM iam.organization WHERE id = ? FOR UPDATE", UUID.class,
                actor.organizationId());
    }

    private void requireNode(Actor actor, UUID id) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM iam.department WHERE id = ? AND organization_id = ?)",
                Boolean.class, id, actor.organizationId()))) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "机构不存在");
        }
    }

    private String validName(String name) {
        var value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 160) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "机构名称长度应为 1-160 个字符");
        }
        return value;
    }

    private int validSortOrder(Integer sortOrder) {
        var value = sortOrder == null ? 1000 : sortOrder;
        if (value < 0 || value > 999999) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "排序号应为 0-999999");
        }
        return value;
    }

    private void requireAny(Actor actor, String first, String second) {
        if (authorization.check(new PermissionCheck(actor.organizationId(), actor.userId(), first,
                "IAM", null, null)).allowed()) return;
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), second,
                "IAM", null, null));
    }

    public record DepartmentNode(UUID id, String name, UUID parentId, int sortOrder, long userCount) { }
    public record DepartmentUser(UUID id, String username, String displayName, String email, String phone,
                                 UUID roleId, String roleCode, String roleName, String status,
                                 java.time.Instant lastLoginAt, long authVersion) { }
}
