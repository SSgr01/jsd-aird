from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Any, Literal

from pydantic import AnyUrl, BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


CONTRACT_VERSION = "formula-model.v1"


class ContractModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        use_enum_values=True,
    )


class ValueType(StrEnum):
    CONTINUOUS = "CONTINUOUS"
    ORDINAL = "ORDINAL"
    BINARY = "BINARY"
    CATEGORICAL = "CATEGORICAL"
    CENSORED_COUNT = "CENSORED_COUNT"


class ModelType(StrEnum):
    LOGISTIC_REGRESSION = "LOGISTIC_REGRESSION"
    GAUSSIAN_PROCESS = "GAUSSIAN_PROCESS"
    RANDOM_FOREST = "RANDOM_FOREST"
    LIGHTGBM = "LIGHTGBM"
    XGBOOST = "XGBOOST"
    CATBOOST = "CATBOOST"
    ORDINAL_CUMULATIVE_LOGIT = "ORDINAL_CUMULATIVE_LOGIT"


class BaselineType(StrEnum):
    DEVELOPMENT_KNN = "DEVELOPMENT_KNN"
    T06_SIMILAR_CASE = "T06_SIMILAR_CASE"


class DomainStatus(StrEnum):
    IN_DOMAIN = "IN_DOMAIN"
    NEAR_BOUNDARY = "NEAR_BOUNDARY"
    OUT_OF_DOMAIN = "OUT_OF_DOMAIN"


class Direction(StrEnum):
    MINIMIZE = "MINIMIZE"
    MAXIMIZE = "MAXIMIZE"


class FeatureType(StrEnum):
    NUMERIC = "NUMERIC"
    CATEGORICAL = "CATEGORICAL"


class TargetMode(StrEnum):
    AT_LEAST = "AT_LEAST"
    AT_MOST = "AT_MOST"
    MATCH = "MATCH"
    RANGE = "RANGE"
    MAXIMIZE = "MAXIMIZE"
    MINIMIZE = "MINIMIZE"


class RecommendationMode(StrEnum):
    FORMULA_PREDICTION = "FORMULA_PREDICTION"
    EXPERIMENT_OPTIMIZATION = "EXPERIMENT_OPTIMIZATION"


class ArtifactReadRef(ContractModel):
    name: str = Field(min_length=1, max_length=180)
    url: AnyUrl
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class ArtifactWriteRef(ContractModel):
    url: AnyUrl
    content_type: str = "application/zip"


class SnapshotArtifacts(ContractModel):
    manifest: ArtifactReadRef
    measurements: ArtifactReadRef
    source_map: ArtifactReadRef


class MaterialSpec(ContractModel):
    code: str = Field(min_length=1)
    column: str = Field(min_length=1)
    role: Literal["MAIN_RESIN", "ADDITIVE", "BALANCE"]
    minimum: float = Field(ge=0.0, le=100.0)
    maximum: float = Field(ge=0.0, le=100.0)
    step: float = Field(gt=0.0, le=100.0)

    @model_validator(mode="after")
    def validate_range(self) -> "MaterialSpec":
        if self.minimum > self.maximum:
            raise ValueError("material minimum cannot exceed maximum")
        return self


class FormulaSpec(ContractModel):
    basis: Literal["PERCENT_SUM_100"] = "PERCENT_SUM_100"
    total: float = 100.0
    sum_tolerance: float = Field(default=0.02, gt=0.0)
    balance_material_code: str
    main_resin_codes: list[str] = Field(min_length=1)
    materials: list[MaterialSpec] = Field(min_length=2)

    @model_validator(mode="after")
    def validate_roles(self) -> "FormulaSpec":
        codes = [item.code for item in self.materials]
        if len(codes) != len(set(codes)):
            raise ValueError("material codes must be unique")
        if self.balance_material_code not in codes:
            raise ValueError("balance material must be present in materials")
        if any(code not in codes for code in self.main_resin_codes):
            raise ValueError("main resin code must be present in materials")
        role_by_code = {item.code: item.role for item in self.materials}
        if role_by_code[self.balance_material_code] != "BALANCE":
            raise ValueError("balance material must have BALANCE role")
        if any(role_by_code[code] != "MAIN_RESIN" for code in self.main_resin_codes):
            raise ValueError("main resin codes must have MAIN_RESIN role")
        return self


class ContextFeatureSpec(ContractModel):
    code: str
    column: str
    value_type: FeatureType
    adjustable: bool = False
    shared_within_source_group: bool = True
    unit: str | None = None


