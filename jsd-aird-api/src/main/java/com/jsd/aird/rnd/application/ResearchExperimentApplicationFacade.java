package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.rnd.api.ResearchExperimentFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.lab.LabWorkbook;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResearchExperimentApplicationFacade implements ResearchExperimentFacade {
    private final ExperimentRepository repository;
    private final ExperimentService experiments;
    private final AuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonCanonicalizer canonical;

    public ResearchExperimentApplicationFacade(ExperimentRepository repository, ExperimentService experiments,
            AuthorizationService authorization, JdbcTemplate jdbc, ObjectMapper json, JsonCanonicalizer canonical) {
        this.repository=repository; this.experiments=experiments; this.authorization=authorization;
        this.jdbc=jdbc; this.json=json; this.canonical=canonical;
    }

    @Override public void requireReadableVersion(UUID org, UUID experiment, UUID version) {
        var actor=ActorContext.required();
        if(!org.equals(actor.organizationId()))throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED);
        authorization.require(new PermissionCheck(org,actor.userId(),"experiment.view","EXPERIMENT",experiment,"READ"));
        var exists=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM rnd.experiment e JOIN rnd.experiment_version v
                ON v.organization_id=e.organization_id AND v.experiment_id=e.id
                AND v.id=coalesce(?::uuid,e.current_version_id)
                WHERE e.organization_id=? AND e.id=? AND e.deleted=false)
            """,Boolean.class,version,org,experiment);
        if(!Boolean.TRUE.equals(exists))throw new ApiException(ApiErrorCode.NOT_FOUND);
    }

    @Override public boolean isCurrentCompletedVersion(UUID org, UUID experiment, UUID version) {
        requireReadableVersion(org,experiment,version);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM rnd.experiment WHERE organization_id=? AND id=?
                AND current_version_id=? AND status='COMPLETED' AND deleted=false)
            """,Boolean.class,org,experiment,version));
    }

    @Override public ObjectNode record(UUID org, UUID experiment, UUID version) {
        var actor=ActorContext.current();
        if(actor!=null) {
            if(!org.equals(actor.organizationId())) throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED);
            authorization.require(new PermissionCheck(org,actor.userId(),"experiment.view","EXPERIMENT",experiment,"READ"));
        }
        var detail=repository.detail(org,experiment).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        var saved=repository.version(org,experiment,version==null?detail.currentVersionId():version)
            .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        var result=json.createObjectNode().put("experimentId",experiment.toString()).put("versionId",saved.id().toString())
            .put("currentVersionId",detail.currentVersionId().toString()).put("status",saved.status())
            .put("currentStatus",detail.summary().status().name()).put("revision",detail.summary().revision())
            .put("title",detail.summary().title()).put("experimentNo",detail.summary().experimentNo())
            .put("categoryId",detail.summary().categoryId()==null?null:detail.summary().categoryId().toString());
        if(detail.summary().projectId()!=null)result.put("projectId",detail.summary().projectId().toString());
        result.set("editModel",saved.editModel()); result.set("templateSnapshot",saved.templateSnapshot());
        return result;
    }

    @Override @Transactional public ObjectNode saveMeasurements(ObjectNode args) {
        var actor=ActorContext.required(); var id=UUID.fromString(args.path("experiment_id").asText());
        authorization.require(new PermissionCheck(actor.organizationId(),actor.userId(),"experiment.update","EXPERIMENT",id,"WRITE"));
        var key=args.path("operation_key").asText();
        if(!key.matches("[a-f0-9]{64}"))throw new ApiException(ApiErrorCode.BAD_REQUEST);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,31))",actor.organizationId()+":"+actor.userId()+":"+key);
        var request=args.deepCopy(); request.remove("operation_key"); var hash=canonical.hash(request);
        var receipt=measurementReceipt(key);
        if(receipt.has("result")) {
            if(!hash.equals(receipt.path("request_hash").asText()))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return (ObjectNode)receipt.path("result");
        }
        var current=experiments.detail(id);var s=current.summary();
        if(!current.currentVersionId().toString().equals(args.path("version_id").asText()) || s.revision()!=args.path("revision").asLong(-1))
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT,"实验内容已变化，请读取当前版本后再填写");
        if(!Set.of("DRAFT","PENDING","IN_PROGRESS","RETURNED").contains(s.status().name()))
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"请在可编辑实验版本中填写实测值");
        if(!LabWorkbook.supported(current.templateSnapshot()))throw new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED);
        var model=(ObjectNode)current.editModel().deepCopy();var workbook=(ObjectNode)model.path("documentSnapshot");
        var sheet=LabWorkbook.sheet(workbook);var fields=LabWorkbook.definition(workbook).path("fields");
        var changes=args.path("measurements");
        if(!changes.isArray()||changes.isEmpty()||changes.size()>200)throw invalid("请提供一至二百项实测读数");
        var seen=new HashSet<String>();
        for(var entry:changes) {
            var sample=entry.path("sample_key").asText();var code=entry.path("field_code").asText();var field=fields.path(code);
            if(!"TEST".equals(field.path("domain").asText())||!"SAMPLE".equals(field.path("scope").asText()))throw invalid("请选择样品的实测指标");
            if(!seen.add(sample+":"+code))throw invalid("同一样品指标不能重复填写");
            int column=-1;
            for(var it=sheet.path("columnData").fields();it.hasNext();) {
                var c=it.next();if(sample.equals(c.getValue().path("custom").path("lab").path("sampleKey").asText()))column=Integer.parseInt(c.getKey());
            }
            var at=LabWorkbook.findField(sheet,code);
            if(column<0||at==null)throw invalid("样品或指标不属于当前实验版本");
            var value=entry.path("value");
            if(!value.isNumber()&&!value.isTextual() || value.isNumber()&&!Double.isFinite(value.asDouble()) || value.asText().length()>1000)
                throw invalid("请填写有效的实测读数");
            if(entry.hasNonNull("unit")&&!entry.path("unit").asText().equals(field.path("unit").asText())) {
                var converted=com.jsd.aird.shared.data.MeasurementValueParser.number(value.asText()+" "+entry.path("unit").asText(),field.path("unit").asText());
                if(converted==null)throw invalid("读数单位与实验指标不兼容，请确认后填写");value=json.getNodeFactory().numberNode(converted);
            }
            var cell=LabWorkbook.cell(sheet,at[0],column);cell.remove(List.of("p","f"));
            if(entry.has("method")||entry.has("conditions")) {
                var protocol=cell.withObject("/custom/measurementProtocol");
                if(entry.has("method")) {
                    var method=entry.path("method");
                    if(!method.isTextual()||method.asText().isBlank()||method.asText().length()>500)throw invalid("请填写明确的测定方法");
                    protocol.put("method",method.asText().strip());
                }
                if(entry.has("conditions")) {
                    if(!entry.path("conditions").isObject()||entry.path("conditions").toString().length()>4000)throw invalid("请按对象填写测定条件");
                    protocol.set("conditions",entry.path("conditions").deepCopy());
                }
            }
            var style=cell.path("s").isTextual()?workbook.path("styles").path(cell.path("s").asText()):cell.path("s");
            if(value.isNumber()&&style.path("n").path("pattern").asText().contains("%"))cell.put("v",value.decimalValue().movePointLeft(2));
            else cell.set("v",value.deepCopy());
            cell.put("t",value.isNumber()?2:1);
        }
        var saved=experiments.save(id,s.revision(),new ExperimentService.DraftCommand(s.experimentNo(),s.title(),s.categoryId(),s.categoryName(),
            s.projectId(),s.stageId(),s.taskId(),s.ownerName(),s.experimentDate(),current.templateVersionId(),current.templateSnapshotHash(),current.templateSnapshot(),model));
        var result=json.createObjectNode().put("id",id.toString()).put("version_id",saved.currentVersionId().toString())
            .put("revision",saved.summary().revision()).put("saved_count",changes.size()).put("status",saved.summary().status().name()).put("path","/experiments/"+id);
        var audit=json.createObjectNode().put("operation_key",key).put("request_hash",hash);audit.set("result",result);audit.set("suppliedReadings",changes.deepCopy());
        jdbc.update("INSERT INTO rnd.experiment_audit(id,organization_id,experiment_id,experiment_version_id,action,after_jsonb,operator_id,operator_name) VALUES(?,?,?,?,'AGENT_MEASUREMENTS_SAVED',?::jsonb,?,?)",
            UUID.randomUUID(),actor.organizationId(),id,saved.currentVersionId(),audit.toString(),actor.userId(),actor.username());
        return result;
    }

    @Override public ObjectNode measurementReceipt(String key) {
        var actor=ActorContext.required();
        var rows=jdbc.queryForList("SELECT after_jsonb::text FROM rnd.experiment_audit WHERE organization_id=? AND operator_id=? AND action='AGENT_MEASUREMENTS_SAVED' AND after_jsonb->>'operation_key'=?",String.class,actor.organizationId(),actor.userId(),key);
        if(rows.isEmpty())return json.createObjectNode().put("status","UNKNOWN");
        try {
            var result=(ObjectNode)json.readTree(rows.getFirst());
            record(actor.organizationId(),UUID.fromString(result.path("result").path("id").asText()),null);
            return result.put("status","SUCCEEDED");
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private ApiException invalid(String message){return new ApiException(ApiErrorCode.VALIDATION_ERROR,message);}
}
