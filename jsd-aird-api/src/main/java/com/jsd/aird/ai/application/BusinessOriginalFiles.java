package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.api.FileStorageFacade;
import com.jsd.aird.shared.business.BusinessReadPort;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

/** Original-file associations only. The caller must authorize the business record before listing, and EXPORT before opening. */
@Service
public class BusinessOriginalFiles {
    public record Entry(UUID file_id, String name, String content_type, long bytes, String sha256) { }
    private final JdbcTemplate jdbc;
    private final FileStorageFacade storage;
    public BusinessOriginalFiles(JdbcTemplate jdbc, FileStorageFacade storage) { this.jdbc = jdbc; this.storage = storage; }
    public static boolean supports(String type) { return type.startsWith("QUALITY_") || Set.of("PRODUCTION_UPLOAD","PRODUCTION_ORDER","RESEARCH_TEST","RESEARCH_TEST_UPLOAD","DATA_IMPORT","PROJECT_DOCUMENT","PROJECT_REFERENCE").contains(type); }

    public List<Entry> list(ObjectNode ref) {
        var org = ActorContext.required().organizationId();
        String type = ref.path("type").asText();
        String source = switch (type) {
            case "PRODUCTION_UPLOAD" -> "SELECT file_id FROM mfg.production_upload WHERE organization_id=? AND id=? AND status<>'DELETED'";
            case "QUALITY_UPLOAD" -> "SELECT file_id FROM quality.upload_file WHERE organization_id=? AND id=? AND deleted=false";
            case "RESEARCH_TEST_UPLOAD" -> "SELECT file_id FROM rnd.research_test_upload WHERE organization_id=? AND id=? AND deleted=false";
            case "DATA_IMPORT" -> "SELECT source_file_id FROM data.import_job WHERE organization_id=? AND id=?";
            case "PROJECT_DOCUMENT" -> "SELECT d.file_object_id FROM mdm.project_document d JOIN mdm.project p ON p.id=d.project_id WHERE p.organization_id=? AND d.id=? AND d.deleted=false AND p.deleted=false";
            case "PROJECT_REFERENCE" -> """
                SELECT r.file_object_id FROM core.project_reference r
                WHERE r.organization_id=? AND r.id=? AND r.status='ACTIVE' AND (
                  (r.resource_type='DATA_IMPORT_JOB' AND EXISTS (SELECT 1 FROM data.import_job j WHERE j.id=r.resource_id AND j.organization_id=r.organization_id AND j.source_file_id=r.file_object_id))
                  OR (r.resource_type='SPECTRUM_CHART' AND EXISTS (SELECT 1 FROM spc.chart_asset c WHERE c.id=r.resource_id AND c.organization_id=r.organization_id AND c.file_object_id=r.file_object_id AND c.status<>'DELETED'))
                  OR (r.resource_type='KNOWLEDGE_DOCUMENT' AND EXISTS (SELECT 1 FROM kb.publication p JOIN kb.document_version v ON v.id=p.document_version_id
                    WHERE p.organization_id=r.organization_id AND p.document_id=r.resource_id AND p.status='PUBLISHED' AND v.file_object_id=r.file_object_id
                    AND (r.file_version_id IS NULL OR v.id=r.file_version_id))))
                """;
            case "RESEARCH_TEST" -> "SELECT file_id FROM (SELECT source_file_id file_id FROM rnd.research_test_record WHERE organization_id=? AND id=? AND deleted=false UNION SELECT u.file_id FROM rnd.research_test_upload u JOIN rnd.research_test_record r ON r.id=u.record_id AND r.organization_id=u.organization_id WHERE r.organization_id=? AND r.id=? AND r.deleted=false AND u.deleted=false) sources";
            case "PRODUCTION_ORDER" -> "SELECT a.file_object_id FROM tpl.record_attachment a JOIN mfg.production_order o ON o.id=a.production_order_id WHERE o.organization_id=? AND o.id=?" + (ref.hasNonNull("version_id") ? " AND a.revision_id=?" : " UNION SELECT s.file_object_id FROM mfg.production_ingest_source s JOIN mfg.production_ingest_job j ON j.id=s.ingest_job_id WHERE j.organization_id=? AND j.production_order_id=? AND j.status='CONFIRMED'");
            default -> type.startsWith("QUALITY_")
                ? "SELECT file_id FROM (SELECT source_file_id file_id FROM quality.data_record WHERE organization_id=? AND id=? AND deleted=false UNION SELECT u.file_id FROM quality.upload_file u JOIN quality.data_record r ON r.id=u.generated_record_id AND r.organization_id=u.organization_id WHERE r.organization_id=? AND r.id=? AND r.deleted=false AND u.deleted=false) sources"
                : null;
        };
        if (source == null) return List.of();
        var args = new ArrayList<Object>(List.of(org, org, id(ref)));
        if ("RESEARCH_TEST".equals(type) || type.startsWith("QUALITY_") && !"QUALITY_UPLOAD".equals(type)) { args.add(org); args.add(id(ref)); }
        if ("PRODUCTION_ORDER".equals(type) && ref.hasNonNull("version_id")) args.add(uuid(ref,"version_id"));
        else if("PRODUCTION_ORDER".equals(type)){args.add(org);args.add(id(ref));}
        return jdbc.query("SELECT f.id,f.original_name,f.content_type,f.size_bytes,f.sha256 FROM ops.file_object f WHERE f.organization_id=? AND f.status<>'DELETED' AND f.id IN ("+source+") ORDER BY f.original_name,f.id",
            (rs,n) -> new Entry(rs.getObject("id",UUID.class),rs.getString("original_name"),rs.getString("content_type"),rs.getLong("size_bytes"),rs.getString("sha256")), args.toArray());
    }

    public BusinessReadPort.FileContent open(ObjectNode ref, UUID selected) {
        var files = list(ref);
        if (files.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND,"该业务对象没有原文件，或文件已被移除");
        if (selected == null && files.size()!=1) throw new ApiException(ApiErrorCode.BAD_REQUEST,"该记录有多个原文件，请从 files 目录选择 file_id");
        var entry = selected == null ? files.getFirst() : files.stream().filter(f -> f.file_id().equals(selected)).findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND,"所选文件不属于该业务对象"));
        try (var file = storage.open(ActorContext.required().organizationId(),entry.file_id())) {
            if (file.size()>8*1024*1024) throw new ApiException(ApiErrorCode.BAD_REQUEST,"原文件超过8MB，请通过业务页面下载后作为原生附件读取");
            byte[] content = file.stream().readNBytes(8*1024*1024+1);
            if (content.length>8*1024*1024) throw new ApiException(ApiErrorCode.BAD_REQUEST,"原文件超过8MB");
            String actual = hash(content);
            if (content.length!=entry.bytes() || !actual.equals(entry.sha256()) || !actual.equals(file.sha256()))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT,"原文件内容与登记哈希不一致，请重新读取文件目录");
            return new BusinessReadPort.FileContent(entry.name(),entry.content_type(),content,actual);
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw new ApiException(ApiErrorCode.NOT_FOUND,"原文件暂时无法读取"); }
    }
}