class TargetSpec(ContractModel):
    code: str
    target_key: str
    value_type: ValueType
    direction: Direction
    unit: str | None = None
    test_method: str
    substrate: str
    mandatory: bool = False
    weight: float = Field(default=1.0, gt=0.0)
    ordinal_values: list[float] | None = None
    ordinal_labels: list[str] | None = None
    class_labels: list[str] | None = None
    positive_class: str | None = None
    decision_threshold: float = Field(default=0.50, gt=0.0, lt=1.0)
    censored_column: str | None = None
    censor_type_column: str | None = None

    @model_validator(mode="after")
    def validate_target_type_configuration(self) -> "TargetSpec":
        if self.value_type == ValueType.ORDINAL:
            if not self.ordinal_values or len(self.ordinal_values) < 2:
                raise ValueError("ordinal target requires at least two configured values")
            if sorted(self.ordinal_values) != self.ordinal_values:
                raise ValueError("ordinal values must be ordered")
            if len(set(self.ordinal_values)) != len(self.ordinal_values):
                raise ValueError("ordinal values must be unique")
            if not self.ordinal_labels or len(self.ordinal_labels) != len(self.ordinal_values):
                raise ValueError("ordinal labels must align with ordinal values")
        if self.value_type == ValueType.CENSORED_COUNT:
            if not self.censored_column or not self.censor_type_column:
                raise ValueError("censored count target requires censor metadata columns")
        if self.value_type in {ValueType.BINARY, ValueType.CATEGORICAL}:
            if not self.class_labels or len(self.class_labels) < 2:
                raise ValueError("classification target requires at least two class labels")
            if len(set(self.class_labels)) != len(self.class_labels):
                raise ValueError("classification labels must be unique")
            if any(not str(label).strip() for label in self.class_labels):
                raise ValueError("classification labels cannot be blank")
        if self.value_type == ValueType.BINARY:
            if len(self.class_labels or []) != 2:
                raise ValueError("binary target requires exactly two class labels")
            if self.positive_class not in (self.class_labels or []):
                raise ValueError("binary positive class must be one of class labels")
        if self.value_type == ValueType.CATEGORICAL and self.positive_class is not None:
            raise ValueError("categorical target cannot define a positive class")
        return self


class DevelopmentReadinessThreshold(ContractModel):
    min_samples: int = Field(default=30, ge=1)
    min_groups: int = Field(default=5, ge=2)


class ContinuousReadinessThreshold(ContractModel):
    min_samples: int = Field(default=80, ge=1)
    min_groups: int = Field(default=12, ge=2)
    min_baseline_improvement: float = Field(default=0.10, ge=0.0)
    max_nmae: float = Field(default=0.20, gt=0.0)
    min_interval_coverage: float = Field(default=0.85, ge=0.0, le=1.0)
    max_interval_coverage: float = Field(default=0.95, ge=0.0, le=1.0)
    required_baseline_type: BaselineType = BaselineType.T06_SIMILAR_CASE

    @model_validator(mode="after")
    def validate_coverage_range(self) -> "ContinuousReadinessThreshold":
        if self.min_interval_coverage > self.max_interval_coverage:
            raise ValueError("minimum interval coverage cannot exceed maximum")
        return self


class OrdinalReadinessThreshold(ContractModel):
    min_samples: int = Field(default=100, ge=1)
    min_samples_per_observed_class: int = Field(default=10, ge=1)
    min_groups: int = Field(default=12, ge=2)
    max_grade_mae: float = Field(default=1.0, ge=0.0)
    min_plus_minus_one_accuracy: float = Field(default=0.85, ge=0.0, le=1.0)


class BinaryReadinessThreshold(ContractModel):
    min_samples: int = Field(default=80, ge=1)
    min_minority_class_samples: int = Field(default=20, ge=1)
    min_groups: int = Field(default=12, ge=2)
    min_roc_auc: float = Field(default=0.65, ge=0.0, le=1.0)
    min_pr_auc: float = Field(default=0.50, ge=0.0, le=1.0)
    min_f1: float = Field(default=0.50, ge=0.0, le=1.0)
    max_log_loss: float = Field(default=0.70, gt=0.0)
    max_brier_score: float = Field(default=0.25, ge=0.0, le=1.0)


class CategoricalReadinessThreshold(ContractModel):
    min_samples: int = Field(default=80, ge=1)
    min_samples_per_observed_class: int = Field(default=10, ge=1)
    min_observed_classes: int = Field(default=2, ge=2)
    min_groups: int = Field(default=12, ge=2)
    min_macro_f1: float = Field(default=0.40, ge=0.0, le=1.0)
    min_balanced_accuracy: float = Field(default=0.40, ge=0.0, le=1.0)
    max_log_loss: float = Field(default=1.50, gt=0.0)


