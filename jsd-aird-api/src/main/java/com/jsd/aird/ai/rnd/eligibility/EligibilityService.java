package com.jsd.aird.ai.rnd.eligibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.eligibility.EligibilityContracts.*;
import static com.jsd.aird.ai.rnd.eligibility.EligibilityRepository.*;

/** Qualification is a persisted, versioned read model. It never mutates source facts. */
@Service
public class EligibilityService {
    private static final Logger log = LoggerFactory.getLogger(EligibilityService.class);
    public static final String JOB_TYPE = "AI_ELIGIBILITY_RECOMPUTE";
    private final EligibilityRepository repository;
    private final EligibilityRuleEvaluator evaluator;
    private final ObjectMapper json;
    private final ConfigurationHashing hashing;
    private final OpsAsyncFacade async;
    private final AuditLogFacade audit;

    public EligibilityService(EligibilityRepository repository, ObjectMapper json,
                              ConfigurationHashing hashing, OpsAsyncFacade async,
                              AuditLogFacade audit) {
        this.repository = repository;
        this.evaluator = new EligibilityRuleEvaluator(json);
        this.json = json;
        this.hashing = hashing;
        this.async = async;
        this.audit = audit;
    }

    public Summary summary(UUID targetVersionId, UUID inputSchemeId) {
        var actor = actor();
        var run = repository.latestRun(actor.organizationId(), targetVersionId, inputSchemeId);
        if (run.isEmpty()) return new Summary("NOT_EVALUATED", "CONFIG_NOT_READY", null,
                null, null, null, null, Map.of(), List.of(), List.of(), null);
        var r = run.get();
        var status = switch (r.status()) {
            case QUEUED, RUNNING -> "RUNNING";
            case SUCCEEDED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELLED -> "FAILED";
        };
        var counts = r.status() == RunStatus.SUCCEEDED ? repository.counts(actor.organizationId(), r.id()) : null;
        var funnel = funnel(r.funnel());
        var coverage = coverage(r.funnel());
        return new Summary(status, r.status() == RunStatus.FAILED ? r.errorCode() : null,
                toRun(r), counts == null ? null : counts.total(), counts == null ? null : counts.trainable(),
                counts == null ? null : counts.excluded(), counts == null ? null : counts.reviewRequired(),
                r.status() == RunStatus.SUCCEEDED ? repository.reasonCounts(actor.organizationId(), r.id()) : Map.of(),
                funnel, coverage, r.finishedAt());
    }

    public PageResponse<EligibilityRow> page(UUID targetVersionId, UUID inputSchemeId, String state,
                                              String reasonCode, String sourceType, String keyword,
                                              int page, int size) {
        var actor = actor();
        return repository.page(actor.organizationId(), targetVersionId, inputSchemeId, state,
                reasonCode, sourceType, keyword, Math.max(1, page), Math.min(100, Math.max(1, size)));
    }

    public EligibilityDetail detail(UUID id) {
        var actor = actor();
        var eligibility = repository.eligibility(actor.organizationId(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "资格结果不存在"));
        var sample = repository.sampleForRevision(actor.organizationId(), eligibility.sampleRevisionId())
                .orElseGet(json::createObjectNode);
        return new EligibilityDetail(eligibility, sample, repository.reviews(actor.organizationId(), id));
    }

