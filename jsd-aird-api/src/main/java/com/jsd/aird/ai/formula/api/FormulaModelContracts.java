package com.jsd.aird.ai.formula.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Java mirror of the language-neutral {@code formula-model.v1} contract.
 *
 * <p>T07-A only freezes transport types. Controllers, persistence and the RND adapter
 * are intentionally deferred until T05/T06 are merged.</p>
 */
public final class FormulaModelContracts {

    public static final String CONTRACT_VERSION = "formula-model.v1";

    private FormulaModelContracts() {
    }

    public enum ValueType { CONTINUOUS, ORDINAL, BINARY, CATEGORICAL, CENSORED_COUNT }

    public enum ModelType {
        LOGISTIC_REGRESSION,
        GAUSSIAN_PROCESS,
        RANDOM_FOREST,
        LIGHTGBM,
        XGBOOST,
        CATBOOST,
        ORDINAL_CUMULATIVE_LOGIT
    }

    public enum BaselineType { DEVELOPMENT_KNN, T06_SIMILAR_CASE }

    public enum DomainStatus { IN_DOMAIN, NEAR_BOUNDARY, OUT_OF_DOMAIN }

    public enum Direction { MINIMIZE, MAXIMIZE }

    public enum FeatureType { NUMERIC, CATEGORICAL }

    public enum TargetMode { AT_LEAST, AT_MOST, MATCH, RANGE, MAXIMIZE, MINIMIZE }

    public enum RecommendationMode { FORMULA_PREDICTION, EXPERIMENT_OPTIMIZATION }

    public record ArtifactReadRef(String name, String url, String sha256) {
    }

    public record ArtifactWriteRef(String url, String contentType) {
    }

    public record SnapshotArtifacts(
            ArtifactReadRef manifest,
            ArtifactReadRef measurements,
            ArtifactReadRef sourceMap
    ) {
    }

    public record ValidationFoldAssignment(
            String analysisRowId,
            String targetKey,
            int foldIndex,
            String groupKey
    ) {
    }

    public record ValidationFoldScheme(
            String validationScheme,
            int foldCount,
            List<ValidationFoldAssignment> assignments
    ) {
    }

    public record ValidationFoldsDocument(
            String schemaVersion,
            String snapshotHash,
            String taskProfileHash,
            long seed,
            List<ValidationFoldScheme> schemes,
            String contentHash
    ) {
    }

    public record T06BaselineObservation(
            String analysisRowId,
            String targetKey,
            String validationScheme,
            int foldIndex,
            String groupKey,
            Object actual,
            Object predicted,
            Map<String, Double> classProbabilities,
            int eligibleNeighborCount,
            String sampleHash
    ) {
    }

    public record T06TargetBaseline(
            String targetKey,
            String targetCode,
            ValueType valueType,
            Map<String, Map<String, Double>> metricsByScheme,
            List<T06BaselineObservation> observations,
            String sampleHash
    ) {
    }

    public record T06BaselineDocument(
            String schemaVersion,
            String baselineType,
            String baselineVersion,
            String snapshotHash,
            String taskProfileHash,
            String validationFoldsHash,
            List<T06TargetBaseline> targets,
            String contentHash
    ) {
    }

    public record MaterialSpec(
            String code,
            String column,
            String role,
            double minimum,
            double maximum,
            double step
    ) {
    }

    public record FormulaSpec(
            String basis,
            double total,
            double sumTolerance,
            String balanceMaterialCode,
            List<String> mainResinCodes,
            List<MaterialSpec> materials
    ) {
    }

    public record ContextFeatureSpec(
            String code,
            String column,
            FeatureType valueType,
            boolean adjustable,
            boolean sharedWithinSourceGroup,
            String unit
    ) {
    }

    public record TargetSpec(
            String code,
            String targetKey,
            ValueType valueType,
            Direction direction,
            String unit,
            String testMethod,
            String substrate,
            boolean mandatory,
            double weight,
            List<Double> ordinalValues,
            List<String> ordinalLabels,
            List<String> classLabels,
            String positiveClass,
            Double decisionThreshold,
            String censoredColumn,
            String censorTypeColumn
    ) {
        public TargetSpec {
            // Mirrors the language-neutral contract default. A nullable wrapper
            // distinguishes an omitted JSON property from an explicit invalid 0.
            if (decisionThreshold == null) decisionThreshold = 0.50d;
        }
    }

