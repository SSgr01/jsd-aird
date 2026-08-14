package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.query.ProjectTaskQuery;
import org.apache.ibatis.jdbc.SQL;

public class ProjectWorkSqlProvider {

    private static final String TASK_COLUMNS =
        "t.id, t.task_code taskCode, t.project_id projectId, p.name projectName, " +
        "t.stage_id stageId, s.name stageName, t.name, t.owner, p.priority, " +
        "t.planned_date plannedDate, t.status, " +
        "(SELECT count(*) FROM mdm.project_experiment e WHERE e.task_id = t.id AND e.deleted = false) experimentCount, " +
        "t.version, t.created_at createdAt, t.updated_at updatedAt";

    private static void applyFilters(SQL sql, ProjectTaskQuery query) {
        sql.JOIN("mdm.project p ON p.id = t.project_id AND p.deleted = false");
        sql.JOIN("mdm.project_stage s ON s.id = t.stage_id AND s.deleted = false");
        sql.WHERE("t.deleted = false");
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
            sql.WHERE("p.priority = #{query.priority}");
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
}
