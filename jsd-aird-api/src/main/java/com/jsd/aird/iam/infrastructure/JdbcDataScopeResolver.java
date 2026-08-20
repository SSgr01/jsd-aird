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
        if (check.resourceId() == null) {
            return switch (scope) {
                case "SELECTED", "PROJECT", "CATEGORY" -> binding.targetIds().isEmpty()
                        ? ResolvedDataScope.deny(scope, "EMPTY_SCOPE")
                        : ResolvedDataScope.allow(scope, Set.copyOf(binding.targetIds()));
                case "SELF", "ASSIGNED" -> ResolvedDataScope.allow(scope);
                default -> ResolvedDataScope.deny(scope, "UNKNOWN_DATA_SCOPE");
            };
        }
        if ("SELECTED".equals(scope)) {
            return binding.targetIds().contains(check.resourceId())
                    ? ResolvedDataScope.allow(scope)
                    : ResolvedDataScope.deny(scope, "TARGET_NOT_SELECTED");
        }
        var resourceType = check.resourceType() == null ? "" : check.resourceType().toUpperCase(Locale.ROOT);
        return switch (scope) {
            case "SELF" -> self(resourceType, check);
            case "ASSIGNED" -> assigned(resourceType, check);
            case "PROJECT" -> relationScope(resourceType, check, Set.copyOf(binding.targetIds()), "project_id", "NOT_IN_SELECTED_PROJECT");
            case "CATEGORY" -> relationScope(resourceType, check, Set.copyOf(binding.targetIds()), "category_id", "CATEGORY_NOT_SELECTED");
            default -> ResolvedDataScope.deny(scope, "UNKNOWN_DATA_SCOPE");
        };
    }

    private ResolvedDataScope self(String resourceType, PermissionCheck check) {
        Boolean found;
        Object[] args;
        String sql;
        switch (resourceType) {
            case "TEMPLATE" -> {
                sql = """
                        SELECT EXISTS (SELECT 1 FROM tpl.template t
                                       WHERE t.organization_id = ? AND t.created_by = ?
                                         AND (t.id = ? OR EXISTS (SELECT 1 FROM tpl.template_version v
                                                                 WHERE v.template_id = t.id AND v.id = ?)))
                        """;
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId(), check.resourceId()};
            }
            case "KNOWLEDGE" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM kb.document WHERE organization_id = ? AND created_by = ? AND id = ?)";
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId()};
            }
            case "DATA" -> {
                sql = """
                        SELECT EXISTS (SELECT 1 FROM data.data_record WHERE organization_id = ? AND created_by = ? AND id = ?)
                        OR EXISTS (SELECT 1 FROM data.import_job WHERE organization_id = ? AND created_by = ? AND id = ?)
                        """;
                args = new Object[]{check.organizationId(), check.userId(), check.resourceId(),
                        check.organizationId(), check.userId(), check.resourceId()};
            }
            case "EXPERIMENT" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM rnd.experiment WHERE organization_id = ? AND (created_by = ? OR owner_id = ?) AND id = ?)";
                args = new Object[]{check.organizationId(), check.userId(), check.userId(), check.resourceId()};
            }
            case "PRODUCTION" -> {
                sql = "SELECT EXISTS (SELECT 1 FROM mfg.production_order WHERE organization_id = ? AND (created_by = ? OR owner_id = ?) AND id = ?)";
                args = new Object[]{check.organizationId(), check.userId(), check.userId(), check.resourceId()};
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
            case "PRODUCTION" -> exists("SELECT EXISTS (SELECT 1 FROM mfg.production_order WHERE organization_id = ? AND owner_id = ? AND id = ?)",
                    new Object[]{check.organizationId(), check.userId(), check.resourceId()}, "ASSIGNED", "NOT_ASSIGNED");
            case "EXPERIMENT" -> exists("SELECT EXISTS (SELECT 1 FROM rnd.experiment WHERE organization_id = ? AND owner_id = ? AND id = ?)",
                    new Object[]{check.organizationId(), check.userId(), check.resourceId()}, "ASSIGNED", "NOT_ASSIGNED");
            default -> ResolvedDataScope.deny("ASSIGNED", "RESOURCE_SCOPE_UNSUPPORTED");
        };
    }

    private ResolvedDataScope relationScope(String resourceType, PermissionCheck check, Set<UUID> targetIds,
                                            String relationColumn, String reason) {
        if (targetIds.isEmpty()) return ResolvedDataScope.deny(relationColumn.equals("project_id") ? "PROJECT" : "CATEGORY", "EMPTY_SCOPE");
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