class ReadinessThresholdProfile(ContractModel):
    development: DevelopmentReadinessThreshold = Field(
        default_factory=DevelopmentReadinessThreshold
    )
    production_continuous: ContinuousReadinessThreshold = Field(
        default_factory=ContinuousReadinessThreshold
    )
    production_ordinal: OrdinalReadinessThreshold = Field(
        default_factory=OrdinalReadinessThreshold
    )
    production_binary: BinaryReadinessThreshold = Field(
        default_factory=BinaryReadinessThreshold
    )
    production_categorical: CategoricalReadinessThreshold = Field(
        default_factory=CategoricalReadinessThreshold
    )


class ModelEligibilityRule(ContractModel):
    min_samples: int = Field(ge=1)


class ModelEligibilityProfile(ContractModel):
    gaussian_process: ModelEligibilityRule = Field(alias="GP")
    random_forest: ModelEligibilityRule = Field(alias="RF")
    lightgbm: ModelEligibilityRule = Field(alias="LIGHTGBM")
    xgboost: ModelEligibilityRule = Field(alias="XGBOOST")
    catboost: ModelEligibilityRule = Field(alias="CATBOOST")

    def minimum_for(self, model_type: ModelType | str) -> int:
        rules = {
            ModelType.GAUSSIAN_PROCESS: self.gaussian_process,
            ModelType.RANDOM_FOREST: self.random_forest,
            ModelType.LIGHTGBM: self.lightgbm,
            ModelType.XGBOOST: self.xgboost,
            ModelType.CATBOOST: self.catboost,
        }
        return rules[ModelType(model_type)].min_samples


class ClassificationModelEligibilityProfile(ContractModel):
    logistic_regression: ModelEligibilityRule = Field(
        default_factory=lambda: ModelEligibilityRule(min_samples=30),
        alias="LOGISTIC_REGRESSION",
    )
    random_forest: ModelEligibilityRule = Field(
        default_factory=lambda: ModelEligibilityRule(min_samples=30), alias="RF"
    )
    lightgbm: ModelEligibilityRule = Field(
        default_factory=lambda: ModelEligibilityRule(min_samples=80), alias="LIGHTGBM"
    )
    xgboost: ModelEligibilityRule = Field(
        default_factory=lambda: ModelEligibilityRule(min_samples=80), alias="XGBOOST"
    )
    catboost: ModelEligibilityRule = Field(
        default_factory=lambda: ModelEligibilityRule(min_samples=80), alias="CATBOOST"
    )

    def minimum_for(self, model_type: ModelType | str) -> int:
        rules = {
            ModelType.LOGISTIC_REGRESSION: self.logistic_regression,
            ModelType.RANDOM_FOREST: self.random_forest,
            ModelType.LIGHTGBM: self.lightgbm,
            ModelType.XGBOOST: self.xgboost,
            ModelType.CATBOOST: self.catboost,
        }
        return rules[ModelType(model_type)].min_samples


class ModelSelectionPolicy(ContractModel):
    tie_nmae_tolerance: float = Field(default=0.01, ge=0.0)
    model_tie_break_order: list[ModelType] = Field(min_length=5)

    @model_validator(mode="after")
    def validate_tie_order(self) -> "ModelSelectionPolicy":
        continuous = {
            ModelType.GAUSSIAN_PROCESS,
            ModelType.RANDOM_FOREST,
            ModelType.LIGHTGBM,
            ModelType.XGBOOST,
            ModelType.CATBOOST,
        }
        if set(self.model_tie_break_order) != continuous:
            raise ValueError("tie break order must contain each continuous model exactly once")
        return self


class ClassificationModelSelectionPolicy(ContractModel):
    tie_score_tolerance: float = Field(default=0.01, ge=0.0)
    binary_tie_break_order: list[ModelType] = Field(
        default_factory=lambda: [
            ModelType.LOGISTIC_REGRESSION,
            ModelType.CATBOOST,
            ModelType.XGBOOST,
            ModelType.LIGHTGBM,
            ModelType.RANDOM_FOREST,
        ]
    )
    categorical_tie_break_order: list[ModelType] = Field(
        default_factory=lambda: [
            ModelType.CATBOOST,
            ModelType.XGBOOST,
            ModelType.LIGHTGBM,
            ModelType.RANDOM_FOREST,
            ModelType.LOGISTIC_REGRESSION,
        ]
    )

    @model_validator(mode="after")
    def validate_classification_tie_order(self) -> "ClassificationModelSelectionPolicy":
        expected = {
            ModelType.LOGISTIC_REGRESSION,
            ModelType.RANDOM_FOREST,
            ModelType.LIGHTGBM,
            ModelType.XGBOOST,
            ModelType.CATBOOST,
        }
        if set(self.binary_tie_break_order) != expected or len(self.binary_tie_break_order) != 5:
            raise ValueError("binary tie break order must contain each classifier exactly once")
        if (
            set(self.categorical_tie_break_order) != expected
            or len(self.categorical_tie_break_order) != 5
        ):
            raise ValueError(
                "categorical tie break order must contain each classifier exactly once"
            )
        return self


