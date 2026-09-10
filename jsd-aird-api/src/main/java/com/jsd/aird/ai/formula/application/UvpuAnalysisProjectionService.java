package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.*;
import com.jsd.aird.rnd.api.CompletedExperimentFactsProvider;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class UvpuAnalysisProjectionService implements ExperimentAnalysisFacade {
    private static final Pattern FIRST_NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
    private final CompletedExperimentFactsProvider factsProvider;
    private final UvpuAnalysisProfile profile;
    private final UvpuProcessFactParser processParser;
    private final UvpuTargetParser targetParser;
    private final JsonCanonicalizer canonicalizer;
    private final ObjectMapper json;

    public UvpuAnalysisProjectionService(CompletedExperimentFactsProvider factsProvider,
                                         UvpuAnalysisProfile profile,
                                         UvpuProcessFactParser processParser,
                                         UvpuTargetParser targetParser,
                                         JsonCanonicalizer canonicalizer,
                                         ObjectMapper json) {
        this.factsProvider = factsProvider;
        this.profile = profile;
        this.processParser = processParser;
        this.targetParser = targetParser;
        this.canonicalizer = canonicalizer;
        this.json = json;
    }

    @Override
    public ExperimentAnalysisPage query(ExperimentAnalysisQuery query) {
        if (query == null || query.taskProfileCode() == null
                || !profile.definition().taskProfileCode().equals(query.taskProfileCode().trim())) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "当前仅支持UVPU_APPLICATION_FORMULATION分析任务");
        }
        var drafts = new ArrayList<RowDraft>();
        for (var fact : allFacts(query)) drafts.addAll(project(fact));
        assignLineages(drafts);
        var rows = drafts.stream().map(this::finish).sorted(Comparator.comparing(AnalysisRow::analysisRowId)).toList();
        var quality = quality(rows);
        var from = Math.min(rows.size(), (query.page() - 1) * query.size());
        var to = Math.min(rows.size(), from + query.size());
        return new ExperimentAnalysisPage(profile.definition().taskProfileCode(),
                profile.definition().analysisProfileVersion(), rows.subList(from, to), query.page(), query.size(),
                rows.size(), (rows.size() + query.size() - 1L) / query.size(), quality);
    }

    private List<CompletedExperimentFactsProvider.CompletedExperimentFacts> allFacts(ExperimentAnalysisQuery query) {
        var result = new ArrayList<CompletedExperimentFactsProvider.CompletedExperimentFacts>();
        var page = 1;
        while (true) {
            var current = factsProvider.query(new CompletedExperimentFactsProvider.CompletedExperimentFactsQuery(
                    query.experimentIds(), query.projectId(), query.categoryId(), page, 200));
            result.addAll(current.items());
            if (page >= current.totalPages() || current.items().isEmpty()) break;
            page++;
        }
        return List.copyOf(result);
    }

    private List<RowDraft> project(CompletedExperimentFactsProvider.CompletedExperimentFacts fact) {
        var groups = nodes(fact.sourceGroups());
        if (groups.isEmpty()) return List.of(projectManual(fact));
        var rows = new ArrayList<RowDraft>();
        for (var group : groups) rows.add(projectGroup(fact, group, false));
        return rows;
    }

    private RowDraft projectManual(CompletedExperimentFactsProvider.CompletedExperimentFacts fact) {
        var keys = new TreeSet<String>();
        for (var item : allItems(fact)) {
            var key = text(item, "sourceGroupKey");
            if (!key.isBlank()) keys.add(key);
        }
        var group = json.createObjectNode()
                .put("sourceGroupKey", "MANUAL:" + fact.experimentVersionId())
                .put("sourceContextKey", "")
                .put("sourceIdentity", "")
                .put("sourceIdentityType", "");
        return projectGroup(fact, group, keys.size() > 1);
    }

    private RowDraft projectGroup(CompletedExperimentFactsProvider.CompletedExperimentFacts fact, JsonNode group,
                                  boolean conflictingManualFacts) {
        var sourceGroupKey = text(group, "sourceGroupKey");
        var sourceContextKey = text(group, "sourceContextKey");
        var formulaFacts = matching(fact.formulaItems(), sourceGroupKey, false, sourceContextKey);
        var testFacts = matching(fact.testResults(), sourceGroupKey, false, sourceContextKey);
        var processFacts = matching(fact.processSteps(), sourceGroupKey, true, sourceContextKey);
        var contextFacts = contextFacts(fact.sourceContexts(), sourceContextKey);
        contextFacts.stream().filter(item -> "PROCESS".equals(text(item, "domain"))).forEach(processFacts::add);

        var formulaProjection = normalizeFormula(formulaFacts);
        var formula = formulaProjection.formula();
        var processProjection = processParser.parse(processFacts, testFacts);
        var process = new LinkedHashMap<>(processProjection.process());
        var context = new LinkedHashMap<>(processProjection.context());
        substrate(contextFacts, testFacts).ifPresent(value -> context.put("substrate", value));
        var targets = targetParser.parse(testFacts);
        var resinBatchProperties = resinBatchProperties(formula, testFacts);
        var rowId = rowId(fact.experimentVersionId(), sourceGroupKey);
        var structuredReasons = structuredReasons(formulaFacts, formula, testFacts, conflictingManualFacts);
        var structuredReady = structuredReasons.isEmpty();
        var caseEligibility = new LinkedHashMap<String, Eligibility>();
        var modelEligibility = new LinkedHashMap<String, Eligibility>();
        for (var target : profile.definition().targets()) {
            var observation = targets.get(target.targetKey());
            caseEligibility.put(target.targetKey(), caseEligibility(structuredReady, structuredReasons,
                    formulaProjection.reasons(), formula, observation));
            modelEligibility.put(target.targetKey(), modelEligibility(structuredReady, structuredReasons,
                    formulaProjection.reasons(), formula, process, context, observation));
        }
        var signature = formulaSignature(formula);
        var contentHash = contentHash(formula, process, context, resinBatchProperties, targets);
        return new RowDraft(rowId, fact, sourceGroupKey, sourceContextKey, text(group, "sourceIdentity"),
                text(group, "sourceIdentityType"), nullableText(group, "logicalSampleKey"), formula,
                formulaProjection.rawTotal(), formulaProjection.normalizationApplied(),
                formulaProjection.normalizationFactor(), formulaProjection.normalizationRuleVersion(),
                Map.copyOf(process), Map.copyOf(context), resinBatchProperties, targets, structuredReady, structuredReasons,
                Map.copyOf(caseEligibility), Map.copyOf(modelEligibility), signature, "", contentHash);
    }

    private FormulaProjection normalizeFormula(List<JsonNode> facts) {
        var prepared = new ArrayList<RawFormulaComponent>();
        for (var item : facts) {
            var materialName = text(item, "materialName");
            var inputCode = text(item, "materialCode");
            var material = profile.material(inputCode, materialName);
            var code = material.map(UvpuAnalysisProfile.MaterialDefinition::code).orElse(inputCode);
            var role = material.map(UvpuAnalysisProfile.MaterialDefinition::role).orElse("");
            var status = code.isBlank() ? "MATERIAL_UNMAPPED"
                    : material.isPresent() && !material.get().modelAllowed() ? "MATERIAL_OUT_OF_PROFILE" : "MAPPED";
            var rawRatio = ratio(item.path("ratio"));
            var effectiveRatio = rawRatio;
            var derivation = rawRatio == null ? "MISSING" : "SOURCE_REPORTED";
            var ruleVersion = rawRatio == null ? "" : "SOURCE_VALUE";
            if (rawRatio == null && blankRawValue(item)) {
                var structuralZeroRule = profile.structuralZeroRule(text(item, "itemSourceKey"));
                if (structuralZeroRule.isPresent()) {
                    effectiveRatio = BigDecimal.ZERO;
                    derivation = "STRUCTURAL_ZERO";
                    ruleVersion = structuralZeroRule.get();
                }
            }
            prepared.add(new RawFormulaComponent(text(item, "itemId"), code, materialName, role, rawRatio,
                    effectiveRatio, item.has("rawValue") ? item.path("rawValue").deepCopy() : json.nullNode(),
                    text(item, "rawUnit"), derivation, ruleVersion, status, refs(item)));
        }
        var reasons = new ArrayList<EligibilityReason>();
        for (var item : prepared) {
            if (item.effectiveRatio() == null) {
                reasons.add(reason("FORMULA_RATIO_MISSING", "配方比例缺失，不能假定为0", item.itemId()));
            } else if (item.effectiveRatio().signum() < 0) {
                reasons.add(reason("FORMULA_RATIO_NEGATIVE", "配方比例不能为负数", item.itemId()));
            }
        }
        var rawTotal = prepared.stream().map(RawFormulaComponent::effectiveRatio).filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
        if (rawTotal.signum() <= 0) {
            reasons.add(reason("FORMULA_TOTAL_NON_POSITIVE", "配方比例合计必须大于0", ""));
        }
        var normalizable = reasons.isEmpty();
        var policy = profile.definition().formulaNormalizationPolicy();
        var factor = normalizable
                ? profile.definition().formulaTotal().divide(rawTotal, MathContext.DECIMAL128).stripTrailingZeros()
                : null;
        var normalizationApplied = normalizable
                && rawTotal.compareTo(profile.definition().formulaTotal()) != 0;
        var result = prepared.stream().map(item -> new FormulaComponent(item.itemId(), item.materialCode(),
                        item.materialName(), item.materialRole(), item.rawRatio(),
                        normalizable ? item.effectiveRatio().multiply(factor, MathContext.DECIMAL128).stripTrailingZeros() : null,
                        item.rawValue(), item.rawUnit(), item.ratioDerivation(), item.ratioRuleVersion(),
                        item.mappingStatus(), item.sourceRefs()))
                .sorted(Comparator.comparing(FormulaComponent::materialCode)
                        .thenComparing(FormulaComponent::materialName).thenComparing(FormulaComponent::itemId))
                .toList();
        return new FormulaProjection(result, rawTotal, normalizationApplied, factor,
                normalizable ? policy.ruleVersion() : "", List.copyOf(reasons));
    }

    private List<EligibilityReason> structuredReasons(List<JsonNode> formulaFacts, List<FormulaComponent> formula,
                                                      List<JsonNode> tests, boolean conflictingManualFacts) {
        var reasons = new ArrayList<EligibilityReason>();
        if (conflictingManualFacts) reasons.add(reason("CONFLICTING_FACTS", "同一手工实验版本存在多套无法归属的事实", ""));
        if (formula.stream().noneMatch(item -> item.rawRatio() != null
                || "STRUCTURAL_ZERO".equals(item.ratioDerivation()))) {
            reasons.add(reason("FORMULA_MISSING", "没有可确认的配方比例", ""));
        }
        var untraced = new ArrayList<JsonNode>();
        untraced.addAll(formulaFacts);
        untraced.addAll(tests);
        untraced.stream().filter(item -> !item.path("sourceRefs").isArray() || item.path("sourceRefs").isEmpty())
                .findFirst().ifPresent(item -> reasons.add(reason("SOURCE_TRACE_MISSING", "结构事实缺少来源引用", text(item, "itemId"))));
        return List.copyOf(reasons);
    }

    private Eligibility caseEligibility(boolean ready, List<EligibilityReason> structured,
                                        List<EligibilityReason> formulaReasons,
                                        List<FormulaComponent> formula, TargetObservation target) {
        var reasons = new ArrayList<EligibilityReason>();
        if (!ready) reasons.addAll(structured);
        reasons.addAll(formulaReasons);
        formula.stream().filter(item -> "MATERIAL_UNMAPPED".equals(item.mappingStatus())
                        && sourceRatio(item) != null && sourceRatio(item).signum() > 0)
                .forEach(item -> reasons.add(reason("MATERIAL_UNMAPPED", "材料身份尚未确认", item.itemId())));
        if (target != null && !"PARSED".equals(target.status())) reasons.addAll(target.reasons());
        if (target == null) reasons.add(reason("TARGET_VALUE_MISSING", "未记录目标结果", ""));
        if (target != null && "QUALITATIVE".equals(target.observationType())) {
            reasons.add(reason("TARGET_UNSUPPORTED", "定性结果不能用于当前案例数值比较", target.sourceItemId()));
        }
        return new Eligibility(reasons.isEmpty(), deduplicate(reasons));
    }

    private Eligibility modelEligibility(boolean ready, List<EligibilityReason> structured,
                                         List<EligibilityReason> formulaReasons,
                                         List<FormulaComponent> formula, Map<String, StandardFact> process,
                                         Map<String, StandardFact> context, TargetObservation target) {
        var reasons = new ArrayList<EligibilityReason>();
        if (!ready) reasons.addAll(structured);
        reasons.addAll(formulaReasons);
        for (var item : formula) {
            if (sourceRatio(item) != null && sourceRatio(item).signum() > 0) {
                if ("MATERIAL_UNMAPPED".equals(item.mappingStatus())) reasons.add(reason("MATERIAL_UNMAPPED", "材料身份尚未确认", item.itemId()));
                if ("MATERIAL_OUT_OF_PROFILE".equals(item.mappingStatus())) reasons.add(reason("MATERIAL_OUT_OF_PROFILE", "材料不属于当前UV/PU模型范围", item.itemId()));
            }
        }
        for (var code : profile.definition().modelRequiredProcessFacts()) {
            var value = process.get(code);
            if (value == null || value.numericValue() == null || !"PARSED".equals(value.status())) {
                var reasonCode = value != null && "PERCENT_REPRESENTATION_UNKNOWN".equals(value.derivationRule())
                        ? "PERCENT_REPRESENTATION_UNKNOWN" : "PROCESS_CONTEXT_MISSING";
                reasons.add(reason(reasonCode, "模型所需工艺变量缺失或含义不确定：" + code,
                        value == null ? "" : value.sourceItemId()));
            }
        }
        for (var code : profile.definition().modelRequiredContextFacts()) {
            if (!context.containsKey(code)) reasons.add(reason("TEST_CONTEXT_MISSING", "模型所需上下文缺失：" + code, ""));
        }
        if (target == null || !"PARSED".equals(target.status())) {
            if (target == null) reasons.add(reason("TARGET_VALUE_MISSING", "未记录目标结果", ""));
            else reasons.addAll(target.reasons());
        } else if (!"EXACT".equals(target.observationType())) {
            reasons.add(reason("CENSORED_TARGET_UNSUPPORTED_FOR_REGRESSION",
                    "当前普通回归只使用明确失效点，界限或观察次数暂不作为精确Y", target.sourceItemId()));
        }
        return new Eligibility(reasons.isEmpty(), deduplicate(reasons));
    }

    private void assignLineages(List<RowDraft> rows) {
        var parent = new int[rows.size()];
        for (var i = 0; i < parent.length; i++) parent[i] = i;
        for (var left = 0; left < rows.size(); left++) {
            for (var right = left + 1; right < rows.size(); right++) {
                if (sameLineage(rows.get(left), rows.get(right))) union(parent, left, right);
            }
        }
        var members = new TreeMap<Integer, List<String>>();
        for (var i = 0; i < rows.size(); i++) {
            var root = find(parent, i);
            members.computeIfAbsent(root, ignored -> new ArrayList<>()).add(rows.get(i).formulaSignature());
        }
        for (var i = 0; i < rows.size(); i++) {
            var signatures = members.get(find(parent, i)).stream().filter(value -> !value.isBlank()).sorted().distinct().toList();
            var basis = json.createObjectNode().put("schema", "formula-lineage.v1");
            basis.set("formulaSignatures", json.valueToTree(signatures.isEmpty() ? List.of(rows.get(i).analysisRowId()) : signatures));
            rows.get(i).formulaLineageGroup = "FORMULA_LINEAGE:" + canonicalizer.hash(basis).substring(0, 24);
        }
    }

    private boolean sameLineage(RowDraft left, RowDraft right) {
        if (left.formulaSignature().isBlank() || right.formulaSignature().isBlank()) return false;
        var leftMap = formulaMap(left.formula());
        var rightMap = formulaMap(right.formula());
        if (!leftMap.keySet().equals(rightMap.keySet())) return false;
        if (!mainResinFamilies(left.formula()).equals(mainResinFamilies(right.formula()))) return false;
        var distance = BigDecimal.ZERO;
        for (var code : leftMap.keySet()) distance = distance.add(leftMap.get(code).subtract(rightMap.get(code)).abs());
        return distance.compareTo(profile.definition().lineageL1Threshold()) <= 0;
    }

    private AnalysisRow finish(RowDraft draft) {
        var leakage = new ArrayList<String>();
        leakage.add("EXPERIMENT_VERSION:" + draft.fact().experimentVersionId());
        leakage.add(draft.formulaLineageGroup);
        if (!draft.sourceContextKey().isBlank()) leakage.add(draft.sourceContextKey());
        if (draft.logicalSampleKey() != null && !draft.logicalSampleKey().isBlank()) {
            leakage.add("LOGICAL_SAMPLE:" + draft.logicalSampleKey());
        }
        return new AnalysisRow(draft.analysisRowId(), draft.fact().experimentId(), draft.fact().experimentVersionId(),
                draft.fact().experimentNo(), draft.fact().sourceType(), draft.sourceGroupKey(), draft.sourceContextKey(),
                draft.sourceIdentity(), draft.sourceIdentityType(), draft.logicalSampleKey(), draft.formula(),
                draft.rawFormulaTotal(), draft.normalizationApplied(), draft.normalizationFactor(),
                draft.normalizationRuleVersion(), draft.process(),
                draft.context(), draft.resinBatchProperties(), draft.targets(), draft.structuredReadiness(), draft.structuredReasons(),
                draft.caseEligibility(), draft.modelEligibility(), draft.formulaSignature(), draft.formulaLineageGroup,
                List.copyOf(leakage), draft.contentHash());
    }

    private String rowId(UUID versionId, String groupKey) {
        var basis = json.createObjectNode().put("schema", "analysis-row-id.v1")
                .put("experimentVersionId", versionId.toString()).put("effectiveSourceGroupKey", groupKey);
        return canonicalizer.hash(basis);
    }

    private String formulaSignature(List<FormulaComponent> formula) {
        var components = json.createArrayNode();
        formulaMap(formula).forEach((materialCode, ratioPercent) -> components.addObject()
                .put("materialCode", materialCode).put("ratioPercent", ratioPercent.stripTrailingZeros()));
        if (components.isEmpty()) return "";
        var basis = json.createObjectNode().put("schema", "formula-signature.v1")
                .put("basis", profile.definition().formulaBasis()).set("components", components);
        return "FORMULA_SIGNATURE:" + canonicalizer.hash(basis);
    }

    private String contentHash(List<FormulaComponent> formula, Map<String, StandardFact> process,
                               Map<String, StandardFact> context,
                               Map<String, ResinBatchProperty> resinBatchProperties,
                               Map<String, TargetObservation> targets) {
        var basis = json.createObjectNode().put("analysisProfileVersion", profile.definition().analysisProfileVersion());
        var formulaNode = basis.putArray("formula");
        formulaMap(formula).forEach((materialCode, ratioPercent) -> formulaNode.addObject()
                .put("materialCode", materialCode)
                .set("ratioPercent", json.valueToTree(ratioPercent.stripTrailingZeros())));
        basis.set("process", hashableFacts(process));
        basis.set("context", hashableFacts(context));
        var propertyNode = basis.putObject("resinBatchProperties");
        new TreeMap<>(resinBatchProperties).forEach((key, value) -> propertyNode.set(key, json.createObjectNode()
                .put("materialCode", value.materialCode()).put("bindingStatus", value.bindingStatus())
                .put("textValue", value.textValue() == null ? "" : value.textValue())
                .put("unit", value.standardUnit() == null ? "" : value.standardUnit())
                .set("numericValue", value.numericValue() == null ? json.nullNode()
                        : json.valueToTree(value.numericValue().stripTrailingZeros()))));
        var targetNode = basis.putObject("targets");
        new TreeMap<>(targets).forEach((key, value) -> {
            var target = json.createObjectNode()
                    .put("status", value.status()).put("observationType", value.observationType())
                    .put("valueType", value.valueType()).put("unit", value.standardUnit())
                    .put("ordinalValue", value.ordinalValue() == null ? "" : value.ordinalValue())
                    .put("direction", value.direction() == null ? "" : value.direction())
                    .put("derivationRule", value.derivationRule());
            target.set("minimum", value.minimum() == null ? json.nullNode()
                    : json.valueToTree(value.minimum().stripTrailingZeros()));
            target.set("maximum", value.maximum() == null ? json.nullNode()
                    : json.valueToTree(value.maximum().stripTrailingZeros()));
            target.set("numericValue", value.numericValue() == null ? json.nullNode()
                    : json.valueToTree(value.numericValue().stripTrailingZeros()));
            targetNode.set(key, target);
        });
        return canonicalizer.hash(basis);
    }

    private ObjectNode hashableFacts(Map<String, StandardFact> facts) {
        var node = json.createObjectNode();
        new TreeMap<>(facts).forEach((key, value) -> {
            var item = json.createObjectNode()
                    .put("status", value.status()).put("unit", value.standardUnit())
                    .put("textValue", value.textValue() == null ? "" : value.textValue())
                    .put("derivationRule", value.derivationRule() == null ? "" : value.derivationRule());
            item.set("numericValue", value.numericValue() == null ? json.nullNode()
                    : json.valueToTree(value.numericValue().stripTrailingZeros()));
            item.set("minimum", value.minimum() == null ? json.nullNode()
                    : json.valueToTree(value.minimum().stripTrailingZeros()));
            item.set("maximum", value.maximum() == null ? json.nullNode()
                    : json.valueToTree(value.maximum().stripTrailingZeros()));
            node.set(key, item);
        });
        return node;
    }

    private AnalysisQualitySummary quality(List<AnalysisRow> rows) {
        var caseCounts = new TreeMap<String, Long>();
        var modelCounts = new TreeMap<String, Long>();
        var reasons = new TreeMap<String, Long>();
        for (var row : rows) {
            row.caseEligibilityByTarget().forEach((key, value) -> {
                if (value.eligible()) caseCounts.merge(key, 1L, Long::sum);
                value.reasons().forEach(reason -> reasons.merge(reason.code(), 1L, Long::sum));
            });
            row.modelEligibilityByTarget().forEach((key, value) -> {
                if (value.eligible()) modelCounts.merge(key, 1L, Long::sum);
                value.reasons().forEach(reason -> reasons.merge(reason.code(), 1L, Long::sum));
            });
            row.structuredReasons().forEach(reason -> reasons.merge(reason.code(), 1L, Long::sum));
        }
        profile.definition().targets().forEach(target -> {
            caseCounts.putIfAbsent(target.targetKey(), 0L);
            modelCounts.putIfAbsent(target.targetKey(), 0L);
        });
        return new AnalysisQualitySummary(rows.size(), rows.stream().filter(AnalysisRow::structuredReadiness).count(),
                Map.copyOf(caseCounts), Map.copyOf(modelCounts), Map.copyOf(reasons));
    }

    private java.util.Optional<StandardFact> substrate(List<JsonNode> contextFacts, List<JsonNode> tests) {
        var texts = new ArrayList<String>();
        contextFacts.stream().filter(item -> "TEST".equals(text(item, "domain"))
                        && "SUBSTRATE".equals(text(item, "field")))
                .map(this::raw).forEach(texts::add);
        tests.stream().map(item -> text(item, "testItem")).forEach(texts::add);
        var joined = String.join(";", texts);
        var substrates = new TreeSet<String>();
        texts.stream().map(this::substrateCode).filter(value -> !value.isBlank()).forEach(substrates::add);
        if (substrates.isEmpty() || substrates.stream().map(this::substrateFamily).distinct().count() != 1) {
            return java.util.Optional.empty();
        }
        var value = substrates.stream().max(Comparator.comparingInt(this::substrateSpecificity)).orElseThrow();
        var family = substrateFamily(value);
        var source = contextFacts.stream().filter(item -> family.equals(substrateFamily(substrateCode(raw(item)))))
                .findFirst().orElse(null);
        return java.util.Optional.of(new StandardFact("substrate", null, value, null, null, "", joined,
                source == null ? "" : text(source, "bindingId"), source == null ? json.createArrayNode() : refs(source),
                "uvpu-context-parser.v1:SUBSTRATE_V1", "PARSED", "CONTROLLED_TERM"));
    }

    private String substrateCode(String text) {
        var normalized = UvpuAnalysisProfile.normalize(text);
        if (normalized.contains("PMMA/PC") || normalized.contains("PMMA-PC") || normalized.contains("PMMAPC复合")) {
            return normalized.contains("0.64") ? "PMMA_PC_COMPOSITE_0_64MM" : "PMMA_PC_COMPOSITE";
        }
        if (normalized.contains("PC")) return normalized.contains("170") ? "PC_FILM_170UM" : "PC_FILM";
        if (normalized.contains("PET")) {
            return normalized.contains("100") || normalized.contains("光学") ? "PET_100UM_OPTICAL" : "PET_FILM";
        }
        return "";
    }

    private String substrateFamily(String code) {
        if (code.startsWith("PMMA_PC_COMPOSITE")) return "PMMA_PC_COMPOSITE";
        if (code.startsWith("PC_FILM")) return "PC_FILM";
        if (code.startsWith("PET_")) return "PET_FILM";
        return code;
    }

    private int substrateSpecificity(String code) {
        return switch (code) {
            case "PMMA_PC_COMPOSITE_0_64MM", "PC_FILM_170UM", "PET_100UM_OPTICAL" -> 2;
            default -> 1;
        };
    }

    private Map<String, ResinBatchProperty> resinBatchProperties(List<FormulaComponent> formula,
                                                                  List<JsonNode> testFacts) {
        var mainResins = formula.stream()
                .filter(item -> "MAIN_RESIN".equals(item.materialRole()) && item.ratioPercent() != null
                        && item.ratioPercent().signum() > 0)
                .map(FormulaComponent::materialCode).filter(code -> code != null && !code.isBlank())
                .distinct().toList();
        var bindingStatus = mainResins.size() == 1 ? "BOUND" : mainResins.isEmpty() ? "UNBOUND" : "AMBIGUOUS";
        var materialCode = mainResins.size() == 1 ? mainResins.getFirst() : "";
        var result = new TreeMap<String, ResinBatchProperty>();
        for (var fact : testFacts) {
            var label = fullLabel(fact);
            var code = resinPropertyCode(label);
            if (code.isBlank() || result.containsKey(code)) continue;
            var raw = raw(fact);
            if (raw.isBlank() || "/".equals(raw)) continue;
            var numeric = resinPropertyNumber(code, raw, fact);
            var textValue = numeric == null ? raw : null;
            result.put(code, new ResinBatchProperty(code, materialCode, numeric, textValue,
                    resinPropertyUnit(code), raw, text(fact, "itemId"), refs(fact), bindingStatus,
                    "uvpu-resin-property-parser.v1:" + code));
        }
        return Map.copyOf(result);
    }

    private String resinPropertyCode(String label) {
        var normalized = UvpuAnalysisProfile.normalize(label);
        if (!normalized.contains("树脂物性")) return "";
        if (normalized.contains("实测固含")) return "measuredSolidsPct";
        if (normalized.contains("含水率")) return "waterContentPct";
        if (normalized.contains("分子量")) return "molecularWeight";
        if (normalized.contains("粘度")) return "viscosity";
        if (normalized.contains("表干") && (normalized.contains("120度") || normalized.contains("120℃"))) {
            return "bake120C1hSurfaceDryness";
        }
        return "";
    }

    private BigDecimal resinPropertyNumber(String code, String raw, JsonNode fact) {
        if ("bake120C1hSurfaceDryness".equals(code)) return null;
        var matcher = FIRST_NUMBER.matcher(raw);
        if (!matcher.find()) return null;
        var value = new BigDecimal(matcher.group(1)).stripTrailingZeros();
        if (("measuredSolidsPct".equals(code) || "waterContentPct".equals(code))
                && !raw.contains("%") && fractionRepresentation(fact)) {
            return value.multiply(new BigDecimal("100")).stripTrailingZeros();
        }
        return value;
    }

    private boolean fractionRepresentation(JsonNode fact) {
        for (var ref : nodes(fact.path("sourceRefs"))) {
            if (ref.path("fractionRepresentation").asBoolean(false)) return true;
        }
        return false;
    }

    private String resinPropertyUnit(String code) {
        return switch (code) {
            case "measuredSolidsPct", "waterContentPct" -> "%";
            case "molecularWeight" -> "g/mol";
            default -> "";
        };
    }

    private String fullLabel(JsonNode fact) {
        var parts = new LinkedHashSet<String>();
        if (fact.path("labelPathSegments").isArray()) {
            fact.path("labelPathSegments").forEach(value -> {
                var segment = value.asText("").strip();
                if (!segment.isBlank()) parts.add(segment);
            });
        }
        for (var field : List.of("testItem", "itemLabel", "labelPath")) {
            var value = text(fact, field);
            if (!value.isBlank()) parts.add(value);
        }
        return String.join(" > ", parts);
    }

    private List<JsonNode> matching(JsonNode array, String groupKey, boolean includeContext, String contextKey) {
        var result = new ArrayList<JsonNode>();
        for (var item : nodes(array)) {
            var itemGroup = text(item, "sourceGroupKey");
            if (groupKey.equals(itemGroup) || includeContext && itemGroup.isBlank()
                    && contextKey.equals(text(item, "sourceContextKey"))) result.add(item);
        }
        return result;
    }

    private List<JsonNode> contextFacts(JsonNode contexts, String contextKey) {
        for (var context : nodes(contexts)) if (contextKey.equals(text(context, "sourceContextKey"))) {
            return new ArrayList<>(nodes(context.path("facts")));
        }
        return new ArrayList<>();
    }

    private List<JsonNode> allItems(CompletedExperimentFactsProvider.CompletedExperimentFacts fact) {
        var result = new ArrayList<JsonNode>();
        result.addAll(nodes(fact.formulaItems()));
        result.addAll(nodes(fact.processSteps()));
        result.addAll(nodes(fact.testResults()));
        return result;
    }

    private List<JsonNode> nodes(JsonNode value) {
        var result = new ArrayList<JsonNode>();
        if (value != null && value.isArray()) value.forEach(result::add);
        return result;
    }

    private BigDecimal ratio(JsonNode value) {
        if (value == null || value.isNull() || value.asText("").isBlank() || "/".equals(value.asText().trim())) return null;
        try { return new BigDecimal(value.asText().replace("%", "").trim()).stripTrailingZeros(); }
        catch (RuntimeException ignored) { return null; }
    }

    private Map<String, BigDecimal> formulaMap(List<FormulaComponent> formula) {
        var result = new TreeMap<String, BigDecimal>();
        formula.stream().filter(item -> item.ratioPercent() != null && item.ratioPercent().signum() > 0
                        && !item.materialCode().isBlank())
                .forEach(item -> result.merge(item.materialCode(), item.ratioPercent(), BigDecimal::add));
        return result;
    }

    private BigDecimal sourceRatio(FormulaComponent item) {
        if (item.rawRatio() != null) return item.rawRatio();
        return "STRUCTURAL_ZERO".equals(item.ratioDerivation()) ? BigDecimal.ZERO : null;
    }

    private Set<String> mainResinFamilies(List<FormulaComponent> formula) {
        var result = new TreeSet<String>();
        formula.stream().filter(item -> "MAIN_RESIN".equals(item.materialRole()) && item.ratioPercent() != null
                        && item.ratioPercent().signum() > 0)
                .forEach(item -> profile.material(item.materialCode(), item.materialName())
                        .ifPresent(material -> result.add(material.family())));
        return result;
    }

    private boolean blankRawValue(JsonNode item) {
        if (!item.has("rawValue") || item.path("rawValue").isNull()) return true;
        return item.path("rawValue").isTextual() && item.path("rawValue").asText().isBlank();
    }

    private int find(int[] parent, int value) {
        if (parent[value] != value) parent[value] = find(parent, parent[value]);
        return parent[value];
    }

    private void union(int[] parent, int left, int right) {
        var a = find(parent, left);
        var b = find(parent, right);
        if (a != b) parent[Math.max(a, b)] = Math.min(a, b);
    }

    private List<EligibilityReason> deduplicate(List<EligibilityReason> reasons) {
        var seen = new LinkedHashSet<String>();
        return reasons.stream().filter(reason -> seen.add(reason.code() + "|" + reason.sourceItemId())).toList();
    }

    private EligibilityReason reason(String code, String message, String itemId) {
        return new EligibilityReason(code, message, itemId == null ? "" : itemId);
    }

    private String raw(JsonNode node) {
        var raw = node.get("rawValue");
        if (raw == null || raw.isNull()) raw = node.get("value");
        return raw == null || raw.isNull() ? "" : raw.asText("").trim();
    }

    private JsonNode refs(JsonNode node) {
        return node.path("sourceRefs").isArray() ? node.path("sourceRefs").deepCopy() : json.createArrayNode();
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private String nullableText(JsonNode node, String field) {
        var value = text(node, field);
        return value.isBlank() ? null : value;
    }

    private static final class RowDraft {
        private final String analysisRowId;
        private final CompletedExperimentFactsProvider.CompletedExperimentFacts fact;
        private final String sourceGroupKey;
        private final String sourceContextKey;
        private final String sourceIdentity;
        private final String sourceIdentityType;
        private final String logicalSampleKey;
        private final List<FormulaComponent> formula;
        private final BigDecimal rawFormulaTotal;
        private final boolean normalizationApplied;
        private final BigDecimal normalizationFactor;
        private final String normalizationRuleVersion;
        private final Map<String, StandardFact> process;
        private final Map<String, StandardFact> context;
        private final Map<String, ResinBatchProperty> resinBatchProperties;
        private final Map<String, TargetObservation> targets;
        private final boolean structuredReadiness;
        private final List<EligibilityReason> structuredReasons;
        private final Map<String, Eligibility> caseEligibility;
        private final Map<String, Eligibility> modelEligibility;
        private final String formulaSignature;
        private String formulaLineageGroup;
        private final String contentHash;

        private RowDraft(String analysisRowId, CompletedExperimentFactsProvider.CompletedExperimentFacts fact,
                         String sourceGroupKey, String sourceContextKey, String sourceIdentity, String sourceIdentityType,
                         String logicalSampleKey, List<FormulaComponent> formula,
                         BigDecimal rawFormulaTotal, boolean normalizationApplied, BigDecimal normalizationFactor,
                         String normalizationRuleVersion, Map<String, StandardFact> process,
                          Map<String, StandardFact> context, Map<String, ResinBatchProperty> resinBatchProperties,
                          Map<String, TargetObservation> targets,
                         boolean structuredReadiness, List<EligibilityReason> structuredReasons,
                         Map<String, Eligibility> caseEligibility, Map<String, Eligibility> modelEligibility,
                         String formulaSignature, String formulaLineageGroup, String contentHash) {
            this.analysisRowId = analysisRowId;
            this.fact = fact;
            this.sourceGroupKey = sourceGroupKey;
            this.sourceContextKey = sourceContextKey;
            this.sourceIdentity = sourceIdentity;
            this.sourceIdentityType = sourceIdentityType;
            this.logicalSampleKey = logicalSampleKey;
            this.formula = formula;
            this.rawFormulaTotal = rawFormulaTotal;
            this.normalizationApplied = normalizationApplied;
            this.normalizationFactor = normalizationFactor;
            this.normalizationRuleVersion = normalizationRuleVersion;
            this.process = process;
            this.context = context;
            this.resinBatchProperties = resinBatchProperties;
            this.targets = targets;
            this.structuredReadiness = structuredReadiness;
            this.structuredReasons = structuredReasons;
            this.caseEligibility = caseEligibility;
            this.modelEligibility = modelEligibility;
            this.formulaSignature = formulaSignature;
            this.formulaLineageGroup = formulaLineageGroup;
            this.contentHash = contentHash;
        }

        String analysisRowId() { return analysisRowId; }
        CompletedExperimentFactsProvider.CompletedExperimentFacts fact() { return fact; }
        String sourceGroupKey() { return sourceGroupKey; }
        String sourceContextKey() { return sourceContextKey; }
        String sourceIdentity() { return sourceIdentity; }
        String sourceIdentityType() { return sourceIdentityType; }
        String logicalSampleKey() { return logicalSampleKey; }
        List<FormulaComponent> formula() { return formula; }
        BigDecimal rawFormulaTotal() { return rawFormulaTotal; }
        boolean normalizationApplied() { return normalizationApplied; }
        BigDecimal normalizationFactor() { return normalizationFactor; }
        String normalizationRuleVersion() { return normalizationRuleVersion; }
        Map<String, StandardFact> process() { return process; }
        Map<String, StandardFact> context() { return context; }
        Map<String, ResinBatchProperty> resinBatchProperties() { return resinBatchProperties; }
        Map<String, TargetObservation> targets() { return targets; }
        boolean structuredReadiness() { return structuredReadiness; }
        List<EligibilityReason> structuredReasons() { return structuredReasons; }
        Map<String, Eligibility> caseEligibility() { return caseEligibility; }
        Map<String, Eligibility> modelEligibility() { return modelEligibility; }
        String formulaSignature() { return formulaSignature; }
        String contentHash() { return contentHash; }
    }

    private record RawFormulaComponent(
            String itemId,
            String materialCode,
            String materialName,
            String materialRole,
            BigDecimal rawRatio,
            BigDecimal effectiveRatio,
            JsonNode rawValue,
            String rawUnit,
            String ratioDerivation,
            String ratioRuleVersion,
            String mappingStatus,
            JsonNode sourceRefs
    ) {
    }

    private record FormulaProjection(
            List<FormulaComponent> formula,
            BigDecimal rawTotal,
            boolean normalizationApplied,
            BigDecimal normalizationFactor,
            String normalizationRuleVersion,
            List<EligibilityReason> reasons
    ) {
    }
}
