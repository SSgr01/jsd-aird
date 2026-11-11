package com.jsd.aird.iam.infrastructure;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.security.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FileBusinessPermissionResolver {
    private static final String ASSOCIATIONS = """
            SELECT CASE WHEN source_owner = 'EXPERIMENT' THEN 'eln.upload.view' ELSE 'data.view.view' END AS permission_code,
                   id AS resource_id FROM data.import_job
             WHERE organization_id = ? AND source_file_id = ?
            UNION ALL SELECT 'knowledge.view.view', v.document_id FROM kb.document_version v
             JOIN kb.document d ON d.id = v.document_id
             WHERE d.organization_id = ? AND v.file_object_id = ?
            UNION ALL SELECT 'knowledge.upload.view', id FROM kb.upload_task
             WHERE organization_id = ? AND source_file_id = ?
            UNION ALL SELECT 'quality.upload.view', id FROM quality.upload_file
             WHERE organization_id = ? AND file_id = ? AND deleted = false
            UNION ALL SELECT 'quality.view.view', id FROM quality.data_record
             WHERE organization_id = ? AND source_file_id = ? AND deleted = false
            UNION ALL SELECT 'production.upload.view', id FROM mfg.production_upload
             WHERE organization_id = ? AND file_id = ?
            UNION ALL SELECT 'production.view.view', id FROM mfg.production_order
             WHERE organization_id = ? AND draft_editor_snapshot_file_id = ?
            UNION ALL SELECT 'production.view.view', rr.production_order_id FROM tpl.record_revision rr
             JOIN mfg.production_order po ON po.id = rr.production_order_id
             WHERE po.organization_id = ? AND rr.editor_snapshot_file_id = ?
            UNION ALL SELECT 'production.view.view', a.production_order_id FROM tpl.record_attachment a
             JOIN mfg.production_order po ON po.id = a.production_order_id
             WHERE po.organization_id = ? AND a.file_object_id = ?
            UNION ALL SELECT 'eln.upload.view', id FROM rnd.experiment_import_job
             WHERE organization_id = ? AND source_file_id = ?
            UNION ALL SELECT CASE WHEN r.record_type = 'STANDARD' THEN 'research-test.standard.upload.view'
                                  ELSE 'research-test.report.upload.view' END, u.id
              FROM rnd.research_test_upload u JOIN rnd.research_test_record r ON r.id = u.record_id
             WHERE u.organization_id = ? AND u.file_id = ?
            UNION ALL SELECT CASE WHEN record_type = 'STANDARD' THEN 'research-test.standard.view'
                                  ELSE 'research-test.report.view.view' END, id FROM rnd.research_test_record
             WHERE organization_id = ? AND source_file_id = ?
            UNION ALL SELECT 'spectrum.view.view', id FROM spc.chart_asset
             WHERE organization_id = ? AND file_object_id = ?
            UNION ALL SELECT 'spectrum.upload.view', id FROM spc.chart_upload_attempt
             WHERE organization_id = ? AND file_object_id = ?
            UNION ALL SELECT 'template.upload.view', id FROM tpl.template_import_job
             WHERE organization_id = ? AND (source_file_id = ? OR original_source_file_id = ?)
            UNION ALL SELECT 'project.reference.view', id FROM core.project_reference
             WHERE organization_id = ? AND file_object_id = ? AND status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    public FileBusinessPermissionResolver(JdbcTemplate jdbc, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    public boolean mayRead(Actor actor, UUID fileId) {
        var creator = jdbc.query("SELECT created_by FROM ops.file_object WHERE organization_id = ? AND id = ? AND status <> 'DELETED'",
                (rs, row) -> rs.getObject(1, UUID.class), actor.organizationId(), fileId);
        if (creator.isEmpty()) return false;
        var args = new Object[33];
        for (var index = 0; index < 32; index += 2) {
            args[index] = actor.organizationId();
            args[index + 1] = fileId;
        }
        args[32] = fileId;
        List<Association> associations = jdbc.query(ASSOCIATIONS,
                (rs, row) -> new Association(rs.getString(1), rs.getObject(2, UUID.class)), args);
        for (var association : associations) {
            var scope = authorization.resolveScope(new PermissionCheck(actor.organizationId(), actor.userId(),
                    association.permissionCode(), "FILE", null, "READ"));
            if (!scope.allowed()) continue;
            var uploadTable = switch (association.permissionCode()) {
                case "eln.upload.view" -> "rnd.experiment_import_job";
                case "quality.upload.view" -> "quality.upload_file";
                case "production.upload.view" -> "mfg.production_upload";
                case "research-test.report.upload.view", "research-test.standard.upload.view" -> "rnd.research_test_upload";
                case "template.upload.view" -> "tpl.template_import_job";
                default -> null;
            };
            if ("eln.upload.view".equals(association.permissionCode())
                    && Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM data.import_job WHERE organization_id = ? AND id = ? AND source_owner = 'EXPERIMENT')",
                            Boolean.class, actor.organizationId(), association.resourceId()))) {
                uploadTable = "data.import_job";
            }
            if ("ALL".equals(scope.scopeType()) || "ORG".equals(scope.scopeType())) return true;
            if (uploadTable != null
                    && ("SELF".equals(scope.scopeType()) || "CREATOR".equals(scope.scopeType()))
                    && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + uploadTable
                            + " job WHERE job.organization_id = ? AND job.id = ? AND job.created_by = ?)",
                            Boolean.class, actor.organizationId(), association.resourceId(), actor.userId()))) return true;
            if (uploadTable == null
                    && ("SELF".equals(scope.scopeType()) || "CREATOR".equals(scope.scopeType()))
                    && actor.userId().equals(creator.getFirst())) return true;
            if ("SELECTED".equals(scope.scopeType())) {
                var resourceType = switch (association.permissionCode()) {
                    case "data.view.view" -> "DATA";
                    case "knowledge.view.view" -> "KNOWLEDGE";
                    case "knowledge.upload.view" -> "KNOWLEDGE_UPLOAD";
                    case "quality.upload.view" -> "QUALITY_UPLOAD";
                    case "quality.view.view" -> "QUALITY";
                    case "production.upload.view" -> "PRODUCTION_UPLOAD";
                    case "production.view.view" -> "PRODUCTION";
                    case "research-test.report.upload.view", "research-test.standard.upload.view" -> "RESEARCH_TEST_UPLOAD";
                    case "research-test.report.view.view", "research-test.standard.view" -> "RESEARCH_TEST";
                    case "spectrum.view.view" -> "SPECTRUM";
                    case "spectrum.upload.view" -> "SPECTRUM_UPLOAD";
                    case "template.upload.view" -> "TEMPLATE_IMPORT";
                    case "eln.upload.view" -> "data.import_job".equals(uploadTable) ? "DATA" : "EXPERIMENT_IMPORT";
                    default -> null;
                };
                if (resourceType != null && authorization.check(new PermissionCheck(actor.organizationId(), actor.userId(),
                        association.permissionCode(), resourceType, association.resourceId(), "READ")).allowed()) return true;
                if (resourceType == null && scope.targetIds().contains(association.resourceId())) return true;
            }
            if (uploadTable != null && "DEPARTMENT".equals(scope.scopeType())
                    && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + uploadTable
                            + " job JOIN iam.app_user viewer ON viewer.id = ? AND viewer.organization_id = job.organization_id"
                            + " WHERE job.organization_id = ? AND job.id = ?"
                            + " AND job.department_id IS NOT NULL AND job.department_id = viewer.department_id)",
                            Boolean.class, actor.userId(), actor.organizationId(), association.resourceId()))) return true;
        }
        return false;
    }

    private record Association(String permissionCode, UUID resourceId) { }
}
