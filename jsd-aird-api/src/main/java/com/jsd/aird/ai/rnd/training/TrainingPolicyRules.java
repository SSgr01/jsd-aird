package com.jsd.aird.ai.rnd.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.rnd.modeling.ModelingContracts.ValidationIssue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structural policy gate. Scientific values are supplied by administrators, never by code defaults. */
public final class TrainingPolicyRules {
    private static final Set<String> DATA_NATURE = Set.of("REAL", "SYNTHETIC");
    private static final Set<String> ALGORITHMS = Set.of("GAUSSIAN_PROCESS", "RANDOM_FOREST", "LIGHTGBM",
            "XGBOOST", "CATBOOST", "LOGISTIC_REGRESSION", "ORDINAL_CUMULATIVE_LOGIT");
    private TrainingPolicyRules() { }

    public static List<ValidationIssue> validate(String valueType, JsonNode qualification,
                                                 JsonNode validation, JsonNode training) {
        var issues = new ArrayList<ValidationIssue>();
        positive(qualification, "minimumTrainableSamples", issues);
        positive(qualification, "minimumIndependentLineages", issues);
        positive(qualification, "minimumSourceGroups", issues);
        if (!"CONTINUOUS".equals(valueType)) positive(qualification, "minimumPerClass", issues);
        range(validation, "foldCount", 2, 10, issues);
        text(validation, "primaryMetric", issues);
        number(validation, "metricThreshold", issues);
        bool(validation, "requireFairComparison", issues);
        bool(training, "autoTrainingEnabled", issues);
        text(training, "dataNature", issues);
        if (training.hasNonNull("dataNature") && !DATA_NATURE.contains(training.path("dataNature").asText()))
            issues.add(issue("training.dataNature", "数据性质只能选择真实数据或测试数据"));
        nonNegative(training, "retrainMinimumNewSamples", issues);
        nonNegative(training, "minimumIntervalHours", issues);
        positive(training, "seed", issues);
        positive(training, "timeoutMinutes", issues);
        text(training, "replicateHandling", issues);
        var allowedReplicates = switch (valueType == null ? "" : valueType) {
            case "CONTINUOUS" -> Set.of("KEEP_GROUPED", "MEAN", "MEDIAN");
            case "ORDINAL" -> Set.of("KEEP_GROUPED", "MEDIAN_GRADE");
            case "BINARY", "CATEGORICAL" -> Set.of("KEEP_GROUPED", "MAJORITY", "CONSENSUS_ONLY");
            default -> Set.<String>of();
        };
        if (training.hasNonNull("replicateHandling")
                && !allowedReplicates.contains(training.path("replicateHandling").asText()))
            issues.add(issue("training.replicateHandling", "重复测量处理方式不适用于当前结果类型"));
        if (!training.path("candidateAlgorithms").isArray() || training.path("candidateAlgorithms").isEmpty()) {
            issues.add(issue("training.candidateAlgorithms", "至少选择一种候选算法"));
        } else {
            var seen = new HashSet<String>();
            for (var algorithm : training.path("candidateAlgorithms")) {
                var value = algorithm.asText();
                if (!ALGORITHMS.contains(value)) issues.add(issue("training.candidateAlgorithms", "包含不支持的算法：" + value));
                if (!seen.add(value)) issues.add(issue("training.candidateAlgorithms", "候选算法不能重复"));
                if ("CONTINUOUS".equals(valueType) && Set.of("LOGISTIC_REGRESSION", "ORDINAL_CUMULATIVE_LOGIT").contains(value))
                    issues.add(issue("training.candidateAlgorithms", "连续值不能使用分类算法"));
                if (!"CONTINUOUS".equals(valueType) && "GAUSSIAN_PROCESS".equals(value))
                    issues.add(issue("training.candidateAlgorithms", "离散结果不能使用高斯过程回归"));
                if (!"ORDINAL".equals(valueType) && "ORDINAL_CUMULATIVE_LOGIT".equals(value))
                    issues.add(issue("training.candidateAlgorithms", "累计Logit只适用于序数等级"));
            }
        }
        return List.copyOf(issues);
    }

    private static void positive(JsonNode n,String key,List<ValidationIssue> out){number(n,key,out);if(n.has(key)&&n.path(key).asDouble()<=0)out.add(issue(key,"必须大于0"));}
    private static void nonNegative(JsonNode n,String key,List<ValidationIssue> out){number(n,key,out);if(n.has(key)&&n.path(key).asDouble()<0)out.add(issue(key,"不能小于0"));}
    private static void range(JsonNode n,String key,double min,double max,List<ValidationIssue> out){number(n,key,out);if(n.has(key)&&(n.path(key).asDouble()<min||n.path(key).asDouble()>max))out.add(issue(key,"必须在"+min+"到"+max+"之间"));}
    private static void number(JsonNode n,String key,List<ValidationIssue> out){if(n==null||!n.has(key)||!n.path(key).isNumber())out.add(issue(key,"必须填写数值"));}
    private static void text(JsonNode n,String key,List<ValidationIssue> out){if(n==null||n.path(key).asText("").isBlank())out.add(issue(key,"必须选择或填写"));}
    private static void bool(JsonNode n,String key,List<ValidationIssue> out){if(n==null||!n.has(key)||!n.path(key).isBoolean())out.add(issue(key,"必须明确选择"));}
    private static ValidationIssue issue(String field,String message){return new ValidationIssue("TRAINING_POLICY_FIELD_REQUIRED",field,message);}
}