class ProbabilityCalibrationPolicy(ContractModel):
    method: Literal["PLATT", "ISOTONIC"] = "PLATT"
    calibration_folds: int = Field(default=3, ge=2, le=5)
    expected_calibration_error_bins: int = Field(default=10, ge=5, le=50)


class ValidationPolicy(ContractModel):
    group_column: str = "formula_lineage_group"
    source_group_column: str = "source_sheet"
    folds: int = Field(default=5, ge=2, le=10)
    interval_level: float = Field(default=0.90, gt=0.5, lt=1.0)
    random_kfold_diagnostic: bool = False


class ApplicabilityDomainPolicy(ContractModel):
    enabled: bool = True
    robust_lower_quantile: float = Field(default=0.01, ge=0.0, lt=0.5)
    robust_upper_quantile: float = Field(default=0.99, gt=0.5, le=1.0)
    near_boundary_distance_quantile: float = Field(default=0.95, gt=0.5, lt=1.0)
    out_of_domain_distance_quantile: float = Field(default=0.99, gt=0.5, le=1.0)
    out_of_domain_requires_fallback: bool = True

    @model_validator(mode="after")
    def validate_quantiles(self) -> "ApplicabilityDomainPolicy":
        if self.robust_lower_quantile >= self.robust_upper_quantile:
            raise ValueError("robust lower quantile must be below upper quantile")
        if self.near_boundary_distance_quantile >= self.out_of_domain_distance_quantile:
            raise ValueError("near-boundary distance quantile must be below OOD quantile")
        return self


class CandidatePolicy(ContractModel):
    maximum_pool_size: int = Field(default=20_000, ge=100, le=100_000)
    minimum_l1_distance: float = Field(default=2.0, ge=0.0)
    conservative_maximum_l1_distance: float = Field(default=10.0, gt=0.0)
    default_count: int = Field(default=4, ge=1, le=16)


class TaskProfile(ContractModel):
    contract_version: Literal[CONTRACT_VERSION] = CONTRACT_VERSION
    code: str
    version: str
    schema_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    rule_version: str
    formula: FormulaSpec
    context_features: list[ContextFeatureSpec]
    targets: list[TargetSpec]
    validation: ValidationPolicy = Field(default_factory=ValidationPolicy)
    readiness_thresholds: ReadinessThresholdProfile = Field(
        default_factory=ReadinessThresholdProfile
    )
    model_eligibility: ModelEligibilityProfile
    model_selection: ModelSelectionPolicy
    classification_model_eligibility: ClassificationModelEligibilityProfile = Field(
        default_factory=ClassificationModelEligibilityProfile
    )
    classification_model_selection: ClassificationModelSelectionPolicy = Field(
        default_factory=ClassificationModelSelectionPolicy
    )
    probability_calibration: ProbabilityCalibrationPolicy = Field(
        default_factory=ProbabilityCalibrationPolicy
    )
    applicability_domain: ApplicabilityDomainPolicy = Field(
        default_factory=ApplicabilityDomainPolicy
    )
    candidate: CandidatePolicy = Field(default_factory=CandidatePolicy)

    @model_validator(mode="after")
    def validate_unique_fields(self) -> "TaskProfile":
        target_codes = [item.code for item in self.targets]
        context_codes = [item.code for item in self.context_features]
        if len(target_codes) != len(set(target_codes)):
            raise ValueError("target codes must be unique")
        if len(context_codes) != len(set(context_codes)):
            raise ValueError("context feature codes must be unique")
        return self


class RequestBase(ContractModel):
    contract_version: Literal[CONTRACT_VERSION] = CONTRACT_VERSION
    request_id: str = Field(min_length=1, max_length=120)
    task_profile_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    snapshot_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    seed: int = 20260903


class SnapshotRequest(RequestBase):
    task_profile: TaskProfile
    snapshot: SnapshotArtifacts


class ValidateSnapshotRequest(SnapshotRequest):
    pass


class TargetCoverage(ContractModel):
    target_code: str
    value_type: ValueType
    valid_rows: int = Field(ge=0)
    missing_rows: int = Field(ge=0)
    groups: int = Field(ge=0)
    source_groups: int = Field(default=0, ge=0)
    status: Literal[
        "SUPPORTED",
        "INSUFFICIENT_DATA",
        "UNSUPPORTED_TARGET_TYPE",
        "CENSORED_MODEL_NOT_IMPLEMENTED",
        "MODEL_NOT_READY",
    ]


class SourceSheetStats(ContractModel):
    total_sheets: int = Field(ge=0)
    sheets_with_multiple_lineages: int = Field(ge=0)
    max_lineages_per_sheet: int = Field(ge=0)


