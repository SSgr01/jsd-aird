package com.jsd.aird.ai.rnd.modeling;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import static com.jsd.aird.ai.rnd.modeling.ModelRegistryContracts.*;
@Service
public class ModelRegistryService {
    private final ModelRegistryRepository repository;
    private final ConfigurationHashing hashing;
    private final ObjectMapper json;
    private final AuditLogFacade audit;
    private final FormulaModelArtifactStore artifacts;
    @org.springframework.beans.factory.annotation.Autowired
    private com.jsd.aird.iam.api.AuthorizationService authorization;
    private org.springframework.transaction.support.TransactionTemplate writeTransaction;
    private record PreparedArtifact(String key,String sha,String media) { }
    @org.springframework.beans.factory.annotation.Autowired
    void setTransactions(org.springframework.transaction.PlatformTransactionManager manager){writeTransaction=new org.springframework.transaction.support.TransactionTemplate(manager);}
    public ModelRegistryService(ModelRegistryRepository repository,ConfigurationHashing hashing,ObjectMapper json,AuditLogFacade audit,FormulaModelArtifactStore artifacts){
        this.repository=repository;this.hashing=hashing;this.json=json;this.audit=audit;this.artifacts=artifacts;
    }
    public ModelDetail model(UUID id){return repository.modelDetail(actor().organizationId(),id);}
    public PageResponse<ModelSummary> models(String status,String category,String keyword,int page,int size){var a=actor();return repository.models(a.organizationId(),status,category,keyword,page,size);}

    @Transactional
    public ModelActionResult pause(UUID id,ModelActionCommand command,String key){return changeModel(id,command,"PAUSE",key);}

    public ModelActionResult rollback(UUID id,ModelActionCommand command,String key){
        var org=actor().organizationId();var normalized=requireKey(key);
        var oldReceipt=repository.jdbc().query("SELECT request_hash,response_jsonb FROM ai.configuration_command_receipt WHERE organization_id=? AND operation=? AND idempotency_key=?",(rs,n)->new Object[]{rs.getString(1),repository.node(rs,"response_jsonb")},org,"ROLLBACK_MODEL:"+id,normalized).stream().findFirst().orElse(null);
        if(oldReceipt!=null){if(!hashing.hash(command).equals(oldReceipt[0]))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);try{return json.treeToValue((JsonNode)oldReceipt[1],ModelActionResult.class);}catch(Exception error){throw new IllegalStateException(error);}}
        var model=repository.model(org,id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        if(model.revision()!=command.expectedRevision())throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        var prepared=artifact(org,id);artifacts.verify(prepared.key(),prepared.sha(),prepared.media());
        java.util.function.Supplier<ModelActionResult> commit=()->idempotent("ROLLBACK_MODEL:"+id,key,command,ModelActionResult.class,()->doChangeModel(id,command,"ROLLBACK",key,prepared));
        return writeTransaction==null?commit.get():writeTransaction.execute(tx->commit.get());
    }

    private PreparedArtifact artifact(UUID org,UUID id){
        return repository.jdbc().query("SELECT a.object_key,a.sha256,coalesce(a.media_type,'application/zip') media_type FROM ai.model_version m JOIN ai.artifact a ON a.organization_id=m.organization_id AND a.id=m.model_artifact_id WHERE m.organization_id=? AND m.id=?",
            (rs,n)->new PreparedArtifact(rs.getString(1),rs.getString(2),rs.getString(3)),org,id).stream().findFirst().orElseThrow(()->new ApiException(ApiErrorCode.MODEL_ARTIFACT_UNAVAILABLE));
    }

    private ModelActionResult changeModel(UUID id,ModelActionCommand command,String action,String key){return idempotent(action+"_MODEL:"+id,key,command,ModelActionResult.class,()->doChangeModel(id,command,action,key));}

    private ModelActionResult doChangeModel(UUID id, ModelActionCommand command, String action, String key) {
        return doChangeModel(id,command,action,key,null);
    }
    private ModelActionResult doChangeModel(UUID id, ModelActionCommand command, String action, String key,PreparedArtifact prepared) {
        var actor = actor();
        if(authorization!=null)authorization.require(new com.jsd.aird.iam.api.PermissionCheck(actor.organizationId(),actor.userId(),"ai.model.publish","AI_MODEL",id,"PUBLISH"));
        var model = repository.model(actor.organizationId(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "模型版本不存在"));
        if (model.revision() != command.expectedRevision()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);

        repository.jdbc().query("SELECT id FROM ai.prediction_target WHERE organization_id=? AND id=? FOR UPDATE",(rs,n)->rs.getObject(1),actor.organizationId(),model.targetId());
        var active = repository.jdbc().query(
                "SELECT id FROM ai.model_version WHERE organization_id=? AND target_id=? AND status='ACTIVE' FOR UPDATE",
                (rs, n) -> rs.getObject(1, UUID.class), actor.organizationId(), model.targetId())
                .stream().findFirst().orElse(null);
        if (!java.util.Objects.equals(active, command.expectedActiveModelVersionId())) {
            throw new ApiException(ApiErrorCode.MODEL_VERSION_CHANGED);
        }

        if ("PAUSE".equals(action)) {
            if (!id.equals(active)) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "只有使用中的模型可以暂停");
            var updated = repository.jdbc().update(
                    "UPDATE ai.model_version SET status='PAUSED',revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",
                    actor.organizationId(), id, model.revision());
            if (updated != 1) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
            insertRelease(actor, id, active, "PAUSE", command.reason(), key);
            return new ModelActionResult(id, "PAUSED", null, model.revision() + 1, Instant.now());
        }