    public record ValidationPolicy(
            String groupColumn,
            String sourceGroupColumn,
            int folds,
            double intervalLevel,
            boolean randomKfoldDiagnostic
    ) {
    }

    public record ApplicabilityDomainPolicy(
            boolean enabled,
            double robustLowerQuantile,
            double robustUpperQuantile,
            double nearBoundaryDistanceQuantile,
            double outOfDomainDistanceQuantile,
            boolean outOfDomainRequiresFallback
    ) {
    }

    public record DevelopmentReadinessThreshold(int minSamples, int minGroups) {
    }

    public record ContinuousReadinessThreshold(
            int minSamples,
            int minGroups,
            double minBaselineImprovement,
            double maxNmae,
            double minIntervalCoverage,
            double maxIntervalCoverage,
            BaselineType requiredBaselineType
    ) {
    }

    public record OrdinalReadinessThreshold(
            int minSamples,
            int minSamplesPerObservedClass,
            int minGroups,
            double maxGradeMae,
            double minPlusMinusOneAccuracy
    ) {
    }

    public record BinaryReadinessThreshold(
            int minSamples,
            int minMinorityClassSamples,
            int minGroups,
            double minRocAuc,
            double minPrAuc,
            double minF1,
            double maxLogLoss,
            double maxBrierScore
    ) {
    }

    public record CategoricalReadinessThreshold(
            int minSamples,
            int minSamplesPerObservedClass,
            int minObservedClasses,
            int minGroups,
            double minMacroF1,
            double minBalancedAccuracy,
            double maxLogLoss
    ) {
    }

    public record ReadinessThresholdProfile(
            DevelopmentReadinessThreshold development,
            ContinuousReadinessThreshold productionContinuous,
            OrdinalReadinessThreshold productionOrdinal,
            BinaryReadinessThreshold productionBinary,
            CategoricalReadinessThreshold productionCategorical
    ) {
    }

    public record ModelEligibilityRule(int minSamples) {
    }

    public record ModelSelectionPolicy(
            double tieNmaeTolerance,
            List<ModelType> modelTieBreakOrder
    ) {
    }

    public record ClassificationModelSelectionPolicy(
            double tieScoreTolerance,
            List<ModelType> binaryTieBreakOrder,
            List<ModelType> categoricalTieBreakOrder
    ) {
    }

    public record ProbabilityCalibrationPolicy(
            String method,
            int calibrationFolds,
            int expectedCalibrationErrorBins
    ) {
    }

    public record CandidatePolicy(
            int maximumPoolSize,
            double minimumL1Distance,
            double conservativeMaximumL1Distance,
            int defaultCount
    ) {
    }

    public record TaskProfile(
            String contractVersion,
            String code,
            String version,
            String schemaHash,
            String ruleVersion,
            FormulaSpec formula,
            List<ContextFeatureSpec> contextFeatures,
            List<TargetSpec> targets,
            ValidationPolicy validation,
            ReadinessThresholdProfile readinessThresholds,
            Map<String, ModelEligibilityRule> modelEligibility,
            ModelSelectionPolicy modelSelection,
            Map<String, ModelEligibilityRule> classificationModelEligibility,
            ClassificationModelSelectionPolicy classificationModelSelection,
            ProbabilityCalibrationPolicy probabilityCalibration,
            ApplicabilityDomainPolicy applicabilityDomain,
            CandidatePolicy candidate
    ) {
    }

    public record ValidateSnapshotRequest(
            String contractVersion,
            String requestId,
            String taskProfileHash,
            String snapshotHash,
            long seed,
            TaskProfile taskProfile,
            SnapshotArtifacts snapshot
    ) {
    }

