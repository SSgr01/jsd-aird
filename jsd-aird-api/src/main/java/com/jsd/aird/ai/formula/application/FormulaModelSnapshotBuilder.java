package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.AnalysisRow;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.StandardFact;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.TaskProfile;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.ValueType;
import com.jsd.aird.ai.formula.application.ResearchCaseLoader.ResearchCaseView;
import com.jsd.aird.ai.formula.infrastructure.ParquetArtifactEncoder;
import com.jsd.aird.ai.formula.infrastructure.ParquetArtifactEncoder.Column;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Builds the immutable REAL snapshot from the public T05 analysis contract. */
@Component
public class FormulaModelSnapshotBuilder {
    private final ResearchCaseLoader cases;
    private final ParquetArtifactEncoder parquet;
    private final JsonCanonicalizer canonicalizer;
    private final ObjectMapper json;

    public FormulaModelSnapshotBuilder(ResearchCaseLoader cases, ParquetArtifactEncoder parquet,
                                       JsonCanonicalizer canonicalizer, ObjectMapper json) {
        this.cases = cases;
        this.parquet = parquet;
        this.canonicalizer = canonicalizer;
        this.json = json;
    }

    public SnapshotBuild build(UUID snapshotId, UUID organizationId, UUID projectId, UUID categoryId,
                               TaskProfile profile, String taskProfileHash, long seed, Instant generatedAt) {
        var source = cases.load(profile.code(), projectId, categoryId).cases().stream()
                .filter(item -> profile.targets().stream().anyMatch(target -> eligible(item.analysisRow(), target.targetKey())))
                .sorted(java.util.Comparator.comparing(item -> item.analysisRow().analysisRowId()))
                .toList();
        if (source.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "没有可进入正式模型快照的实验结果");

        var measurementColumns = measurementColumns(profile);
        var sourceColumns = sourceColumns(profile);
        var measurementRows = new ArrayList<Map<String, Object>>();
        var sourceRows = new ArrayList<Map<String, Object>>();
        var targetCounts = new LinkedHashMap<String, Integer>();
        for (var view : source) {
            measurementRows.add(measurement(view.analysisRow(), profile, targetCounts));
            sourceRows.add(source(view, organizationId, profile));
        }
        var measurements = parquet.encode("FormulaMeasurementsV11", measurementColumns, measurementRows);
        var sourceMap = parquet.encode("FormulaSourceMapV11", sourceColumns, sourceRows);
        var measurementsHash = FormulaModelArtifactStore.sha256(measurements);
        var sourceMapHash = FormulaModelArtifactStore.sha256(sourceMap);
        var manifest = manifest(snapshotId, profile, taskProfileHash, source.size(), targetCounts,
                measurementColumns, measurementsHash, sourceMapHash, seed, generatedAt);
        byte[] manifestBytes;
        try {
            manifestBytes = json.writeValueAsBytes(canonicalizer.canonicalize(manifest));
        } catch (Exception exception) {
            throw new IllegalStateException("模型快照清单无法序列化", exception);
        }
        var manifestHash = FormulaModelArtifactStore.sha256(manifestBytes);
        var snapshotBasis = json.createObjectNode()
                .put("manifest.json", manifestHash)
                .put("measurements.parquet", measurementsHash)
                .put("source-map.parquet", sourceMapHash);
        return new SnapshotBuild(snapshotId, source.size(), Map.copyOf(targetCounts), List.copyOf(source),
                measurements, measurementsHash, sourceMap, sourceMapHash, manifestBytes, manifestHash,
                canonicalizer.hash(snapshotBasis));
    }

    private List<Column> measurementColumns(TaskProfile profile) {
        var result = new ArrayList<Column>();
        result.add(Column.string("analysis_row_id"));
        profile.formula().materials().forEach(item -> result.add(Column.number(item.column())));
        profile.contextFeatures().forEach(item -> result.add(item.valueType().name().equals("NUMERIC")
                ? Column.number(item.column()) : Column.string(item.column())));
        profile.targets().forEach(item -> result.add(item.valueType() == ValueType.BINARY
                || item.valueType() == ValueType.CATEGORICAL
                ? Column.nullableString(item.code()) : Column.nullableNumber(item.code())));
        return List.copyOf(result);
    }