class SnapshotValidationResponse(ContractModel):
    contract_version: Literal[CONTRACT_VERSION] = CONTRACT_VERSION
    request_id: str
    snapshot_id: str
    snapshot_hash: str
    valid: bool
    production_eligible: bool
    rows: int = Field(ge=0)
    groups: int = Field(ge=0)
    source_sheets: int = Field(ge=0)
    source_sheet_stats: SourceSheetStats
    data_nature: str
    snapshot_purpose: str
    targets: list[TargetCoverage]
    errors: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class TrainRequest(SnapshotRequest):
    output: ArtifactWriteRef


class MetricSet(ContractModel):
    mae: float
    rmse: float
    nmae: float
    r2: float
    spearman: float
    interval_coverage: float
    mean_interval_width: float
    raw_interval_coverage: float | None = None
    raw_mean_interval_width: float | None = None
    calibrated_radius: float = Field(ge=0.0)
    calibration_sample_source: Literal["INNER_GROUP_OOF"] = "INNER_GROUP_OOF"
    evaluation_radius_mean: float = Field(default=0.0, ge=0.0)
    evaluation_radius_max: float = Field(default=0.0, ge=0.0)


class OrdinalMetricSet(ContractModel):
    grade_mae: float = Field(ge=0.0)
    plus_minus_one_accuracy: float = Field(ge=0.0, le=1.0)
    spearman: float
    confusion_matrix: list[list[int]]
    interval_coverage: float = Field(ge=0.0, le=1.0)
    mean_interval_width: float = Field(ge=0.0)


class ProbabilityQuality(ContractModel):
    log_loss: float = Field(ge=0.0)
    brier_score: float | None = Field(default=None, ge=0.0)
    expected_calibration_error: float = Field(ge=0.0, le=1.0)


class PerClassMetric(ContractModel):
    precision: float = Field(ge=0.0, le=1.0)
    recall: float = Field(ge=0.0, le=1.0)
    f1: float = Field(ge=0.0, le=1.0)
    support: int = Field(ge=0)


class BinaryMetricSet(ContractModel):
    roc_auc: float = Field(ge=0.0, le=1.0)
    pr_auc: float = Field(ge=0.0, le=1.0)
    precision: float = Field(ge=0.0, le=1.0)
    recall: float = Field(ge=0.0, le=1.0)
    f1: float = Field(ge=0.0, le=1.0)
    minority_class: str
    minority_class_recall: float = Field(ge=0.0, le=1.0)
    confusion_matrix: list[list[int]]
    raw_probability_quality: ProbabilityQuality
    calibrated_probability_quality: ProbabilityQuality
    decision_threshold: float = Field(gt=0.0, lt=1.0)


class CategoricalMetricSet(ContractModel):
    macro_f1: float = Field(ge=0.0, le=1.0)
    weighted_f1: float = Field(ge=0.0, le=1.0)
    balanced_accuracy: float = Field(ge=0.0, le=1.0)
    per_class: dict[str, PerClassMetric]
    confusion_matrix: list[list[int]]
    raw_probability_quality: ProbabilityQuality
    calibrated_probability_quality: ProbabilityQuality


class AlgorithmMetadata(ContractModel):
    model_type: ModelType
    library: str
    library_version: str
    final_params: dict[str, Any] = Field(default_factory=dict)
    seed: int


class CandidateModelMetric(ContractModel):
    model_type: ModelType
    status: Literal["ELIGIBLE", "SKIPPED_INSUFFICIENT_SAMPLES", "TRAINING_FAILED"]
    lineage_cv: MetricSet | BinaryMetricSet | CategoricalMetricSet | None = None
    sheet_cv: MetricSet | BinaryMetricSet | CategoricalMetricSet | None = None
    random_kfold_diagnostic: MetricSet | None = None
    final_params: dict[str, Any] = Field(default_factory=dict)
    training_time_seconds: float = Field(default=0.0, ge=0.0)
    prediction_time_seconds: float = Field(default=0.0, ge=0.0)
    failure_reason: str | None = None
    algorithm: AlgorithmMetadata | None = None
    lineage_fold_nmae: list[float] = Field(default_factory=list)
    sheet_fold_nmae: list[float] = Field(default_factory=list)
    lineage_fold_scores: list[float] = Field(default_factory=list)
    sheet_fold_scores: list[float] = Field(default_factory=list)


class BaselineMetrics(ContractModel):
    lineage_nmae: float
    sheet_nmae: float


class BaselineSummary(ContractModel):
    type: BaselineType
    version: str
    metrics: BaselineMetrics


class ModelSelectionSummary(ContractModel):
    eligible_models: list[ModelType] = Field(default_factory=list)
    skipped_models: list[ModelType] = Field(default_factory=list)
    failed_models: list[ModelType] = Field(default_factory=list)
    candidate_metrics: list[CandidateModelMetric] = Field(default_factory=list)
    champion_model: ModelType | None = None
    selection_reason: str | None = None


