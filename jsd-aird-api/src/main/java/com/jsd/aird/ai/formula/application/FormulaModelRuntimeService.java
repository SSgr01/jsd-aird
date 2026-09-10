package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.FormulaComponent;
import com.jsd.aird.ai.formula.api.FormulaModelContracts;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.*;
import com.jsd.aird.ai.formula.api.FormulaResearchFacade.Constraints;
import com.jsd.aird.ai.formula.api.FormulaResearchFacade.Goal;
import com.jsd.aird.ai.formula.api.FormulaResearchFacade.ResearchRequest;
import com.jsd.aird.ai.formula.application.ResearchComputationService.ComputedResearch;
import com.jsd.aird.ai.formula.application.port.FormulaModelComputeClient;
import com.jsd.aird.ai.formula.application.port.FormulaModelRepository;
import com.jsd.aird.ai.formula.application.port.FormulaModelRepository.ActivationRow;
import com.jsd.aird.ai.formula.application.port.FormulaModelRepository.NewTaskProfile;
import com.jsd.aird.ai.formula.application.port.ResearchRepository.NewCandidate;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.ActorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Applies active production scorers to T06 candidates. T06 remains the safe fallback;
 * every model formula is checked again by the Java rule engine before it can be returned.
 */
@Service
public class FormulaModelRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(FormulaModelRuntimeService.class);
    private final FormulaModelRepository models;
    private final FormulaModelTaskProfileRegistry profiles;
    private final FormulaModelArtifactStore artifacts;
    private final FormulaModelComputeClient compute;
    private final FormulaRuleEngine rules;
    private final JsonCanonicalizer canonicalizer;
    private final ObjectMapper json;

    public FormulaModelRuntimeService(FormulaModelRepository models,
                                      FormulaModelTaskProfileRegistry profiles,
                                      FormulaModelArtifactStore artifacts,
                                      FormulaModelComputeClient compute,
                                      FormulaRuleEngine rules,
                                      JsonCanonicalizer canonicalizer,
                                      ObjectMapper json) {
        this.models = models;
        this.profiles = profiles;
        this.artifacts = artifacts;
        this.compute = compute;
        this.rules = rules;
        this.canonicalizer = canonicalizer;
        this.json = json;
    }

    public ComputedResearch apply(String runType, ResearchRequest request, ComputedResearch fallback) {
        if (fallback.candidates().isEmpty()) return fallback;
        var actor = ActorContext.required();
        var profileRow = models.ensureProfile(new NewTaskProfile(UUID.randomUUID(), actor.organizationId(),
                profiles.production().code(), profiles.production().version(), "ACTIVE",
                profiles.production().contractVersion(), profiles.production().schemaHash(),
                profiles.productionHash(), profiles.productionJson(), actor.userId()));
        var activations = models.activeTargets(actor.organizationId(), profileRow.id()).stream()
                .collect(Collectors.toMap(ActivationRow::targetKey, item -> item));
        var requested = request.goals().stream().map(Goal::targetKey).collect(Collectors.toCollection(LinkedHashSet::new));
        var applicable = activations.entrySet().stream().filter(entry -> requested.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (applicable.isEmpty()) return fallback;

        var recommendation = new ModelRecommendation(fallback.candidates(), false);
        var continuous = request.goals().stream().filter(goal -> applicable.containsKey(goal.targetKey()))
                .filter(goal -> target(goal.targetKey()).valueType() == ValueType.CONTINUOUS).toList();
        if (!continuous.isEmpty() && continuous.stream().map(goal -> applicable.get(goal.targetKey()).modelVersionId())
                .distinct().count() == 1) {
            recommendation = recommend(runType, request, fallback.candidates(), continuous,
                    applicable.get(continuous.getFirst().targetKey()), actor.organizationId());
        }

        var scored = score(request, recommendation.candidates(), applicable, actor.organizationId());
        var ranked = rerank(request, scored.candidates(), applicable, scored.successfulTargets());
        var successfulTargets = scored.successfulTargets();
        var result = fallback.result().deepCopy();
        var sources = result.putObject("targetModelSources");
        requested.forEach(key -> {
            var activation = applicable.get(key);
            if (activation == null || !successfulTargets.contains(key)) {
                sources.putObject(key).put("source", "T06_SIMILAR_CASE")
                        .put("fallbackReason", activation == null ? "NO_ACTIVE_MODEL" : "MODEL_UNAVAILABLE_OR_OOD");
            } else {
                var version = models.modelVersion(actor.organizationId(), activation.modelVersionId()).orElseThrow();
                var snapshot = models.snapshot(actor.organizationId(), version.snapshotId()).orElseThrow();
                sources.putObject(key).put("source", "MODEL").put("modelVersionId", version.id().toString())
                        .put("modelBundleHash", version.modelBundleHash())
                        .put("snapshotHash", snapshot.snapshotHash())
                        .put("validationFoldsHash", snapshot.validationFoldsHash());
            }
        });
        var allModel = recommendation.modelGenerated()
                && !requested.isEmpty() && successfulTargets.containsAll(requested);
        var anyModel = !successfulTargets.isEmpty();
        var mode = allModel ? "MODEL" : anyModel ? "HYBRID" : "CASE_STAT_RULE";
        result.put("mode", mode).put("modelUsed", anyModel);
        if (!allModel && anyModel) result.put("fallbackReason", "PARTIAL_TARGET_MODEL_COVERAGE");
        if (ranked.mandatoryGateFailure()) {
            result.put("reason", "NO_CANDIDATE_MEETS_MODEL_PROBABILITY_GATE");
            result.put("modelProbabilityGate", "FAILED");
        }
        return new ComputedResearch(fallback.status(), fallback.analysisProfileVersion(), result,
                ranked.candidates(), mode);
    }

    private ModelRecommendation recommend(String runType, ResearchRequest request, List<NewCandidate> fallback,
                                          List<Goal> continuous, ActivationRow activation, UUID organizationId) {
        try {
            var runtime = runtime(organizationId, activation);
            var baseline = fallback.getFirst();
            var response = callRecommend(new RecommendRequest(FormulaModelContracts.CONTRACT_VERSION,
                    UUID.randomUUID().toString(), profiles.productionHash(), runtime.snapshot().snapshotHash(),
                    seed(runtime.version().modelCard()), profiles.production(), runtime.version().modelBundleHash(),
                    bundle(runtime.version()), formulaMap(baseline.formula()), modelContext(baseline),
                    continuous.stream().map(this::requestedTarget).toList(), constraints(request.constraints()),
                    request.candidateCount(), RecommendationMode.valueOf(runType), 0.20));
            requireBundle(response.modelBundleSha256(), runtime.version().modelBundleHash());
            var result = new ArrayList<NewCandidate>();
            var seen = new LinkedHashSet<String>();
            for (var item : response.candidates()) {
                var formula = components(item.formula());
                var checked = rules.check(formula, processFacts(baseline.process()), request.constraints());
                if (!checked.eligible()) continue;
                var formulaNode = json.valueToTree(formula);
                var formulaHash = canonicalizer.hash(formulaNode);
                if (!seen.add(formulaHash)) continue;
                var estimates = asObject(baseline.estimates()).deepCopy();
                item.predictions().forEach(prediction -> mergeEstimate(estimates,
                        targetKey(prediction.targetCode()), continuousEstimate(prediction, runtime.version().id())));
                var evidence = json.createObjectNode().put("source", "MODEL_RECOMMENDATION")
                        .put("modelVersionId", runtime.version().id().toString())
                        .put("modelBundleHash", runtime.version().modelBundleHash());
                var strategy = "CONTROL".equals(item.strategy()) && request.baselineAnalysisRowId() == null
                        ? "REFERENCE" : item.strategy();
                var title = switch (strategy) {
                    case "CONTROL" -> "当前对照方案";
                    case "REFERENCE" -> "模型参考方案";
                    case "CONSERVATIVE" -> "模型保守方案";
                    case "BALANCED" -> "模型平衡方案";
                    default -> "模型探索方案";
                };
                var content = json.createObjectNode().put("schema", "research-candidate.v2")
                        .put("modelVersionId", runtime.version().id().toString());
                content.set("formula", formulaNode.deepCopy());
                content.set("process", baseline.process().deepCopy());
                result.add(new NewCandidate(UUID.randomUUID(), result.size() + 1, strategy, title,
                        formulaNode, baseline.process().deepCopy(), baseline.modelContext().deepCopy(), estimates,
                        checked.detail(), evidence, domainConfidence(item.predictions()),
                        BigDecimal.valueOf(item.desirability()), canonicalizer.hash(content)));
                if (result.size() >= request.candidateCount()) break;
            }
            models.recordModelCallSuccess(organizationId, activation.id());
            return result.isEmpty() ? new ModelRecommendation(fallback, false)
                    : new ModelRecommendation(List.copyOf(result), true);
        } catch (RuntimeException exception) {
            recordFailure(organizationId, List.of(activation), exception);
            return new ModelRecommendation(fallback, false);
        }
    }

    private ScoredCandidates score(ResearchRequest request, List<NewCandidate> candidates,
                                   Map<String, ActivationRow> activations, UUID organizationId) {
        var mutable = new ArrayList<>(candidates);
        var successByTarget = new LinkedHashSet<String>();
        var groups = activations.values().stream().collect(Collectors.groupingBy(ActivationRow::modelVersionId));
        for (var entry : groups.entrySet()) {
            var groupActivations = entry.getValue();
            try {
                var runtime = runtime(organizationId, groupActivations.getFirst());
                var targetCodes = groupActivations.stream().map(item -> target(item.targetKey()).code()).toList();
                var rows = mutable.stream().map(candidate -> new ScoreRow(candidate.id().toString(),
                        formulaMap(candidate.formula()), modelContext(candidate))).toList();
                var response = callScore(new ScoreRequest(FormulaModelContracts.CONTRACT_VERSION,
                        UUID.randomUUID().toString(), profiles.productionHash(), runtime.snapshot().snapshotHash(),
                        seed(runtime.version().modelCard()), profiles.production(), runtime.version().modelBundleHash(),
                        bundle(runtime.version()), rows, targetCodes));
                requireBundle(response.modelBundleSha256(), runtime.version().modelBundleHash());
                var byId = response.rows().stream().collect(Collectors.toMap(ScoredRow::rowId, item -> item));
                for (var index = 0; index < mutable.size(); index++) {
                    var candidate = mutable.get(index);
                    var row = byId.get(candidate.id().toString());
                    if (row == null) continue;
                    var estimates = asObject(candidate.estimates()).deepCopy();
                    var evidence = asObject(candidate.evidence()).deepCopy();
                    var modelEvidence = evidence.withObject("modelScoring");
                    row.predictions().forEach(prediction -> {
                        var key = targetKey(prediction.targetCode());
                        if (activationForCode(groupActivations, prediction.targetCode()) != null
                                && usable(prediction.applicabilityDomain(), row)) {
                            mergeEstimate(estimates, key, continuousEstimate(prediction, runtime.version().id()));
                            successByTarget.add(key);
                        }
                    });
                    row.ordinalPredictions().forEach(prediction -> {
                        var key = targetKey(prediction.targetCode());
                        if (activationForCode(groupActivations, prediction.targetCode()) != null
                                && usable(prediction.applicabilityDomain(), row)) {
                            mergeEstimate(estimates, key, ordinalEstimate(prediction, runtime.version().id()));
                            successByTarget.add(key);
                        }
                    });
                    row.classificationPredictions().forEach(prediction -> {
                        var key = targetKey(prediction.targetCode());
                        if (activationForCode(groupActivations, prediction.targetCode()) != null
                                && usable(prediction.applicabilityDomain(), row)) {
                            mergeEstimate(estimates, key, classificationEstimate(prediction, runtime.version().id()));
                            successByTarget.add(key);
                        }
                    });
                    modelEvidence.put("modelVersionId", runtime.version().id().toString())
                            .put("modelBundleHash", runtime.version().modelBundleHash())
                            .put("domainStatus", row.domainStatus().name());
                    modelEvidence.set("fallbackReasons", json.valueToTree(row.fallbackReasons()));
                    var content = json.createObjectNode().put("schema", "research-candidate.v2")
                            .put("modelVersionId", runtime.version().id().toString());
                    content.set("formula", candidate.formula());
                    content.set("process", candidate.process());
                    content.set("estimates", estimates);
                    mutable.set(index, new NewCandidate(candidate.id(), candidate.candidateNo(), candidate.strategy(),
                            candidate.title(), candidate.formula(), candidate.process(), candidate.modelContext(),
                            estimates, candidate.ruleCheck(), evidence, lower(candidate.confidence(),
                            row.domainStatus()), candidate.score(), canonicalizer.hash(content)));
                }
                groupActivations.forEach(item -> models.recordModelCallSuccess(organizationId, item.id()));
            } catch (RuntimeException exception) {
                recordFailure(organizationId, groupActivations, exception);
            }
        }
        return new ScoredCandidates(List.copyOf(mutable), Set.copyOf(successByTarget));
    }

    /**
     * Re-rank T06/BayBE candidates using active ordinal and binary model
     * probabilities. BayBE remains continuous-only; this method is the
     * deterministic Java-side bridge for mixed goals and the mandatory gate.
     */
    private RankedCandidates rerank(ResearchRequest request, List<NewCandidate> candidates,
                                    Map<String, ActivationRow> activations, Set<String> successfulTargets) {
        if (candidates.isEmpty()) return new RankedCandidates(List.of(), false);
        var scored = new ArrayList<NewCandidate>();
        var mandatoryFailureCount = 0;
        var probabilityWeight = request.goals().stream()
                .filter(goal -> target(goal.targetKey()).valueType() != ValueType.CONTINUOUS)
                .mapToDouble(goal -> goal.weight().doubleValue()).sum();
        for (var candidate : candidates) {
            var estimates = asObject(candidate.estimates());
            var weightedProbability = 0d;
            var observedWeight = 0d;
            var mandatoryPass = true;
            for (var goal : request.goals()) {
                var target = target(goal.targetKey());
                if (target.valueType() == ValueType.CONTINUOUS || !successfulTargets.contains(goal.targetKey())) continue;
                var activation = activations.get(goal.targetKey());
                if (activation == null) continue;
                var probability = probabilityFor(target, goal, estimates.path(goal.targetKey()));
                if (probability == null) {
                    if (goal.mandatory()) mandatoryPass = false;
                    continue;
                }
                weightedProbability += probability * goal.weight().doubleValue();
                observedWeight += goal.weight().doubleValue();
                if (goal.mandatory()) {
                    var threshold = goal.minimumProbability() == null
                            ? target.decisionThreshold() : goal.minimumProbability().doubleValue();
                    if (probability + 1e-12 < threshold) mandatoryPass = false;
                }
            }
            if (!mandatoryPass) {
                mandatoryFailureCount++;
                continue;
            }
            var base = candidate.score() == null ? 0d : candidate.score().doubleValue();
            var probability = observedWeight > 0d ? weightedProbability / observedWeight : 0d;
            var adjusted = probabilityWeight > 0d ? (0.70d * base + 0.30d * probability) : base;
            scored.add(copyWithScore(candidate, BigDecimal.valueOf(adjusted)));
        }
        scored.sort(Comparator.comparing(NewCandidate::score, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(NewCandidate::candidateNo));
        return new RankedCandidates(List.copyOf(scored), mandatoryFailureCount > 0 && scored.isEmpty());
    }

    private Double probabilityFor(TargetSpec target, Goal goal, JsonNode estimate) {
        if (estimate == null || !estimate.isObject()) return null;
        var probabilities = estimate.path("classProbabilities");
        if (!probabilities.isObject()) return null;
        if (target.valueType() == ValueType.BINARY) {
            var positive = target.positiveClass();
            return positive == null || !probabilities.has(positive) ? null : probabilities.path(positive).asDouble();
        }
        if (target.valueType() == ValueType.ORDINAL) {
            var threshold = goal.value() == null
                    ? target.ordinalValues().getFirst()
                    : goal.value().doubleValue();
            var values = target.ordinalValues();
            var labels = target.ordinalLabels();
            var sum = 0d;
            for (var index = 0; index < Math.min(values.size(), labels.size()); index++) {
                if (values.get(index) >= threshold && probabilities.has(labels.get(index))) {
                    sum += probabilities.path(labels.get(index)).asDouble();
                }
            }
            return sum;
        }
        return null;
    }

    private NewCandidate copyWithScore(NewCandidate candidate, BigDecimal score) {
        return new NewCandidate(candidate.id(), candidate.candidateNo(), candidate.strategy(), candidate.title(),
                candidate.formula(), candidate.process(), candidate.modelContext(), candidate.estimates(),
                candidate.ruleCheck(), candidate.evidence(), candidate.confidence(), score, candidate.contentHash());
    }

    private RuntimeModel runtime(UUID organizationId, ActivationRow activation) {
        var version = models.modelVersion(organizationId, activation.modelVersionId())
                .orElseThrow(() -> artifactFailure("活动模型版本不存在", null));
        var snapshot = models.snapshot(organizationId, version.snapshotId())
                .orElseThrow(() -> artifactFailure("活动模型快照不存在", null));
        if (!"CANDIDATE".equals(version.status()) || !"READY".equals(snapshot.status())
                || version.modelBundleHash() == null || version.modelBundleKey() == null) {
            throw artifactFailure("活动模型制品状态无效", null);
        }
        return new RuntimeModel(version, snapshot);
    }

    private ModelBundleRef bundle(FormulaModelRepository.ModelVersionRow version) {
        try {
            var ref = artifacts.readRef("model-bundle.zip", version.modelBundleKey(), version.modelBundleHash());
            return new ModelBundleRef(ref.url(), ref.sha256());
        } catch (ApiException exception) {
            if (exception.errorCode() == ApiErrorCode.AI_MODEL_ARTIFACT_INVALID) {
                throw artifactFailure(exception.getMessage(), exception);
            }
            throw exception;
        }
    }

    private Map<String, Double> formulaMap(JsonNode formula) {
        var result = new LinkedHashMap<String, Double>();
        formula.forEach(item -> {
            var code = item.path("materialCode").asText();
            if (!code.isBlank() && item.path("ratioPercent").isNumber()) {
                result.merge(code, item.path("ratioPercent").asDouble(), Double::sum);
            }
        });
        return Map.copyOf(result);
    }

    private Map<String, Object> modelContext(NewCandidate candidate) {
        var result = new LinkedHashMap<String, Object>();
        for (var spec : profiles.production().contextFeatures()) {
            var node = candidate.process().path(spec.code());
            if (node.isMissingNode()) node = candidate.modelContext().path(spec.code());
            if (node.isMissingNode()) throw new ApiException(ApiErrorCode.INVALID_SCHEMA,
                    "候选缺少模型上下文：" + spec.code());
            if (spec.valueType() == FeatureType.NUMERIC) {
                var value = node.path("numericValue");
                if (!value.isNumber()) throw new ApiException(ApiErrorCode.INVALID_SCHEMA,
                        "候选数值上下文无效：" + spec.code());
                result.put(spec.code(), value.asDouble());
            } else {
                var value = node.path("textValue").asText();
                if (value.isBlank()) throw new ApiException(ApiErrorCode.INVALID_SCHEMA,
                        "候选分类上下文无效：" + spec.code());
                result.put(spec.code(), value);
            }
        }
        return Map.copyOf(result);
    }

    private List<FormulaComponent> components(Map<String, Double> values) {
        return profiles.production().formula().materials().stream().map(spec -> {
            var ratio = BigDecimal.valueOf(values.getOrDefault(spec.code(), 0d));
            return new FormulaComponent("MODEL:" + spec.code(), spec.code(), spec.code(), spec.role(), ratio,
                    ratio, json.valueToTree(ratio), "%", "MODEL", profiles.production().ruleVersion(),
                    "MAPPED", json.createArrayNode());
        }).sorted(Comparator.comparing(FormulaComponent::materialCode)).toList();
    }

    private Map<String, com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.StandardFact> processFacts(JsonNode node) {
        var type = json.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
                com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.StandardFact.class);
        return json.convertValue(node, type);
    }

    private List<MaterialConstraint> constraints(Constraints source) {
        var result = new LinkedHashMap<String, MaterialConstraint>();
        source.materialRanges().forEach((code, range) -> result.put(code,
                new MaterialConstraint(code, number(range.minimum()), number(range.maximum()), null)));
        source.fixedMaterials().forEach((code, value) -> result.put(code,
                new MaterialConstraint(code, null, null, value.doubleValue())));
        source.forbiddenMaterials().forEach(code -> result.put(code, new MaterialConstraint(code, 0d, 0d, 0d)));
        source.requiredMaterials().forEach(code -> result.putIfAbsent(code,
                new MaterialConstraint(code, materialMinimum(code), null, null)));
        return List.copyOf(result.values());
    }

    private RequestedTarget requestedTarget(Goal goal) {
        return new RequestedTarget(target(goal.targetKey()).code(), TargetMode.valueOf(mode(goal)), goal.mandatory(),
                goal.weight().doubleValue(), number(goal.value()), number(goal.minimum()), number(goal.maximum()),
                number(goal.tolerance()), number(goal.minimumProbability()));
    }

    private String mode(Goal goal) {
        var value = goal.mode() == null || goal.mode().isBlank()
                ? target(goal.targetKey()).direction().name() : goal.mode().toUpperCase(Locale.ROOT);
        return "RANGE".equals(value) || "MATCH".equals(value) || "AT_LEAST".equals(value)
                || "AT_MOST".equals(value) || "MAXIMIZE".equals(value) || "MINIMIZE".equals(value)
                ? value : target(goal.targetKey()).direction().name();
    }

    private TargetSpec target(String targetKey) {
        return profiles.production().targets().stream().filter(item -> item.targetKey().equals(targetKey))
                .findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_ERROR,
                        "生产任务档案没有目标：" + targetKey));
    }

    private String targetKey(String targetCode) {
        return profiles.production().targets().stream().filter(item -> item.code().equals(targetCode))
                .map(TargetSpec::targetKey).findFirst().orElse(targetCode);
    }

    private ActivationRow activationForCode(List<ActivationRow> values, String targetCode) {
        var key = targetKey(targetCode);
        return values.stream().filter(item -> item.targetKey().equals(key)).findFirst().orElse(null);
    }

    private ObjectNode continuousEstimate(Prediction value, UUID versionId) {
        var result = json.createObjectNode().put("level", "MODEL").put("confidence", "MODEL")
                .put("predictionSource", "MODEL").put("modelVersionId", versionId.toString())
                .put("pointEstimate", value.expected()).put("minimum", value.lower()).put("maximum", value.upper());
        if (value.unit() != null) result.put("unit", value.unit());
        if (value.applicabilityDomain() != null) result.set("applicabilityDomain",
                json.valueToTree(value.applicabilityDomain()));
        return result;
    }

    private ObjectNode ordinalEstimate(OrdinalPrediction value, UUID versionId) {
        var result = json.createObjectNode().put("level", "MODEL").put("confidence", "MODEL")
                .put("predictionSource", "MODEL").put("modelVersionId", versionId.toString())
                .put("predictedClass", value.predictedClass()).put("lowerClass90", value.lowerClass90())
                .put("upperClass90", value.upperClass90());
        result.set("classProbabilities", json.valueToTree(value.classProbabilities()));
        if (value.applicabilityDomain() != null) result.set("applicabilityDomain",
                json.valueToTree(value.applicabilityDomain()));
        return result;
    }

    private ObjectNode classificationEstimate(ClassificationPrediction value, UUID versionId) {
        var result = json.createObjectNode().put("level", "MODEL").put("confidence", "MODEL")
                .put("predictionSource", "MODEL").put("modelVersionId", versionId.toString())
                .put("predictedClass", value.predictedClass())
                .put("probability", value.calibratedPositiveProbability() == null
                        ? value.confidence() : value.calibratedPositiveProbability());
        if (value.positiveClass() != null) result.put("positiveClass", value.positiveClass());
        if (value.decisionThreshold() != null) result.put("decisionThreshold", value.decisionThreshold());
        result.set("classProbabilities", json.valueToTree(value.classProbabilities()));
        if (value.applicabilityDomain() != null) result.set("applicabilityDomain",
                json.valueToTree(value.applicabilityDomain()));
        return result;
    }

    private boolean usable(ApplicabilityDomainEvidence evidence, ScoredRow row) {
        return row.modelUsable() && (evidence == null || evidence.modelUsable())
                && row.domainStatus() != DomainStatus.OUT_OF_DOMAIN;
    }

    private String lower(String current, DomainStatus status) {
        if (status == DomainStatus.OUT_OF_DOMAIN) return "NONE";
        if (status == DomainStatus.NEAR_BOUNDARY) return "LOW";
        return current;
    }

    private String domainConfidence(List<Prediction> predictions) {
        return predictions.stream().anyMatch(item -> item.applicabilityDomain() != null
                && item.applicabilityDomain().status() == DomainStatus.NEAR_BOUNDARY) ? "LOW" : "MEDIUM";
    }

    private void requireBundle(String actual, String expected) {
        if (!expected.equals(actual)) throw artifactFailure("模型响应制品哈希不一致", null);
    }

    private void recordFailure(UUID organizationId, List<ActivationRow> activations, RuntimeException exception) {
        if (exception instanceof ModelArtifactFailure artifact) {
            activations.forEach(item -> models.pauseModelForArtifactFailure(organizationId, item.id(),
                    artifact.getMessage()));
            log.error("formula_model_artifact_failure organizationId={} modelVersionId={} targetKeys={} action=PAUSE_PENDING_REVIEW",
                    organizationId, activations.getFirst().modelVersionId(),
                    activations.stream().map(ActivationRow::targetKey).toList(), exception);
            return;
        }
        log.warn("formula_model_infrastructure_failure organizationId={} modelVersionId={} targetKeys={} "
                        + "action=REQUEST_FALLBACK_ACTIVE_UNCHANGED",
                organizationId, activations.getFirst().modelVersionId(),
                activations.stream().map(ActivationRow::targetKey).toList(), exception);
    }

    private ScoreResponse callScore(ScoreRequest request) {
        try {
            return compute.score(request);
        } catch (ApiException exception) {
            if (exception.errorCode() == ApiErrorCode.AI_MODEL_ARTIFACT_INVALID) {
                throw artifactFailure(exception.getMessage(), exception);
            }
            throw exception;
        }
    }

    private RecommendResponse callRecommend(RecommendRequest request) {
        try {
            return compute.recommend(request);
        } catch (ApiException exception) {
            if (exception.errorCode() == ApiErrorCode.INVALID_SCHEMA) {
                throw artifactFailure(exception.getMessage(), exception);
            }
            throw exception;
        }
    }

    private ModelArtifactFailure artifactFailure(String message, Throwable cause) {
        return new ModelArtifactFailure(message, cause);
    }

    private long seed(JsonNode modelCard) { return modelCard.path("seed").asLong(2026L); }
    private Double number(BigDecimal value) { return value == null ? null : value.doubleValue(); }
    private double materialMinimum(String code) {
        return profiles.production().formula().materials().stream().filter(item -> item.code().equals(code))
                .map(MaterialSpec::minimum).findFirst().orElse(0.000001d);
    }
    private ObjectNode asObject(JsonNode value) {
        return value instanceof ObjectNode object ? object : json.createObjectNode();
    }

    private void mergeEstimate(ObjectNode estimates, String targetKey, ObjectNode model) {
        var merged = asObject(estimates.get(targetKey)).deepCopy();
        merged.setAll(model);
        estimates.set(targetKey, merged);
    }

    private record RuntimeModel(FormulaModelRepository.ModelVersionRow version,
                                FormulaModelRepository.SnapshotRow snapshot) {}
    private record ModelRecommendation(List<NewCandidate> candidates, boolean modelGenerated) {}
    private record ScoredCandidates(List<NewCandidate> candidates, Set<String> successfulTargets) {}
    private record RankedCandidates(List<NewCandidate> candidates, boolean mandatoryGateFailure) {}

    private static final class ModelArtifactFailure extends RuntimeException {
        private ModelArtifactFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