    private List<Column> sourceColumns(TaskProfile profile) {
        return List.of(Column.string("analysis_row_id"), Column.string("experiment_version_id"),
                Column.string("experiment_id"), Column.string("project_id"), Column.string("organization_id"),
                Column.string(profile.validation().groupColumn()), Column.string("main_resin_code"),
                Column.string("source_file"), Column.string(profile.validation().sourceGroupColumn()),
                Column.string("source_range"), Column.string("content_hash"), Column.string("permission_scope"),
                Column.string("data_nature"), Column.string("snapshot_purpose"));
    }

    private Map<String, Object> measurement(AnalysisRow row, TaskProfile profile, Map<String, Integer> counts) {
        var result = new LinkedHashMap<String, Object>();
        result.put("analysis_row_id", row.analysisRowId());
        var formula = new TreeMap<String, BigDecimal>();
        row.formula().forEach(item -> {
            if (item.materialCode() != null && item.ratioPercent() != null) {
                formula.merge(item.materialCode(), item.ratioPercent(), BigDecimal::add);
            }
        });
        var sum = BigDecimal.ZERO;
        for (var material : profile.formula().materials()) {
            var value = formula.get(material.code());
            if (value == null) throw invalid(row, "模型可用分析行缺少材料向量：" + material.code());
            result.put(material.column(), value.doubleValue());
            sum = sum.add(value);
        }
        if (sum.subtract(BigDecimal.valueOf(profile.formula().total())).abs()
                .compareTo(BigDecimal.valueOf(profile.formula().sumTolerance())) > 0) {
            throw invalid(row, "T05规范化分析配方未通过模型向量总量技术复核");
        }
        for (var feature : profile.contextFeatures()) {
            var fact = row.process().get(feature.code());
            if (fact == null) fact = row.context().get(feature.code());
            if (fact == null) throw invalid(row, "模型可用分析行缺少特征：" + feature.code());
            result.put(feature.column(), feature.valueType().name().equals("NUMERIC")
                    ? numeric(fact, row, feature.code()) : text(fact, row, feature.code()));
        }
        for (var target : profile.targets()) {
            Object value = null;
            if (eligible(row, target.targetKey())) {
                var observation = row.targets().get(target.targetKey());
                if (observation == null || "LOWER_BOUND".equals(observation.observationType())) {
                    throw invalid(row, "模型资格与目标观测契约不一致：" + target.targetKey());
                }
                value = switch (target.valueType()) {
                    case CONTINUOUS, CENSORED_COUNT -> observation.numericValue() == null
                            ? null : observation.numericValue().doubleValue();
                    case ORDINAL -> grade(observation.ordinalValue());
                    case BINARY, CATEGORICAL -> observation.ordinalValue();
                };
                if (value == null) throw invalid(row, "模型可用目标没有标准值：" + target.targetKey());
                counts.merge(target.targetKey(), 1, Integer::sum);
            }
            result.put(target.code(), value);
        }
        return result;
    }

    private Map<String, Object> source(ResearchCaseView view, UUID organizationId, TaskProfile profile) {
        var row = view.analysisRow();
        var result = new LinkedHashMap<String, Object>();
        result.put("analysis_row_id", row.analysisRowId());
        result.put("experiment_version_id", row.experimentVersionId().toString());
        result.put("experiment_id", row.experimentId().toString());
        result.put("project_id", view.projectId() == null ? "" : view.projectId().toString());
        result.put("organization_id", organizationId.toString());
        result.put(profile.validation().groupColumn(), required(row.formulaLineageGroup(), row, "formulaLineageGroup"));
        result.put("main_resin_code", mainResin(row, profile));
        result.put("source_file", "experiment:" + row.experimentId());
        result.put(profile.validation().sourceGroupColumn(), row.sourceContextKey() == null || row.sourceContextKey().isBlank()
                ? "EXPERIMENT_VERSION:" + row.experimentVersionId() : row.sourceContextKey());
        result.put("source_range", required(row.sourceGroupKey(), row, "sourceGroupKey"));
        result.put("content_hash", required(row.analysisRowContentHash(), row, "analysisRowContentHash"));
        result.put("permission_scope", "organization:" + organizationId);
        result.put("data_nature", "REAL");
        result.put("snapshot_purpose", "TRAINING");
        return result;
    }