class ClassificationTrainingSummary(ContractModel):
    target_type: Literal[ValueType.BINARY, ValueType.CATEGORICAL]
    configured_classes: list[str]
    observed_classes: list[str]
    positive_class: str | None = None
    decision_threshold: float | None = None
    calibration_method: Literal["PLATT", "ISOTONIC"]
    calibration_sample_source: Literal["GROUP_ISOLATED_OOF"] = "GROUP_ISOLATED_OOF"
    lineage_cv: BinaryMetricSet | CategoricalMetricSet
    sheet_cv: BinaryMetricSet | CategoricalMetricSet
    class_probabilities_supported: bool = True


class UncertaintySummary(ContractModel):
    method: Literal["NESTED_GROUP_CONFORMAL_ABSOLUTE_RESIDUAL"] = (
        "NESTED_GROUP_CONFORMAL_ABSOLUTE_RESIDUAL"
    )
    raw_coverage: float | None = None
    calibrated_coverage: float
    calibrated_radius: float = Field(ge=0.0)


class TargetReadiness(ContractModel):
    threshold_profile: ReadinessThresholdProfile
    checks: dict[str, bool] = Field(default_factory=dict)
    blocking_reasons: list[str] = Field(default_factory=list)


class ValidationManifest(ContractModel):
    effective_row_count: int = Field(ge=0)
    effective_row_ids_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    lineage_folds_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    sheet_folds_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    lineage_fold_sizes: list[int] = Field(default_factory=list)
    sheet_fold_sizes: list[int] = Field(default_factory=list)


class GroupStabilitySummary(ContractModel):
    fold_nmae_mean: float = Field(ge=0.0)
    fold_nmae_std: float = Field(ge=0.0)
    fold_nmae_min: float = Field(ge=0.0)
    fold_nmae_max: float = Field(ge=0.0)


class StabilitySummary(ContractModel):
    method: Literal["HELD_OUT_GROUP_FOLD_DISPERSION"] = (
        "HELD_OUT_GROUP_FOLD_DISPERSION"
    )
    lineage_cv: GroupStabilitySummary
    sheet_cv: GroupStabilitySummary


class ApplicabilityDomainSummary(ContractModel):
    method: Literal["GROUP_ISOLATED_NEAREST_DISTANCE"] = (
        "GROUP_ISOLATED_NEAREST_DISTANCE"
    )
    reference_rows: int = Field(ge=1)
    near_boundary_threshold: float = Field(ge=0.0)
    out_of_domain_threshold: float = Field(ge=0.0)
    numeric_features: list[str] = Field(default_factory=list)
    categorical_features: list[str] = Field(default_factory=list)


class OrdinalTrainingSummary(ContractModel):
    ordered_classes: list[str]
    observed_classes: list[str]
    lineage_cv: OrdinalMetricSet
    sheet_cv: OrdinalMetricSet
    class_probabilities_supported: bool = True
    algorithm: AlgorithmMetadata
    training_time_seconds: float = Field(default=0.0, ge=0.0)
    prediction_time_seconds: float = Field(default=0.0, ge=0.0)


class TargetTrainingResult(ContractModel):
    target_code: str
    target_type: ValueType
    status: Literal[
        "READY",
        "INSUFFICIENT_DATA",
        "UNSUPPORTED_TARGET_TYPE",
        "MODEL_NOT_READY",
        "FAILED",
    ]
    scorer_type: ModelType | None = None
    metrics: MetricSet | None = None
    lineage_cv: MetricSet | BinaryMetricSet | CategoricalMetricSet | None = None
    sheet_cv: MetricSet | BinaryMetricSet | CategoricalMetricSet | None = None
    baseline_nmae: float | None = None
    baseline_improvement: float | None = None
    source_group_audit_nmae: float | None = None
    baseline: BaselineSummary | None = None
    model_selection: ModelSelectionSummary | None = None
    uncertainty: UncertaintySummary | None = None
    ordinal: OrdinalTrainingSummary | None = None
    classification: ClassificationTrainingSummary | None = None
    readiness: TargetReadiness | None = None
    validation_manifest: ValidationManifest | None = None
    stability: StabilitySummary | None = None
    applicability_domain: ApplicabilityDomainSummary | None = None
    valid_rows: int = 0
    groups: int = 0
    source_groups: int = 0
    development_eligible: bool = False
    production_eligible: bool = False
    reasons: list[str] = Field(default_factory=list)


class TrainResponse(ContractModel):
    contract_version: Literal[CONTRACT_VERSION] = CONTRACT_VERSION
    request_id: str
    snapshot_id: str
    snapshot_hash: str
    status: Literal["READY", "PARTIAL", "REJECTED"]
    model_bundle_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    model_bundle_size: int = Field(ge=0)
    uploaded: bool
    targets: list[TargetTrainingResult]
    warnings: list[str] = Field(default_factory=list)