    @Transactional
    public RecomputeAccepted reevaluate(UUID targetId, String idempotencyKey) {
        var actor = actor();
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "缺少 Idempotency-Key");
        idempotencyKey = idempotencyKey.strip();
        var config = repository.configuration(actor.organizationId(), targetId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "预测目标不存在"));
        requireReady(config);
        var highWater = repository.factHighWater(actor.organizationId());
        var fingerprint = hashing.hash(Map.of(
                "targetVersionId", config.targetVersionId(), "inputSchemeId", config.inputSchemeId(),
                "mappings", config.mappings().values().stream().map(SourceMappingRow::id).toList(),
                "dictionaryId", String.valueOf(config.dictionaryId()), "dictionaryHash", String.valueOf(config.dictionaryHash()),
                "policyId", config.policy().id(), "policyHash", config.policy().hash(), "factHighWater", highWater));
        var requestHash = hashing.hash(Map.of("targetId", targetId, "targetVersionId", config.targetVersionId(),
                "inputSchemeId", config.inputSchemeId(), "configurationFingerprint", fingerprint));
        repository.lockCommand(actor.organizationId(), "AI_ELIGIBILITY_RECOMPUTE", idempotencyKey);
        var receipt = repository.commandReceipt(actor.organizationId(), "AI_ELIGIBILITY_RECOMPUTE", idempotencyKey);
        if (receipt.isPresent()) {
            if (!requestHash.equals(receipt.get().get("requestHash"))) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            try { return json.treeToValue((JsonNode) receipt.get().get("response"), RecomputeAccepted.class); }
            catch (Exception exception) { throw new IllegalStateException("资格重算幂等结果无法恢复", exception); }
        }
        var payload = json.createObjectNode().put("targetId", targetId.toString())
                .put("targetVersionId", config.targetVersionId().toString())
                .put("inputSchemeId", config.inputSchemeId().toString()).put("runFingerprint", fingerprint)
                .put("factHighWater", highWater);
        payload.set("mappingVersions", json.valueToTree(config.mappings().entrySet().stream()
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue().id().toString()), Map::putAll)));
        if (config.dictionaryId() != null) payload.put("dictionaryVersionId", config.dictionaryId().toString());
        payload.put("policyVersionId", config.policy().id().toString());
        var key = "ai-eligibility:" + hashing.hash(Map.of("organizationId", actor.organizationId(), "targetId", targetId,
                "targetVersionId", config.targetVersionId(), "inputSchemeId", config.inputSchemeId(),
                "fingerprint", fingerprint, "idempotencyKey", idempotencyKey == null ? "" : idempotencyKey));
        var existingJob = async.findJob(actor.organizationId(), key);
        if (existingJob.isPresent()) {
            var existingRun = repository.runByJob(actor.organizationId(), existingJob.get().id());
            if (existingRun.isPresent()) {
                var old = existingRun.get();
                return new RecomputeAccepted(old.id(), old.asyncJobId(), old.targetId(), old.targetVersionId(), old.inputSchemeId(), old.status(), old.fingerprint());
            }
        }
        var runId = UUID.randomUUID();
        payload.put("organizationId", actor.organizationId().toString()).put("runId", runId.toString());
        var asyncJobId = async.enqueue(actor.organizationId(), JOB_TYPE, payload, key, 60);
        var racedRun = repository.runByJob(actor.organizationId(), asyncJobId);
        if (racedRun.isPresent()) {
            var accepted = new RecomputeAccepted(racedRun.get().id(), racedRun.get().asyncJobId(), racedRun.get().targetId(),
                    racedRun.get().targetVersionId(), racedRun.get().inputSchemeId(), racedRun.get().status(), racedRun.get().fingerprint());
            repository.insertCommandReceipt(actor.organizationId(), "AI_ELIGIBILITY_RECOMPUTE", idempotencyKey,
                    requestHash, RecomputeAccepted.class.getSimpleName(), accepted.runId(), json.valueToTree(accepted), actor.userId());
            return accepted;
        }
        var mappings = json.createArrayNode();
        config.mappings().forEach((source, mapping) -> mappings.add(json.createObjectNode()
                .put("sourceType", source).put("versionId", mapping.id().toString()).put("version", mapping.version())));
        repository.insertRun(actor.organizationId(), runId, targetId, config.targetVersionId(), config.inputSchemeId(),
                mappings, config.dictionaryId(), config.policy().id(), fingerprint, asyncJobId, actor.userId());
        audit.append(actor.organizationId(), actor.userId(), "AI_ELIGIBILITY_RECOMPUTE_REQUESTED",
                "AI_ELIGIBILITY_RUN", runId, payload);
        var accepted = new RecomputeAccepted(runId, asyncJobId, targetId, config.targetVersionId(), config.inputSchemeId(),
                RunStatus.QUEUED, fingerprint);
        repository.insertCommandReceipt(actor.organizationId(), "AI_ELIGIBILITY_RECOMPUTE", idempotencyKey,
                requestHash, RecomputeAccepted.class.getSimpleName(), runId, json.valueToTree(accepted), actor.userId());
        return accepted;
    }

    /** Event driven scheduling hook used by fact projection and configuration publication. */
    @Transactional
    public void scheduleForTarget(UUID organizationId, UUID targetId) {
        var config = repository.configuration(organizationId, targetId).orElse(null);
        if (config == null) return;
        try { requireReady(config); } catch (ApiException ignored) { return; }
        var highWater = repository.factHighWater(organizationId);
        var fingerprint = hashing.hash(Map.of("targetVersionId", config.targetVersionId(),
                "inputSchemeId", config.inputSchemeId(), "mappings", config.mappings().values().stream().map(SourceMappingRow::id).toList(),
                "dictionaryHash", String.valueOf(config.dictionaryHash()), "policyHash", config.policy().hash(), "factHighWater", highWater));
        var key = "ai-eligibility:auto:" + hashing.hash(Map.of("organizationId", organizationId, "targetId", targetId,
                "inputSchemeId", config.inputSchemeId(), "fingerprint", fingerprint));
        var runId = UUID.randomUUID();
        var payload = json.createObjectNode().put("organizationId", organizationId.toString()).put("runId", runId.toString())
                .put("targetId", targetId.toString()).put("targetVersionId", config.targetVersionId().toString())
                .put("inputSchemeId", config.inputSchemeId().toString()).put("runFingerprint", fingerprint).put("factHighWater", highWater)
                .put("policyVersionId", config.policy().id().toString());
        var jobId = async.enqueue(organizationId, JOB_TYPE, payload, key, 60);
        if (repository.runByJob(organizationId, jobId).isPresent()) return;
        repository.insertRun(organizationId, runId, targetId, config.targetVersionId(), config.inputSchemeId(),
                mappingArray(config),
                config.dictionaryId(), config.policy().id(), fingerprint, jobId, null);
    }

    public void scheduleForChangedFacts(UUID organizationId) {
        repository.jdbc().query("SELECT id FROM ai.prediction_target WHERE organization_id=? AND current_input_scheme_id IS NOT NULL",
                (rs, ignored) -> rs.getObject(1, UUID.class), organizationId).forEach(id -> scheduleForTarget(organizationId, id));
    }

    /** Called by the worker. The run owns one immutable configuration fingerprint. */
    @Transactional
    public JsonNode executeRun(UUID organizationId, UUID runId) {
        var run = repository.run(organizationId, runId)
                .orElseThrow(() -> new IllegalStateException("资格运行不存在: " + runId));
        var config = repository.configurationForRun(organizationId, run)
                .orElseThrow(() -> new IllegalStateException("资格配置不存在: " + run.targetId()));
        requireReady(config, true);
        repository.startRun(organizationId, runId);
        long total = 0, trainable = 0, excluded = 0, review = 0;
        var funnel = json.createObjectNode();
        try {
            var batch = 200;
            var offset = 0;
            var steps = new long[7];
            while (true) {
                var samples = repository.currentSamples(organizationId, batch, offset);
                if (samples.isEmpty()) break;
                for (var sample : samples) {
                    var result = evaluator.evaluate(config, sample);
                    var reasons = json.valueToTree(result.reasons());
                    var warnings = json.valueToTree(result.warnings());
                    repository.saveEligibility(organizationId, UUID.randomUUID(), runId, sample,
                            config.targetVersionId(), config.inputSchemeId(), mappingId(config, sample),
                            config.dictionaryId(), config.policy().id(), result.state(), result.primaryReason(),
                            reasons, warnings, result.evidence(), sample.revisionNo(), run.fingerprint());
                    for (var reason : result.reasons()) {
                        if ("UNKNOWN_MATERIAL".equals(reason.code()) || "ANOMALY_REVIEW_REQUIRED".equals(reason.code())
                                || "SAMPLE_IDENTITY_CONFLICT".equals(reason.code()))
                            repository.ensureReview(organizationId, latestEligibilityId(organizationId, runId, sample.revisionId()),
                                    reviewType(reason.code()), reason.code(), reason.evidence());
                    }
                    total++;
                    if (result.state() == State.TRAINABLE) trainable++; else if (result.state() == State.EXCLUDED) excluded++; else review++;

                    // Funnel stages are cumulative. A later stage can only contain a sample
                    // that passed every preceding gate; do not count unrelated reasons in a
                    // later stage just because another gate happened to pass.
                    steps[0]++;
                    steps[1]++; // one current source observation maps to one logical sample
                    var targetMatched = !hasAny(result.reasons(), "TARGET_SEMANTIC_MISMATCH", "TARGET_AMBIGUOUS");
                    var targetValid = targetMatched && result.evidence().has("targetValue");
                    var inputsComplete = targetValid && !hasAny(result.reasons(),
                            "MISSING_REQUIRED_X", "FORMULA_BASIS_UNRESOLVED", "POST_EXPERIMENT_FIELD_NOT_ALLOWED", "UNIT_UNKNOWN");
                    var materialReviewPassed = inputsComplete && !hasAny(result.reasons(),
                            "UNKNOWN_MATERIAL", "MATERIAL_NOT_IN_MODEL", "MATERIAL_NOT_IN_DICTIONARY",
                            "ANOMALY_REVIEW_REQUIRED", "SAMPLE_IDENTITY_CONFLICT");
                    if (targetMatched) steps[2]++;
                    if (targetValid) steps[3]++;
                    if (inputsComplete) steps[4]++;
                    if (materialReviewPassed) steps[5]++;
                    if (result.state() == State.TRAINABLE) steps[6]++;
                }
                offset += samples.size();
            }
            var labels = List.of("SOURCE_OBSERVATIONS", "DEDUPLICATED_SAMPLES", "Y_MATCHED", "Y_VALID_VALUE", "X_COMPLETE", "MATERIAL_REVIEW_PASSED", "TRAINABLE");
            var values = json.createArrayNode();
            for (int i = 0; i < steps.length; i++) values.add(json.createObjectNode().put("code", labels.get(i)).put("label", labels.get(i)).put("count", steps[i]).put("denominator", total));
            funnel.set("steps", values);
            funnel.set("fieldCoverage", fieldCoverage(config, organizationId, runId, total));
            repository.finishRun(organizationId, runId, RunStatus.SUCCEEDED, total, trainable, excluded, review, funnel, null, null);
            var trainingEvent = json.createObjectNode().put("organizationId", organizationId.toString())
                    .put("targetId", run.targetId().toString()).put("eligibilityRunId", runId.toString())
                    .put("ruleFingerprint", run.fingerprint()).put("trainable", trainable);
            async.appendOutbox(organizationId, "AI_ELIGIBILITY_RUN", runId, "AI_ELIGIBILITY_COMPLETED",
                    "eligibility-completed:" + runId, trainingEvent);
            return json.createObjectNode().put("runId", runId.toString()).put("status", "SUCCEEDED")
                    .put("total", total).put("trainable", trainable).put("excluded", excluded).put("reviewRequired", review);
        } catch (RuntimeException exception) {
            log.error("eligibility run failed before terminal update: organizationId={}, runId={}, processed={}, error={}",
                    organizationId, runId, total, exception.getMessage(), exception);
            repository.finishRun(organizationId, runId, RunStatus.FAILED, total, trainable, excluded, review,
                    funnel, "ELIGIBILITY_RUN_FAILED", exception.getMessage());
            throw exception;
        }
    }

    @Transactional
    public ReviewDecision decide(UUID reviewId, ReviewDecisionCommand command, String idempotencyKey, String requestId) {
        var actor = actor();
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "缺少 Idempotency-Key");
        var review = repository.reviewForUpdate(actor.organizationId(), reviewId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "审查项不存在"));
        var prior = repository.decision(actor.organizationId(), reviewId, idempotencyKey);
        if (prior.isPresent()) return new ReviewDecision(reviewId, prior.get().decision(), prior.get().reason(), null, review.revision(), prior.get().decidedAt());
        if (!"OPEN".equals(review.status()) && !"IN_REVIEW".equals(review.status()))
            throw new ApiException(ApiErrorCode.REVIEW_DECISION_NOT_ALLOWED, "当前审查项已经处理，不能重复决定");
        if (command.expectedRevision() != review.revision()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        if (command.reason() == null || command.reason().isBlank())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "审查决定必须填写理由");
        if (!allowed(review.reasonCode(), command.decision())) throw new ApiException(ApiErrorCode.REVIEW_DECISION_NOT_ALLOWED, "当前问题不支持该审查动作");
        if ("REMAP".equals(command.decision()) && command.materialId() == null)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "重新匹配材料必须指定材料");
        var before = json.createObjectNode().put("status", review.status()).put("revision", review.revision());
        var after = json.createObjectNode().put("decision", command.decision()).put("reason", command.reason());
        if (command.materialId() != null) after.put("materialId", command.materialId().toString());
        var decisionNo = repository.nextDecisionNo(actor.organizationId(), reviewId);
        repository.insertDecision(actor.organizationId(), reviewId, decisionNo, command.decision(), command.reason(), actor.userId(), requestId, idempotencyKey, before, after);
        repository.resolveReview(actor.organizationId(), reviewId, review.revision(), "RESOLVED");
        audit.append(actor.organizationId(), actor.userId(), "AI_ELIGIBILITY_REVIEW_DECIDED", "AI_DATA_REVIEW", reviewId, after);
        var target = repository.targetForEligibility(actor.organizationId(), review.eligibilityId()).orElse(null);
        UUID recompute = null;
        if (target != null) {
            try { recompute = reevaluate(target, "review:" + reviewId + ":" + decisionNo).runId(); } catch (ApiException ignored) { }
        }
        return new ReviewDecision(reviewId, command.decision(), command.reason(), recompute, review.revision() + 1, Instant.now());
    }

    public void failRun(UUID organizationId, UUID runId, Exception exception) {
        repository.finishRun(organizationId, runId, RunStatus.FAILED, 0, 0, 0, 0, json.createObjectNode(), "ELIGIBILITY_RUN_FAILED", exception.getMessage());
    }

    private void requireReady(Configuration c) { requireReady(c, false); }

    private void requireReady(Configuration c, boolean historicalRun) {
        var missing = new ArrayList<String>();
        if (!"PUBLISHED".equals(c.targetVersionStatus()) && !(historicalRun && "RETIRED".equals(c.targetVersionStatus()))) missing.add("targetVersion");
        if (!"FROZEN".equals(c.schemeStatus()) && !(historicalRun && "RETIRED".equals(c.schemeStatus()))) missing.add("inputScheme");
        if (c.policy() == null) missing.add("trainingPolicy");
        if (c.mappings().isEmpty()) missing.add("sourceMapping");
        if (!missing.isEmpty()) throw new ApiException(ApiErrorCode.ELIGIBILITY_CONFIG_NOT_READY, "资格评估配置尚未就绪: " + String.join(",", missing));
    }

    private UUID mappingId(Configuration c, SampleRow sample) { var m = c.mappings().get(sample.sourceType()); return m == null ? null : m.id(); }
    private ArrayNode mappingArray(Configuration c) { var out = json.createArrayNode(); c.mappings().forEach((source, mapping) -> out.add(json.createObjectNode().put("sourceType", source).put("versionId", mapping.id().toString()).put("version", mapping.version()))); return out; }
    private UUID latestEligibilityId(UUID org, UUID run, UUID revision) { return repository.jdbc().queryForObject("SELECT id FROM ai.training_eligibility WHERE organization_id=? AND evaluation_run_id=? AND sample_revision_id=?", UUID.class, org, run, revision); }
    private boolean hasAny(List<QualificationReason> reasons, String... codes) {
        var expected = Set.of(codes);
        return reasons.stream().anyMatch(reason -> expected.contains(reason.code()));
    }
    private String reviewType(String code) { return code.startsWith("UNKNOWN_MATERIAL") ? "UNKNOWN_MATERIAL" : code.startsWith("SAMPLE") ? "IDENTITY" : "OUTLIER"; }
    private boolean allowed(String reason, String decision) { return switch (reason == null ? "" : reason) { case "UNKNOWN_MATERIAL" -> "REMAP".equals(decision); case "SAMPLE_IDENTITY_CONFLICT" -> "MERGE".equals(decision) || "KEEP_SEPARATE".equals(decision); case "ANOMALY_REVIEW_REQUIRED" -> "KEEP".equals(decision) || "EXCLUDE".equals(decision); default -> false; }; }

    private List<FunnelStep> funnel(JsonNode n) { var out = new ArrayList<FunnelStep>(); if (n != null && n.path("steps").isArray()) for (var item : n.path("steps")) out.add(new FunnelStep(item.path("code").asText(), item.path("label").asText(), item.path("count").asLong(), item.path("denominator").asLong(), null)); return out; }
    private List<FieldCoverage> coverage(JsonNode n) { var out = new ArrayList<FieldCoverage>(); if (n != null && n.path("fieldCoverage").isArray()) for (var item : n.path("fieldCoverage")) { var reasons = new ArrayList<String>(); item.path("reasonCodes").forEach(x -> reasons.add(x.asText())); out.add(new FieldCoverage(item.path("fieldCode").asText(), item.path("fieldName").asText(), item.path("available").asLong(), item.path("denominator").asLong(), item.path("ratio").asDouble(), reasons)); } return out; }
    private ArrayNode fieldCoverage(Configuration c, UUID org, UUID run, long ignoredDenominator) {
        var out = json.createArrayNode();
        Long yValid = repository.jdbc().queryForObject("""
                SELECT count(*) FROM ai.training_eligibility
                WHERE organization_id=? AND evaluation_run_id=?
                  AND jsonb_exists(evidence_jsonb, 'targetValue')
                """, Long.class, org, run);
        long denominator = yValid == null ? 0 : yValid;
        for (var field : c.fields()) {
            Long missingValue = repository.jdbc().queryForObject("""
                    SELECT count(*) FROM ai.training_eligibility
                    WHERE organization_id=? AND evaluation_run_id=?
                      AND jsonb_exists(evidence_jsonb, 'targetValue')
                      AND reasons_jsonb @> ?::jsonb
                    """, Long.class, org, run,
                    "[{\"code\":\"MISSING_REQUIRED_X\",\"fieldCode\":\"" + field.code().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}]");
            long missing = missingValue == null ? 0 : missingValue;
            long available = Math.max(0, denominator - missing);
            var item = json.createObjectNode().put("fieldCode", field.code()).put("fieldName", field.name())
                    .put("available", available).put("denominator", denominator)
                    .put("ratio", denominator == 0 ? 0 : ((double) available / denominator));
            var reasonCodes = json.createArrayNode();
            if (missing > 0) reasonCodes.add("MISSING_REQUIRED_X");
            item.set("reasonCodes", reasonCodes);
            out.add(item);
        }
        return out;
    }
    private EvaluationRun toRun(RunRow r) { return new EvaluationRun(r.id(), r.targetId(), r.targetVersionId(), r.inputSchemeId(), r.policyId(), r.fingerprint(), r.asyncJobId(), r.status(), r.total(), r.trainable(), r.excluded(), r.reviewRequired(), funnel(r.funnel()), r.createdAt(), r.startedAt(), r.finishedAt(), r.errorCode(), r.errorMessage()); }
    private Actor actor() { return ActorContext.required(); }
}