    public record GenerateValidationFoldsRequest(
            String contractVersion,
            String requestId,
            String taskProfileHash,
            String snapshotHash,
            long seed,
            TaskProfile taskProfile,
            SnapshotArtifacts snapshot,
            ArtifactWriteRef output
    ) {
    }

    public record ValidationFoldTargetSummary(
            String targetKey,
            int eligibleRows,
            int formulaLineageFolds,
            int sourceContextFolds
    ) {
    }

    public record GenerateValidationFoldsResponse(
            String contractVersion,
            String requestId,
            String snapshotHash,
            String validationFoldsSha256,
            long validationFoldsSize,
            boolean uploaded,
            List<ValidationFoldTargetSummary> targets
    ) {
    }

    public record TargetCoverage(
            String targetCode,
            ValueType valueType,
            int validRows,
            int missingRows,
            int groups,
            int sourceGroups,
            String status
    ) {
    }

    public record SourceSheetStats(
            int totalSheets,
            int sheetsWithMultipleLineages,
            int maxLineagesPerSheet
    ) {
    }

    public record SnapshotValidationResponse(
            String contractVersion,
            String requestId,
            String snapshotId,
            String snapshotHash,
            boolean valid,
            boolean productionEligible,
            int rows,
            int groups,
            int sourceSheets,
            SourceSheetStats sourceSheetStats,
            String dataNature,
            String snapshotPurpose,
            List<TargetCoverage> targets,
            List<String> errors,
            List<String> warnings
    ) {
    }

    public record TrainRequest(
            String contractVersion,
            String requestId,
            String taskProfileHash,
            String snapshotHash,
            long seed,
            TaskProfile taskProfile,
            SnapshotArtifacts snapshot,
            ArtifactWriteRef output,
            ArtifactReadRef validationFolds,
            ArtifactReadRef t06Baseline,
            JsonNode featureView
    ) {
        public TrainRequest(String contractVersion, String requestId, String taskProfileHash,
                            String snapshotHash, long seed, TaskProfile taskProfile,
                            SnapshotArtifacts snapshot, ArtifactWriteRef output,
                            ArtifactReadRef validationFolds, ArtifactReadRef t06Baseline) {
            this(contractVersion, requestId, taskProfileHash, snapshotHash, seed, taskProfile,
                    snapshot, output, validationFolds, t06Baseline, null);
        }
    }

    public record MetricSet(
            double mae,
            double rmse,
            double nmae,
            double r2,
            double spearman,
            double intervalCoverage,
            double meanIntervalWidth,
            Double rawIntervalCoverage,
            Double rawMeanIntervalWidth,
            double calibratedRadius,
            String calibrationSampleSource,
            double evaluationRadiusMean,
            double evaluationRadiusMax
    ) {
    }

    public record OrdinalMetricSet(
            double gradeMae,
            double plusMinusOneAccuracy,
            double spearman,
            List<List<Integer>> confusionMatrix,
            double intervalCoverage,
            double meanIntervalWidth
    ) {
    }

    public record ProbabilityQuality(
            double logLoss,
            Double brierScore,
            double expectedCalibrationError
    ) {
    }

    public record PerClassMetric(
            double precision,
            double recall,
            double f1,
            int support
    ) {
    }

    public record BinaryMetricSet(
            double rocAuc,
            double prAuc,
            double precision,
            double recall,
            double f1,
            String minorityClass,
            double minorityClassRecall,
            List<List<Integer>> confusionMatrix,
            ProbabilityQuality rawProbabilityQuality,
            ProbabilityQuality calibratedProbabilityQuality,
            double decisionThreshold
    ) {
    }

    public record CategoricalMetricSet(
            double macroF1,
            double weightedF1,
            double balancedAccuracy,
            Map<String, PerClassMetric> perClass,
            List<List<Integer>> confusionMatrix,
            ProbabilityQuality rawProbabilityQuality,
            ProbabilityQuality calibratedProbabilityQuality
    ) {
    }

    public record AlgorithmMetadata(
            ModelType modelType,
            String library,
            String libraryVersion,
            Map<String, Object> finalParams,
            long seed
    ) {
    }

