package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.query.ProjectTaskQuery;
import org.apache.ibatis.jdbc.SQL;

public class ProjectWorkSqlProvider {

    private static final String TASK_COLUMNS =
        "t.id, t.task_code taskCode, t.project_id projectId, p.name projectName, " +
        "t.stage_id stageId, s.name stageName, t.name, t.owner, t.priority, " +
        "t.planned_date plannedDate, t.planned_end plannedEnd, t.status, " +
        "(SELECT count(*) FROM rnd.experiment e WHERE e.task_id = t.id AND e.deleted = false) experimentCount, " +
        "t.version, t.created_at createdAt, t.updated_at updatedAt";

    private static void applyFilters(SQL sql, ProjectTaskQuery query) {
        sql.JOIN("mdm.project p ON p.id = t.project_id AND p.deleted = false");
        sql.JOIN("mdm.project_stage s ON s.id = t.stage_id AND s.deleted = false");
        sql.WHERE("t.deleted = false");
        if (query.organizationId() != null) sql.WHERE("p.organization_id = #{query.organizationId}");
        if (query.mine()) {
            sql.WHERE("EXISTS (SELECT 1 FROM iam.app_user me WHERE me.id = #{query.scopeUserId} " +
                "AND me.organization_id = #{query.organizationId} AND (t.owner_user_id = me.id OR " +
                "(t.owner_user_id IS NULL AND (t.owner = me.username OR " +
                "(t.owner = me.display_name AND (SELECT count(*) FROM iam.app_user same_name " +
                "WHERE same_name.organization_id = me.organization_id AND same_name.display_name = me.display_name) = 1)))))");
        } else switch (query.scopeType() == null ? "ALL" : query.scopeType()) {
            case "ALL", "ORG" -> { }
            case "SELF", "CREATOR" -> sql.WHERE("p.created_by = #{query.scopeUserId}");
            case "DEPARTMENT" -> sql.WHERE("p.department_id IS NOT NULL AND p.department_id = (SELECT u.department_id FROM iam.app_user u WHERE u.id = #{query.scopeUserId} AND u.organization_id = #{query.organizationId})");
            case "SELECTED", "PROJECT" -> {
                if (query.scopeTargetIds().isEmpty()) sql.WHERE("1 = 0");
                else sql.WHERE("(p.created_by = #{query.scopeUserId} OR p.id IN (" + java.util.stream.IntStream.range(0, query.scopeTargetIds().size())
                        .mapToObj(index -> "#{query.scopeTargetIds[" + index + "]}")
                        .collect(java.util.stream.Collectors.joining(",")) + "))");
            }
            default -> sql.WHERE("1 = 0");
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            sql.WHERE("(t.name ILIKE concat('%', #{query.keyword}, '%') OR t.task_code ILIKE concat('%', #{query.keyword}, '%'))");
        }
        if (query.projectId() != null && !query.projectId().isBlank()) {
            sql.WHERE("t.project_id = #{query.projectId}::uuid");
        }
        if (query.stageId() != null && !query.stageId().isBlank()) {
            sql.WHERE("t.stage_id = #{query.stageId}::uuid");
        }
        if (query.status() != null && !query.status().isBlank()) {
            sql.WHERE("t.status = #{query.status}");
        }
        if (query.owner() != null && !query.owner().isBlank()) {
            sql.WHERE("t.owner = #{query.owner}");
        }
        if (query.priority() != null && !query.priority().isBlank()) {
            sql.WHERE("t.priority = #{query.priority}");
        }
    }

    public String findPage(ProjectTaskQuery query) {
        SQL sql = new SQL();
        sql.SELECT(TASK_COLUMNS);
        sql.FROM("mdm.project_task t");
        applyFilters(sql, query);
        sql.ORDER_BY("t.created_at DESC");
        return sql.toString() + " LIMIT #{limit} OFFSET #{offset}";
    }

    public String count(ProjectTaskQuery query) {
        SQL sql = new SQL();
        sql.SELECT("count(*)");
        sql.FROM("mdm.project_task t");
        applyFilters(sql, query);
        return sql.toString();
    }

    public String owners(ProjectTaskQuery query) {
        SQL sql = new SQL();
        sql.SELECT("DISTINCT t.owner");
        sql.FROM("mdm.project_task t");
        applyFilters(sql, query);
        sql.WHERE("t.owner IS NOT NULL AND t.owner <> ''");
        sql.ORDER_BY("t.owner");
        return sql.toString();
    }
}
