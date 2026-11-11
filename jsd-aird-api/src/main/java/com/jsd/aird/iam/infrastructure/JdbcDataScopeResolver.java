package com.jsd.aird.iam.infrastructure;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.iam.api.DataScopeResolver;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcDataScopeResolver implements DataScopeResolver {

    private final JdbcTemplate jdbc;

    public JdbcDataScopeResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ResolvedDataScope resolve(PermissionCheck check, Binding binding) {
        var scope = binding.scopeType() == null ? "ALL" : binding.scopeType().toUpperCase(Locale.ROOT);
        if ("ALL".equals(scope)) return ResolvedDataScope.allow(scope);
        if ("ORG".equals(scope)) return ResolvedDataScope.allow("ALL");
        if (check.resourceId() == null) {
            return switch (scope) {
                case "SELECTED", "PROJECT", "CATEGORY" -> binding.targetIds().isEmpty()
                        ? ResolvedDataScope.deny(scope, "EMPTY_SCOPE")
                        : ResolvedDataScope.allow(scope, Set.copyOf(binding.targetIds()));
                case "SELF", "ASSIGNED", "CREATOR", "DEPARTMENT", "OWNER", "MANAGER", "PARTICIPANT", "PROJECT_GROUP"
                        -> ResolvedDataScope.allow(scope);
                default -> ResolvedDataScope.deny(scope, "UNKNOWN_DATA_SCOPE");
            };
        }
        if ("SELECTED".equals(scope)) {
            if ("PRODUCTION".equalsIgnoreCase(check.resourceType())) {
                if (binding.targetIds().isEmpty()) return ResolvedDataScope.deny(scope, "EMPTY_SCOPE");
                var placeholders = String.join(",", binding.targetIds().stream().map(id -> "?").toList());
                var args = new java.util.ArrayList<Object>();
                args.add(check.organizationId());
                args.add(check.resourceId());
                args.addAll(binding.targetIds());
                args.add(check.userId());
                return exists("SELECT EXISTS (SELECT 1 FROM mfg.production_order WHERE organization_id = ? " +
                                "AND id = ? AND (project_id IN (" + placeholders + ") OR created_by = ?))",
                        args.toArray(), scope, "NOT_IN_SELECTED_PROJECT");
            }
            if ("PRODUCTION_UPLOAD".equalsIgnoreCase(check.resourceType())) {
                if (binding.targetIds().isEmpty()) return ResolvedDataScope.deny(scope, "EMPTY_SCOPE");
                var placeholders = String.join(",", binding.targetIds().stream().map(id -> "?").toList());
                var args = new java.util.ArrayList<Object>();
                args.add(check.organizationId());
                args.add(check.resourceId());
                args.addAll(binding.targetIds());
                args.add(check.userId());
                var targetColumn = check.permissionCode() != null && check.permissionCode().startsWith("production.upload.")
                        ? "id" : "project_id";
                return exists("SELECT EXISTS (SELECT 1 FROM mfg.production_upload WHERE organization_id = ? " +
                                "AND id = ? AND status <> 'DELETED' AND (" + targetColumn + " IN (" + placeholders + ") OR created_by = ?))",
                        args.toArray(), scope, "TARGET_NOT_SELECTED");
            }
            if ("RESEARCH_TEST".equalsIgnoreCase(check.resourceType())
                    && check.permissionCode() != null && check.permissionCode().startsWith("research-test.standard")) {
                if (check.permissionCode().startsWith("research-test.standard.upload.")) {
                    if (binding.targetIds().isEmpty()) return ResolvedDataScope.deny(scope, "EMPTY_SCOPE");
                    var placeholders = String.join(",", binding.targetIds().stream().map(id -> "?").toList());
                    var args = new java.util.ArrayList<Object>();
                    args.add(check.organizationId());
                    args.add(check.resourceId());
                    args.addAll(binding.targetIds());
                    args.add(check.userId());
                    return exists("SELECT EXISTS (SELECT 1 FROM rnd.research_test_upload u " +
                                    "JOIN rnd.research_test_record r ON r.id = u.record_id AND r.organization_id = u.organization_id " +
                                    "WHERE u.organization_id = ? AND u.id = ? AND u.deleted = false " +
                                    "AND r.deleted = false AND r.record_type = 'STANDARD' AND (u.id IN (" + placeholders + ") OR u.created_by = ?))",
                            args.toArray(), scope, "TARGET_NOT_SELECTED");
                }
                return exists("SELECT EXISTS (SELECT 1 FROM rnd.research_test_record WHERE organization_id = ? " +
                                "AND id = ? AND record_type = 'STANDARD' AND deleted = false AND (created_by = ? OR ?))",
                        new Object[]{check.organizationId(), check.resourceId(), check.userId(),
                                binding.targetIds().contains(check.resourceId())}, scope, "TARGET_NOT_SELECTED");
            }
            var projectScopedTable = switch (check.resourceType() == null ? "" : check.resourceType().toUpperCase(Locale.ROOT)) {
                case "EXPERIMENT" -> "rnd.experiment";
                case "RESEARCH_TEST" -> "rnd.research_test_record";
                case "QUALITY" -> "quality.data_record";
                default -> null;
            };
            if (projectScopedTable != null) {
                if (binding.targetIds().isEmpty()) return ResolvedDataScope.deny(scope, "EMPTY_SCOPE");
                var placeholders = String.join(",", binding.targetIds().stream().map(id -> "?").toList());
                var args = new java.util.ArrayList<Object>();
                args.add(check.organizationId());
                args.add(check.resourceId());
                args.addAll(binding.targetIds());
                args.add(check.userId());
                return exists("SELECT EXISTS (SELECT 1 FROM " + projectScopedTable +
                                " WHERE organization_id = ? AND id = ? AND deleted = false AND (project_id IN (" + placeholders + ") OR created_by = ?))",
                        args.toArray(), scope, "NOT_IN_SELECTED_PROJECT");
            }
            if ("CUSTOMER".equalsIgnoreCase(check.resourceType())) {
                return Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT EXISTS (SELECT 1 FROM mdm.business_partner WHERE id = ? AND organization_id = ? AND deleted = false AND (created_by = ? OR ?))",
                                Boolean.class, check.resourceId(), check.organizationId(), check.userId(),
                                binding.targetIds().contains(check.resourceId())))
                        ? ResolvedDataScope.allow(scope) : ResolvedDataScope.deny(scope, "TARGET_NOT_SELECTED");
            }
            if ("TEMPLATE".equalsIgnoreCase(check.resourceType())) {
                if (binding.targetIds().contains(check.resourceId())) return ResolvedDataScope.allow(scope);
                var placeholders = String.join(",", binding.targetIds().stream().map(id -> "?").toList());
                var args = new java.util.ArrayList<Object>();
                args.add(check.organizationId());
                args.add(check.resourceId());
                args.add(check.resourceId());
                args.add(check.userId());
                args.addAll(binding.targetIds());
                return exists("SELECT EXISTS (SELECT 1 FROM tpl.template t " +
                                "LEFT JOIN tpl.template_version v ON v.template_id = t.id " +
                                "WHERE t.organization_id = ? AND (t.id = ? OR v.id = ?) " +
                                "AND (t.created_by = ? OR t.id IN (" + placeholders + ")))",
                        args.toArray(), scope, "TARGET_NOT_SELECTED");
            }
            var directTable = switch (check.resourceType() == null ? "" : check.resourceType().toUpperCase(Locale.ROOT)) {
                case "PROJECT" -> "mdm.project";
                case "SPECTRUM" -> "spc.chart_asset";
                case "KNOWLEDGE" -> "kb.document";
                case "DATA" -> "data.import_job";
                case "INVENTORY" -> "mfg.inventory_product";
                case "TEMPLATE_IMPORT" -> "tpl.template_import_job";
                case "EXPERIMENT_IMPORT" -> "rnd.experiment_import_job";
                case "RESEARCH_TEST_UPLOAD" -> "rnd.research_test_upload";
                case "QUALITY_UPLOAD" -> "quality.upload_file";
                case "KNOWLEDGE_UPLOAD" -> "kb.upload_task";
                case "SPECTRUM_UPLOAD" -> "spc.chart_upload_attempt";
                case "FILE" -> "ops.file_object";
                default -> null;
            };
            if (directTable != null) {
                if (binding.targetIds().contains(check.resourceId())) return ResolvedDataScope.allow(scope);
                var creatorColumn = "KNOWLEDGE_UPLOAD".equalsIgnoreCase(check.resourceType()) ? "actor_id" : "created_by";
                return exists("SELECT EXISTS (SELECT 1 FROM " + directTable
                                + " WHERE organization_id = ? AND id = ? AND " + creatorColumn + " = ?)",
                        new Object[]{check.organizationId(), check.resourceId(), check.userId()}, scope, "TARGET_NOT_SELECTED");
            }
            return binding.targetIds().contains(check.resourceId())
                    ? ResolvedDataScope.allow(scope)
                    : ResolvedDataScope.deny(scope, "TARGET_NOT_SELECTED");
        }
        var resourceType = check.resourceType() == null ? "" : check.resourceType().toUpperCase(Locale.ROOT);
        return switch (scope) {
            case "DEPARTMENT" -> department(resourceType, check);
            case "SELF", "CREATOR" -> self(resourceType, check);
            case "ASSIGNED" -> assigned(resourceType, check);
            case "OWNER" -> relationOwner(resourceType, check, "OWNER");
            case "MANAGER" -> relationOwner(resourceType, check, "MANAGER");
            case "PARTICIPANT", "PROJECT_GROUP" -> participant(resourceType, check);
            case "PROJECT" -> "PROJECT".equals(resourceType)
                    ? selectedProject(check, Set.copyOf(binding.targetIds()))
                    : relationScope(resourceType, check, Set.copyOf(binding.targetIds()), "project_id", "NOT_IN_SELECTED_PROJECT");
            case "CATEGORY" -> relationScope(resourceType, check, Set.copyOf(binding.targetIds()), "category_id", "CATEGORY_NOT_SELECTED");
            default -> ResolvedDataScope.deny(scope, "UNKNOWN_DATA_SCOPE");
        };
    }

    private ResolvedDataScope selectedProject(PermissionCheck check, Set<UUID> targetIds) {
        if (targetIds.isEmpty()) return ResolvedDataScope.deny("PROJECT", "EMPTY_SCOPE");
        return targetIds.contains(check.resourceId())
                ? ResolvedDataScope.allow("PROJECT")
                : ResolvedDataScope.deny("PROJECT", "NOT_IN_SELECTED_PROJECT");
    }

    private ResolvedDataScope department(String resourceType, PermissionCheck check) {
        var table = scopedTable(resourceType);
        if (table == null)
            return ResolvedDataScope.deny("DEPARTMENT", "RESOURCE_SCOPE_UNSUPPORTED");
        return exists("""
                SELECT EXISTS (SELECT 1 FROM %s p
                  JOIN iam.app_user u ON u.id = ? AND u.organization_id = p.organization_id
                  WHERE p.id = ? AND p.organization_id = ? %s
                    AND p.department_id IS NOT NULL AND p.department_id = u.department_id)
                """.formatted(table, deletedCondition(resourceType)), new Object[]{check.userId(), check.resourceId(), check.organizationId()},
                "DEPARTMENT", "NOT_IN_DEPARTMENT");
    }

    private String scopedTable(String resourceType) {
        return switch (resourceType == null ? "" : resourceType.toUpperCase(Locale.ROOT)) {
            case "CUSTOMER" -> "mdm.business_partner";
            case "PROJECT" -> "mdm.project";
            case "EXPERIMENT" -> "rnd.experiment";
            case "EXPERIMENT_IMPORT" -> "rnd.experiment_import_job";
            case "RESEARCH_TEST" -> "rnd.research_test_record";
            case "RESEARCH_TEST_UPLOAD" -> "rnd.research_test_upload";
            case "TEMPLATE" -> "tpl.template";
            case "TEMPLATE_IMPORT" -> "tpl.template_import_job";
            case "KNOWLEDGE" -> "kb.document";
            case "DATA" -> "data.import_job";
            case "QUALITY" -> "quality.data_record";
            case "QUALITY_UPLOAD" -> "quality.upload_file";
            case "PRODUCTION" -> "mfg.production_order";
            case "PRODUCTION_UPLOAD" -> "mfg.production_upload";
            case "FILE" -> "ops.file_object";
            case "SPECTRUM" -> "spc.chart_asset";
            case "INVENTORY" -> "mfg.inventory_product";
            default -> null;
        };
    }

    private String deletedCondition(String resourceType) {
        return Set.of("CUSTOMER", "PROJECT", "EXPERIMENT", "RESEARCH_TEST", "QUALITY")
                .contains(resourceType == null ? "" : resourceType.toUpperCase(Locale.ROOT))
                ? " AND deleted = false" : "";
    }

    private ResolvedDataScope self(String resourceType, PermissionCheck check) {
        Boolean found;
        Object[] args;
        String sql;
        switch (resourceType) {
            case "CUSTOMER" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM mdm.business_partner WHERE organization_id = ? AND created_by::text = ? AND id = ? AND deleted = false)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "TEMPLATE" -> {
                sql = """
                        SELECT EXISTS (SELECT 1 FROM tpl.template t
                                       WHERE t.organization_id = ? AND t.created_by = ?
                                         AND (t.id = ? OR EXISTS (SELECT 1 FROM tpl.template_version v
                                                                 WHERE v.template_id = t.id AND v.id = ?)))
                        """;
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId(), check.resourceId()};
            }
            case "TEMPLATE_IMPORT" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM tpl.template_import_job WHERE organization_id = ? AND created_by = ? AND id = ?)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "KNOWLEDGE" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM kb.document WHERE organization_id = ? AND created_by = ? AND id = ?)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "DATA" -> {
                sql = """
                        SELECT EXISTS (SELECT 1 FROM data.data_record r JOIN data.import_job j ON j.id=r.import_job_id
                                       WHERE r.organization_id = ? AND j.created_by = ? AND r.id = ?)
                        OR EXISTS (SELECT 1 FROM data.import_job WHERE organization_id = ? AND created_by = ? AND id = ?)
                        """;
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId(),
                        check.organizationId(), check.userId(), check.resourceId()};
            }
            case "EXPERIMENT" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM rnd.experiment WHERE organization_id = ? AND created_by = ? AND id = ? AND deleted = false)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "EXPERIMENT_IMPORT" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM rnd.experiment_import_job WHERE organization_id = ? AND created_by = ? AND id = ?)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "RESEARCH_TEST_UPLOAD" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM rnd.research_test_upload WHERE organization_id = ? AND created_by = ? AND id = ? AND deleted = false)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "QUALITY_UPLOAD" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM quality.upload_file WHERE organization_id = ? AND created_by = ? AND id = ? AND deleted = false)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "PRODUCTION" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM mfg.production_order WHERE organization_id = ? AND created_by = ? AND id = ?)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "PRODUCTION_UPLOAD" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM mfg.production_upload WHERE organization_id = ? AND created_by = ? AND id = ? AND status <> 'DELETED')";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "SPECTRUM" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM spc.chart_asset WHERE organization_id = ? AND created_by = ? AND id = ? AND status <> 'DELETED')";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "QUALITY" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM quality.data_record WHERE organization_id = ? AND created_by = ? AND id = ? AND deleted = false)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "PROJECT_DOCUMENT" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM mdm.project_document d JOIN mdm.project p ON p.id=d.project_id "
                        + "JOIN iam.app_user u ON u.organization_id=p.organization_id AND u.id=? "
                        + "WHERE p.organization_id=? AND d.id=? AND d.deleted=false "
                        + "AND (d.created_by=u.username OR p.created_by=u.username))";
                args = new Object[]{check.userId(), check.organizationId(), check.resourceId()};
            }
            case "RESEARCH_TEST" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM rnd.research_test_record WHERE organization_id = ? AND created_by = ? AND id = ? AND deleted = false)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            // 库存业务（流水、发样、出货）都挂在库存产品上，部门与创建人范围统一按产品判定。
            case "INVENTORY" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM mfg.inventory_product WHERE organization_id = ? AND created_by = ? AND id = ?)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "PROJECT" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM mdm.project WHERE organization_id = ? AND created_by::text = ? AND id = ? AND deleted = false)";
                args = new Object[]{check.organizationId(), check.userId().toString(), check.resourceId()};
            }
            case "QUOTATION" -> {
                // 上传任务与报价记录共用报价权限：重试/删除上传记录时资源 id 是导入任务 id。
                sql = """
                        SELECT EXISTS (SELECT 1 FROM rnd.quotation_record
                                       WHERE organization_id = ? AND created_by = ? AND id = ? AND deleted = false)
                            OR EXISTS (SELECT 1 FROM rnd.quotation_import_job
                                       WHERE organization_id = ? AND created_by = ? AND id = ?)
                        """;
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId(),
                        check.organizationId(), check.userId(), check.resourceId()};
            }
            case "FILE" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM ops.file_object WHERE organization_id = ? AND created_by = ? AND id = ?)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            default -> { return ResolvedDataScope.deny(resourceType, "RESOURCE_SCOPE_UNSUPPORTED"); }
        }
        found = jdbc.queryForObject(sql, Boolean.class, args);
        return Boolean.TRUE.equals(found)
                ? ResolvedDataScope.allow("SELF")
                : ResolvedDataScope.deny("SELF", "SELF_NOT_OWNER");
    }

    private ResolvedDataScope assigned(String resourceType, PermissionCheck check) {
        String sql;
        return switch (resourceType) {
            case "DATA" -> exists("SELECT EXISTS (SELECT 1 FROM data.publication WHERE organization_id = ? AND reviewer_id = ? AND import_job_id = ?)",
                    new Object[]{check.organizationId(), check.userId(), check.resourceId()}, "ASSIGNED", "NOT_ASSIGNED");
            case "PRODUCTION" -> exists("SELECT EXISTS (SELECT 1 FROM mfg.production_order WHERE organization_id = ? AND owner_id = ? AND id = ?)",
                    new Object[]{check.organizationId(), check.userId(), check.resourceId()}, "ASSIGNED", "NOT_ASSIGNED");
            case "EXPERIMENT" -> exists("SELECT EXISTS (SELECT 1 FROM rnd.experiment WHERE organization_id = ? AND owner_id = ? AND id = ?)",
                    new Object[]{check.organizationId(), check.userId(), check.resourceId()}, "ASSIGNED", "NOT_ASSIGNED");
            default -> ResolvedDataScope.deny("ASSIGNED", "RESOURCE_SCOPE_UNSUPPORTED");
        };
    }

    private ResolvedDataScope relationOwner(String resourceType, PermissionCheck check, String relationType) {
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM iam.resource_owner_identity
                    WHERE organization_id = ? AND resource_type = ? AND resource_id = ?
                      AND user_id = ? AND relation_type = ?
                )
                """, Boolean.class, check.organizationId(), resourceType, check.resourceId(), check.userId(), relationType);
        return Boolean.TRUE.equals(found)
                ? ResolvedDataScope.allow(relationType)
                : ResolvedDataScope.deny(relationType, "NOT_IN_RESOURCE_RELATION");
    }

    private ResolvedDataScope participant(String resourceType, PermissionCheck check) {
        if ("PROJECT".equals(resourceType)) {
            Boolean found = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM iam.project_member
                                   WHERE organization_id = ? AND project_id = ? AND user_id = ?)
                    """, Boolean.class, check.organizationId(), check.resourceId(), check.userId());
            return Boolean.TRUE.equals(found)
                    ? ResolvedDataScope.allow("PROJECT_GROUP")
                    : ResolvedDataScope.deny("PROJECT_GROUP", "NOT_PROJECT_MEMBER");
        }
        var sql = """
                SELECT EXISTS (
                    SELECT 1 FROM core.project_resource_link l
                    JOIN iam.project_member m ON m.organization_id = l.organization_id
                                               AND m.project_id = l.project_id
                                               AND m.user_id = ?
                    WHERE l.organization_id = ? AND l.resource_type = ? AND l.resource_id = ?
                )
                """;
        Boolean found = jdbc.queryForObject(sql, Boolean.class, check.userId(), check.organizationId(), resourceType,
                check.resourceId());
        return Boolean.TRUE.equals(found)
                ? ResolvedDataScope.allow("PROJECT_GROUP")
                : ResolvedDataScope.deny("PROJECT_GROUP", "NOT_PROJECT_MEMBER");
    }

    private ResolvedDataScope relationScope(String resourceType, PermissionCheck check, Set<UUID> targetIds,
                                            String relationColumn, String reason) {
        if (targetIds.isEmpty()) return ResolvedDataScope.deny(relationColumn.equals("project_id") ? "PROJECT" : "CATEGORY", "EMPTY_SCOPE");
        if ("DATA".equals(resourceType)) {
            var placeholders = String.join(",", targetIds.stream().map(ignored -> "?").toList());
            var args = new java.util.ArrayList<Object>(); args.add(check.organizationId()); args.add(check.resourceId()); args.addAll(targetIds);
            var sql = "SELECT EXISTS(SELECT 1 FROM data.import_job j WHERE j.organization_id=? AND j.id=? AND "
                    + ("category_id".equals(relationColumn) ? "j.category_id IN (" + placeholders + ")"
                    : "EXISTS(SELECT 1 FROM core.project_resource_link l WHERE l.organization_id=j.organization_id AND l.resource_type='DATA_IMPORT_JOB' AND l.resource_id=j.id AND l.project_id IN (" + placeholders + "))") + ")";
            return exists(sql, args.toArray(), "project_id".equals(relationColumn) ? "PROJECT" : "CATEGORY", reason);
        }
        String table;
        String idColumn = "id";
        if ("TEMPLATE".equals(resourceType)) table = "tpl.template";
        else if ("KNOWLEDGE".equals(resourceType)) table = "kb.document";
        else if ("EXPERIMENT".equals(resourceType)) table = "rnd.experiment";
        else if ("PRODUCTION".equals(resourceType)) table = "mfg.production_order";
        else return ResolvedDataScope.deny(relationColumn.equals("project_id") ? "PROJECT" : "CATEGORY", "RESOURCE_SCOPE_UNSUPPORTED");
        if (!"EXPERIMENT".equals(resourceType) && "project_id".equals(relationColumn)) {
            return ResolvedDataScope.deny("PROJECT", "RESOURCE_SCOPE_UNSUPPORTED");
        }
        if (!Set.of("TEMPLATE", "KNOWLEDGE").contains(resourceType) && "category_id".equals(relationColumn)) {
            return ResolvedDataScope.deny("CATEGORY", "RESOURCE_SCOPE_UNSUPPORTED");
        }
        var placeholders = String.join(",", targetIds.stream().map(ignored -> "?").toList());
        var sql = "SELECT EXISTS (SELECT 1 FROM " + table + " WHERE organization_id = ? AND "
                + relationColumn + " IN (" + placeholders + ") AND " + idColumn + " = ?)";
        var args = new Object[2 + targetIds.size()];
        args[0] = check.organizationId();
        int index = 1;
        for (UUID targetId : targetIds) args[index++] = targetId;
        args[index] = check.resourceId();
        var scope = "project_id".equals(relationColumn) ? "PROJECT" : "CATEGORY";
        return exists(sql, args, scope, reason);
    }

    private ResolvedDataScope exists(String sql, Object[] args, String scope, String reason) {
        Boolean found = jdbc.queryForObject(sql, Boolean.class, args);
        return Boolean.TRUE.equals(found) ? ResolvedDataScope.allow(scope) : ResolvedDataScope.deny(scope, reason);
    }
}
