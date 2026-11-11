package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.application.ResearchActivityService;
import com.jsd.aird.ai.application.ResearchWording;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.rnd.api.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.lab.LabWorkbook;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResearchLoopService {
    private final OptimizationRepository runs;
    private final AgentExperimentFacade drafts;
    private final ResearchExperimentFacade experiments;
    private final ResearchObservationService observations;
    private final ResearchActivityService activities;
    private final AuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConfigurationHashing hashing;
    private final com.jsd.aird.mdm.api.RawMaterialFacade rawMaterials;
    public ResearchLoopService(OptimizationRepository runs,AgentExperimentFacade drafts,ResearchExperimentFacade experiments,
            ResearchObservationService observations,ResearchActivityService activities,AuthorizationService authorization,
            JdbcTemplate jdbc,ObjectMapper json,ConfigurationHashing hashing,com.jsd.aird.mdm.api.RawMaterialFacade rawMaterials) {
        this.runs=runs;this.drafts=drafts;this.experiments=experiments;this.observations=observations;this.activities=activities;
        this.authorization=authorization;this.jdbc=jdbc;this.json=json;this.hashing=hashing;
        this.rawMaterials=rawMaterials;
    }

    public OptimizationRepository.RunRow requireRun(UUID id,String action) {
        var actor=ActorContext.required();authorization.require(new PermissionCheck(actor.organizationId(),actor.userId(),"ai.experiment.optimize","AI_RESEARCH_RUN",id,action));
        var run=runs.byId(actor.organizationId(),actor.userId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        activities.authorizedContext("EXPERIMENT_OPTIMIZATION",id);
        if(rawMaterials!=null)rawMaterials.authorizeSnapshot(run.request().path("materialDossiers"));
        var checked=new HashSet<String>();
        for(var row:run.request().path("datasetSnapshot").path("rows"))if(checked.add(row.path("sourceExperimentId").asText()+":"+row.path("versionId").asText()))
            experiments.requireReadableVersion(actor.organizationId(),UUID.fromString(row.path("sourceExperimentId").asText()),UUID.fromString(row.path("versionId").asText()));
        return run;
    }

    public ObjectNode draftReceipt(String key){return drafts.receipt(key);}
    public ObjectNode draftReceipt(ObjectNode args) {
        // Closing uses the same RND transaction lock and fences a delayed original call.
        if(args.path("close_uncommitted").asBoolean() && args.path("arguments") instanceof ObjectNode request) {
            if(request.path("recommendation").hasNonNull("run_id"))request=normalizedRequest(request,requireRun(UUID.fromString(request.path("recommendation").path("run_id").asText()),"READ"));
            return drafts.reconcileReceipt(args.path("operation_key").asText(),request);
        }
        return drafts.receipt(args.path("operation_key").asText());
    }

    @Transactional public ObjectNode createDraft(ObjectNode args) {
        var actor=ActorContext.required();var ref=args.path("recommendation");var id=UUID.fromString(ref.path("run_id").asText());
        var run=requireRun(id,"READ");
        if(!"SUCCEEDED".equals(run.executionStatus()))throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"请等待推荐方案生成完成");
        var selected=ref.path("candidate_ids");if(!selected.isArray()||selected.isEmpty()||selected.size()>16)throw invalid("请选择推荐方案");
        var seen=new HashSet<UUID>();var candidates=new ArrayList<JsonNode>();
        for(var value:selected) {
            var candidate=UUID.fromString(value.asText());if(!seen.add(candidate))throw invalid("同一推荐方案不能重复选择");
            candidates.add(json.valueToTree(runs.candidate(actor.organizationId(),id,candidate).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND))));
        }
        var request=normalizedRequest(args,run);
        var activity=activities.authorizedContext("EXPERIMENT_OPTIMIZATION",id);
        var prior=drafts.receipt(args.path("operation_key").asText());
        if("SUCCEEDED".equals(prior.path("status").asText())) {
            // RND compares the complete normalized request before returning the existing draft.
            return drafts.createDraft(request,null);
        }
        var workbook=ResearchDraftWorkbook.build(json,run.baseline(),run.request(),candidates);
        var result=drafts.createDraft(request,workbook);
        var experiment=UUID.fromString(result.path("id").asText());var version=UUID.fromString(result.path("version_id").asText());
        var actual=experiments.record(actor.organizationId(),experiment,version);var sheet=LabWorkbook.sheet(actual.path("editModel").path("documentSnapshot"));
        int first=LabWorkbook.definition(actual.path("editModel").path("documentSnapshot")).path("firstSampleColumn").asInt(2);
        for(int i=0;i<candidates.size();i++) {
            var candidate=candidates.get(i);var sample=sheet.path("columnData").path(""+(first+i)).path("custom").path("lab").path("sampleKey").asText();
            if(sample.isBlank())throw invalid("生成的实验缺少样品身份");
            var evidence=json.createObjectNode().put("runId",id.toString()).put("candidateId",candidate.path("id").asText()).put("sampleKey",sample);
            evidence.set("sources",activity.path("sourceRefs").isArray()?activity.path("sourceRefs"):json.createArrayNode());
            evidence.set("researchBasis",activity.path("researchBasis").isArray()?activity.path("researchBasis"):json.createArrayNode());
            var evidenceId=UUID.randomUUID();var evidenceHash=hashing.hash(evidence);
            jdbc.update("""
                INSERT INTO rnd.experiment_ai_source_evidence(id,organization_id,experiment_id,experiment_version_id,request_hash,
                    formula_jsonb,predictions_jsonb,model_bindings_jsonb,source_summary_jsonb,evidence_hash,captured_by,
                    research_run_id,research_candidate_id,baseline_snapshot_jsonb,target_evidence_jsonb,request_jsonb)
                VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?::jsonb,?::jsonb,?::jsonb) ON CONFLICT DO NOTHING
                """,evidenceId,actor.organizationId(),experiment,version,run.requestHash(),candidate.path("formula").toString(),candidate.path("results").toString(),
                run.bindings().toString(),evidence.toString(),evidenceHash,actor.userId(),id,UUID.fromString(candidate.path("id").asText()),run.baseline().toString(),candidate.path("results").toString(),run.request().toString());
            evidenceId=jdbc.queryForObject("SELECT id FROM rnd.experiment_ai_source_evidence WHERE organization_id=? AND experiment_version_id=? AND evidence_hash=?",UUID.class,actor.organizationId(),version,evidenceHash);
            var intentHash=hashing.hash(request);var linkKey=args.path("operation_key").asText()+":"+candidate.path("id").asText();
            jdbc.update("""
                INSERT INTO ai.research_experiment_link_v2(id,organization_id,research_run_id,research_candidate_id,experiment_id,experiment_version_id,
                    idempotency_key,created_by,creation_intent_jsonb,creation_intent_hash,source_evidence_id,sample_key)
                VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?,?) ON CONFLICT(organization_id,idempotency_key) DO NOTHING
                """,UUID.randomUUID(),actor.organizationId(),id,UUID.fromString(candidate.path("id").asText()),experiment,version,linkKey,actor.userId(),request.toString(),intentHash,evidenceId,sample);
        }
        return result;
    }

    private ObjectNode normalizedRequest(ObjectNode args,OptimizationRepository.RunRow run) {
        var actor=ActorContext.required();var request=args.deepCopy();var frozenTemplate=run.baseline().path("templateVersionId").asText();
        if(frozenTemplate.isBlank())throw invalid("推荐方案缺少标准模板版本，请重新选择实验数据");
        if(request.hasNonNull("template_version_id")&&!frozenTemplate.equals(request.path("template_version_id").asText()))throw invalid("请沿用本轮推荐的标准模板版本");
        request.put("template_version_id",frozenTemplate);
        var activity=activities.authorizedContext("EXPERIMENT_OPTIMIZATION",run.id());
        if(activity.hasNonNull("projectId")) {
            if(request.hasNonNull("project_id")&&!activity.path("projectId").equals(request.path("project_id")))throw invalid("推荐方案与目标项目不一致");
            request.set("project_id",activity.path("projectId"));
        }
        var source=experiments.record(actor.organizationId(),run.baselineEntityId(),run.baselineVersionId());
        if(!request.hasNonNull("category_id"))request.set("category_id",source.path("categoryId"));
        // Keep the caller's exact intent in the authoritative receipt. Resolved fields are separate.
        request.set("_caller_intent",args.deepCopy().remove("operation_key"));
        if(request.path("title").isTextual())request.put("title",ResearchWording.business(request.path("title").asText()));
        if(request.path("edit_model") instanceof ObjectNode edit) {
            for(var field:List.of("plan","purpose"))if(edit.path(field).isTextual())
                edit.put(field,ResearchWording.business(edit.path(field).asText()));
        }
        return request;
    }

    public ObjectNode context(ObjectNode args) {
        var actor=ActorContext.required();var out=json.createObjectNode();var items=out.putArray("runs");
        UUID project=args.hasNonNull("project_id")?UUID.fromString(args.path("project_id").asText()):null;
        activities.requireProject(project);out.set("activities",activities.projectActivities(project));
        var categories=drafts.read("draft_options",json.createObjectNode()).path("categories");
        List<UUID> ids=args.hasNonNull("run_id")?List.of(UUID.fromString(args.path("run_id").asText())):
            jdbc.queryForList("""
                SELECT DISTINCT r.id,r.created_at FROM ai.research_run_v2 r
                LEFT JOIN ai.research_activity_context a ON a.organization_id=r.organization_id AND a.resource_id=r.id
                LEFT JOIN ai.research_experiment_link_v2 l ON l.organization_id=r.organization_id AND l.research_run_id=r.id
                LEFT JOIN rnd.experiment e ON e.organization_id=l.organization_id AND e.id=l.experiment_id
                WHERE r.organization_id=? AND r.created_by=? AND r.run_type='EXPERIMENT_OPTIMIZATION'
                    AND (?::uuid IS NULL OR a.project_id=? OR e.project_id=?) ORDER BY r.created_at DESC LIMIT 20
                """,actor.organizationId(),actor.userId(),project,project,project).stream().map(row->(UUID)row.get("id")).toList();
        for(var id:ids) {
            var run=requireRun(id,"READ");observations.refresh(actor.organizationId(),id);
            var item=items.addObject().put("runId",id.toString()).put("status",run.executionStatus()).put("targetName",ResearchWording.business(run.request().path("targetName").asText()))
                .put("path","/assistant/experiment-optimization?runId="+id);
            item.put("roundNumber",jdbc.queryForObject("""
                WITH RECURSIVE chain AS (
                    SELECT id,parent_run_id FROM ai.research_run_v2 WHERE organization_id=? AND id=?
                    UNION SELECT p.id,p.parent_run_id FROM ai.research_run_v2 p JOIN chain c ON c.parent_run_id=p.id WHERE p.organization_id=?
                ) SELECT count(*) FROM chain
                """,Integer.class,actor.organizationId(),id,actor.organizationId()));
            item.set("activity",activities.authorizedContext("EXPERIMENT_OPTIMIZATION",id));
            item.set("materialDossiers",run.request().path("materialDossiers"));
            item.set("frozenData",json.createObjectNode().put("rowCount",run.request().path("rows").size()).put("selectedRowCount",run.request().path("selectedRowCount").asInt()));
            if(run.request().has("parentRunId"))item.set("parentRunId",run.request().path("parentRunId"));
            var candidates=item.putArray("candidates");
            for(var c:runs.candidates(actor.organizationId(),id)) {
                var candidate=candidates.addObject().put("candidateId",c.id().toString()).put("title",c.title());candidate.set("inputs",c.inputs());candidate.set("predictedResults",c.results());
            }
            var links=item.putArray("experiments");
            for(var link:jdbc.queryForList("SELECT research_candidate_id,experiment_id,experiment_version_id,sample_key FROM ai.research_experiment_link_v2 WHERE organization_id=? AND research_run_id=? ORDER BY created_at,id",actor.organizationId(),id)) {
                var experiment=(UUID)link.get("experiment_id");var record=experiments.record(actor.organizationId(),experiment,null);
                var result=links.addObject().put("candidateId",link.get("research_candidate_id").toString()).put("experimentId",experiment.toString())
                    .put("createdVersionId",link.get("experiment_version_id").toString()).put("versionId",record.path("versionId").asText())
                    .put("revision",record.path("revision").asLong()).put("sampleKey",Objects.toString(link.get("sample_key"),""))
                    .put("title",record.path("title").asText()).put("status",record.path("currentStatus").asText()).put("path","/experiments/"+experiment);
                var fields=result.putArray("measurementFields");var definitions=LabWorkbook.definition(record.path("editModel").path("documentSnapshot")).path("fields");
                for(var it=definitions.fields();it.hasNext();) {var f=it.next();if("TEST".equals(f.getValue().path("domain").asText())) {
                    var field=fields.addObject().put("fieldCode",f.getKey()).put("name",ResearchWording.business(f.getValue().path("label").asText())).put("unit",f.getValue().path("unit").asText());
                    for(var value:record.path("editModel").path("testResults"))if(f.getKey().equals(value.path("fieldCode").asText())&&result.path("sampleKey").asText().equals(value.path("sampleKey").asText())) {
                        if(value.has("testMethod"))field.set("method",value.path("testMethod"));if(value.has("testCondition"))field.set("conditions",value.path("testCondition"));
                    }
                }}
            }
            var actual=observations.observations(actor.organizationId(),id);item.set("observations",json.valueToTree(actual));
            long eligible=actual.stream().filter(o->"COMPARABLE".equals(o.path("comparisonStatus").asText())).count();
            item.put("comparableCount",eligible).put("nextAction",eligible>0?"可继续下一轮优化":links.isEmpty()?"选择推荐方案并生成实验草稿":"在关联实验中填写读数并完成现有流程");
            var baseline=experiments.record(actor.organizationId(),run.baselineEntityId(),run.baselineVersionId());
            var defaults=item.putObject("draftDefaults").put("title",ResearchWording.business(run.request().path("targetName").asText())+" · 配方优化")
                .put("templateVersionId",run.baseline().path("templateVersionId").asText());
            if(baseline.hasNonNull("categoryId"))defaults.set("categoryId",baseline.path("categoryId"));
            item.set("draftCategories",categories);
            if(baseline.hasNonNull("projectId"))defaults.set("projectId",baseline.path("projectId"));
        }
        return out;
    }
    private ApiException invalid(String message){return new ApiException(ApiErrorCode.VALIDATION_ERROR,message);}
}
