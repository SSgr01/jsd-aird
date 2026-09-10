package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.api.SimilarCaseBaselineFacade;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.T06BaselineDocument;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.T06BaselineObservation;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.T06TargetBaseline;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.ValidationFoldAssignment;
import com.jsd.aird.ai.formula.application.ResearchCaseLoader.ResearchCaseView;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class T06SimilarCaseBaselineService implements SimilarCaseBaselineFacade {
    private final ResearchCaseLoader loader;
    private final SimilarCaseEngine similar;
    private final TargetStatisticsService statistics;
    private final UvpuResearchProfile profile;
    private final JsonCanonicalizer canonicalizer;
    private final ObjectMapper json;

    public T06SimilarCaseBaselineService(ResearchCaseLoader loader, SimilarCaseEngine similar,
                                         TargetStatisticsService statistics, UvpuResearchProfile profile,
                                         JsonCanonicalizer canonicalizer, ObjectMapper json) {
        this.loader = loader;
        this.similar = similar;
        this.statistics = statistics;
        this.profile = profile;
        this.canonicalizer = canonicalizer;
        this.json = json;
    }

    @Override
    public BaselineReport evaluate(BaselineQuery query) {
        var collection = loader.load(query.taskProfileCode(), query.projectId(), query.categoryId());
        var keys = query.targetKeys().isEmpty()
                ? profile.definition().targets().stream().map(UvpuResearchProfile.Target::targetKey).toList()
                : query.targetKeys();
        var targets = keys.stream().map(key -> evaluateTarget(collection.cases(), key)).toList();
        return new BaselineReport("T06_SIMILAR_CASE", profile.definition().similarCaseBaselineVersion(),
                collection.taskProfileCode(), collection.analysisProfileVersion(), targets);
    }

    @Override
    public T06BaselineDocument evaluateFolded(FoldedBaselineQuery query) {
        if (query.validationFolds() == null || query.taskProfile() == null) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "缺少不可变验证折或任务档案");
        }
        if (!query.snapshotHash().equals(query.validationFolds().snapshotHash())
                || !query.taskProfileHash().equals(query.validationFolds().taskProfileHash())) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "验证折与快照或任务档案不匹配");
        }
        var collection = loader.load(query.taskProfileCode(), query.projectId(), query.categoryId());
        var rowsById = collection.cases().stream().collect(Collectors.toMap(
                item -> item.analysisRow().analysisRowId(), Function.identity(), (left, right) -> left));
        var targets = query.taskProfile().targets().stream()
                .map(target -> evaluateFoldedTarget(target, query.validationFolds(), rowsById))
                .toList();
        var draft = new T06BaselineDocument("t06-similar-case-baseline.v1", "T06_SIMILAR_CASE",
                profile.definition().similarCaseBaselineVersion(), query.snapshotHash(), query.taskProfileHash(),
                query.validationFoldsArtifactHash(), targets, null);
        var hashInput = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(draft);
        hashInput.remove("contentHash");
        var contentHash = canonicalizer.hash(hashInput);
        return new T06BaselineDocument(draft.schemaVersion(), draft.baselineType(), draft.baselineVersion(),
                draft.snapshotHash(), draft.taskProfileHash(), draft.validationFoldsHash(), draft.targets(), contentHash);
    }

    private T06TargetBaseline evaluateFoldedTarget(
            com.jsd.aird.ai.formula.api.FormulaModelContracts.TargetSpec target,
            com.jsd.aird.ai.formula.api.FormulaModelContracts.ValidationFoldsDocument folds,
            Map<String, ResearchCaseView> rowsById
    ) {
        var metricsByScheme = new LinkedHashMap<String, Map<String, Double>>();
        var observations = new ArrayList<T06BaselineObservation>();
        var cohortActual = new TreeMap<String, Object>();
        for (var scheme : folds.schemes()) {
            var assignments = scheme.assignments().stream()
                    .filter(item -> target.targetKey().equals(item.targetKey()))
                    .sorted(java.util.Comparator.comparingInt(ValidationFoldAssignment::foldIndex)
                            .thenComparing(ValidationFoldAssignment::analysisRowId))
                    .toList();
            var assignmentById = assignments.stream().collect(Collectors.toMap(
                    ValidationFoldAssignment::analysisRowId, Function.identity()));
            var predictionNodes = json.createArrayNode();
            for (var heldAssignment : assignments) {
                var held = rowsById.get(heldAssignment.analysisRowId());
                if (held == null) throw new ApiException(ApiErrorCode.INVALID_SCHEMA,
                        "验证折引用了当前权限范围外或不存在的分析行");
                var actual = actualValue(held, target.targetKey(), target.valueType().name());
                if (actual == null) throw new ApiException(ApiErrorCode.INVALID_SCHEMA,
                        "验证折引用了没有有效目标值的分析行");
                cohortActual.put(heldAssignment.analysisRowId(), actual);
                var training = assignments.stream()
                        .filter(item -> item.foldIndex() != heldAssignment.foldIndex())
                        .map(item -> rowsById.get(item.analysisRowId()))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                var neighbours = similar.find(training, similar.anchor(held), target.targetKey(), Map.of(), Set.of(), null);
                var summary = statistics.calculate(target.targetKey(), neighbours).summary();
                var predicted = predictedValue(summary, target.valueType().name());
                var probabilities = probabilities(summary, target.valueType().name());
                var sampleHash = sampleHash(held, target.targetKey(), actual);
                observations.add(new T06BaselineObservation(heldAssignment.analysisRowId(), target.targetKey(),
                        scheme.validationScheme(), heldAssignment.foldIndex(), heldAssignment.groupKey(), actual,
                        predicted, probabilities, neighbours.size(), sampleHash));
                appendMetricNode(predictionNodes, held, actual, predicted, probabilities, target.valueType().name());
            }
            metricsByScheme.put(scheme.validationScheme(), numericMetrics(metrics(target.valueType().name(), predictionNodes)));
            if (assignmentById.size() != assignments.size()) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "同一验证方案中分析行被重复分配");
            }
        }
        var sampleRows = json.createArrayNode();
        cohortActual.forEach((rowId, actual) -> {
            var node = sampleRows.addObject().put("analysisRowId", rowId).put("targetKey", target.targetKey());
            node.set("actual", json.valueToTree(actual));
            node.put("analysisRowContentHash", rowsById.get(rowId).analysisRow().analysisRowContentHash());
        });
        return new T06TargetBaseline(target.targetKey(), target.code(), target.valueType(), metricsByScheme,
                observations.stream().sorted(java.util.Comparator.comparing(T06BaselineObservation::validationScheme)
                        .thenComparingInt(T06BaselineObservation::foldIndex)
                        .thenComparing(T06BaselineObservation::analysisRowId)).toList(),
                canonicalizer.hash(sampleRows));
    }

    private String sampleHash(ResearchCaseView row, String targetKey, Object actual) {
        var value = json.createObjectNode().put("analysisRowId", row.analysisRow().analysisRowId())
                .put("targetKey", targetKey)
                .put("analysisRowContentHash", row.analysisRow().analysisRowContentHash());
        value.set("actual", json.valueToTree(actual));
        return canonicalizer.hash(value);
    }

    private Object actualValue(ResearchCaseView row, String targetKey, String valueType) {
        var value = row.analysisRow().targets().get(targetKey);
        if (value == null || !"PARSED".equals(value.status()) || "LOWER_BOUND".equals(value.observationType())) return null;
        if ("CONTINUOUS".equals(valueType)) return value.numericValue() == null ? null : value.numericValue().doubleValue();
        if ("ORDINAL".equals(valueType)) return grade(value.ordinalValue());
        return value.ordinalValue();
    }

    private Object predictedValue(ObjectNode summary, String valueType) {
        var point = summary.get("pointEstimate");
        if (point == null || point.isNull()) return null;
        if ("CONTINUOUS".equals(valueType)) return point.isNumber() ? point.doubleValue() : null;
        if ("ORDINAL".equals(valueType)) return grade(point.asText());
        return point.asText();
    }

    private Map<String, Double> probabilities(ObjectNode summary, String valueType) {
        if (!"BINARY".equals(valueType) || !summary.path("positiveRate").isNumber()) return Map.of();
        var positiveRate = summary.path("positiveRate").asDouble();
        return Map.of("OK", positiveRate, "NOT_OK", 1d - positiveRate);
    }

    private void appendMetricNode(ArrayNode nodes, ResearchCaseView held, Object actual, Object predicted,
                                  Map<String, Double> probabilities, String valueType) {
        var item = nodes.addObject().put("analysisRowId", held.analysisRow().analysisRowId());
        if ("CONTINUOUS".equals(valueType) && actual instanceof Number number) item.put("actual", number.doubleValue());
        else item.put("actualClass", displayClass(actual, valueType));
        if ("CONTINUOUS".equals(valueType) && predicted instanceof Number number) item.put("predicted", number.doubleValue());
        else if (predicted != null) item.put("predicted", displayClass(predicted, valueType));
        if (probabilities.containsKey("OK")) item.put("positiveProbability", probabilities.get("OK"));
    }

    private String displayClass(Object value, String valueType) {
        if (value == null) return "";
        if ("ORDINAL".equals(valueType) && value instanceof Number number) {
            var numeric = number.doubleValue();
            return numeric == Math.rint(numeric) ? Long.toString(Math.round(numeric)) + "H" : numeric + "H";
        }
        return String.valueOf(value);
    }

    private Map<String, Double> numericMetrics(ObjectNode node) {
        var result = new LinkedHashMap<String, Double>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNumber()) result.put(entry.getKey(), entry.getValue().doubleValue());
        });
        return result;
    }

    private TargetBaseline evaluateTarget(List<ResearchCaseView> rows, String targetKey) {
        var target = profile.target(targetKey).orElse(null);
        if (target == null) return new TargetBaseline(targetKey, "UNKNOWN", "UNSUPPORTED_TARGET",
                json.createObjectNode(), json.createArrayNode(), canonicalizer.hashText("unsupported:" + targetKey),
                List.of("TARGET_NOT_REGISTERED"));
        var eligible = rows.stream().filter(row -> {
            var decision = row.analysisRow().caseEligibilityByTarget().get(targetKey);
            var observation = row.analysisRow().targets().get(targetKey);
            return decision != null && decision.eligible() && observation != null
                    && "PARSED".equals(observation.status()) && !"LOWER_BOUND".equals(observation.observationType());
        }).toList();
        var predictions = json.createArrayNode();
        for (var groupMode : List.of("FORMULA_LINEAGE", "SOURCE_CONTEXT")) {
            for (var held : eligible) {
                var excluded = excludedKeys(held, groupMode);
                var neighbours = similar.find(eligible, similar.anchor(held), targetKey, Map.of(), excluded,
                        held.analysisRow().analysisRowId());
                var estimate = statistics.calculate(targetKey, neighbours).summary();
                if (!estimate.has("pointEstimate")) continue;
                var actual = held.analysisRow().targets().get(targetKey);
                var item = predictions.addObject().put("analysisRowId", held.analysisRow().analysisRowId())
                        .put("analysisRowContentHash", held.analysisRow().analysisRowContentHash())
                        .put("groupMode", groupMode).put("neighbourCount", neighbours.size())
                        .put("statisticsLevel", estimate.path("statisticsLevel").asText());
                if (actual.numericValue() != null) item.put("actual", actual.numericValue());
                else item.put("actualClass", actual.ordinalValue());
                item.set("predicted", estimate.path("pointEstimate").deepCopy());
                if (estimate.has("positiveRate")) item.set("positiveProbability", estimate.path("positiveRate").deepCopy());
            }
        }
        var metrics = metrics(target.valueType(), predictions);
        var sampleHash = canonicalizer.hash(predictions);
        var status = predictions.isEmpty() ? "INSUFFICIENT_DATA" : "READY";
        return new TargetBaseline(targetKey, target.valueType(), status, metrics, predictions, sampleHash,
                predictions.isEmpty() ? List.of("INSUFFICIENT_CASES_AFTER_LEAKAGE_GROUP_HOLDOUT") : List.of());
    }

    private Set<String> excludedKeys(ResearchCaseView row, String mode) {
        return row.analysisRow().leakageGroupKeys().stream().filter(key ->
                key.startsWith("EXPERIMENT_VERSION:") || key.startsWith("LOGICAL_SAMPLE:")
                        || ("FORMULA_LINEAGE".equals(mode) && key.startsWith("FORMULA_LINEAGE:"))
                        || ("SOURCE_CONTEXT".equals(mode) && key.startsWith("SOURCE_CONTEXT:")))
                .collect(Collectors.toUnmodifiableSet());
    }

    private ObjectNode metrics(String valueType, ArrayNode predictions) {
        var result = json.createObjectNode().put("predictionCount", predictions.size());
        if (predictions.isEmpty()) return result;
        if ("CONTINUOUS".equals(valueType)) continuous(result, predictions);
        else if ("ORDINAL".equals(valueType)) ordinal(result, predictions);
        else if ("BINARY".equals(valueType)) binary(result, predictions);
        return result;
    }

    private void continuous(ObjectNode result, ArrayNode values) {
        var actuals = new ArrayList<Double>();
        var absolute = new ArrayList<Double>();
        var squared = new ArrayList<Double>();
        values.forEach(item -> {
            if (!item.path("actual").isNumber() || !item.path("predicted").isNumber()) return;
            var actual = item.path("actual").asDouble();
            var predicted = item.path("predicted").asDouble();
            actuals.add(actual);
            absolute.add(Math.abs(actual - predicted));
            squared.add(Math.pow(actual - predicted, 2));
        });
        if (absolute.isEmpty()) return;
        var mae = absolute.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        var rmse = Math.sqrt(squared.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        actuals.sort(Double::compareTo);
        var p05 = quantile(actuals, 0.05);
        var p95 = quantile(actuals, 0.95);
        result.put("mae", mae).put("rmse", rmse)
                .put("nmae", p95 > p05 ? mae / (p95 - p05) : 0d)
                .put("normalization", "P95_MINUS_P05");
    }

    private void ordinal(ObjectNode result, ArrayNode values) {
        var distance = 0d;
        var exact = 0;
        var plusOne = 0;
        var count = 0;
        for (var item : values) {
            var actual = grade(item.path("actualClass").asText());
            var predicted = grade(item.path("predicted").asText());
            if (actual == null || predicted == null) continue;
            var delta = Math.abs(actual - predicted);
            distance += delta;
            if (delta == 0) exact++;
            if (delta <= 1) plusOne++;
            count++;
        }
        if (count > 0) result.put("gradeMae", distance / count).put("accuracy", exact / (double) count)
                .put("plusMinusOneAccuracy", plusOne / (double) count);
    }

    private void binary(ObjectNode result, ArrayNode values) {
        var brier = 0d;
        var logLoss = 0d;
        var correct = 0;
        var count = 0;
        for (var item : values) {
            if (!item.path("positiveProbability").isNumber()) continue;
            var actual = positive(item.path("actualClass").asText()) ? 1d : 0d;
            var probability = Math.max(1e-15, Math.min(1d - 1e-15, item.path("positiveProbability").asDouble()));
            brier += Math.pow(probability - actual, 2);
            logLoss += -(actual * Math.log(probability) + (1d - actual) * Math.log(1d - probability));
            if ((probability >= 0.5) == (actual == 1d)) correct++;
            count++;
        }
        if (count > 0) result.put("brierScore", brier / count).put("logLoss", logLoss / count)
                .put("accuracy", correct / (double) count);
    }

    private double quantile(List<Double> values, double q) {
        if (values.isEmpty()) return 0d;
        var index = (int) Math.floor(q * (values.size() - 1));
        return values.get(Math.max(0, Math.min(values.size() - 1, index)));
    }

    private Double grade(String value) {
        if (value == null) return null;
        var normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.equals("H")) return 1d;
        if (normalized.matches("[0-5]B") || normalized.matches("\\d+(?:\\.\\d+)?H")) {
            return Double.parseDouble(normalized.substring(0, normalized.length() - 1));
        }
        return null;
    }

    private boolean positive(String value) {
        return Set.of("OK", "PASS", "TRUE", "合格", "表干", "干爽").contains(value.toUpperCase(Locale.ROOT));
    }
}