    private ObjectNode manifest(UUID snapshotId, TaskProfile profile, String taskProfileHash, int rowCount,
                                Map<String, Integer> targetCounts, List<Column> columns,
                                String measurementsHash, String sourceMapHash, long seed, Instant generatedAt) {
        var root = json.createObjectNode().put("snapshot_schema_version", "1.1")
                .put("snapshot_id", snapshotId.toString()).put("task_profile_code", profile.code())
                .put("task_profile_version", profile.version()).put("task_profile_hash", taskProfileHash)
                .put("snapshot_purpose", "TRAINING").put("data_nature", "REAL")
                .put("production_eligible", true).put("seed", seed)
                .put("generated_at_utc", generatedAt.toString());
        root.putObject("generator").put("name", "jsd-aird-t05-analysis-snapshot")
                .put("version", "1.1").put("row_count", rowCount);
        var featureSchema = root.putArray("feature_schema");
        columns.forEach(column -> featureSchema.addObject().put("name", column.name())
                .put("role", column.name().equals("analysis_row_id") ? "IDENTITY"
                        : column.name().startsWith("Y__") ? "TARGET" : "FEATURE")
                .put("type", column.type().name()));
        var targets = root.putObject("targets");
        profile.targets().forEach(target -> {
            var node = targets.putObject(target.code()).put("target_key", target.targetKey())
                    .put("value_type", manifestType(target.valueType())).put("direction", target.direction().name())
                    .put("valid_count", targetCounts.getOrDefault(target.targetKey(), 0));
            if (target.unit() == null) node.putNull("unit"); else node.put("unit", target.unit());
        });
        var artifacts = root.putObject("artifacts");
        artifacts.putObject("measurements.parquet").put("sha256", measurementsHash).put("rows", rowCount)
                .put("columns", columns.size()).put("format", "PARQUET").put("writer", "parquet-avro");
        artifacts.putObject("source-map.parquet").put("sha256", sourceMapHash).put("rows", rowCount)
                .put("columns", sourceColumns(profile).size()).put("format", "PARQUET").put("writer", "parquet-avro");
        return root;
    }

    private boolean eligible(AnalysisRow row, String targetKey) {
        var decision = row.modelEligibilityByTarget().get(targetKey);
        return decision != null && decision.eligible();
    }

    private double numeric(StandardFact fact, AnalysisRow row, String code) {
        if (!"PARSED".equals(fact.status()) || fact.numericValue() == null) throw invalid(row, "数值特征不可用：" + code);
        return fact.numericValue().doubleValue();
    }

    private String text(StandardFact fact, AnalysisRow row, String code) {
        if (!"PARSED".equals(fact.status()) || fact.textValue() == null || fact.textValue().isBlank()) {
            throw invalid(row, "分类特征不可用：" + code);
        }
        return fact.textValue();
    }

    private String mainResin(AnalysisRow row, TaskProfile profile) {
        return row.formula().stream().filter(item -> item.ratioPercent() != null && item.ratioPercent().signum() > 0
                        && profile.formula().mainResinCodes().contains(item.materialCode()))
                .map(item -> item.materialCode()).findFirst().orElseThrow(() -> invalid(row, "未找到唯一主树脂"));
    }

    private Double grade(String value) {
        if (value == null) return null;
        var normalized = value.strip().toUpperCase(Locale.ROOT);
        if ("H".equals(normalized)) return 1d;
        if (normalized.matches("[0-5]B") || normalized.matches("\\d+(?:\\.\\d+)?H")) {
            return Double.parseDouble(normalized.substring(0, normalized.length() - 1));
        }
        return null;
    }

    private String required(String value, AnalysisRow row, String name) {
        if (value == null || value.isBlank()) throw invalid(row, "缺少来源字段：" + name);
        return value;
    }

    private ApiException invalid(AnalysisRow row, String message) {
        return new ApiException(ApiErrorCode.INVALID_SCHEMA, message + " [analysisRowId=" + row.analysisRowId() + "]");
    }

    private String manifestType(ValueType type) {
        return switch (type) {
            case CONTINUOUS -> "CONT";
            case ORDINAL -> "ORD";
            case BINARY -> "BINARY";
            case CATEGORICAL -> "CATEGORICAL";
            case CENSORED_COUNT -> "CENSORED_COUNT";
        };
    }

    public record SnapshotBuild(UUID snapshotId, int rowCount, Map<String, Integer> targetCounts,
                                List<ResearchCaseView> cases, byte[] measurements, String measurementsHash,
                                byte[] sourceMap, String sourceMapHash, byte[] manifest, String manifestHash,
                                String snapshotHash) {
        public JsonNode targetSummary(ObjectMapper json) { return json.valueToTree(targetCounts); }
    }
}
