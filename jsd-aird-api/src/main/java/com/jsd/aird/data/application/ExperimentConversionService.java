package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Owned source queue. AI can prepare an import, never confirm or publish it. */
@Service
public class ExperimentConversionService implements com.jsd.aird.data.api.ExperimentConversionFacade {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuthorizationService authorization;
    private final FileObjectRepository files;
    private final FileStorageFacade storage;
    private final TemplateDataImportFacade templates;
    private final DataImportService imports;
    private final com.jsd.aird.rnd.api.ExperimentDraftFacade experiments;

    public ExperimentConversionService(JdbcTemplate jdbc, ObjectMapper json, AuthorizationService authorization,
            FileObjectRepository files, FileStorageFacade storage, TemplateDataImportFacade templates, DataImportService imports,
            com.jsd.aird.rnd.api.ExperimentDraftFacade experiments) {
        this.jdbc=jdbc; this.json=json; this.authorization=authorization; this.files=files;
        this.storage=storage; this.templates=templates; this.imports=imports;
        this.experiments=experiments;
    }

    private void require(String action) {
        var actor=ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(),actor.userId(),"eln.upload."+action,"EXPERIMENT",null,
                action.equals("view")?"READ":"WRITE"));
    }

    @Transactional
    public JsonNode create(UUID batchId, UUID fileId, UUID templateId, UUID categoryId) {
        require("create"); require("view");
        var actor=ActorContext.required();
        var template=templates.getPublished(actor.organizationId(),templateId);
        var kind=switch(template.templateCode()) {
            case "EXP_TEST_UNIFIED" -> "test";
            case "EXP_SYNTHESIS_UNIFIED" -> "synthesis";
            default -> throw invalid("专家转换仅支持公司测试实验和合成实验标准模板");
        };
        if(template.importContractVersion()!=9) throw invalid("请选择已发布的实验模板");
        if(categoryId!=null)experiments.requireActiveCategory(actor.organizationId(),categoryId);
        var owned=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ops.file_object WHERE organization_id=? AND id=? AND created_by=? AND status IN ('STAGED','ACTIVE'))",
                Boolean.class,actor.organizationId(),fileId,actor.userId());
        if(!Boolean.TRUE.equals(owned)) throw new ApiException(ApiErrorCode.NOT_FOUND,"本人上传的原文件不存在");
        var file=files.find(actor.organizationId(),fileId).orElseThrow(()->invalid("原文件不存在"));
        if(!file.originalName().toLowerCase(Locale.ROOT).matches(".*\\.(xlsx|docx)$")) throw invalid("专家转换当前支持 XLSX / DOCX 原件");
        if(file.size()>8*1024*1024) throw invalid("专家转换单个原件暂限 8 MiB，请拆分文件后上传");
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,117))",actor.organizationId()+":"+actor.userId()+":"+batchId);
        var existing=jdbc.queryForList("SELECT id FROM data.experiment_conversion WHERE organization_id=? AND owner_id=? AND batch_id=? AND source_file_id=?",
                UUID.class,actor.organizationId(),actor.userId(),batchId,fileId);
        if(!existing.isEmpty()) return get(existing.getFirst(),false);
        if(jdbc.queryForObject("SELECT count(*) FROM data.experiment_conversion WHERE organization_id=? AND owner_id=? AND batch_id=?",
                Integer.class,actor.organizationId(),actor.userId(),batchId)>=100) throw invalid("每批最多 100 个文件");
        var id=UUID.randomUUID();
        files.activate(fileId);
        jdbc.update("INSERT INTO data.experiment_conversion(id,organization_id,owner_id,batch_id,source_file_id,source_name,source_sha256,template_version_id,category_id,kind) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,actor.organizationId(),actor.userId(),batchId,fileId,file.originalName(),file.sha256(),templateId,categoryId,kind);
        return get(id,false);
    }

    public List<ObjectNode> list(UUID batchId) {
        require("view"); var actor=ActorContext.required();
        return jdbc.query("SELECT * FROM data.experiment_conversion WHERE organization_id=? AND owner_id=?"+
                (batchId==null?"":" AND batch_id=?")+" ORDER BY created_at DESC LIMIT 100",(rs,n)->row(rs),
                batchId==null?new Object[]{actor.organizationId(),actor.userId()}:new Object[]{actor.organizationId(),actor.userId(),batchId});
    }

    public ObjectNode forImport(UUID importId) {
        require("view"); var actor=ActorContext.required();
        return jdbc.query("SELECT * FROM data.experiment_conversion WHERE organization_id=? AND owner_id=? AND import_job_id=?",
                (rs,n)->row(rs),actor.organizationId(),actor.userId(),importId).stream().findFirst().orElse(null);
    }

    public ObjectNode get(UUID id, boolean lock) {
        require("view"); var actor=ActorContext.required();
        return jdbc.query("SELECT * FROM data.experiment_conversion WHERE organization_id=? AND owner_id=? AND id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->row(rs),actor.organizationId(),actor.userId(),id).stream().findFirst()
                .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"转换任务不存在"));
    }

    public FileStorageFacade.StoredFile original(UUID id) {
        var task=get(id,false); require("create");
        var file=storage.open(ActorContext.required().organizationId(),uuid(task,"sourceFileId"));
        if(!file.sha256().equals(task.path("sourceSha256").asText())) throw invalid("原件指纹变化，请重新上传");
        return file;
    }

    /** Called with artifact bytes already checked by the AI artifact owner/export boundary. */
    @Transactional
    public ObjectNode stage(UUID id, UUID artifactId, UUID planId, UUID reportId,
            UUID resultFileId, JsonNode plan, JsonNode report, String resultSha) {
        require("create"); var task=get(id,true);
        if(task.hasNonNull("importJobId")) {
            if(!artifactId.toString().equals(task.path("resultArtifactId").asText()) ||
                    !planId.toString().equals(task.path("planArtifactId").asText()) ||
                    !reportId.toString().equals(task.path("reportArtifactId").asText()))
                throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT,"已有转换结果，请在核对工作台修正");
            return task;
        }
        validateResult(task,plan,report,resultSha);
        ((ObjectNode)report).put("human_review","pending");
        var job=imports.createExperiment(new DataImportService.CreateCommand(resultFileId,uuid(task,"templateVersionId"),null,false,List.of(),
                "EXPERIMENT_DRAFT",task.hasNonNull("categoryId")?uuid(task,"categoryId"):null));
        var actor=ActorContext.required();
        jdbc.update("UPDATE data.experiment_conversion SET status='WAITING_REVIEW',result_artifact_id=?,plan_artifact_id=?,report_artifact_id=?,import_job_id=?,report=?::jsonb,error_message=NULL,updated_at=now() WHERE organization_id=? AND id=?",
                artifactId,planId,reportId,job.id(),report.toString(),actor.organizationId(),id);
        return get(id,false);
    }

    public static void validateResult(JsonNode task, JsonNode plan, JsonNode report, String resultSha) {
        if(!task.path("sourceSha256").asText().equals(plan.path("source_sha256").asText()) ||
                !task.path("kind").asText().equals(plan.path("kind").asText())) throw invalid("映射计划与原件或分类不匹配");
        if(!task.path("sourceSha256").asText().equals(report.path("source_sha256").asText()) ||
                !resultSha.equals(report.path("output_sha256").asText())) throw invalid("校验报告与原件或结果指纹不匹配");
        if(!Set.of("validated","review_required").contains(report.path("status").asText())) throw invalid("请先执行转换并保存校验报告");
    }

    @Transactional
    public ObjectNode fail(UUID id,String message) {
        require("create"); var task=get(id,true);
        if(task.hasNonNull("importJobId")) return task;
        if(message==null||message.isBlank()||message.length()>2000) throw invalid("请提供具体文件的失败原因");
        jdbc.update("UPDATE data.experiment_conversion SET status='FAILED',error_message=?,updated_at=now() WHERE id=?",message,id);
        return get(id,false);
    }

    private ObjectNode row(java.sql.ResultSet rs) throws java.sql.SQLException {
        var node=json.createObjectNode();
        for(var pair:List.of(new String[]{"id","id"},new String[]{"batch_id","batchId"},new String[]{"source_file_id","sourceFileId"},
                new String[]{"source_name","sourceName"},new String[]{"source_sha256","sourceSha256"},new String[]{"template_version_id","templateVersionId"},
                new String[]{"category_id","categoryId"},new String[]{"kind","kind"},new String[]{"status","status"},new String[]{"result_artifact_id","resultArtifactId"},
                new String[]{"plan_artifact_id","planArtifactId"},new String[]{"report_artifact_id","reportArtifactId"},new String[]{"import_job_id","importJobId"},
                new String[]{"error_message","errorMessage"})) node.put(pair[1],rs.getString(pair[0]));
        node.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());
        node.put("updatedAt",rs.getTimestamp("updated_at").toInstant().toString());
        try {if(rs.getString("report")!=null) node.set("report",json.readTree(rs.getString("report")));}
        catch(Exception error){throw new IllegalStateException(error);}
        return node;
    }
    public static UUID uuid(JsonNode node,String key){return UUID.fromString(node.path(key).asText());}
    private static ApiException invalid(String message){return new ApiException(ApiErrorCode.VALIDATION_ERROR,message);}
}
