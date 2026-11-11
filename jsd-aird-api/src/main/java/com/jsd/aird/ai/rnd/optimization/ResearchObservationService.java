package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ops.application.port.OutboxEventHandler;
import com.jsd.aird.rnd.api.ExperimentEvents;
import com.jsd.aird.rnd.api.ResearchExperimentFacade;
import com.jsd.aird.shared.error.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Completed facts are append-only; current eligibility is resolved from the source version. */
@Service
public class ResearchObservationService implements OutboxEventHandler {
    private final OptimizationRepository runs;
    private final ResearchExperimentFacade experiments;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConfigurationHashing hashing;

    public ResearchObservationService(OptimizationRepository runs, ResearchExperimentFacade experiments,
            JdbcTemplate jdbc, ObjectMapper json, ConfigurationHashing hashing) {
        this.runs=runs;this.experiments=experiments;this.jdbc=jdbc;this.json=json;this.hashing=hashing;
    }

    @Override public boolean supports(String event) {
        return Set.of(ExperimentEvents.PUBLISHED,ExperimentEvents.VERSION_PUBLISHED,ExperimentEvents.VOIDED,ExperimentEvents.REVISION_STARTED).contains(event);
    }
    @Override @Transactional public void handle(UUID experiment,JsonNode payload) {
        for(var org:jdbc.queryForList("SELECT DISTINCT organization_id FROM ai.research_experiment_link_v2 WHERE experiment_id=?",UUID.class,experiment))
            capture(org,experiment,payload.hasNonNull("versionId")?UUID.fromString(payload.path("versionId").asText()):null);
    }

    @Transactional public void refresh(UUID org,UUID run) {
        for(var experiment:jdbc.queryForList("SELECT DISTINCT experiment_id FROM ai.research_experiment_link_v2 WHERE organization_id=? AND research_run_id=?",UUID.class,org,run))
            capture(org,experiment,null);
    }

    private void capture(UUID org,UUID experiment,UUID version) {
        ObjectNode record;
        try {record=experiments.record(org,experiment,version);}
        catch(ApiException error){if(error.errorCode()==ApiErrorCode.NOT_FOUND)return;throw error;}
        if(!"COMPLETED".equals(record.path("status").asText()))return;
        var links=jdbc.queryForList("SELECT research_run_id,research_candidate_id,sample_key FROM ai.research_experiment_link_v2 WHERE organization_id=? AND experiment_id=? AND sample_key IS NOT NULL",org,experiment);
        for(var link:links) {
            var run=runs.any(org,(UUID)link.get("research_run_id")).orElseThrow();var request=run.request();
            var candidate=runs.candidate(org,run.id(),(UUID)link.get("research_candidate_id")).orElseThrow();
            var dataset=UUID.fromString(request.path("datasetId").asText());var target=request.path("target").asText();
            var savedVersion=UUID.fromString(record.path("versionId").asText());var sample=(String)link.get("sample_key");
            var projection=new HistorySampleProjection(json).dataset(dataset,"实验结果",List.of(new OptimizationRepository.HistoryRecordRow(experiment,savedVersion,
                record.path("experimentNo").asText(),record.path("title").asText(),record.path("editModel"),Instant.now())),Map.of(),runs.materialCatalog(org));
            JsonNode row=json.createObjectNode();
            for(var item:projection.path("rows"))if(sample.equals(item.path("sampleKey").asText()))row=item;
            var assessment=assess(json,request,row,candidate.inputs(),candidate.results());
            var observed=row.path("values").path(target);var predicted=predicted(candidate.results(),target);
            var inputs=json.createObjectNode();for(var f:request.path("features")) {
                var code=f.path("code").asText();if(row.path("values").has(code))inputs.set(code,row.path("values").path(code));
            }
            var content=json.createObjectNode().put("versionId",savedVersion.toString()).put("sampleKey",sample).put("target",target);
            content.set("inputs",inputs);content.set("observed",observed.isMissingNode()?json.nullNode():observed);content.set("assessment",assessment);
            jdbc.update("""
                INSERT INTO ai.research_observation_revision(id,organization_id,research_run_id,research_candidate_id,experiment_id,
                    experiment_version_id,sample_key,field_code,actual_inputs_jsonb,observed_value_jsonb,predicted_value_jsonb,
                    residual_jsonb,source_refs_jsonb,comparison_status,reason_code,content_hash)
                VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?) ON CONFLICT DO NOTHING
                """,UUID.randomUUID(),org,run.id(),candidate.id(),experiment,savedVersion,sample,target,inputs.toString(),
                observed.isMissingNode()?null:observed.toString(),predicted.isMissingNode()?null:predicted.toString(),
                assessment.path("metrics").toString(),row.path("sourceRefs").isObject()?row.path("sourceRefs").toString():"{}",
                assessment.path("status").asText(),assessment.path("reason").asText(null),hashing.hash(content));
        }
    }