class ModelBundleRef(ContractModel):
    url: AnyUrl
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class ScoreRow(ContractModel):
    row_id: str
    formula: dict[str, float]
    context: dict[str, Any]


class ApplicabilityDomainEvidence(ContractModel):
    status: DomainStatus
    model_usable: bool
    nearest_training_row_id: str | None = None
    nearest_distance: float = Field(ge=0.0)
    near_boundary_threshold: float = Field(ge=0.0)
    out_of_domain_threshold: float = Field(ge=0.0)
    unknown_categories: list[str] = Field(default_factory=list)
    outside_observed_range: list[str] = Field(default_factory=list)
    near_boundary_features: list[str] = Field(default_factory=list)
    reasons: list[str] = Field(default_factory=list)


class ScoreRequest(RequestBase):
    task_profile: TaskProfile
    model_bundle_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    model_bundle: ModelBundleRef
    rows: list[ScoreRow] = Field(min_length=1, max_length=20_000)
    target_codes: list[str] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_model_bundle_hash(self) -> "ScoreRequest":
        if self.model_bundle_hash != self.model_bundle.sha256:
            raise ValueError("modelBundleHash must match modelBundle.sha256")
        return self


class Prediction(ContractModel):
    target_code: str
    expected: float
    lower: float
    upper: float
    unit: str | None
    scorer_type: ModelType
    interval_method: Literal["CALIBRATED_CONFORMAL"] = "CALIBRATED_CONFORMAL"
    applicability_domain: ApplicabilityDomainEvidence | None = None


class OrdinalPrediction(ContractModel):
    target_code: str
    predicted_class: str
    class_probabilities: dict[str, float]
    lower_class90: str
    upper_class90: str
    scorer_type: Literal[ModelType.ORDINAL_CUMULATIVE_LOGIT] = (
        ModelType.ORDINAL_CUMULATIVE_LOGIT
    )
    applicability_domain: ApplicabilityDomainEvidence | None = None


class ClassificationPrediction(ContractModel):
    target_code: str
    target_type: Literal[ValueType.BINARY, ValueType.CATEGORICAL]
    predicted_class: str
    class_probabilities: dict[str, float]
    raw_class_probabilities: dict[str, float]
    confidence: float = Field(ge=0.0, le=1.0)
    positive_class: str | None = None
    raw_positive_probability: float | None = Field(default=None, ge=0.0, le=1.0)
    calibrated_positive_probability: float | None = Field(
        default=None, ge=0.0, le=1.0
    )
    decision_threshold: float | None = Field(default=None, gt=0.0, lt=1.0)
    calibration_method: Literal["PLATT", "ISOTONIC"]
    scorer_type: ModelType
    applicability_domain: ApplicabilityDomainEvidence | None = None


class ScoredRow(ContractModel):
    row_id: str
    predictions: list[Prediction]
    ordinal_predictions: list[OrdinalPrediction] = Field(default_factory=list)
    classification_predictions: list[ClassificationPrediction] = Field(
        default_factory=list
    )
    domain_status: DomainStatus = DomainStatus.IN_DOMAIN
    model_usable: bool = True
    fallback_reasons: list[str] = Field(default_factory=list)


class ScoreResponse(ContractModel):
    contract_version: Literal[CONTRACT_VERSION] = CONTRACT_VERSION
    request_id: str
    model_bundle_sha256: str
    rows: list[ScoredRow]
    unsupported_targets: list[str] = Field(default_factory=list)


class RequestedTarget(ContractModel):
    code: str
    mode: TargetMode
    mandatory: bool = False
    weight: float = Field(default=1.0, gt=0.0)
    value: float | None = None
    minimum: float | None = None
    maximum: float | None = None
    tolerance: float | None = Field(default=None, ge=0.0)

    @model_validator(mode="after")
    def validate_goal(self) -> "RequestedTarget":
        if self.mode in {TargetMode.AT_LEAST, TargetMode.AT_MOST, TargetMode.MATCH}:
            if self.value is None:
                raise ValueError(f"{self.mode} requires value")
        if self.mode == TargetMode.RANGE:
            if self.minimum is None or self.maximum is None or self.minimum > self.maximum:
                raise ValueError("RANGE requires ordered minimum and maximum")
        return self


class MaterialConstraint(ContractModel):
    material_code: str
    minimum: float | None = Field(default=None, ge=0.0, le=100.0)
    maximum: float | None = Field(default=None, ge=0.0, le=100.0)
    fixed: float | None = Field(default=None, ge=0.0, le=100.0)

    @model_validator(mode="after")
    def validate_constraint(self) -> "MaterialConstraint":
        if self.minimum is not None and self.maximum is not None and self.minimum > self.maximum:
            raise ValueError("constraint minimum cannot exceed maximum")
        return self


