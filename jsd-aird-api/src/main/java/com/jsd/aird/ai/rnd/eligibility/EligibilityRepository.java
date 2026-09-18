package com.jsd.aird.ai.rnd.eligibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.api.PageResponse;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.eligibility.EligibilityContracts.*;

@Repository
public class EligibilityRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EligibilityRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JdbcTemplate jdbc() { return jdbc; }

    public void lockCommand(UUID organizationId, String operation, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (rs, ignored) -> rs.getObject(1),
                organizationId + ":" + operation + ":" + key);
    }

    public Optional<Map<String, Object>> commandReceipt(UUID organizationId, String operation, String key) {
        return jdbc.query("""
                SELECT request_hash,response_jsonb FROM ai.configuration_command_receipt
                WHERE organization_id=? AND operation=? AND idempotency_key=?
                """, (rs, ignored) -> Map.<String,Object>of("requestHash", rs.getString("request_hash"),
                "response", node(rs,"response_jsonb")), organizationId, operation, key).stream().findFirst();
    }

    public void insertCommandReceipt(UUID organizationId, String operation, String key, String requestHash,
                                     String resourceType, UUID resourceId, JsonNode response, UUID createdBy) {
        jdbc.update("""
                INSERT INTO ai.configuration_command_receipt(
                    id,organization_id,operation,idempotency_key,request_hash,resource_type,resource_id,response_jsonb,created_by)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), organizationId, operation, key, requestHash, resourceType, resourceId,
                pg(response), createdBy);
    }

    public Optional<Configuration> configuration(UUID organizationId, UUID targetId) {
        var target = jdbc.query("""
                SELECT t.id,t.current_version_id,t.current_input_scheme_id,
                       tv.value_type,tv.unit,tv.classes_jsonb,tv.definition_jsonb,
                       tv.observation_semantics_jsonb,s.material_dictionary_version_id,
                       s.status scheme_status,tv.status target_version_status
                FROM ai.prediction_target t
                LEFT JOIN ai.target_version tv ON tv.organization_id=t.organization_id
                    AND tv.id=t.current_version_id
                LEFT JOIN ai.input_scheme s ON s.organization_id=t.organization_id
                    AND s.id=t.current_input_scheme_id
                WHERE t.organization_id=? AND t.id=?
                """, (rs, ignored) -> new Configuration(
                rs.getObject("id", UUID.class), rs.getObject("current_version_id", UUID.class),
                rs.getObject("current_input_scheme_id", UUID.class), rs.getString("value_type"),
                rs.getString("unit"), list(node(rs,"classes_jsonb")), node(rs,"definition_jsonb"),
                node(rs,"observation_semantics_jsonb"), rs.getObject("material_dictionary_version_id", UUID.class),
                rs.getString("scheme_status"), rs.getString("target_version_status")), organizationId, targetId)
                .stream().findFirst();
        if (target.isEmpty() || target.get().targetVersionId() == null || target.get().inputSchemeId() == null) return target;
        var base=target.get();
        var fields=jdbc.query("""
                SELECT sf.input_field_version_id,sf.required,sf.ordinal,
                       f.field_code,f.name,fv.value_type,fv.unit,fv.availability_stage,
                       fv.definition_jsonb,fv.preprocessing_jsonb
                FROM ai.input_scheme_field sf
                JOIN ai.input_field_version fv ON fv.organization_id=sf.organization_id AND fv.id=sf.input_field_version_id
                JOIN ai.input_field f ON f.organization_id=fv.organization_id AND f.id=fv.input_field_id
                WHERE sf.organization_id=? AND sf.input_scheme_id=? ORDER BY sf.ordinal,sf.input_field_version_id
                """, (rs, ignored) -> new FieldRow(rs.getObject("input_field_version_id",UUID.class),rs.getBoolean("required"),rs.getInt("ordinal"),
                rs.getString("field_code"),rs.getString("name"),rs.getString("value_type"),rs.getString("unit"),
                rs.getString("availability_stage"),node(rs,"definition_jsonb"),node(rs,"preprocessing_jsonb")),organizationId,base.inputSchemeId());
        var mappings=new LinkedHashMap<String,SourceMappingRow>();
        jdbc.query("""
                SELECT sm.* FROM ai.source_mapping_version sm
                WHERE sm.organization_id=? AND sm.target_version_id=? AND sm.status='PUBLISHED'
                ORDER BY sm.source_type,sm.version_no DESC,sm.id
                """, rs -> { while(rs.next()){var type=rs.getString("source_type");mappings.putIfAbsent(type,new SourceMappingRow(rs.getObject("id",UUID.class),type,node(rs,"mapping_jsonb"),rs.getInt("version_no")));} return null; },organizationId,base.targetVersionId());
        var policy=jdbc.query("""
                SELECT id,qualification_jsonb,validation_jsonb,training_jsonb,policy_hash
                FROM ai.modeling_policy_version WHERE organization_id=? AND target_id=? AND kind='TRAINING' AND status='PUBLISHED'
                ORDER BY version_no DESC LIMIT 1
                """, (rs, ignored) -> new PolicyRow(rs.getObject("id",UUID.class),node(rs,"qualification_jsonb"),node(rs,"validation_jsonb"),node(rs,"training_jsonb"),rs.getString("policy_hash")), organizationId,targetId)
                .stream().findFirst().orElse(null);
        JsonNode dictionary=null;String dictionaryHash=null;
        if(base.dictionaryId()!=null){var dict=jdbc.query("SELECT vocabulary_jsonb,dictionary_hash FROM ai.material_dictionary_version WHERE organization_id=? AND id=?",(rs,ignored)->new Object[]{node(rs,"vocabulary_jsonb"),rs.getString("dictionary_hash")},organizationId,base.dictionaryId()).stream().findFirst().orElse(null);if(dict!=null){dictionary=(JsonNode)dict[0];dictionaryHash=(String)dict[1];}}
        return Optional.of(base.with(fields,mappings,policy,dictionary,dictionaryHash));
    }

    /** Load the exact versions captured by a queued run; never follow the target's current pointers. */
    public Optional<Configuration> configurationForRun(UUID organizationId, RunRow run) {
        var target = jdbc.query("""
                SELECT t.id,tv.id target_version_id,s.id input_scheme_id,
                       tv.value_type,tv.unit,tv.classes_jsonb,tv.definition_jsonb,
                       tv.observation_semantics_jsonb,s.material_dictionary_version_id,
                       s.status scheme_status,tv.status target_version_status
                FROM ai.prediction_target t
                JOIN ai.target_version tv ON tv.organization_id=t.organization_id
                    AND tv.target_id=t.id AND tv.id=?
                JOIN ai.input_scheme s ON s.organization_id=t.organization_id
                    AND s.target_id=t.id AND s.id=?
                WHERE t.organization_id=? AND t.id=?
                """, (rs, ignored) -> new Configuration(
                rs.getObject("id", UUID.class), rs.getObject("target_version_id", UUID.class),
                rs.getObject("input_scheme_id", UUID.class), rs.getString("value_type"),
                rs.getString("unit"), list(node(rs,"classes_jsonb")), node(rs,"definition_jsonb"),
                node(rs,"observation_semantics_jsonb"), rs.getObject("material_dictionary_version_id", UUID.class),
                rs.getString("scheme_status"), rs.getString("target_version_status")),
                run.targetVersionId(), run.inputSchemeId(), organizationId, run.targetId())
                .stream().findFirst();
        if (target.isEmpty()) return target;
        var base = target.get();
        var fields = jdbc.query("""
                SELECT sf.input_field_version_id,sf.required,sf.ordinal,
                       f.field_code,f.name,fv.value_type,fv.unit,fv.availability_stage,
                       fv.definition_jsonb,fv.preprocessing_jsonb
                FROM ai.input_scheme_field sf
                JOIN ai.input_field_version fv ON fv.organization_id=sf.organization_id AND fv.id=sf.input_field_version_id
                JOIN ai.input_field f ON f.organization_id=fv.organization_id AND f.id=fv.input_field_id
                WHERE sf.organization_id=? AND sf.input_scheme_id=? ORDER BY sf.ordinal,sf.input_field_version_id
                """, (rs, ignored) -> new FieldRow(rs.getObject("input_field_version_id",UUID.class),rs.getBoolean("required"),rs.getInt("ordinal"),
                rs.getString("field_code"),rs.getString("name"),rs.getString("value_type"),rs.getString("unit"),
                rs.getString("availability_stage"),node(rs,"definition_jsonb"),node(rs,"preprocessing_jsonb")),
                organizationId, base.inputSchemeId());
        var pinnedMappings = new java.util.HashSet<UUID>();
        if (run.sourceMappingVersions() != null && run.sourceMappingVersions().isArray())
            run.sourceMappingVersions().forEach(x -> { if (x.hasNonNull("versionId")) pinnedMappings.add(UUID.fromString(x.path("versionId").asText())); });
        var mappings = new LinkedHashMap<String,SourceMappingRow>();
        jdbc.query("""
                SELECT sm.* FROM ai.source_mapping_version sm
                WHERE sm.organization_id=? AND sm.target_version_id=?
                ORDER BY sm.source_type,sm.version_no DESC,sm.id
                """, rs -> { while(rs.next()) {
                    var id = rs.getObject("id", UUID.class);
                    if (!pinnedMappings.isEmpty() && !pinnedMappings.contains(id)) continue;
                    var type=rs.getString("source_type");
                    mappings.putIfAbsent(type,new SourceMappingRow(id,type,node(rs,"mapping_jsonb"),rs.getInt("version_no")));
                } return null; }, organizationId, base.targetVersionId());
        var policy = jdbc.query("""
                SELECT id,qualification_jsonb,validation_jsonb,training_jsonb,policy_hash
                FROM ai.modeling_policy_version WHERE organization_id=? AND id=? AND kind='TRAINING'
                """, (rs, ignored) -> new PolicyRow(rs.getObject("id",UUID.class),node(rs,"qualification_jsonb"),
                node(rs,"validation_jsonb"),node(rs,"training_jsonb"),rs.getString("policy_hash")),
                organizationId, run.policyId()).stream().findFirst().orElse(null);
        JsonNode dictionary=null; String dictionaryHash=null;
        var dictionaryId = run.dictionaryId() != null ? run.dictionaryId() : base.dictionaryId();
        if (dictionaryId != null) {
            var dict=jdbc.query("SELECT vocabulary_jsonb,dictionary_hash FROM ai.material_dictionary_version WHERE organization_id=? AND id=?",
                    (rs,ignored)->new Object[]{node(rs,"vocabulary_jsonb"),rs.getString("dictionary_hash")},organizationId,dictionaryId)
                    .stream().findFirst().orElse(null);
            if (dict != null) { dictionary=(JsonNode)dict[0]; dictionaryHash=(String)dict[1]; }
        }
        return Optional.of(base.with(fields,mappings,policy,dictionary,dictionaryHash));
    }

    public Optional<RunRow> run(UUID organizationId, UUID id) {
        return jdbc.query("""
                SELECT * FROM ai.eligibility_evaluation_run
                WHERE organization_id=? AND id=?
                """, this::runRow, organizationId, id).stream().findFirst();
    }

    public Optional<RunRow> runByJob(UUID organizationId, UUID asyncJobId) {
        return jdbc.query("SELECT * FROM ai.eligibility_evaluation_run WHERE organization_id=? AND async_job_id=? ORDER BY created_at DESC LIMIT 1", this::runRow, organizationId, asyncJobId).stream().findFirst();
    }

    public Optional<RunRow> latestRun(UUID organizationId, UUID targetVersionId, UUID inputSchemeId) {
        return jdbc.query("""
                SELECT * FROM ai.eligibility_evaluation_run
                WHERE organization_id=? AND target_version_id=? AND input_scheme_id=?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, this::runRow, organizationId, targetVersionId, inputSchemeId).stream().findFirst();
    }

    public void insertRun(UUID organizationId, UUID runId, UUID targetId, UUID targetVersionId,
                          UUID inputSchemeId, JsonNode mappings, UUID dictionaryId,
                          UUID policyId, String fingerprint, UUID asyncJobId, UUID requestedBy) {
        jdbc.update("""
                INSERT INTO ai.eligibility_evaluation_run(
                    id,organization_id,target_id,target_version_id,input_scheme_id,
                    source_mapping_versions_jsonb,material_dictionary_version_id,
                    modeling_policy_version_id,rule_fingerprint,async_job_id,status,requested_by)
                VALUES(?,?,?,?,?,?,?,?,?,?, 'QUEUED', ?)
                """, runId, organizationId, targetId, targetVersionId, inputSchemeId,
                pg(mappings), dictionaryId, policyId, fingerprint, asyncJobId, requestedBy);
    }

    public void startRun(UUID organizationId, UUID runId) {
        jdbc.update("""
                UPDATE ai.eligibility_evaluation_run
                SET status='RUNNING', started_at=coalesce(started_at,now()), updated_at=now()
                WHERE organization_id=? AND id=? AND status IN ('QUEUED','RUNNING')
                """, organizationId, runId);
    }

    public void finishRun(UUID organizationId, UUID runId, RunStatus status, long total,
                          long trainable, long excluded, long reviewRequired,
                          JsonNode funnel, String errorCode, String errorMessage) {
        jdbc.update("""
                UPDATE ai.eligibility_evaluation_run
                SET status=?, total_samples=?, trainable_count=?, excluded_count=?,
                    review_required_count=?, funnel_jsonb=?, error_code=?, error_message=?,
                    finished_at=now(), updated_at=now()
                WHERE organization_id=? AND id=?
                """, status.name(), total, trainable, excluded, reviewRequired, pg(funnel),
                errorCode, errorMessage, organizationId, runId);
    }

    public List<SampleRow> currentSamples(UUID organizationId, int limit, int offset) {
        return jdbc.query("""
                SELECT s.id sample_id,s.logical_sample_key,s.status sample_status,
                       s.authority_source_type,r.id revision_id,r.revision_no,
                       r.composition_jsonb,r.process_jsonb,r.conditions_jsonb,
                       r.observations_jsonb,r.facts_jsonb,r.source_coordinates_jsonb,
                       src.source_type,src.status source_status,
                       EXISTS(SELECT 1 FROM ai.sample_identity_issue i
                              WHERE i.organization_id=s.organization_id
                                AND (i.training_sample_id=s.id OR i.left_sample_source_id=src.id
                                     OR i.right_sample_source_id=src.id)
                                AND i.status='OPEN') identity_conflict
                FROM ai.training_sample s
                JOIN ai.sample_revision r ON r.organization_id=s.organization_id
                    AND r.id=s.current_sample_revision_id AND r.status='CURRENT'
                JOIN ai.sample_source src ON src.organization_id=r.organization_id
                    AND src.id=r.sample_source_id
                WHERE s.organization_id=? AND s.status IN ('ACTIVE','TAKEN_OVER')
                ORDER BY s.logical_sample_key,s.id
                LIMIT ? OFFSET ?
                """, this::sampleRow, organizationId, limit, offset);
    }

    public long currentSampleCount(UUID organizationId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM ai.training_sample s
                JOIN ai.sample_revision r ON r.organization_id=s.organization_id
                    AND r.id=s.current_sample_revision_id AND r.status='CURRENT'
                WHERE s.organization_id=? AND s.status IN ('ACTIVE','TAKEN_OVER')
                """, Long.class, organizationId);
        return count == null ? 0 : count;
    }

    public long factHighWater(UUID organizationId) {
        Long value = jdbc.queryForObject("""
                SELECT coalesce(max(revision_no),0) FROM ai.sample_revision
                WHERE organization_id=?
                """, Long.class, organizationId);
        return value == null ? 0 : value;
    }

    public void saveEligibility(UUID organizationId, UUID id, UUID runId, SampleRow sample,
                                UUID targetVersionId, UUID inputSchemeId, UUID mappingId,
                                UUID dictionaryId, UUID policyId, State state, String primary,
                                JsonNode reasons, JsonNode warnings, JsonNode evidence,
                                long revision, String ruleFingerprint) {
        jdbc.update("""
                INSERT INTO ai.training_eligibility AS te(
                    id,organization_id,evaluation_run_id,sample_revision_id,target_version_id,
                    input_scheme_id,source_mapping_version_id,material_dictionary_version_id,
                    modeling_policy_version_id,state,reasons_jsonb,warnings_jsonb,evidence_jsonb,
                    rule_fingerprint,revision,evaluated_at)
                SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now()
                FROM ai.modeling_policy_version p
                WHERE p.organization_id=? AND p.id=? AND p.kind='TRAINING'
                ON CONFLICT (organization_id, sample_revision_id, target_version_id, input_scheme_id,
                    (coalesce(source_mapping_version_id, '00000000-0000-0000-0000-000000000000'::uuid)),
                    (coalesce(material_dictionary_version_id, '00000000-0000-0000-0000-000000000000'::uuid)),
                    (coalesce(modeling_policy_version_id, '00000000-0000-0000-0000-000000000000'::uuid)))
                    DO UPDATE SET evaluation_run_id=EXCLUDED.evaluation_run_id,
                    source_mapping_version_id=EXCLUDED.source_mapping_version_id,
                    material_dictionary_version_id=EXCLUDED.material_dictionary_version_id,
                    modeling_policy_version_id=EXCLUDED.modeling_policy_version_id,
                    state=EXCLUDED.state,reasons_jsonb=EXCLUDED.reasons_jsonb,
                    warnings_jsonb=EXCLUDED.warnings_jsonb,evidence_jsonb=EXCLUDED.evidence_jsonb,
                    rule_fingerprint=EXCLUDED.rule_fingerprint,revision=EXCLUDED.revision,
                    evaluated_at=EXCLUDED.evaluated_at
                WHERE te.evaluation_run_id IS NULL
                   OR te.evaluation_run_id = EXCLUDED.evaluation_run_id
                   OR EXISTS (
                       SELECT 1
                       FROM ai.eligibility_evaluation_run previous_run
                       JOIN ai.eligibility_evaluation_run incoming_run
                         ON incoming_run.organization_id=EXCLUDED.organization_id
                        AND incoming_run.id=EXCLUDED.evaluation_run_id
                       WHERE previous_run.organization_id=te.organization_id
                         AND previous_run.id=te.evaluation_run_id
                         AND (previous_run.created_at, previous_run.id)
                             <= (incoming_run.created_at, incoming_run.id)
                   )
                """, id, organizationId, runId, sample.revisionId(), targetVersionId,
                inputSchemeId, mappingId, dictionaryId, policyId, state.name(), pg(reasons),
                pg(warnings), pg(evidence), ruleFingerprint, revision, organizationId, policyId);
    }

    public void ensureReview(UUID organizationId, UUID eligibilityId, String reviewType,
                             String reasonCode, JsonNode evidence) {
        // data_review.evidence_jsonb is intentionally an object so review
        // consumers can safely render structured evidence.  Some rule
        // evaluators (for example identity conflicts) naturally return a
        // scalar sample id; preserve it under a stable value key instead of
        // violating the database contract.
        var reviewEvidence = evidence != null && evidence.isObject()
                ? evidence
                : json.createObjectNode().set("value", evidence == null ? json.nullNode() : evidence);
        jdbc.update("""
                INSERT INTO ai.data_review(id,organization_id,review_type,eligibility_id,
                    reason_code,evidence_jsonb,status,priority,revision)
                VALUES(gen_random_uuid(),?,?,?,?,?,'OPEN',100,0)
                ON CONFLICT DO NOTHING
                """, organizationId, reviewType, eligibilityId, reasonCode, pg(reviewEvidence));
    }

    public SummaryCounts counts(UUID organizationId, UUID runId) {
        var result = jdbc.query("""
                SELECT state,count(*) FROM ai.training_eligibility
                WHERE organization_id=? AND evaluation_run_id=? GROUP BY state
                """, rs -> {
            long total=0, trainable=0, excluded=0, review=0;
            while (rs.next()) {
                long value=rs.getLong(2); total+=value;
                switch (rs.getString(1)) { case "TRAINABLE" -> trainable=value; case "EXCLUDED" -> excluded=value; case "REVIEW_REQUIRED" -> review=value; default -> { } }
            }
            return new SummaryCounts(total,trainable,excluded,review);
        }, organizationId, runId);
        return result == null ? new SummaryCounts(0,0,0,0) : result;
    }

    public Map<String,Long> reasonCounts(UUID organizationId, UUID runId) {
        return jdbc.query("""
                SELECT reason->>'code' code,count(*) count
                FROM ai.training_eligibility e CROSS JOIN LATERAL jsonb_array_elements(e.reasons_jsonb) reason
                WHERE e.organization_id=? AND e.evaluation_run_id=?
                GROUP BY reason->>'code' ORDER BY code
                """, rs -> { var result=new LinkedHashMap<String,Long>(); while(rs.next()) result.put(rs.getString(1),rs.getLong(2)); return result; }, organizationId, runId);
    }

    public PageResponse<EligibilityRow> page(UUID organizationId, UUID targetVersionId, UUID inputSchemeId,
                                             String state, String reasonCode, String sourceType,
                                             String keyword, int page, int size) {
        var where=new StringBuilder(" WHERE e.organization_id=? AND e.target_version_id=? AND e.input_scheme_id=?");
        var args=new ArrayList<Object>(List.of(organizationId,targetVersionId,inputSchemeId));
        if(state!=null&&!state.isBlank()){where.append(" AND e.state=?");args.add(state);}
        if(sourceType!=null&&!sourceType.isBlank()){where.append(" AND s.authority_source_type=?");args.add(sourceType);}
        if(keyword!=null&&!keyword.isBlank()){where.append(" AND lower(s.logical_sample_key) LIKE lower(?)");args.add("%"+keyword.strip()+"%");}
        if(reasonCode!=null&&!reasonCode.isBlank()){where.append(" AND e.reasons_jsonb @> ?::jsonb");args.add("[{\"code\":\""+reasonCode.replace("\"","\\\"")+"\"}]");}
        var total=jdbc.queryForObject("""
                SELECT count(*) FROM ai.training_eligibility e
                JOIN ai.sample_revision r ON r.organization_id=e.organization_id AND r.id=e.sample_revision_id
                JOIN ai.training_sample s ON s.organization_id=r.organization_id AND s.current_sample_revision_id=r.id
                """+where,Long.class,args.toArray());
        var queryArgs=new ArrayList<>(args);queryArgs.add(size);queryArgs.add((page-1)*size);
        var rows=jdbc.query("""
                SELECT e.*,s.logical_sample_key,s.authority_source_type
                FROM ai.training_eligibility e
                JOIN ai.sample_revision r ON r.organization_id=e.organization_id AND r.id=e.sample_revision_id
                JOIN ai.training_sample s ON s.organization_id=r.organization_id AND s.current_sample_revision_id=r.id
                """+where+" ORDER BY e.evaluated_at DESC,e.id LIMIT ? OFFSET ?",this::eligibilityRow,queryArgs.toArray());
        long count=total==null?0:total;return new PageResponse<>(rows,page,size,count,count==0?0:(count+size-1)/size);
    }

    public Optional<Eligibility> eligibility(UUID organizationId, UUID id) {
        return jdbc.query("SELECT * FROM ai.training_eligibility WHERE organization_id=? AND id=?",this::eligibility,organizationId,id).stream().findFirst();
    }

    public Optional<JsonNode> sampleForRevision(UUID organizationId, UUID revisionId) {
        return jdbc.query("""
                SELECT jsonb_build_object('sampleId',s.id,'logicalSampleKey',s.logical_sample_key,
                    'status',s.status,'authoritySourceType',s.authority_source_type,
                    'revisionId',r.id,'revisionNo',r.revision_no,'composition',r.composition_jsonb,
                    'process',r.process_jsonb,'conditions',r.conditions_jsonb,'observations',r.observations_jsonb,
                    'facts',r.facts_jsonb,'sourceCoordinates',r.source_coordinates_jsonb) 
                FROM ai.sample_revision r JOIN ai.training_sample s
                  ON s.organization_id=r.organization_id AND s.current_sample_revision_id=r.id
                WHERE r.organization_id=? AND r.id=?
                """, (rs, ignored) -> node(rs, 1), organizationId, revisionId).stream().findFirst();
    }

    public Optional<UUID> targetForEligibility(UUID organizationId, UUID eligibilityId) {
        return jdbc.query("""
                SELECT t.id FROM ai.training_eligibility e
                JOIN ai.target_version tv ON tv.organization_id=e.organization_id AND tv.id=e.target_version_id
                JOIN ai.prediction_target t ON t.organization_id=tv.organization_id AND t.id=tv.target_id
                WHERE e.organization_id=? AND e.id=?
                """, (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, eligibilityId).stream().findFirst();
    }

    public List<Review> reviews(UUID organizationId, UUID eligibilityId) {
        return jdbc.query("""
                SELECT * FROM ai.data_review WHERE organization_id=? AND eligibility_id=?
                ORDER BY priority,created_at,id
                """,this::review,organizationId,eligibilityId);
    }

    public Optional<ReviewRow> reviewForUpdate(UUID organizationId, UUID id) {
        return jdbc.query("SELECT * FROM ai.data_review WHERE organization_id=? AND id=? FOR UPDATE",this::reviewRow,organizationId,id).stream().findFirst();
    }

    public Optional<DecisionRow> decision(UUID organizationId, UUID reviewId, String key) {
        return jdbc.query("""
                SELECT id,decision_no,decision,reason,decided_at FROM ai.data_review_decision
                WHERE organization_id=? AND data_review_id=? AND idempotency_key=?
                """,this::decisionRow,organizationId,reviewId,key).stream().findFirst();
    }

    public int nextDecisionNo(UUID organizationId, UUID reviewId) {
        Integer n=jdbc.queryForObject("SELECT coalesce(max(decision_no),0)+1 FROM ai.data_review_decision WHERE organization_id=? AND data_review_id=?",Integer.class,organizationId,reviewId);
        return n==null?1:n;
    }

    public void insertDecision(UUID organizationId, UUID reviewId, int no, String decision, String reason,
                               UUID actorId, String requestId, String key, JsonNode before, JsonNode after) {
        jdbc.update("""
                INSERT INTO ai.data_review_decision(
                    id,organization_id,data_review_id,decision_no,decision,reason,evidence_jsonb,
                    decided_by,request_id,idempotency_key,before_jsonb,after_jsonb)
                VALUES(gen_random_uuid(),?,?,?,?,?,'{}'::jsonb,?,?,?,?,?)
                """, organizationId, reviewId, no, decision, reason, actorId, requestId, key, pg(before), pg(after));
    }

    public void resolveReview(UUID organizationId, UUID id, long revision, String status) {
        jdbc.update("""
                UPDATE ai.data_review SET status=?,revision=revision+1,updated_at=now()
                WHERE organization_id=? AND id=? AND revision=?
                """,status,organizationId,id,revision);
    }

    private RunRow runRow(ResultSet rs,int ignored)throws java.sql.SQLException{
        return new RunRow(rs.getObject("id",UUID.class),rs.getObject("target_id",UUID.class),rs.getObject("target_version_id",UUID.class),
                rs.getObject("input_scheme_id",UUID.class),rs.getObject("modeling_policy_version_id",UUID.class),
                rs.getObject("material_dictionary_version_id",UUID.class),node(rs,"source_mapping_versions_jsonb"),rs.getString("rule_fingerprint"),
                rs.getObject("async_job_id",UUID.class),RunStatus.valueOf(rs.getString("status")),longNullable(rs,"total_samples"),
                longNullable(rs,"trainable_count"),longNullable(rs,"excluded_count"),longNullable(rs,"review_required_count"),node(rs,"funnel_jsonb"),
                instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("started_at")),instant(rs.getTimestamp("finished_at")),
                rs.getString("error_code"),rs.getString("error_message"));
    }
    private SampleRow sampleRow(ResultSet rs,int ignored)throws java.sql.SQLException{return new SampleRow(rs.getObject("sample_id",UUID.class),rs.getString("logical_sample_key"),rs.getString("sample_status"),rs.getString("authority_source_type"),rs.getObject("revision_id",UUID.class),rs.getInt("revision_no"),node(rs,"composition_jsonb"),node(rs,"process_jsonb"),node(rs,"conditions_jsonb"),node(rs,"observations_jsonb"),node(rs,"facts_jsonb"),node(rs,"source_coordinates_jsonb"),rs.getString("source_type"),rs.getString("source_status"),rs.getBoolean("identity_conflict"));}
    private EligibilityRow eligibilityRow(ResultSet rs,int ignored)throws java.sql.SQLException{var reasons=reasonList(node(rs,"reasons_jsonb"));var warnings=reasonList(node(rs,"warnings_jsonb"));return new EligibilityRow(rs.getObject("id",UUID.class),rs.getObject("evaluation_run_id",UUID.class),rs.getObject("sample_revision_id",UUID.class),rs.getObject("target_version_id",UUID.class),rs.getObject("input_scheme_id",UUID.class),State.valueOf(rs.getString("state")),reasons.isEmpty()?null:reasons.getFirst().code(),reasons,warnings,rs.getString("logical_sample_key"),rs.getString("authority_source_type"),rs.getLong("revision"),instant(rs.getTimestamp("evaluated_at")));}
    private Eligibility eligibility(ResultSet rs,int ignored)throws java.sql.SQLException{var reasons=reasonList(node(rs,"reasons_jsonb"));return new Eligibility(rs.getObject("id",UUID.class),rs.getObject("evaluation_run_id",UUID.class),rs.getObject("sample_revision_id",UUID.class),rs.getObject("target_version_id",UUID.class),rs.getObject("input_scheme_id",UUID.class),State.valueOf(rs.getString("state")),reasons.isEmpty()?null:reasons.getFirst().code(),reasons,reasonList(node(rs,"warnings_jsonb")),node(rs,"evidence_jsonb"),rs.getLong("revision"),instant(rs.getTimestamp("evaluated_at")));}
    private Review review(ResultSet rs,int ignored)throws java.sql.SQLException{return new Review(rs.getObject("id",UUID.class),rs.getString("review_type"),rs.getString("reason_code"),rs.getString("status"),rs.getShort("priority"),node(rs,"evidence_jsonb"),rs.getLong("revision"),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("updated_at")));}
    private ReviewRow reviewRow(ResultSet rs,int ignored)throws java.sql.SQLException{return new ReviewRow(rs.getObject("id",UUID.class),rs.getObject("eligibility_id",UUID.class),rs.getString("review_type"),rs.getString("reason_code"),rs.getString("status"),rs.getLong("revision"),node(rs,"evidence_jsonb"));}
    private DecisionRow decisionRow(ResultSet rs,int ignored)throws java.sql.SQLException{return new DecisionRow(rs.getObject("id",UUID.class),rs.getInt("decision_no"),rs.getString("decision"),rs.getString("reason"),instant(rs.getTimestamp("decided_at")));}
    private List<QualificationReason> reasonList(JsonNode n){var result=new ArrayList<QualificationReason>();if(n!=null&&n.isArray())for(var x:n)result.add(new QualificationReason(x.path("code").asText(),x.path("message").asText(),x.path("fieldCode").isNull()?null:x.path("fieldCode").asText(null),x.path("evidence")));return result;}
    private JsonNode node(ResultSet rs,String column)throws java.sql.SQLException{var value=rs.getObject(column);if(value instanceof PGobject pg)try{return json.readTree(pg.getValue());}catch(Exception ignored){}return value==null?json.createObjectNode():json.valueToTree(value);}
    private JsonNode node(ResultSet rs,int column)throws java.sql.SQLException{var value=rs.getObject(column);if(value instanceof PGobject pg)try{return json.readTree(pg.getValue());}catch(Exception ignored){}return value==null?json.createObjectNode():json.valueToTree(value);}
    private List<String> list(JsonNode node){var result=new ArrayList<String>();if(node!=null&&node.isArray())for(var item:node)result.add(item.asText());return result;}
    private Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private Long longNullable(ResultSet rs,String column)throws java.sql.SQLException{long value=rs.getLong(column);return rs.wasNull()?null:value;}
    private PGobject pg(JsonNode value){try{var pg=new PGobject();pg.setType("jsonb");pg.setValue(value==null?"{}":value.toString());return pg;}catch(Exception e){throw new IllegalArgumentException(e);}}

    public record RunRow(UUID id, UUID targetId, UUID targetVersionId, UUID inputSchemeId, UUID policyId,
                          UUID dictionaryId, JsonNode sourceMappingVersions,
                          String fingerprint, UUID asyncJobId, RunStatus status, Long total, Long trainable,
                          Long excluded, Long reviewRequired, JsonNode funnel, Instant createdAt,
                          Instant startedAt, Instant finishedAt, String errorCode, String errorMessage) { }
    public record SummaryCounts(long total,long trainable,long excluded,long reviewRequired) { }
    public record SampleRow(UUID sampleId,String logicalSampleKey,String sampleStatus,String authoritySourceType,
                            UUID revisionId,int revisionNo,JsonNode composition,JsonNode process,JsonNode conditions,
                            JsonNode observations,JsonNode facts,JsonNode coordinates,String sourceType,
                            String sourceStatus,boolean identityConflict) { }
    public record ReviewRow(UUID id,UUID eligibilityId,String reviewType,String reasonCode,String status,long revision,JsonNode evidence) { }
    public record DecisionRow(UUID id,int decisionNo,String decision,String reason,Instant decidedAt) { }
    public record Configuration(UUID targetId,UUID targetVersionId,UUID inputSchemeId,String valueType,String unit,
                                List<String> classes,JsonNode definition,JsonNode observationSemantics,
                                UUID dictionaryId,String schemeStatus,String targetVersionStatus,
                                List<FieldRow> fields,Map<String,SourceMappingRow> mappings,PolicyRow policy,
                                JsonNode dictionary,String dictionaryHash) {
        public Configuration(UUID targetId,UUID targetVersionId,UUID inputSchemeId,String valueType,String unit,
                             List<String> classes,JsonNode definition,JsonNode observationSemantics,UUID dictionaryId,
                             String schemeStatus,String targetVersionStatus){this(targetId,targetVersionId,inputSchemeId,valueType,unit,classes,definition,observationSemantics,dictionaryId,schemeStatus,targetVersionStatus,List.of(),Map.of(),null,null,null);}
        Configuration with(List<FieldRow> fields,Map<String,SourceMappingRow> mappings,PolicyRow policy,JsonNode dictionary,String dictionaryHash){return new Configuration(targetId,targetVersionId,inputSchemeId,valueType,unit,classes,definition,observationSemantics,dictionaryId,schemeStatus,targetVersionStatus,fields,mappings,policy,dictionary,dictionaryHash);}
    }
    public record FieldRow(UUID id,boolean required,int ordinal,String code,String name,String valueType,String unit,String availabilityStage,JsonNode definition,JsonNode preprocessing) { }
    public record SourceMappingRow(UUID id,String sourceType,JsonNode mapping,int version) { }
    public record PolicyRow(UUID id,JsonNode qualification,JsonNode validation,JsonNode training,String hash) { }
}
