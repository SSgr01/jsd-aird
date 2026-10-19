package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** Registers the frozen workbench result without rebuilding its training data or model. */
@Service
public class WorkbenchModelRegistrationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConfigurationHashing hashing;
    private final FormulaModelArtifactStore artifacts;
    private final AuditLogFacade audit;

    public WorkbenchModelRegistrationService(JdbcTemplate jdbc, ObjectMapper json, ConfigurationHashing hashing,
                                             FormulaModelArtifactStore artifacts, AuditLogFacade audit) {
        this.jdbc=jdbc; this.json=json; this.hashing=hashing; this.artifacts=artifacts; this.audit=audit;
    }

    @Transactional
    public JsonNode register(UUID runId, String key) {
        var actor=ActorContext.required(); var org=actor.organizationId();
        if(key==null || key.isBlank() || key.length()>200) throw invalid("注册请求无效，请重新点击注册模型");
        var run=jdbc.query("SELECT * FROM ai.modeling_workbench_run WHERE organization_id=? AND created_by=? AND id=? FOR UPDATE",
                (rs,n)->json.createObjectNode().put("status",rs.getString("status"))
                    .put("datasetName",rs.getString("dataset_name")).put("artifactKey",rs.getString("model_artifact_key"))
                    .put("artifactHash",rs.getString("model_artifact_sha256"))
                    .<JsonNode>set("request",parse(rs.getString("request_jsonb"))),org,actor.userId(),runId)
                .stream().findFirst().orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"建模任务不存在或无权注册"));
        var existing=registration(org,runId); if(existing!=null) return existing;
        if(!"SUCCEEDED".equals(run.path("status").asText())) throw invalid("请等待训练成功后再注册模型");
        var artifact=artifacts.verify(run.path("artifactKey").asText(),run.path("artifactHash").asText(),"application/zip");
        var request=sanitizeMaterialMappings(run.path("request"));
        var result=jdbc.queryForObject("SELECT result_jsonb FROM ai.modeling_workbench_run WHERE organization_id=? AND id=?",
                (rs,n)->parse(rs.getString(1)),org,runId);
        var identity=familyIdentity(request); var familyHash=hashing.hash(identity);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(rs,n)->rs.getObject(1),org+":workbench-model:"+familyHash);
        var targetCode="WB_"+familyHash;
        var ids=jdbc.query("SELECT id,current_version_id,current_input_scheme_id FROM ai.prediction_target WHERE organization_id=? AND target_code=? FOR UPDATE",
                (rs,n)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class)},org,targetCode);
        var targetId=ids.isEmpty()?UUID.randomUUID():ids.getFirst()[0];
        var targetVersionId=UUID.randomUUID();
        int configurationVersion=jdbc.queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.target_version WHERE organization_id=? AND target_id=?",Integer.class,org,targetId);
        var schemeId=UUID.randomUUID();
        var y=request.path("yColumn"); var numeric="NUMERIC".equals(y.path("kind").asText());
        var valueType=numeric?"CONTINUOUS":"CATEGORICAL";
        var classes=json.createArrayNode();
        if(!numeric){var labels=new TreeSet<String>();request.path("rows").forEach(r->labels.add(r.path("values").path(y.path("code").asText()).asText().strip()));labels.forEach(classes::add);}
        var target=ref(targetVersionId);target.put("version",configurationVersion);target.put("code",targetCode).put("valueType",valueType);
        target.set("unit",y.path("unit").isMissingNode()?json.nullNode():y.path("unit"));
        target.set("classes",classes);target.set("observationSemantics",json.createObjectNode());
        var modelSource=target.putObject("modelSource").put("datasetName",run.path("datasetName").asText());
        modelSource.set("xColumns",request.path("xColumns"));modelSource.set("yColumn",y);
        for(var metadata:List.of("testMethod","testStage","testMethods","semanticKey","semanticType","materialId","materialCode","mappingStatus","compositionBasis","measurementComponent"))if(y.has(metadata))target.set(metadata,y.get(metadata));
        target.put("sha256",hashing.hash(target));
        var scheme=ref(schemeId);scheme.put("version",configurationVersion);scheme.put("targetVersionId",targetVersionId.toString());var fields=scheme.putArray("fields");
        for(var x:request.path("xColumns")){
            var code=x.path("code").asText();var field=fields.addObject().put("fieldVersionId",schemeId+":"+fields.size())
                    .put("code",code).put("name",x.path("name").asText()).put("valueType","NUMERIC".equals(x.path("kind").asText())?"NUMBER":"CATEGORY")
                    .put("required",true).put("acquisitionTiming","PRE_EXPERIMENT");
            if(x.hasNonNull("unit"))field.set("unit",x.path("unit"));
            for(var metadataKey:List.of("semanticKey","semanticType","materialId","materialCode","mappingStatus","compositionBasis","testMethods","testStage","measurementComponent"))if(x.has(metadataKey))field.set(metadataKey,x.get(metadataKey));
            var encoding=field.putObject("encoding");
            if("CATEGORICAL".equals(x.path("kind").asText())){var values=new TreeSet<String>();request.path("rows").forEach(r->values.add(r.path("values").path(code).asText().strip()));var allowed=encoding.putArray("allowedValues");values.forEach(allowed::add);}
        }
        scheme.put("sha256",hashing.hash(scheme));
        var materialDictionary=WorkbenchFormulaSupport.dictionary(json,scheme,request.path("rows"));
        var selectedCodes=new java.util.HashSet<String>();request.path("xColumns").forEach(column->selectedCodes.add(column.path("code").asText()));
        var missingMaterials=materialDictionary.putArray("missingMaterials");
        boolean hasMappedMaterial=false;
        for(var column:request.path("xColumns"))if(WorkbenchFormulaSupport.materialId(column)!=null){hasMappedMaterial=true;break;}
        if(hasMappedMaterial)for(var column:request.path("materialColumns"))
            if(WorkbenchFormulaSupport.materialId(column)==null||!selectedCodes.contains(column.path("code").asText()))
                missingMaterials.add(column.path("name").asText());
        if(hasMappedMaterial&&!missingMaterials.isEmpty())materialDictionary.put("compositionStatus","INCOMPLETE");
        materialDictionary.remove("sha256");materialDictionary.put("sha256",hashing.hash(materialDictionary));
        if(ids.isEmpty()){
            jdbc.update("INSERT INTO ai.prediction_target(id,organization_id,target_code,name,performance_project,value_type,status,created_by,updated_by) VALUES(?,?,?,?,'GENERAL_DATA',?,'ACTIVE',?,?)",
                    targetId,org,targetCode,y.path("name").asText(),valueType,actor.userId(),actor.userId());
        }
        {
            jdbc.update("INSERT INTO ai.target_version(id,organization_id,target_id,version_no,status,value_type,unit,classes_jsonb,definition_jsonb,config_hash,published_at,created_by) VALUES(?,?,?,?,'PUBLISHED',?,?,?,?,?,now(),?)",
                    targetVersionId,org,targetId,configurationVersion,valueType,y.hasNonNull("unit")?y.path("unit").asText():null,pg(classes),pg(target),target.path("sha256").asText(),actor.userId());
            jdbc.update("INSERT INTO ai.input_scheme(id,organization_id,target_id,target_version_id,scheme_code,name,version_no,status,preprocessing_jsonb,config_hash,frozen_at,created_by,updated_by) VALUES(?,?,?,?,?,'建模输入',?,'FROZEN',?,?,now(),?,?)",
                    schemeId,org,targetId,targetVersionId,"WB_"+familyHash,configurationVersion,pg(scheme),scheme.path("sha256").asText(),actor.userId(),actor.userId());
            jdbc.update("UPDATE ai.prediction_target SET current_version_id=?,current_input_scheme_id=? WHERE organization_id=? AND id=?",targetVersionId,schemeId,org,targetId);
        }
        var modelId=UUID.randomUUID();var artifactId=UUID.randomUUID();
        jdbc.update("INSERT INTO ai.artifact(id,organization_id,artifact_type,workbench_run_id,object_key,sha256,size_bytes,media_type) VALUES(?,?,'MODEL_BUNDLE',?,?,?,?, 'application/zip') ON CONFLICT DO NOTHING",
                artifactId,org,runId,artifact.objectKey(),artifact.sha256(),artifact.size());
        artifactId=jdbc.queryForObject("SELECT id FROM ai.artifact WHERE organization_id=? AND sha256=? AND artifact_type='MODEL_BUNDLE'",UUID.class,org,artifact.sha256());
        var previous=jdbc.query("SELECT id FROM ai.model_version WHERE organization_id=? AND target_id=? AND status='ACTIVE' FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),org,targetId).stream().findFirst().orElse(null);
        int version=jdbc.queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.model_version WHERE organization_id=? AND target_id=?",Integer.class,org,targetId);
        var card=json.createObjectNode().put("origin","WORKBENCH").put("workbenchRunId",runId.toString()).put("datasetName",run.path("datasetName").asText());
        card.set("xColumns",request.path("xColumns"));card.set("yColumn",y);card.set("targetDefinition",target);card.set("inputScheme",scheme);
        card.set("materialDictionary",materialDictionary);
        card.put("rowCount",result.path("rowCount").asInt());
        var domain=(ObjectNode)result.path("applicabilityDomain").deepCopy();domain.put("nearBoundaryRatio",0.05);
        if(previous!=null)jdbc.update("UPDATE ai.model_version SET status='RETIRED',revision=revision+1,updated_at=now() WHERE organization_id=? AND id=?",org,previous);
        jdbc.update("""
                INSERT INTO ai.model_version(id,organization_id,target_id,target_version_id,input_scheme_id,workbench_run_id,
                    model_artifact_id,model_type,status,metrics_jsonb,applicability_domain_jsonb,model_card_jsonb,contract_version,
                    model_hash,created_by,version_no,data_nature,production_eligible)
                VALUES(?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,'modeling-workbench.v1',?,?,?,'REAL',true)
                """,modelId,org,targetId,targetVersionId,schemeId,runId,artifactId,result.path("metrics").path("selectedAlgorithm").asText(),
                pg(result.path("metrics")),pg(domain),pg(card),hashing.hash(List.of(runId,artifact.sha256())),actor.userId(),version);
        jdbc.update("INSERT INTO ai.model_release(id,organization_id,target_id,model_version_id,previous_model_version_id,action,reason,actor_id,request_id,idempotency_key) VALUES(?,?,?,?,?,?,'建模结果注册',?,?,?)",
                UUID.randomUUID(),org,targetId,modelId,previous,previous==null?"ACTIVATE":"REPLACE",actor.userId(),RequestIdHolder.currentOrUnknown(),"WB_REGISTER:"+runId);
        audit.append(org,actor.userId(),"AI_WORKBENCH_MODEL_REGISTERED","AI_MODEL_VERSION",modelId,card);
        return registration(org,runId);
    }

    public JsonNode registration(UUID org,UUID runId){
        return jdbc.query("SELECT id,target_id,version_no,status,created_at FROM ai.model_version WHERE organization_id=? AND workbench_run_id=?",
                (rs,n)->json.createObjectNode().put("modelVersionId",rs.getObject(1).toString()).put("targetId",rs.getObject(2).toString())
                    .put("version",rs.getInt(3)).put("status",rs.getString(4)).put("registeredAt",rs.getTimestamp(5).toInstant().toString()),org,runId).stream().findFirst().orElse(null);
    }

    JsonNode familyIdentity(JsonNode request){
        var identity=json.createObjectNode();identity.set("y",columnIdentity(request.path("yColumn")));
        var x=new ArrayList<JsonNode>();request.path("xColumns").forEach(c->x.add(columnIdentity(c)));
        x.sort(Comparator.comparing(JsonNode::toString));identity.set("x",json.valueToTree(x));return identity;
    }
    private JsonNode columnIdentity(JsonNode column){var out=json.createObjectNode();
        for(var key:List.of("code","kind","unit","testMethod","testStage","testMethods","semanticKey","semanticType","materialId","materialCode","mappingStatus","compositionBasis","measurementComponent"))out.set(key,(column.hasNonNull(key)&&!(column.get(key).isArray()&&column.get(key).isEmpty()))?column.get(key):json.nullNode());return out;}
    private ObjectNode sanitizeMaterialMappings(JsonNode source) {
        var request=(ObjectNode)source.deepCopy();
        var candidates=new java.util.LinkedHashSet<String>();
        request.path("xColumns").forEach(column->collectMaterialId(column,candidates));
        request.path("materialColumns").forEach(column->collectMaterialId(column,candidates));
        collectMaterialId(request.path("yColumn"),candidates);
        var valid=new java.util.LinkedHashSet<String>();
        if(!candidates.isEmpty()) {
            var placeholders=String.join(",",java.util.Collections.nCopies(candidates.size(),"?::uuid"));
            valid.addAll(jdbc.query("SELECT id::text FROM mdm.material WHERE status<>'RETIRED' AND id IN ("+placeholders+")",
                    (rs,n)->rs.getString(1),candidates.toArray()));
        }
        request.path("xColumns").forEach(column->sanitizeMaterialColumn(column,valid));
        request.path("materialColumns").forEach(column->sanitizeMaterialColumn(column,valid));
        sanitizeMaterialColumn(request.path("yColumn"),valid);
        return request;
    }
    private void collectMaterialId(JsonNode column,java.util.Set<String> candidates) {
        if(!"MATERIAL_RATIO".equals(column.path("semanticType").asText())||!"MAPPED".equals(column.path("mappingStatus").asText()))return;
        try { candidates.add(UUID.fromString(column.path("materialId").asText()).toString()); }
        catch(IllegalArgumentException ignored) { }
    }
    static void sanitizeMaterialColumn(JsonNode column,java.util.Set<String> validIds) {
        if(!(column instanceof ObjectNode object)||!"MATERIAL_RATIO".equals(column.path("semanticType").asText())
                ||!"MAPPED".equals(column.path("mappingStatus").asText()))return;
        var id=column.path("materialId").asText();
        if(!validIds.contains(id)) { object.put("mappingStatus","UNMAPPED");object.remove("materialId"); }
    }
    private ObjectNode ref(UUID id){return json.createObjectNode().put("id",id.toString()).put("version",1);}
    private JsonNode parse(String text){try{return json.readTree(text);}catch(Exception e){throw new IllegalStateException(e);}}
    private PGobject pg(JsonNode node){try{var out=new PGobject();out.setType("jsonb");out.setValue(node.toString());return out;}catch(Exception e){throw new IllegalStateException(e);}}
    private ApiException invalid(String message){return new ApiException(ApiErrorCode.VALIDATION_ERROR,message);}
}