class RecommendRequest(RequestBase):
    task_profile: TaskProfile
    model_bundle_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    model_bundle: ModelBundleRef
    baseline_formula: dict[str, float]
    context: dict[str, Any]
    targets: list[RequestedTarget] = Field(min_length=1)
    material_constraints: list[MaterialConstraint] = Field(default_factory=list)
    count: Annotated[int, Field(ge=1, le=16)] = 4
    recommendation_mode: RecommendationMode = RecommendationMode.EXPERIMENT_OPTIMIZATION
    minimum_potential_desirability: float = Field(default=0.20, ge=0.0, le=1.0)

    @model_validator(mode="after")
    def validate_model_bundle_hash(self) -> "RecommendRequest":
        if self.model_bundle_hash != self.model_bundle.sha256:
            raise ValueError("modelBundleHash must match modelBundle.sha256")
        return self


class FormulaDifference(ContractModel):
    material_code: str
    baseline_value: float
    candidate_value: float
    delta: float


class TargetTradeoff(ContractModel):
    target_code: str
    outcome: Literal["IMPROVES", "TRADES_OFF", "UNCHANGED", "NO_BASELINE"]
    expected: float
    baseline_expected: float | None = None
    desirability_delta: float | None = None
    explanation: str


class TargetConflict(ContractModel):
    target_codes: list[str] = Field(min_length=2)
    conflict_type: Literal["PARETO_TRADE_OFF", "NO_JOINT_IMPROVEMENT"]
    explanation: str


class SearchSpaceStatistics(ContractModel):
    raw_candidate_count: int = Field(default=0, ge=0)
    material_constraint_rejected_count: int = Field(default=0, ge=0)
    duplicate_candidate_count: int = Field(default=0, ge=0)
    accepted_after_constraints_count: int = Field(default=0, ge=0)
    out_of_domain_rejected_count: int = Field(default=0, ge=0)
    near_boundary_count: int = Field(default=0, ge=0)
    in_domain_count: int = Field(default=0, ge=0)
    final_scoreable_candidate_count: int = Field(default=0, ge=0)
    baybe_shortlist_count: int = Field(default=0, ge=0)
    coverage_ratio: float = Field(default=0.0, ge=0.0, le=1.0)
    insufficient_space_reasons: list[str] = Field(default_factory=list)


class RecommendedCandidate(ContractModel):
    candidate_id: str
    strategy: Literal["CONTROL", "CONSERVATIVE", "BALANCED", "EXPLORATORY"]
    formula: dict[str, float]
    predictions: list[Prediction]
    desirability: float = Field(ge=0.0, le=1.0)
    l1_distance_from_baseline: float = Field(ge=0.0)
    reasons: list[str] = Field(default_factory=list)
    recommendation_mode: RecommendationMode = RecommendationMode.EXPERIMENT_OPTIMIZATION
    domain_status: DomainStatus = DomainStatus.IN_DOMAIN
    nearest_training_distance: float = Field(default=0.0, ge=0.0)
    l1_distance_from_history: float = Field(default=0.0, ge=0.0)
    l1_distance_from_batch: float | None = Field(default=None, ge=0.0)
    conservative_desirability: float = Field(default=0.0, ge=0.0, le=1.0)
    exploration_value: float = Field(default=0.0, ge=0.0, le=1.0)
    formula_differences: list[FormulaDifference] = Field(default_factory=list)
    target_tradeoffs: list[TargetTradeoff] = Field(default_factory=list)
    conflicts: list[TargetConflict] = Field(default_factory=list)
    control_source: Literal["EXACT_BASELINE", "NEAREST_FEASIBLE_BASELINE"] | None = None
    selection_reason: str = ""
    risks: list[str] = Field(default_factory=list)


class RecommendResponse(ContractModel):
    contract_version: Literal[CONTRACT_VERSION] = CONTRACT_VERSION
    request_id: str
    status: Literal["READY", "PARTIAL"]
    model_bundle_sha256: str
    candidates: list[RecommendedCandidate]
    missing_strategies: list[str] = Field(default_factory=list)
    missing_strategy_reasons: dict[str, str] = Field(default_factory=dict)
    unsupported_targets: list[str] = Field(default_factory=list)
    unavailable_targets: list[str] = Field(default_factory=list)
    recommendation_mode: RecommendationMode = RecommendationMode.EXPERIMENT_OPTIMIZATION
    search_space: SearchSpaceStatistics = Field(default_factory=SearchSpaceStatistics)
    target_conflicts: list[TargetConflict] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class ErrorResponse(ContractModel):
    contract_version: Literal[CONTRACT_VERSION] = CONTRACT_VERSION
    request_id: str | None = None
    code: str
    message: str
    retryable: bool = False
    details: dict[str, Any] = Field(default_factory=dict)
