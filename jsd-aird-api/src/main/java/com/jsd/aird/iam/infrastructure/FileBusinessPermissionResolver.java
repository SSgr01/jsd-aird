package com.jsd.aird.iam.infrastructure;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.business.BusinessDataAccess;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final List<BusinessDataAccess> businessAccess;

    public FileBusinessPermissionResolver(JdbcTemplate jdbc, AuthorizationService authorization) {
        this(jdbc, authorization, List.of());
    }

    @Autowired
    public FileBusinessPermissionResolver(JdbcTemplate jdbc, AuthorizationService authorization, List<BusinessDataAccess> businessAccess) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.businessAccess = businessAccess;
    }

    public boolean mayRead(Actor actor, UUID fileId) {
        var creator = jdbc.query("SELECT created_by FROM ops.file_object WHERE organization_id = ? AND id = ? AND status <> 'DELETED'",
                (rs, row) -> rs.getObject(1, UUID.class), actor.organizationId(), fileId);
        if (creator.isEmpty()) return false;
        if (!businessAccess.isEmpty()) {
            var sources=jdbc.queryForList("""
                SELECT 'QUALITY_'||upper(business_type) type,id FROM quality.data_record WHERE organization_id=? AND source_file_id=? AND deleted=false
                UNION SELECT 'QUALITY_UPLOAD',id FROM quality.upload_file WHERE organization_id=? AND file_id=? AND deleted=false
                UNION SELECT 'PRODUCTION_UPLOAD',id FROM mfg.production_upload WHERE organization_id=? AND file_id=? AND status<>'DELETED'
                UNION SELECT 'PRODUCTION_ORDER',o.id FROM mfg.production_order o WHERE o.organization_id=? AND (o.draft_editor_snapshot_file_id=? OR EXISTS(SELECT 1 FROM tpl.record_revision r WHERE r.production_order_id=o.id AND r.editor_snapshot_file_id=?) OR EXISTS(SELECT 1 FROM tpl.record_attachment a WHERE a.production_order_id=o.id AND a.file_object_id=?) OR EXISTS(SELECT 1 FROM mfg.production_ingest_source s JOIN mfg.production_ingest_job j ON j.id=s.ingest_job_id WHERE j.production_order_id=o.id AND j.organization_id=o.organization_id AND j.status='CONFIRMED' AND s.file_object_id=?))
                UNION SELECT 'RESEARCH_TEST',id FROM rnd.research_test_record WHERE organization_id=? AND source_file_id=? AND deleted=false
                UNION SELECT 'RESEARCH_TEST_UPLOAD',id FROM rnd.research_test_upload WHERE organization_id=? AND file_id=? AND deleted=false
                UNION SELECT 'DATA_IMPORT',id FROM data.import_job WHERE organization_id=? AND source_file_id=? AND source_owner='DATA_CENTER'
                UNION SELECT 'PROJECT_DOCUMENT',d.id FROM mdm.project_document d JOIN mdm.project p ON p.id=d.project_id WHERE p.organization_id=? AND d.file_object_id=? AND d.deleted=false AND p.deleted=false
                UNION SELECT 'PROJECT_REFERENCE',id FROM core.project_reference WHERE organization_id=? AND file_object_id=? AND status='ACTIVE'
                UNION SELECT 'SPECTRUM',id FROM spc.chart_asset WHERE organization_id=? AND file_object_id=? AND status<>'DELETED'
                """, actor.organizationId(),fileId,actor.organizationId(),fileId,actor.organizationId(),fileId,
                actor.organizationId(),fileId,fileId,fileId,fileId,actor.organizationId(),fileId,actor.organizationId(),fileId,
                actor.organizationId(),fileId,actor.organizationId(),fileId,actor.organizationId(),fileId,actor.organizationId(),fileId);
            for(var source:sources) {
                String type=source.get("type").toString();UUID id=(UUID)source.get("id");
                for(var policy:businessAccess)if(policy.resourceTypes().contains(type)
                    && policy.allowed(actor,type,id,"READ",JsonNodeFactory.instance.objectNode())
                    && policy.allowed(actor,type,id,"DOWNLOAD",JsonNodeFactory.instance.objectNode()))return true;
            }
        }
        var args = new Object[33];
        for (var index = 0; index < 32; index += 2) {
            args[index] = actor.organizationId();
            args[index + 1] = fileId;
        }
        args[32] = fileId;
        List<Association> associations = jdbc.query(ASSOCIATIONS,
                (rs, row) -> new Association(rs.getString(1), rs.getObject(2, UUID.class)), args);
        for (var association : associations) {
            // These domains use their record policy above, including explicit download denial.
            if(!businessAccess.isEmpty() && (association.permissionCode().startsWith("quality.")
                || association.permissionCode().startsWith("production.") || association.permissionCode().startsWith("research-test.")
                || association.permissionCode().equals("data.view.view") || association.permissionCode().equals("project.reference.view")
                || association.permissionCode().equals("spectrum.view.view")))continue;
            String transfer=switch(association.permissionCode()) {
                case "knowledge.view.view" -> "knowledge.download";
                case "spectrum.upload.view" -> "spectrum.view.download";
                default -> association.permissionCode().replaceFirst("\\.view$",".download");
            };
            // Apply explicit download denials before the legacy scope projection below.
            if(!authorization.check(new PermissionCheck(actor.organizationId(),actor.userId(),transfer,"FILE",null,"READ")).allowed())continue;
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
