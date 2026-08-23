package com.jsd.aird.core.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.core.api.ProjectResourceFacade;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProjectResourceService implements ProjectResourceFacade {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final AuthorizationService authorization;

    public ProjectResourceService(JdbcTemplate jdbc, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.authorization = authorization;
    }

    @Override
    public List<RelatedProjectView> links(Actor actor, ResourceType resourceType, UUID resourceId) {
        return links(actor, resourceType, List.of(resourceId)).getOrDefault(resourceId, List.of());
    }

    @Override
    public Map<UUID, List<RelatedProjectView>> links(Actor actor, ResourceType resourceType,
                                                     Collection<UUID> resourceIds) {
        var ids = resourceIds == null ? List.<UUID>of() : resourceIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        var sql = """
                SELECT l.resource_id, l.project_id, p.project_code, p.name AS project_name,
                       l.stage_id, s.name AS stage_name, l.task_id, t.name AS task_name
                  FROM core.project_resource_link l
                  JOIN mdm.project p ON p.id = l.project_id AND p.deleted = false
                  LEFT JOIN mdm.project_stage s ON s.id = l.stage_id
                  LEFT JOIN mdm.project_task t ON t.id = l.task_id
                 WHERE l.organization_id = :organizationId
                   AND l.resource_type = :resourceType
                   AND l.resource_id IN (:resourceIds)
                 ORDER BY p.project_code, s.order_no NULLS FIRST, t.created_at NULLS FIRST
                """;
        var params = new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId())
                .addValue("resourceType", resourceType.name())
                .addValue("resourceIds", ids);
        var visibility = new LinkedHashMap<UUID, Boolean>();
        var grouped = new LinkedHashMap<UUID, List<RelatedProjectView>>();
        namedJdbc.query(sql, params, rs -> {
            var projectId = rs.getObject("project_id", UUID.class);
            if (!visibility.computeIfAbsent(projectId, id -> allowed(actor, "project.view", id, "READ"))) return;
            var resourceId = rs.getObject("resource_id", UUID.class);
            grouped.computeIfAbsent(resourceId, ignored -> new ArrayList<>()).add(new RelatedProjectView(
                    projectId, rs.getString("project_code"), rs.getString("project_name"),
                    rs.getObject("stage_id", UUID.class), rs.getString("stage_name"),
                    rs.getObject("task_id", UUID.class), rs.getString("task_name")));
        });
        return grouped.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    @Override
    public Set<UUID> resourceIdsForProject(Actor actor, ResourceType resourceType, UUID projectId) {
        requireProject(actor, "project.view", projectId, "READ");
        return Set.copyOf(jdbc.queryForList("""
                        SELECT DISTINCT resource_id
                          FROM core.project_resource_link
                         WHERE organization_id = ? AND resource_type = ? AND project_id = ?
                        """, UUID.class, actor.organizationId(), resourceType.name(), projectId));
    }

    @Override
    @Transactional
    public List<RelatedProjectView> replaceLinks(Actor actor, ResourceType resourceType, UUID resourceId,
                                                 List<ProjectRelationTarget> targets) {
        requireResource(actor.organizationId(), resourceType, resourceId);
        var desired = normalizeTargets(targets);
        var existing = rawTargets(actor.organizationId(), resourceType, resourceId);
        var involvedProjects = new LinkedHashSet<UUID>();
        existing.forEach(target -> involvedProjects.add(target.projectId()));
        desired.forEach(target -> involvedProjects.add(target.projectId()));
        involvedProjects.forEach(projectId -> requireProject(actor, "project.assign", projectId, "WRITE"));
        desired.forEach(this::validateHierarchy);

        var desiredKeys = desired.stream().map(TargetKey::new).collect(java.util.stream.Collectors.toSet());
        var existingKeys = existing.stream().map(TargetKey::new).collect(java.util.stream.Collectors.toSet());
        for (var target : existing) {
            if (!desiredKeys.contains(new TargetKey(target))) {
                if (activeReferenceExists(actor.organizationId(), resourceType, resourceId, target)) {
                    throw new ApiException(ApiErrorCode.REFERENCE_IN_USE,
                            "该项目关联仍被活动资料参考使用，请先删除资料参考");
                }
                deleteLink(actor.organizationId(), resourceType, resourceId, target);
            }
        }
        for (var target : desired) {
            if (!existingKeys.contains(new TargetKey(target))) insertLink(actor, resourceType, resourceId, target);
        }
        return links(actor, resourceType, resourceId);
    }

    @Override
    @Transactional
    public List<ReferenceView> addReferences(Actor actor, ResourceType resourceType, UUID resourceId,
                                             String summary, List<ProjectRelationTarget> targets) {
        var source = requireResource(actor.organizationId(), resourceType, resourceId);
        var normalized = normalizeTargets(targets);
        if (normalized.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "至少选择一个项目");
        normalized.forEach(target -> {
            requireProject(actor, "project.assign", target.projectId(), "WRITE");
            validateHierarchy(target);
            insertLink(actor, resourceType, resourceId, target);
            upsertReference(actor, resourceType, source, summary, target);
        });
        return normalized.stream().map(target -> referenceByTuple(actor.organizationId(), resourceType, resourceId, target))
                .toList();
    }

    @Override
    public PageResponse<ReferenceView> references(Actor actor, UUID projectId, ReferenceQuery query) {
        requireProject(actor, "project.view", projectId, "READ");
        var safePage = Math.max(1, query == null ? 1 : query.page());
        var safeSize = Math.min(100, Math.max(1, query == null ? 20 : query.size()));
        var where = new StringBuilder(" WHERE r.organization_id = :organizationId AND r.project_id = :projectId");
        var params = new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId())
                .addValue("projectId", projectId)
                .addValue("limit", safeSize)
                .addValue("offset", (safePage - 1) * safeSize);
        if (query != null) {
            if (StringUtils.hasText(query.keyword())) {
                where.append(" AND (r.title ILIKE :keyword OR coalesce(r.original_name, '') ILIKE :keyword OR coalesce(r.summary, '') ILIKE :keyword)");
                params.addValue("keyword", "%" + query.keyword().trim() + "%");
            }
            if (StringUtils.hasText(query.sourceModule())) {
                where.append(" AND r.source_module = :sourceModule");
                params.addValue("sourceModule", query.sourceModule().trim().toUpperCase(Locale.ROOT));
            }
            if (query.stageId() != null) { where.append(" AND r.stage_id = :stageId"); params.addValue("stageId", query.stageId()); }
            if (query.taskId() != null) { where.append(" AND r.task_id = :taskId"); params.addValue("taskId", query.taskId()); }
            if (query.addedBy() != null) { where.append(" AND r.added_by = :addedBy"); params.addValue("addedBy", query.addedBy()); }
            if (StringUtils.hasText(query.status())) {
                where.append(" AND r.status = :status");
                params.addValue("status", query.status().trim().toUpperCase(Locale.ROOT));
            }
        }
        var select = referenceSelect() + where + " ORDER BY r.added_at DESC LIMIT :limit OFFSET :offset";
        var items = namedJdbc.query(select, params, this::mapReference);
        var total = namedJdbc.queryForObject("SELECT count(*) FROM core.project_reference r" + where,
                params, Long.class);
        var count = total == null ? 0 : total;
        return new PageResponse<>(items, safePage, safeSize, count,
                count == 0 ? 0 : (count + safeSize - 1) / safeSize);
    }

    @Override
    @Transactional
    public void removeReference(Actor actor, UUID referenceId) {
        var row = requireReference(actor.organizationId(), referenceId);
        requireProject(actor, "project.assign", row.projectId(), "WRITE");
        jdbc.update("""
                UPDATE core.project_reference
                   SET status = 'REMOVED', removed_by = ?, removed_at = now()
                 WHERE id = ? AND organization_id = ? AND status = 'ACTIVE'
                """, actor.userId(), referenceId, actor.organizationId());
    }

    @Override
    @Transactional
    public ReferenceView restoreReference(Actor actor, UUID referenceId) {
        var row = requireReference(actor.organizationId(), referenceId);
        requireProject(actor, "project.assign", row.projectId(), "WRITE");
        var target = new ProjectRelationTarget(row.projectId(), row.stageId(), row.taskId());
        validateHierarchy(target);
        insertLink(actor, row.resourceType(), row.resourceId(), target);
        jdbc.update("""
                UPDATE core.project_reference
                   SET status = 'ACTIVE', removed_by = NULL, removed_at = NULL,
                       added_by = ?, added_at = now()
                 WHERE id = ? AND organization_id = ?
                """, actor.userId(), referenceId, actor.organizationId());
        return requireReference(actor.organizationId(), referenceId);
    }

    private List<ProjectRelationTarget> normalizeTargets(List<ProjectRelationTarget> targets) {
        if (targets == null) return List.of();
        var unique = new LinkedHashMap<TargetKey, ProjectRelationTarget>();
        for (var target : targets) {
            if (target == null || target.projectId() == null) {
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "项目不能为空");
            }
            if (target.taskId() != null && target.stageId() == null) {
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "选择任务时必须同时选择阶段");
            }
            unique.putIfAbsent(new TargetKey(target), target);
        }
        return List.copyOf(unique.values());
    }

    private void validateHierarchy(ProjectRelationTarget target) {
        var valid = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM mdm.project p
                     WHERE p.id = ? AND p.deleted = false
                       AND (?::uuid IS NULL OR EXISTS (
                           SELECT 1 FROM mdm.project_stage s
                            WHERE s.id = ? AND s.project_id = p.id AND s.deleted = false))
                       AND (?::uuid IS NULL OR EXISTS (
                           SELECT 1 FROM mdm.project_task t
                            WHERE t.id = ? AND t.project_id = p.id AND t.stage_id = ? AND t.deleted = false))
                )
                """, Boolean.class, target.projectId(), target.stageId(), target.stageId(), target.taskId(),
                target.taskId(), target.stageId());
        if (!Boolean.TRUE.equals(valid)) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "项目、阶段、任务关系无效或已删除");
        }
    }

    private SourceSnapshot requireResource(UUID organizationId, ResourceType type, UUID resourceId) {
        var rows = switch (type) {
            case KNOWLEDGE_DOCUMENT -> jdbc.query("""
                    SELECT d.id AS resource_id, d.title, v.id AS file_version_id, v.file_object_id,
                           v.original_name, v.content_type, v.size_bytes
                      FROM kb.document d
                      LEFT JOIN LATERAL (
                          SELECT id, file_object_id, original_name, content_type, size_bytes
                            FROM kb.document_version
                           WHERE document_id = d.id
                           ORDER BY version_no DESC LIMIT 1
                      ) v ON true
                     WHERE d.organization_id = ? AND d.id = ?
                    """, this::mapSource, organizationId, resourceId);
            case DATA_IMPORT_JOB -> jdbc.query("""
                    SELECT j.id AS resource_id, j.source_file_name AS title, j.id AS file_version_id,
                           j.source_file_id AS file_object_id, j.source_file_name AS original_name,
                           coalesce(fo.content_type, 'application/octet-stream') AS content_type,
                           coalesce(fo.size_bytes, 0) AS size_bytes
                      FROM data.import_job j
                      LEFT JOIN ops.file_object fo ON fo.id = j.source_file_id AND fo.organization_id = j.organization_id
                     WHERE j.organization_id = ? AND j.id = ?
                    """, this::mapSource, organizationId, resourceId);
        };
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND, "关联来源不存在");
        return rows.getFirst();
    }

    private SourceSnapshot mapSource(ResultSet rs, int ignored) throws SQLException {
        return new SourceSnapshot(rs.getObject("resource_id", UUID.class), rs.getString("title"),
                rs.getObject("file_version_id", UUID.class), rs.getObject("file_object_id", UUID.class),
                rs.getString("original_name"), rs.getString("content_type"), rs.getLong("size_bytes"));
    }

    private List<ProjectRelationTarget> rawTargets(UUID organizationId, ResourceType type, UUID resourceId) {
        return jdbc.query("""
                SELECT project_id, stage_id, task_id
                  FROM core.project_resource_link
                 WHERE organization_id = ? AND resource_type = ? AND resource_id = ?
                """, (rs, ignored) -> new ProjectRelationTarget(rs.getObject("project_id", UUID.class),
                rs.getObject("stage_id", UUID.class), rs.getObject("task_id", UUID.class)),
                organizationId, type.name(), resourceId);
    }

    private void insertLink(Actor actor, ResourceType type, UUID resourceId, ProjectRelationTarget target) {
        jdbc.update("""
                INSERT INTO core.project_resource_link
                    (id, organization_id, resource_type, resource_id, project_id, stage_id, task_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, resource_type, resource_id, project_id, stage_id, task_id)
                DO NOTHING
                """, UUID.randomUUID(), actor.organizationId(), type.name(), resourceId, target.projectId(),
                target.stageId(), target.taskId(), actor.userId());
    }

    private void deleteLink(UUID organizationId, ResourceType type, UUID resourceId, ProjectRelationTarget target) {
        jdbc.update("""
                DELETE FROM core.project_resource_link
                 WHERE organization_id = ? AND resource_type = ? AND resource_id = ? AND project_id = ?
                   AND stage_id IS NOT DISTINCT FROM ? AND task_id IS NOT DISTINCT FROM ?
                """, organizationId, type.name(), resourceId, target.projectId(), target.stageId(), target.taskId());
    }

    private boolean activeReferenceExists(UUID organizationId, ResourceType type, UUID resourceId,
                                          ProjectRelationTarget target) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM core.project_reference
                 WHERE organization_id = ? AND resource_type = ? AND resource_id = ? AND project_id = ?
                   AND stage_id IS NOT DISTINCT FROM ? AND task_id IS NOT DISTINCT FROM ? AND status = 'ACTIVE')
                """, Boolean.class, organizationId, type.name(), resourceId, target.projectId(),
                target.stageId(), target.taskId()));
    }

    private void upsertReference(Actor actor, ResourceType resourceType, SourceSnapshot source, String summary,
                                 ProjectRelationTarget target) {
        var sourceModule = resourceType == ResourceType.KNOWLEDGE_DOCUMENT ? "KNOWLEDGE" : "DATA_CENTER";
        jdbc.update("""
                INSERT INTO core.project_reference
                    (id, organization_id, project_id, stage_id, task_id, resource_type, resource_id,
                     file_version_id, file_object_id, source_module, title, original_name, content_type,
                     size_bytes, summary, status, added_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (organization_id, resource_type, resource_id, project_id, stage_id, task_id)
                DO UPDATE SET status = 'ACTIVE', removed_by = NULL, removed_at = NULL,
                              file_version_id = EXCLUDED.file_version_id,
                              file_object_id = EXCLUDED.file_object_id,
                              source_module = EXCLUDED.source_module,
                              title = EXCLUDED.title, original_name = EXCLUDED.original_name,
                              content_type = EXCLUDED.content_type, size_bytes = EXCLUDED.size_bytes,
                              summary = coalesce(EXCLUDED.summary, core.project_reference.summary),
                              added_by = EXCLUDED.added_by, added_at = now()
                """, UUID.randomUUID(), actor.organizationId(), target.projectId(), target.stageId(), target.taskId(),
                resourceType.name(),
                source.resourceId(), source.fileVersionId(), source.fileObjectId(), sourceModule,
                source.title(), source.originalName(), source.contentType(), source.size(), summary, actor.userId());
    }

    private ReferenceView referenceByTuple(UUID organizationId, ResourceType type, UUID resourceId,
                                           ProjectRelationTarget target) {
        var rows = jdbc.query(referenceSelect() + """
                 WHERE r.organization_id = ? AND r.resource_type = ? AND r.resource_id = ? AND r.project_id = ?
                   AND r.stage_id IS NOT DISTINCT FROM ? AND r.task_id IS NOT DISTINCT FROM ?
                """, this::mapReference, organizationId, type.name(), resourceId, target.projectId(),
                target.stageId(), target.taskId());
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND, "资料参考不存在");
        return rows.getFirst();
    }

    private ReferenceView requireReference(UUID organizationId, UUID referenceId) {
        var rows = jdbc.query(referenceSelect() + " WHERE r.organization_id = ? AND r.id = ?",
                this::mapReference, organizationId, referenceId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND, "资料参考不存在");
        return rows.getFirst();
    }

    private String referenceSelect() {
        return """
                SELECT r.*, s.name AS stage_name, t.name AS task_name,
                       coalesce(u.display_name, u.username) AS added_by_name,
                       CASE WHEN r.resource_type = 'KNOWLEDGE_DOCUMENT'
                            THEN EXISTS (SELECT 1 FROM kb.document d WHERE d.id = r.resource_id AND d.organization_id = r.organization_id)
                            ELSE EXISTS (SELECT 1 FROM data.import_job j WHERE j.id = r.resource_id AND j.organization_id = r.organization_id)
                       END AS source_available
                  FROM core.project_reference r
                  LEFT JOIN iam.app_user u ON u.id = r.added_by
                  LEFT JOIN mdm.project_stage s ON s.id = r.stage_id AND s.project_id = r.project_id
                  LEFT JOIN mdm.project_task t ON t.id = r.task_id AND t.project_id = r.project_id
                                                AND t.stage_id = r.stage_id
                """;
    }

    private ReferenceView mapReference(ResultSet rs, int ignored) throws SQLException {
        return new ReferenceView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("stage_id", UUID.class), rs.getString("stage_name"),
                rs.getObject("task_id", UUID.class), rs.getString("task_name"),
                ResourceType.valueOf(rs.getString("resource_type")), rs.getObject("resource_id", UUID.class),
                rs.getObject("file_version_id", UUID.class), rs.getObject("file_object_id", UUID.class),
                rs.getString("source_module"), rs.getString("title"), rs.getString("original_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("summary"),
                rs.getString("status"), rs.getObject("added_by", UUID.class), rs.getString("added_by_name"),
                instant(rs.getTimestamp("added_at")), rs.getObject("removed_by", UUID.class),
                instant(rs.getTimestamp("removed_at")), rs.getBoolean("source_available"));
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private void requireProject(Actor actor, String permission, UUID projectId, String operation) {
        if (projectId == null) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "项目不能为空");
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), permission,
                "PROJECT", projectId, operation));
    }

    private boolean allowed(Actor actor, String permission, UUID projectId, String operation) {
        return authorization.check(new PermissionCheck(actor.organizationId(), actor.userId(), permission,
                "PROJECT", projectId, operation)).allowed();
    }

    private record TargetKey(UUID projectId, UUID stageId, UUID taskId) {
        private TargetKey(ProjectRelationTarget target) { this(target.projectId(), target.stageId(), target.taskId()); }
    }

    private record SourceSnapshot(UUID resourceId, String title, UUID fileVersionId, UUID fileObjectId,
                                  String originalName, String contentType, long size) { }
}