        if ("ROLLBACK".equals(action) && !Set.of("PAUSED", "RETIRED").contains(model.status())) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "只能回退到历史正式模型");
        }

        var artifact = repository.jdbc().query(
                "SELECT a.object_key,a.sha256,coalesce(a.media_type,'application/zip') media_type "
                        + "FROM ai.model_version m JOIN ai.artifact a ON a.organization_id=m.organization_id "
                        + "AND a.id=m.model_artifact_id WHERE m.organization_id=? AND m.id=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, actor.organizationId(), id)
                .stream().findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.MODEL_ARTIFACT_UNAVAILABLE));
        if(prepared==null)artifacts.verify(artifact[0], artifact[1], artifact[2]);
        else if(!prepared.equals(new PreparedArtifact(artifact[0],artifact[1],artifact[2])))throw new ApiException(ApiErrorCode.MODEL_VERSION_CHANGED);

        if (active != null && !active.equals(id)) {
            repository.jdbc().update("UPDATE ai.model_version SET status='RETIRED',revision=revision+1,updated_at=now() "
                    + "WHERE organization_id=? AND id=? AND status='ACTIVE'", actor.organizationId(), active);
        }
        var updated = repository.jdbc().update(
                "UPDATE ai.model_version SET status='ACTIVE',revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",
                actor.organizationId(), id, model.revision());
        if (updated != 1) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        repository.jdbc().update("UPDATE ai.prediction_target t SET current_version_id=m.target_version_id,current_input_scheme_id=m.input_scheme_id FROM ai.model_version m WHERE m.organization_id=t.organization_id AND m.target_id=t.id AND m.id=? AND m.organization_id=? AND m.workbench_run_id IS NOT NULL",id,actor.organizationId());
        insertRelease(actor, id, active, action, command.reason(), key);
        return new ModelActionResult(id, "ACTIVE", id, model.revision() + 1, Instant.now());
    }

    private void insertRelease(Actor a,UUID id,UUID previous,String action,String reason,String key){if(reason==null||reason.isBlank())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请填写操作原因");var model=repository.model(a.organizationId(),id).orElseThrow();var releaseKey=action+":"+hashing.hash(Map.of("modelId",id,"idempotencyKey",key));repository.jdbc().update("INSERT INTO ai.model_release(id,organization_id,target_id,model_version_id,previous_model_version_id,action,expected_previous_revision,reason,actor_id,request_id,idempotency_key) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),a.organizationId(),model.targetId(),id,previous,action,null,reason,a.userId(),RequestIdHolder.currentOrUnknown(),releaseKey);audit.append(a.organizationId(),a.userId(),"AI_MODEL_"+action,"AI_MODEL_VERSION",id,json.createObjectNode().put("reason",reason));}

    private <T>T idempotent(String operation,String key,Object request,Class<T> type,Supplier<T> action){
        var a=actor();var normalized=requireKey(key);var requestHash=hashing.hash(request);
        repository.jdbc().query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(rs,n)->rs.getObject(1),a.organizationId()+":"+operation+":"+normalized);
        var receipt=repository.jdbc().query("SELECT request_hash,response_jsonb FROM ai.configuration_command_receipt WHERE organization_id=? AND operation=? AND idempotency_key=?",(rs,n)->new Object[]{rs.getString(1),repository.node(rs,"response_jsonb")},a.organizationId(),operation,normalized).stream().findFirst().orElse(null);
        if(receipt!=null){if(!requestHash.equals(receipt[0]))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);try{return json.treeToValue((JsonNode)receipt[1],type);}catch(Exception e){throw new IllegalStateException("幂等结果无法恢复",e);}}
        var response=action.get();var responseJson=json.valueToTree(response);UUID resourceId=responseJson.hasNonNull("id")?UUID.fromString(responseJson.path("id").asText()):responseJson.hasNonNull("jobId")?UUID.fromString(responseJson.path("jobId").asText()):responseJson.hasNonNull("modelVersionId")?UUID.fromString(responseJson.path("modelVersionId").asText()):null;
        repository.jdbc().update("INSERT INTO ai.configuration_command_receipt(id,organization_id,operation,idempotency_key,request_hash,resource_type,resource_id,response_jsonb,created_by) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),a.organizationId(),operation,normalized,requestHash,type.getSimpleName(),resourceId,repository.pg(responseJson),a.userId());return response;
    }

    private String requireKey(String key){if(key==null||key.isBlank())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"缺少 Idempotency-Key");var value=key.strip();if(value.length()>200)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"Idempotency-Key 不能超过200个字符");return value;}

    private Actor actor(){return ActorContext.required();}
}
