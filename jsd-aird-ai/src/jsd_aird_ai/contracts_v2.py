from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Any, Literal, Union

from pydantic import AnyUrl, BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


CONTRACT_VERSION_V2 = "formula-model.v2"
Sha256 = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]


class V2Model(BaseModel):
    """Independent v2 source contract. No v1 model is imported or accepted."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        use_enum_values=True,
    )


class ValueTypeV2(StrEnum):
    CONTINUOUS = "CONTINUOUS"
    ORDINAL = "ORDINAL"
    BINARY = "BINARY"
    CATEGORICAL = "CATEGORICAL"


class DomainStatusV2(StrEnum):
    IN_DOMAIN = "IN_DOMAIN"
    NEAR_BOUNDARY = "NEAR_BOUNDARY"
    OUT_OF_DOMAIN = "OUT_OF_DOMAIN"


class VersionedRef(V2Model):
    id: str = Field(min_length=1)
    version: int = Field(ge=1)
    sha256: Sha256


class ArtifactRefV2(V2Model):
    url: AnyUrl
    sha256: Sha256


class TargetDefinitionV2(VersionedRef):
    code: str = Field(min_length=1, max_length=160)
    value_type: ValueTypeV2
    unit: str | None = None
    classes: list[str] = Field(default_factory=list)
    positive_class: str | None = None
    observation_semantics: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def type_configuration(self) -> "TargetDefinitionV2":
        if self.value_type == ValueTypeV2.BINARY:
            if len(self.classes) != 2 or self.positive_class not in self.classes:
                raise ValueError("binary target requires two classes and a positiveClass")
        if self.value_type in {ValueTypeV2.ORDINAL, ValueTypeV2.CATEGORICAL} and len(self.classes) < 2:
            raise ValueError("ordinal/categorical target requires configured classes")
        if self.value_type == ValueTypeV2.CONTINUOUS and self.classes:
            raise ValueError("continuous target cannot define classes")
        return self


class InputFieldV2(V2Model):
    field_version_id: str = Field(min_length=1)
    code: str = Field(min_length=1)
    name: str | None = None
    unit: str | None = None
    ordinal: int | None = Field(default=None, ge=0)
    value_type: Literal["NUMBER", "STRING", "BOOLEAN", "CATEGORY", "COMPOSITION"]
    required: bool
    acquisition_timing: Literal["PRE_EXPERIMENT", "POST_EXPERIMENT"]
    encoding: dict[str, Any] = Field(default_factory=dict)


class InputSchemeV2(VersionedRef):
    target_version_id: str
    fields: list[InputFieldV2] = Field(min_length=1)

    @model_validator(mode="after")
    def unique_fields(self) -> "InputSchemeV2":
        codes = [field.code for field in self.fields]
        if len(codes) != len(set(codes)):
            raise ValueError("input field codes must be unique")
        return self


class MaterialV2(V2Model):
    material_id: str
    code: str
    role: str | None = None
    encoder_index: int = Field(ge=0)


class MaterialDictionaryV2(VersionedRef):
    materials: list[MaterialV2] = Field(min_length=1)


class PreprocessingV2(VersionedRef):
    config: dict[str, Any]


class TrainingPolicyV2(VersionedRef):
    config: dict[str, Any]


class ContractRequestV2(V2Model):
    contract_version: Literal[CONTRACT_VERSION_V2] = CONTRACT_VERSION_V2
    request_id: str = Field(min_length=1)
    seed: int


class ContractResponseV2(V2Model):
    contract_version: Literal[CONTRACT_VERSION_V2] = CONTRACT_VERSION_V2
    request_id: str


class FrozenContextV2(V2Model):
    target_definition: TargetDefinitionV2
    input_scheme: InputSchemeV2
    material_dictionary: MaterialDictionaryV2
    preprocessing: PreprocessingV2
    training_policy: TrainingPolicyV2


class SnapshotRefV2(VersionedRef):
    manifest: ArtifactRefV2
    rows: ArtifactRefV2
    source_map: ArtifactRefV2


class ValidateRequestV2(ContractRequestV2):
    snapshot: SnapshotRefV2
    context: FrozenContextV2


class ValidationIssueV2(V2Model):
    code: str
    message: str
    row_id: str | None = None
    field_code: str | None = None


class ValidateResponseV2(ContractResponseV2):
    status: Literal["VALID", "REJECTED"]
    snapshot_hash: Sha256
    issues: list[ValidationIssueV2] = Field(default_factory=list)


class ValidationFoldsRequestV2(ValidateRequestV2):
    fold_count: int = Field(ge=2, le=10)
    group_fields: list[str] = Field(min_length=1)


class FoldAssignmentV2(V2Model):
    row_id: str
    group_key: str
    fold: int = Field(ge=0)


class ValidationFoldsResponseV2(ContractResponseV2):
    snapshot_hash: Sha256
    assignments_hash: Sha256
    assignments: list[FoldAssignmentV2]


class TrainRequestV2(ContractRequestV2):
    job_id: str
    snapshot: SnapshotRefV2
    context: FrozenContextV2
    validation_folds: ArtifactRefV2
    output: Annotated[AnyUrl, Field()]


class TrainResponseV2(ContractResponseV2):
    job_id: str
    status: Literal["CANDIDATE", "REJECTED", "FAILED"]
    model_bundle: ArtifactRefV2 | None = None
    metrics: dict[str, Any] = Field(default_factory=dict)
    applicability_domain: dict[str, Any] = Field(default_factory=dict)
    reasons: list[str] = Field(default_factory=list)


class FormulaComponentV2(V2Model):
    material_id: str
    ratio: float | None = Field(default=None, ge=0)
    unit: str
    amount_known: bool

    @model_validator(mode="after")
    def amount_semantics(self) -> "FormulaComponentV2":
        if self.amount_known != (self.ratio is not None):
            raise ValueError("amountKnown must distinguish zero from missing")
        return self


class FormulaV2(V2Model):
    basis: Literal["MASS_PERCENT", "MASS_PART", "ABSOLUTE_MASS"]
    composition_complete: bool
    components: list[FormulaComponentV2] = Field(min_length=1)
    recorded_total: float | None = Field(default=None, ge=0)


class ModelBindingV2(V2Model):
    target: TargetDefinitionV2
    model_version_id: str
    model_bundle: ArtifactRefV2
    input_scheme: InputSchemeV2
    material_dictionary: MaterialDictionaryV2
    preprocessing: PreprocessingV2
    applicability_domain: dict[str, Any]


class ScoreRequestV2(ContractRequestV2):
    run_id: str
    formula: FormulaV2 | None = None
    inputs: dict[str, Any]
    model_bindings: list[ModelBindingV2] = Field(min_length=1)


class ApplicabilityV2(V2Model):
    status: DomainStatusV2
    distance: float = Field(ge=0)
    reasons: list[str] = Field(default_factory=list)


class ContinuousResultV2(V2Model):
    result_type: Literal["CONTINUOUS"] = "CONTINUOUS"
    value: float
    unit: str | None = None
    lower: float
    upper: float
    coverage_level: float = Field(gt=0, lt=1)


class OrdinalResultV2(V2Model):
    result_type: Literal["ORDINAL"] = "ORDINAL"
    label: str
    probabilities: dict[str, float]
    threshold_probability: float = Field(ge=0, le=1)


class BinaryResultV2(V2Model):
    result_type: Literal["BINARY"] = "BINARY"
    label: str
    positive_class: str
    probability: float = Field(ge=0, le=1)
    decision_threshold: float = Field(gt=0, lt=1)


class CategoricalResultV2(V2Model):
    result_type: Literal["CATEGORICAL"] = "CATEGORICAL"
    label: str
    probabilities: dict[str, float]


TypedResultV2 = Annotated[
    Union[ContinuousResultV2, OrdinalResultV2, BinaryResultV2, CategoricalResultV2],
    Field(discriminator="result_type"),
]


class TargetSuccessV2(V2Model):
    status: Literal["SUCCEEDED"] = "SUCCEEDED"
    target_id: str
    model_version_id: str
    result: TypedResultV2
    applicability: ApplicabilityV2
    warnings: list[ValidationIssueV2] = Field(default_factory=list)


class TargetBlockedV2(V2Model):
    status: Literal["BLOCKED"] = "BLOCKED"
    target_id: str
    model_version_id: str | None = None
    code: str
    message: str
    detail: dict[str, Any] = Field(default_factory=dict)


class TargetFailedV2(V2Model):
    status: Literal["FAILED"] = "FAILED"
    target_id: str
    model_version_id: str | None = None
    code: str
    message: str
    detail: dict[str, Any] = Field(default_factory=dict)


TargetOutcomeV2 = Annotated[
    Union[TargetSuccessV2, TargetBlockedV2, TargetFailedV2],
    Field(discriminator="status"),
]


class ScoreResponseV2(ContractResponseV2):
    run_id: str
    execution_status: Literal["SUCCEEDED", "FAILED"]
    outcome_status: Literal["SUCCEEDED", "PARTIAL", "BLOCKED"] | None = None
    results: list[TargetOutcomeV2]

    @model_validator(mode="after")
    def state_consistency(self) -> "ScoreResponseV2":
        if self.execution_status == "FAILED" and self.outcome_status is not None:
            raise ValueError("failed execution cannot have an outcomeStatus")
        if self.execution_status == "SUCCEEDED" and self.outcome_status is None:
            raise ValueError("successful execution requires an outcomeStatus")
        return self


class TypedGoalV2(V2Model):
    target_id: str
    operator: Literal["AT_LEAST", "AT_MOST", "MATCH", "RANGE", "MAXIMIZE", "MINIMIZE"]
    value: Any = None
    mandatory: bool = False
    weight: float = Field(default=1, gt=0)
    minimum_probability: float | None = Field(default=None, ge=0, le=1)


class RecommendRequestV2(ContractRequestV2):
    run_id: str
    mode: Literal["FORMULA_PREDICTION", "EXPERIMENT_OPTIMIZATION"]
    baseline_formula: FormulaV2 | None = None
    fixed_inputs: dict[str, Any]
    goals: list[TypedGoalV2] = Field(min_length=1)
    constraints: dict[str, Any]
    search_space: dict[str, Any] = Field(default_factory=dict)
    model_bindings: list[ModelBindingV2] = Field(min_length=1)
    candidate_count: int = Field(default=4, ge=1, le=16)

    @model_validator(mode="after")
    def optimization_requires_baseline(self) -> "RecommendRequestV2":
        if self.mode == "EXPERIMENT_OPTIMIZATION" and self.baseline_formula is None:
            raise ValueError("experiment optimization requires baselineFormula")
        return self


class RecommendedCandidateV2(V2Model):
    candidate_id: str
    formula: FormulaV2
    results: list[TargetSuccessV2]
    score: float
    rule_check: dict[str, Any]
    strategy: Literal["CONTROLLED_POOL", "CONTROL", "CONSERVATIVE", "BALANCED", "EXPLORATORY"] = "CONTROLLED_POOL"
    inputs: dict[str, Any] = Field(default_factory=dict)
    quality: dict[str, Any] = Field(default_factory=dict)
    applicability: dict[str, Any] = Field(default_factory=dict)
    baseline_distance: float = Field(default=0, ge=0)
    change_summary: dict[str, Any] = Field(default_factory=dict)
    strategy_evidence: dict[str, Any] = Field(default_factory=dict)
    risk_flags: list[str] = Field(default_factory=list)


class RecommendResponseV2(ContractResponseV2):
    run_id: str
    # ``status`` remains as a wire-compatible label for existing internal
    # callers; new consumers use the explicit execution/outcome pair.
    status: Literal["READY", "PARTIAL", "NO_FEASIBLE_CANDIDATE", "FAILED"]
    execution_status: Literal["SUCCEEDED", "FAILED"] = "SUCCEEDED"
    outcome_status: Literal["SUCCEEDED", "PARTIAL", "BLOCKED"] | None = None
    candidates: list[RecommendedCandidateV2]
    # Preserve the reason for each missing strategy so callers can explain a
    # partial result instead of showing an unexplained shortfall.
    missing_strategies: list[str] = Field(default_factory=list)
    missing_strategy_reasons: dict[str, str] = Field(default_factory=dict)
    blocked_targets: list[TargetBlockedV2] = Field(default_factory=list)
    warnings: list[ValidationIssueV2] = Field(default_factory=list)
    shortfall_reason: str | None = None
    shortfall_reason_code: str | None = None
    rejection_summary: dict[str, int] = Field(default_factory=dict)
    action_hints: list[str] = Field(default_factory=list)
    code: str | None = None
    message: str | None = None
    # Search provenance is part of the frozen execution evidence.  Continuous
    # objectives use the BayBE shortlist; ordinal/categorical-only requests
    # use the deterministic candidate pool.  Clients must not infer this from
    # the result type after the fact.
    search_engine: Literal["BAYBE", "DETERMINISTIC_CANDIDATE_POOL"] = "DETERMINISTIC_CANDIDATE_POOL"
    search_strategy: Literal["CONTINUOUS_BAYBE", "DISCRETE_DETERMINISTIC_POOL"] = "DISCRETE_DETERMINISTIC_POOL"
    search_evidence: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def state_consistency(self) -> "RecommendResponseV2":
        if self.execution_status == "FAILED" and self.outcome_status is not None:
            raise ValueError("failed recommendation cannot have outcomeStatus")
        if self.execution_status == "SUCCEEDED" and self.outcome_status is None:
            self.outcome_status = "BLOCKED" if not self.candidates else (
                "PARTIAL" if self.status == "PARTIAL" else "SUCCEEDED"
            )
        return self


class ErrorResponseV2(ContractResponseV2):
    code: str
    message: str
    retryable: bool = False
    detail: dict[str, Any] = Field(default_factory=dict)
