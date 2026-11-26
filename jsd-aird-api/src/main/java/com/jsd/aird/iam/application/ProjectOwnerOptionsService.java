package com.jsd.aird.iam.application;

import com.jsd.aird.shared.security.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectOwnerOptionsService {
    private final JdbcTemplate jdbc;

    public ProjectOwnerOptionsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OwnerOption> list(Actor actor) {
        return jdbc.query("""
            WITH RECURSIVE subtree AS (
                SELECT d.id FROM iam.department d
                JOIN iam.app_user me ON me.department_id = d.id
                WHERE me.id = ? AND me.organization_id = ? AND d.organization_id = ?
                UNION ALL
                SELECT child.id FROM iam.department child
                JOIN subtree parent ON child.parent_id = parent.id
                WHERE child.organization_id = ?
            )
            SELECT u.id, u.username, u.display_name, u.department_name
            FROM iam.app_user u JOIN subtree d ON u.department_id = d.id
            WHERE u.organization_id = ? AND u.status = 'ACTIVE'
            ORDER BY u.display_name, u.username
            """, (rs, row) -> new OwnerOption(rs.getObject("id", UUID.class),
                rs.getString("username"), rs.getString("display_name"), rs.getString("department_name")),
            actor.userId(), actor.organizationId(), actor.organizationId(), actor.organizationId(), actor.organizationId());
    }

    public record OwnerOption(UUID id, String username, String displayName, String departmentName) { }
}
