package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade;
import com.jsd.aird.ai.formula.api.FormulaResearchFacade.Goal;
import com.jsd.aird.ai.formula.api.FormulaResearchFacade.ResearchRequest;
import com.jsd.aird.ai.formula.application.ResearchCaseLoader.ResearchCaseView;
import com.jsd.aird.ai.formula.application.SimilarCaseEngine.Anchor;
import com.jsd.aird.ai.formula.application.port.ResearchRepository.NewCandidate;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ResearchComputationService {
    private static final MathContext MC = MathContext.DECIMAL128;
    private final ResearchCaseLoader loader;
    private final SimilarCaseEngine similar;
    private final TargetStatisticsService statistics;
    private final FormulaRuleEngine rules;
    private final UvpuResearchProfile profile;
    private final JsonCanonicalizer canonicalizer;
    private final ObjectMapper json;
    private final FormulaModelTaskProfileRegistry modelProfiles;
    private FormulaModelRuntimeService modelRuntime;

    public ResearchComputationService(ResearchCaseLoader loader, SimilarCaseEngine similar,
                                      TargetStatisticsService statistics, FormulaRuleEngine rules,
                                      UvpuResearchProfile profile, JsonCanonicalizer canonicalizer,
                                      ObjectMapper json, FormulaModelTaskProfileRegistry modelProfiles) {
        this.loader = loader;
        this.similar = similar;
        this.statistics = statistics;
        this.rules = rules;
        this.profile = profile;
        this.canonicalizer = canonicalizer;
        this.json = json;
        this.modelProfiles = modelProfiles;
    }

    @Autowired(required = false)
    void setModelRuntime(FormulaModelRuntimeService modelRuntime) {
        this.modelRuntime = modelRuntime;
    }

    public ComputedResearch compute(String runType, ResearchRequest request) {
        validate(runType, request);
        var collection = loader.load(request.taskProfileCode(), request.projectId(), request.categoryId());
        var source = collection.cases().stream().filter(item -> similar.matchesContext(item, request.context())).toList();
        var baseline = request.baselineAnalysisRowId() == null || request.baselineAnalysisRowId().isBlank() ? null
                : source.stream().filter(item -> item.analysisRow().analysisRowId()
                        .equals(request.baselineAnalysisRowId())).findFirst().orElseThrow(() ->
                        new ApiException(ApiErrorCode.VALIDATION_ERROR, "基线实验不存在、已失效或无权访问"));
        if ("EXPERIMENT_OPTIMIZATION".equals(runType) && baseline == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "AI实验优化必须选择一个已完成实验作为基线");
        }
        var reference = baseline == null ? bestReference(source, request.goals()) : baseline;
        if (reference == null) return empty(collection, runType, request, "NO_COMPARABLE_CASES");
        var anchor = similar.anchor(reference);
        var targetSummaries = json.createArrayNode();
        var statisticsByTarget = new LinkedHashMap<String, TargetStatisticsService.Statistics>();
        for (var goal : request.goals()) {
            var cases = similar.find(source, anchor, goal.targetKey(), request.context(), Set.of(), null);
            var stat = statistics.calculate(goal.targetKey(), cases);
            statisticsByTarget.put(goal.targetKey(), stat);
            targetSummaries.add(stat.summary());
        }
        var proposals = proposals(runType, baseline, reference, source, request, statisticsByTarget);
        var accepted = new ArrayList<NewCandidate>();
        var skipped = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        for (var proposal : proposals) {
            if (accepted.size() >= request.candidateCount()) break;
            var pre = rules.check(proposal.formula(), proposal.process(), request.constraints());
            var post = rules.check(proposal.formula(), proposal.process(), request.constraints());
            if (!pre.eligible() || !post.eligible()) { skipped.add(proposal.strategy()); continue; }
            var formulaHash = canonicalizer.hash(formulaJson(proposal.formula()));
            if (!seen.add(formulaHash)) { skipped.add(proposal.strategy()); continue; }
            var estimates = json.createObjectNode();
            var evidence = json.createObjectNode();
            var confidence = "MEDIUM";
            var score = 0d;
            var count = 0;
            var candidateAnchor = similar.anchor(proposal.formula(), proposal.process(), proposal.context());
            for (var goal : request.goals()) {
                var cases = similar.find(source, candidateAnchor, goal.targetKey(), request.context(), Set.of(), null);
                var stat = statistics.calculate(goal.targetKey(), cases);
                var estimate = stat.summary().deepCopy();
                estimate.remove("cases");
                estimates.set(goal.targetKey(), estimate);
                evidence.set(goal.targetKey(), stat.evidence());
                confidence = lower(confidence, stat.confidence());
                if (!cases.isEmpty()) { score += cases.getFirst().similarity(); count++; }
            }
            var ruleDetail = json.createObjectNode();
            ruleDetail.set("beforeGeneration", pre.detail());
            ruleDetail.set("afterGeneration", post.detail());
            var formulaNode = formulaJson(proposal.formula());
            var processNode = json.valueToTree(proposal.process());
            var contextNode = json.valueToTree(proposal.context());
            ObjectNode content = json.createObjectNode().put("schema", "research-candidate.v1");
            content.set("formula", formulaNode.deepCopy());
            content.set("process", processNode.deepCopy());
            var id = UUID.randomUUID();
            accepted.add(new NewCandidate(id, accepted.size() + 1, proposal.strategy(), proposal.title(),
                    formulaNode, processNode, contextNode, estimates, ruleDetail, evidence, confidence,
                    BigDecimal.valueOf(count == 0 ? 0d : score / count), canonicalizer.hash(content)));
        }
        var requiredConfidence = overallConfidence(request.goals(), statisticsByTarget);
        var result = json.createObjectNode().put("mode", "CASE_STAT_RULE").put("runType", runType)
                .put("taskProfileCode", request.taskProfileCode())
                .put("analysisProfileVersion", collection.analysisProfileVersion())
                .put("researchProfileVersion", profile.definition().researchProfileVersion())
                .put("baselineType", "T06_SIMILAR_CASE")
                .put("overallConfidence", requiredConfidence)
                .put("candidateCount", accepted.size())
                .put("requestedCandidateCount", request.candidateCount())
                .put("referenceAnalysisRowId", reference.analysisRow().analysisRowId())
                .put("referenceKind", baseline == null ? "REFERENCE" : "CONTROL")
                .put("modelUsed", false);
        putUuid(result, "referenceProjectId", reference.projectId());
        putUuid(result, "referenceStageId", reference.stageId());
        putUuid(result, "referenceTaskId", reference.taskId());
        putUuid(result, "referenceCategoryId", reference.categoryId());
        result.set("targetStatistics", targetSummaries);
        result.set("missingStrategies", json.valueToTree(skipped));
        result.set("warnings", warnings(request.goals(), statisticsByTarget, accepted.size()));
        var status = accepted.size() == request.candidateCount()
                && request.goals().stream().filter(Goal::mandatory).allMatch(goal ->
                statisticsByTarget.get(goal.targetKey()).caseCount() > 0) ? "SUCCEEDED" : "PARTIAL";
        var fallback = new ComputedResearch(status, collection.analysisProfileVersion(), result,
                List.copyOf(accepted));
        return modelRuntime == null ? fallback : modelRuntime.apply(runType, request, fallback);
    }

    private ComputedResearch empty(ResearchCaseLoader.CaseCollection collection, String runType,
                                   ResearchRequest request, String reason) {
        var result = json.createObjectNode().put("mode", "CASE_STAT_RULE").put("runType", runType)
                .put("taskProfileCode", request.taskProfileCode()).put("analysisProfileVersion",
                        collection.analysisProfileVersion()).put("overallConfidence", "NONE")
                .put("candidateCount", 0).put("modelUsed", false).put("reason", reason);
        result.set("targetStatistics", json.createArrayNode());
        result.set("missingStrategies", json.valueToTree(List.of("CONTROL", "REFERENCE", "CONSERVATIVE", "BALANCED", "EXPLORATORY")));
        return new ComputedResearch("PARTIAL", collection.analysisProfileVersion(), result, List.of());
    }

    private List<Proposal> proposals(String runType, ResearchCaseView baseline, ResearchCaseView reference,
                                     List<ResearchCaseView> source, ResearchRequest request,
                                     Map<String, TargetStatisticsService.Statistics> targetStats) {
        var result = new ArrayList<Proposal>();
        var firstStrategy = baseline == null ? "REFERENCE" : "CONTROL";
        result.add(new Proposal(firstStrategy, baseline == null ? "历史参考方案" : "当前对照方案",
                reference.analysisRow().formula(), reference.analysisRow().process(), reference.analysisRow().context()));
        var ranked = source.stream().filter(item -> !item.analysisRow().analysisRowId()
                        .equals(reference.analysisRow().analysisRowId()))
                .sorted(Comparator.comparingDouble((ResearchCaseView item) -> referenceScore(item, source, request.goals())).reversed()
                        .thenComparing(item -> item.analysisRow().analysisRowId())).toList();
        var blendCompatible = ranked.stream().filter(item -> sameActiveFormulaFamily(reference, item)).toList();
        if (!blendCompatible.isEmpty()) {
            result.add(new Proposal("CONSERVATIVE", "保守改进方案",
                    blend(List.of(reference, blendCompatible.getFirst()), List.of(0.75d, 0.25d)),
                    reference.analysisRow().process(), reference.analysisRow().context()));
        }
        var fullSupport = request.goals().stream().filter(Goal::mandatory)
                .allMatch(goal -> targetStats.get(goal.targetKey()).exactCount() >= 5);
        if (fullSupport && blendCompatible.size() >= 2) {
            var balanced = new ArrayList<ResearchCaseView>();
            balanced.add(reference);
            blendCompatible.stream().limit(4).forEach(balanced::add);
            result.add(new Proposal("BALANCED", "多目标平衡方案", blend(balanced, null),
                    reference.analysisRow().process(), reference.analysisRow().context()));
            exploratory(reference, source).ifPresent(item -> result.add(new Proposal("EXPLORATORY", "案例范围探索方案",
                    item.analysisRow().formula(), item.analysisRow().process(), item.analysisRow().context())));
        }
        return result;
    }

    /**
     * Weighted blending is only meaningful inside the same active formulation family.  In particular,
     * mutually alternative main resins or catalyst systems must not be introduced merely because two
     * historical rows are both similar to the requested target.
     */
    private boolean sameActiveFormulaFamily(ResearchCaseView left, ResearchCaseView right) {
        return activeNonBalanceMaterials(left).equals(activeNonBalanceMaterials(right));
    }

    private Set<String> activeNonBalanceMaterials(ResearchCaseView source) {
        var result = new LinkedHashSet<String>();
        for (var item : source.analysisRow().formula()) {
            if (item.materialCode() == null || item.ratioPercent() == null || item.ratioPercent().signum() <= 0
                    || "BALANCE".equals(item.materialRole())) continue;
            result.add(item.materialCode().strip().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private java.util.Optional<ResearchCaseView> exploratory(ResearchCaseView reference, List<ResearchCaseView> source) {
        return source.stream().filter(item -> !item.analysisRow().formulaSignature()
                        .equals(reference.analysisRow().formulaSignature()))
                .filter(item -> similar.formulaL1(reference.analysisRow().formula(), item.analysisRow().formula()) <= 40d)
                .filter(item -> source.stream().filter(neighbour -> similar.formulaL1(
                        item.analysisRow().formula(), neighbour.analysisRow().formula()) <= 40d).count() >= 3)
                .max(Comparator.comparingDouble(item -> similar.formulaL1(reference.analysisRow().formula(),
                        item.analysisRow().formula())));
    }

    private List<ExperimentAnalysisFacade.FormulaComponent> blend(List<ResearchCaseView> sources,
                                                                   List<Double> weights) {
        var effectiveWeights = weights == null ? java.util.Collections.nCopies(sources.size(), 1d / sources.size()) : weights;
        var values = new LinkedHashMap<String, BigDecimal>();
        var metadata = new LinkedHashMap<String, ExperimentAnalysisFacade.FormulaComponent>();
        for (var index = 0; index < sources.size(); index++) {
            var weight = BigDecimal.valueOf(effectiveWeights.get(index));
            for (var item : sources.get(index).analysisRow().formula()) {
                if (item.materialCode() == null || item.materialCode().isBlank() || item.ratioPercent() == null) continue;
                metadata.putIfAbsent(item.materialCode(), item);
                values.merge(item.materialCode(), item.ratioPercent().multiply(weight, MC), BigDecimal::add);
            }
        }
        var total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        var factor = total.signum() == 0 ? BigDecimal.ONE : new BigDecimal("100").divide(total, MC);
        values.replaceAll((code, value) -> value.multiply(factor, MC));
        quantizeGeneratedFormula(values);
        return values.entrySet().stream().map(entry -> {
            var source = metadata.get(entry.getKey());
            var ratio = entry.getValue().stripTrailingZeros();
            return new ExperimentAnalysisFacade.FormulaComponent("T06:" + entry.getKey(), entry.getKey(),
                    source.materialName(), source.materialRole(), ratio, ratio, json.valueToTree(ratio), "%",
                    "T06_WEIGHTED_BLEND", "T06_CANDIDATE_V1", source.mappingStatus(), json.createArrayNode());
        }).sorted(Comparator.comparing(ExperimentAnalysisFacade.FormulaComponent::materialCode)).toList();
    }

    private void quantizeGeneratedFormula(Map<String, BigDecimal> values) {
        var formula = modelProfiles.production().formula();
        var balanceCode = formula.balanceMaterialCode();
        var steps = new HashMap<String, BigDecimal>();
        formula.materials().forEach(item -> steps.put(item.code(), BigDecimal.valueOf(item.step())));
        var nonBalanceTotal = BigDecimal.ZERO;
        for (var entry : values.entrySet()) {
            if (entry.getKey().equals(balanceCode)) continue;
            var step = steps.get(entry.getKey());
            var rounded = step == null
                    ? entry.getValue()
                    : entry.getValue().divide(step, 0, RoundingMode.HALF_UP).multiply(step);
            entry.setValue(rounded);
            nonBalanceTotal = nonBalanceTotal.add(rounded);
        }
        if (values.containsKey(balanceCode)) {
            values.put(balanceCode, BigDecimal.valueOf(formula.total()).subtract(nonBalanceTotal));
        }
    }

    private ResearchCaseView bestReference(List<ResearchCaseView> cases, List<Goal> goals) {
        return cases.stream().filter(item -> goals.stream().anyMatch(goal -> eligible(item, goal.targetKey())))
                .max(Comparator.comparingDouble((ResearchCaseView item) -> referenceScore(item, cases, goals))
                        .thenComparing(item -> item.analysisRow().analysisRowId())).orElse(null);
    }

    private double referenceScore(ResearchCaseView item, List<ResearchCaseView> cases, List<Goal> goals) {
        double score = 0d;
        double weights = 0d;
        for (var goal : goals) {
            var value = scalar(item, goal.targetKey());
            if (value == null) continue;
            var available = cases.stream().map(candidate -> scalar(candidate, goal.targetKey()))
                    .filter(java.util.Objects::nonNull).sorted().toList();
            if (available.isEmpty()) continue;
            var rank = available.size() == 1 ? 1d : available.indexOf(value) / (double) (available.size() - 1);
            var mode = effectiveMode(goal);
            if (Set.of("MINIMIZE", "AT_MOST").contains(mode)) rank = 1d - rank;
            if ("MATCH".equals(mode) && goal.value() != null) {
                var range = Math.max(1e-9, available.getLast() - available.getFirst());
                rank = Math.max(0d, 1d - Math.abs(value - goal.value().doubleValue()) / range);
            }
            score += rank * goal.weight().doubleValue();
            weights += goal.weight().doubleValue();
        }
        return weights == 0 ? 0d : score / weights;
    }

    private Double scalar(ResearchCaseView item, String targetKey) {
        var target = item.analysisRow().targets().get(targetKey);
        if (target == null || !"PARSED".equals(target.status()) || "LOWER_BOUND".equals(target.observationType())) return null;
        if (target.numericValue() != null) return target.numericValue().doubleValue();
        if (target.ordinalValue() == null) return null;
        var normalized = target.ordinalValue().toUpperCase(Locale.ROOT);
        if (normalized.matches("\\d+(?:\\.\\d+)?H")) return Double.parseDouble(normalized.substring(0, normalized.length() - 1));
        return Set.of("OK", "PASS", "TRUE", "合格", "表干", "干爽").contains(normalized) ? 1d : 0d;
    }

    private String effectiveMode(Goal goal) {
        if (goal.mode() != null && !goal.mode().isBlank()) return goal.mode();
        return profile.target(goal.targetKey()).map(UvpuResearchProfile.Target::direction).orElse("MAXIMIZE");
    }

    private boolean eligible(ResearchCaseView item, String targetKey) {
        var eligibility = item.analysisRow().caseEligibilityByTarget().get(targetKey);
        return eligibility != null && eligibility.eligible();
    }

    private ArrayNode formulaJson(List<ExperimentAnalysisFacade.FormulaComponent> formula) {
        return json.valueToTree(formula);
    }

    private String overallConfidence(List<Goal> goals,
                                     Map<String, TargetStatisticsService.Statistics> statistics) {
        var required = goals.stream().filter(Goal::mandatory).toList();
        var effective = required.isEmpty() ? goals : required;
        var confidence = "MEDIUM";
        for (var goal : effective) confidence = lower(confidence, statistics.get(goal.targetKey()).confidence());
        return confidence;
    }

    private String lower(String left, String right) {
        var rank = Map.of("NONE", 0, "LOW", 1, "MEDIUM", 2);
        return rank.getOrDefault(left, 0) <= rank.getOrDefault(right, 0) ? left : right;
    }

    private void putUuid(ObjectNode target, String field, UUID value) {
        if (value != null) target.put(field, value.toString());
    }

    private ArrayNode warnings(List<Goal> goals, Map<String, TargetStatisticsService.Statistics> stats,
                               int candidateCount) {
        var result = json.createArrayNode();
        for (var goal : goals) {
            var value = stats.get(goal.targetKey());
            if (value.caseCount() == 0) result.add("目标暂无可比较案例：" + profile.target(goal.targetKey()).orElseThrow().name());
            else if (!"FULL_STATISTICS".equals(value.level())) result.add("目标案例较少，仅提供有限历史证据："
                    + profile.target(goal.targetKey()).orElseThrow().name());
        }
        if (candidateCount < 4) result.add("符合证据和硬规则的候选不足4个，系统未凑数");
        return result;
    }

    private void validate(String runType, ResearchRequest request) {
        if (!profile.definition().taskProfileCode().equals(request.taskProfileCode())) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "当前仅支持UVPU_APPLICATION_FORMULATION");
        }
        if (request.goals().isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "至少选择一个性能目标");
        for (var goal : request.goals()) {
            if (profile.target(goal.targetKey()).isEmpty()) {
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "未登记的性能目标：" + goal.targetKey());
            }
        }
        if (!Set.of("FORMULA_PREDICTION", "EXPERIMENT_OPTIMIZATION").contains(runType)) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "未知研究运行类型");
        }
    }

    private record Proposal(String strategy, String title,
                            List<ExperimentAnalysisFacade.FormulaComponent> formula,
                            Map<String, ExperimentAnalysisFacade.StandardFact> process,
                            Map<String, ExperimentAnalysisFacade.StandardFact> context) {}

    public record ComputedResearch(String status, String analysisProfileVersion, ObjectNode result,
                                   List<NewCandidate> candidates, String mode) {
        public ComputedResearch(String status, String analysisProfileVersion, ObjectNode result,
                                List<NewCandidate> candidates) {
            this(status, analysisProfileVersion, result, candidates,
                    result == null ? "CASE_STAT_RULE" : result.path("mode").asText("CASE_STAT_RULE"));
        }
    }
}