    static ObjectNode assess(ObjectMapper json,JsonNode request,JsonNode row,JsonNode proposed,JsonNode results) {
        var out=json.createObjectNode().put("status","COMPARABLE");var metrics=out.putObject("metrics");
        var target=request.path("target").asText();String reason=null;
        if(row.isEmpty())reason="SAMPLE_NOT_FOUND";
        else if(!row.path("values").path(target).isNumber())reason="RESULT_NOT_COMPARABLE";
        if(target.startsWith("result:")) {
            JsonNode column=json.createObjectNode();
            for(var c:request.path("columns"))if(target.equals(c.path("code").asText()))column=c;
            if(column.path("testMethods").isEmpty())reason="MEASUREMENT_METHOD_MISSING";
        }
        var changes=metrics.putObject("inputChanges");
        for(var feature:request.path("features")) {
            var code=feature.path("code").asText();var actual=row.path("values").path(code);
            if(actual.isMissingNode()||actual.isNull() || "NUMERIC".equals(feature.path("kind").asText())&&!actual.isNumber())reason="ACTUAL_INPUT_MISSING";
            if(!actual.isMissingNode()&&!equivalent(actual,proposed.path(code))) {
                var change=changes.putObject(code);change.set("proposed",proposed.path(code));change.set("actual",actual);
            }
        }
        var prediction=predicted(results,target);var actual=row.path("values").path(target);
        if(prediction.isNumber()&&actual.isNumber())metrics.put("residual",actual.decimalValue().subtract(prediction.decimalValue()));
        // Column identity includes the actual method, stage and unit. Fixed pre-experiment conditions
        // are comparable only when the completed sample retains the frozen value.
        var reference=request.path("comparisonContext");
        for(var fields=reference.fields();fields.hasNext();) {
            var field=fields.next();
            // A sample or proposal identifier may change between the source and its new batch.
            if(HistorySampleProjection.processMetadata(field.getKey()))continue;
            var value=row.path("values").path(field.getKey());
            if(!equivalent(field.getValue(),value)) {
                reason="CONDITIONS_CHANGED";
                var change=metrics.withObject("/changedConditions").putObject(field.getKey());
                change.set("expected",field.getValue());change.set("actual",value.isMissingNode()?json.nullNode():value);
            }
        }
        if(reason!=null)out.put("status","EXCLUDED").put("reason",reason);
        return out;
    }

    private static boolean equivalent(JsonNode left,JsonNode right) {
        if(left.isNumber()&&right.isNumber()) {
            var scale=left.decimalValue().abs().max(right.decimalValue().abs()).max(java.math.BigDecimal.ONE);
            return left.decimalValue().subtract(right.decimalValue()).abs().compareTo(scale.multiply(new java.math.BigDecimal("1e-12")))<=0;
        }
        return left.equals(right);
    }

    static JsonNode predicted(JsonNode results,String target) {
        for(var item:results.path("targets"))if(target.equals(item.path("targetId").asText()))return item.path("result").path("value");
        return results.path(target).path("value");
    }

    public List<JsonNode> observations(UUID org,UUID run) {
        var result=jdbc.query("""
            SELECT jsonb_build_object('candidateId',o.research_candidate_id,'experimentId',o.experiment_id,
                'experimentVersionId',o.experiment_version_id,'sampleKey',o.sample_key,'fieldCode',o.field_code,
                'value',o.observed_value_jsonb,'predictedValue',o.predicted_value_jsonb,'actualInputs',o.actual_inputs_jsonb,
                'metrics',o.residual_jsonb,'sourceRefs',o.source_refs_jsonb,'capturedAt',o.captured_at,
                'comparisonStatus',CASE WHEN e.deleted OR e.status<>'COMPLETED' OR e.current_version_id<>o.experiment_version_id THEN 'NOT_CURRENT' ELSE o.comparison_status END,
                'reasonCode',CASE WHEN e.deleted THEN 'SOURCE_DELETED' WHEN e.status='VOIDED' THEN 'SOURCE_VOIDED'
                    WHEN e.current_version_id<>o.experiment_version_id THEN 'SOURCE_REVISED' WHEN e.status<>'COMPLETED' THEN 'SOURCE_NOT_COMPLETE' ELSE o.reason_code END)::text
            FROM ai.research_observation_revision o JOIN rnd.experiment e ON e.organization_id=o.organization_id AND e.id=o.experiment_id
            WHERE o.organization_id=? AND o.research_run_id=? ORDER BY o.captured_at,o.id
            """,(rs,n)->read(rs.getString(1)),org,run);
        if(com.jsd.aird.shared.security.ActorContext.current()!=null)for(var observation:result)
            experiments.requireReadableVersion(org,UUID.fromString(observation.path("experimentId").asText()),UUID.fromString(observation.path("experimentVersionId").asText()));
        return result;
    }

    public boolean currentSource(UUID org,JsonNode row) {
        return experiments.isCurrentCompletedVersion(org,UUID.fromString(row.path("sourceExperimentId").asText()),UUID.fromString(row.path("versionId").asText()));
    }

    public List<JsonNode> trainingManifest(UUID org,UUID run) {
        var chain=jdbc.queryForList("""
            WITH RECURSIVE chain AS (
                SELECT id,parent_run_id FROM ai.research_run_v2 WHERE organization_id=? AND id=?
                UNION ALL SELECT p.id,p.parent_run_id FROM ai.research_run_v2 p JOIN chain c ON c.parent_run_id=p.id WHERE p.organization_id=?
            ) SELECT id FROM chain
            """,UUID.class,org,run,org);
        var deduplicated=new LinkedHashMap<String,JsonNode>();
        for(var id:chain) {
            refresh(org,id);
            for(var observation:observations(org,id))if("COMPARABLE".equals(observation.path("comparisonStatus").asText())) {
                experiments.requireReadableVersion(org,UUID.fromString(observation.path("experimentId").asText()),UUID.fromString(observation.path("experimentVersionId").asText()));
                deduplicated.putIfAbsent(observation.path("experimentId").asText()+":"+observation.path("sampleKey").asText(),observation);
            }
        }
        return new ArrayList<>(deduplicated.values());
    }
    private JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