    public record CandidateModelMetric(
            ModelType modelType,
            String status,
            Object lineageCv,
            Object sheetCv,
            MetricSet randomKfoldDiagnostic,
            Map<String, Object> finalParams,
            double trainingTimeSeconds,
            double predictionTimeSeconds,
            String failureReason,
            AlgorithmMetadata algorithm,
            List<Double> lineageFoldNmae,
            List<Double> sheetFoldNmae,
            List<Double> lineageFoldScores,
            List<Double> sheetFoldScores
    ) {
    }

    public record BaselineMetrics(double lineageNmae, double sheetNmae) {
    }

    public record BaselineSummary(
            BaselineType type,
            String version,
            BaselineMetrics metrics
    ) {
    }

    public record ModelSelectionSummary(
            List<ModelType> eligibleModels,
            List<ModelType> skippedModels,
            List<ModelType> failedModels,
            List<CandidateModelMetric> candidateMetrics,
            ModelType championModel,
            String selectionReason
    ) {
    }

    public record UncertaintySummary(
            String method,
            Double rawCoverage,
            double calibratedCoverage,
            double calibratedRadius
    ) {
    }

    public record TargetReadiness(
            ReadinessThresholdProfile thresholdProfile,
            Map<String, Boolean> checks,
            List<String> blockingReasons
    ) {
    }

    public record ValidationManifest(
            int effectiveRowCount,
            String effectiveRowIdsSha256,
            String lineageFoldsSha256,
            String sheetFoldsSha256,
            List<Integer> lineageFoldSizes,
            List<Integer> sheetFoldSizes
    ) {
    }

    public record GroupStabilitySummary(
            double foldNmaeMean,
            double foldNmaeStd,
            double foldNmaeMin,
            double foldNmaeMax
    ) {
    }

    public record StabilitySummary(
            String method,
            GroupStabilitySummary lineageCv,
            GroupStabilitySummary sheetCv
    ) {
    }

    public record ApplicabilityDomainSummary(
            String method,
            int referenceRows,
            double nearBoundaryThreshold,
            double outOfDomainThreshold,
            List<String> numericFeatures,
            List<String> categoricalFeatures
    ) {
    }

    public record OrdinalTrainingSummary(
            List<String> orderedClasses,
            List<String> observedClasses,
            OrdinalMetricSet lineageCv,
            OrdinalMetricSet sheetCv,
            boolean classProbabilitiesSupported,
            AlgorithmMetadata algorithm,
            double trainingTimeSeconds,
            double predictionTimeSeconds
    ) {
    }

    public record ClassificationTrainingSummary(
            ValueType targetType,
            List<String> configuredClasses,
            List<String> observedClasses,
            String positiveClass,
            Double decisionThreshold,
            String calibrationMethod,
            String calibrationSampleSource,
            Object lineageCv,
            Object sheetCv,
            boolean classProbabilitiesSupported
    ) {
    }

    public record TargetTrainingResult(
            String targetCode,
            ValueType targetType,
            String status,
            ModelType scorerType,
            MetricSet metrics,
            Object lineageCv,
            Object sheetCv,
            Double baselineNmae,
            Double baselineImprovement,
            Double sourceGroupAuditNmae,
            BaselineSummary baseline,
            ModelSelectionSummary modelSelection,
            UncertaintySummary uncertainty,
            OrdinalTrainingSummary ordinal,
            ClassificationTrainingSummary classification,
            TargetReadiness readiness,
            ValidationManifest validationManifest,
            StabilitySummary stability,
            ApplicabilityDomainSummary applicabilityDomain,
            int validRows,
            int groups,
            int sourceGroups,
            boolean developmentEligible,
            boolean productionEligible,
            List<String> reasons
    ) {
    }

    public record TrainResponse(
            String contractVersion,
            String requestId,
            String snapshotId,
            String snapshotHash,
            String status,
            String modelBundleSha256,
            long modelBundleSize,
            boolean uploaded,
            List<TargetTrainingResult> targets,
            List<String> warnings
    ) {
    }

    public record ModelBundleRef(String url, String sha256) {
    }

    public record ScoreRow(
            String rowId,
            Map<String, Double> formula,
            Map<String, Object> context
    ) {
    }

    public record ScoreRequest(
            String contractVersion,
            String requestId,
            String taskProfileHash,
            String snapshotHash,
            long seed,
            TaskProfile taskProfile,
            String modelBundleHash,
            ModelBundleRef modelBundle,
            List<ScoreRow> rows,
            List<String> targetCodes
    ) {
    }

    public record RequestedTarget(
            String code,
            TargetMode mode,
            boolean mandatory,
            double weight,
            Double value,
            Double minimum,
            Double maximum,
            Double tolerance,
            Double minimumProbability
    ) {
        public RequestedTarget(String code, TargetMode mode, boolean mandatory, double weight,
                               Double value, Double minimum, Double maximum, Double tolerance) {
            this(code, mode, mandatory, weight, value, minimum, maximum, tolerance, null);
        }
    }

    public record MaterialConstraint(
            String materialCode,
            Double minimum,
            Double maximum,
            Double fixed
    ) {
    }

    public record RecommendRequest(
            String contractVersion,
            String requestId,
            String taskProfileHash,
            String snapshotHash,
            long seed,
            TaskProfile taskProfile,
            String modelBundleHash,
            ModelBundleRef modelBundle,
            Map<String, Double> baselineFormula,
            Map<String, Object> context,
            List<RequestedTarget> targets,
            List<MaterialConstraint> materialConstraints,
            int count,
            RecommendationMode recommendationMode,
            double minimumPotentialDesirability
    ) {
    }

    public record Prediction(
            String targetCode,
            double expected,
            double lower,
            double upper,
            String unit,
            ModelType scorerType,
            String intervalMethod,
            ApplicabilityDomainEvidence applicabilityDomain
    ) {
    }

    public record OrdinalPrediction(
            String targetCode,
            String predictedClass,
            Map<String, Double> classProbabilities,
            String lowerClass90,
            String upperClass90,
            ModelType scorerType,
            ApplicabilityDomainEvidence applicabilityDomain
    ) {
    }

    public record ClassificationPrediction(
            String targetCode,
            ValueType targetType,
            String predictedClass,
            Map<String, Double> classProbabilities,
            Map<String, Double> rawClassProbabilities,
            double confidence,
            String positiveClass,
            Double rawPositiveProbability,
            Double calibratedPositiveProbability,
            Double decisionThreshold,
            String calibrationMethod,
            ModelType scorerType,
            ApplicabilityDomainEvidence applicabilityDomain
    ) {
    }

    public record ApplicabilityDomainEvidence(
            DomainStatus status,
            boolean modelUsable,
            String nearestTrainingRowId,
            double nearestDistance,
            double nearBoundaryThreshold,
            double outOfDomainThreshold,
            List<String> unknownCategories,
            List<String> outsideObservedRange,
            List<String> nearBoundaryFeatures,
            List<String> reasons
    ) {
    }

    public record ScoredRow(
            String rowId,
            List<Prediction> predictions,
            List<OrdinalPrediction> ordinalPredictions,
            List<ClassificationPrediction> classificationPredictions,
            DomainStatus domainStatus,
            boolean modelUsable,
            List<String> fallbackReasons
    ) {
    }

    public record ScoreResponse(
            String contractVersion,
            String requestId,
            String modelBundleSha256,
            List<ScoredRow> rows,
            List<String> unsupportedTargets
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecommendedCandidate(
            String candidateId,
            String strategy,
            Map<String, Double> formula,
            List<Prediction> predictions,
            double desirability,
            double l1DistanceFromBaseline,
            List<String> reasons
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecommendResponse(
            String contractVersion,
            String requestId,
            String status,
            String modelBundleSha256,
            List<RecommendedCandidate> candidates,
            List<String> missingStrategies,
            List<String> unsupportedTargets,
            List<String> warnings
    ) {
    }

    public record ErrorResponse(
            String contractVersion,
            String requestId,
            String code,
            String message,
            boolean retryable,
            Map<String, Object> details
    ) {
    }
}
