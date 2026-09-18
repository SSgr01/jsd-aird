from __future__ import annotations

from copy import deepcopy

import numpy as np
import pandas as pd
import pytest
from pydantic import ValidationError
from sklearn.dummy import DummyRegressor

from jsd_aird_ai.applicability import fit_applicability_domain
from jsd_aird_ai.contracts import (
    AlgorithmMetadata,
    ApplicabilityDomainPolicy,
    CandidateModelMetric,
    DomainStatus,
    MetricSet,
    ModelSelectionSummary,
    ModelType,
    ScoreRequest,
    TargetTrainingResult,
    ValueType,
)
from jsd_aird_ai.features import FeatureLayout
from jsd_aird_ai.model_adapters import ModelAdapter
from jsd_aird_ai.modeling import _model_card_target, _validation_manifest
from jsd_aird_ai.reproducibility import compare_model_cards
from jsd_aird_ai.stability import (
    choose_point_champion,
    point_cv_diagnostic,
    prediction_sensitivity,
)
from jsd_aird_ai.validation import evaluate_adapter, group_folds


class MeanAdapter(ModelAdapter):
    model_type = ModelType.RANDOM_FOREST
    library = "scikit-learn"
    library_version = "test"

    def parameter_candidates(self) -> list[dict]:
        return [{}]

    def _estimator(self, seed: int, params: dict, dimension: int):
        return DummyRegressor(strategy="mean")


def domain_fixture(rows: int = 60):
    features = pd.DataFrame(
        {
            "numeric": np.linspace(0.0, 10.0, rows),
            "process": np.tile([1.0, 2.0, 3.0], rows // 3),
            "category": np.tile(["A", "B"], rows // 2),
        }
    )
    layout = FeatureLayout(numeric=["numeric", "process"], categorical=["category"])
    row_ids = np.array([f"row-{index}" for index in range(rows)])
    lineages = np.repeat(np.arange(12), rows // 12)
    sheets = np.repeat(np.arange(20), rows // 20)
    model = fit_applicability_domain(
        features,
        layout,
        row_ids,
        lineages,
        sheets,
        ApplicabilityDomainPolicy(),
    )
    return features, layout, row_ids, lineages, sheets, model


def metric(nmae: float = 0.1) -> MetricSet:
    return MetricSet(
        mae=1.0,
        rmse=1.2,
        nmae=nmae,
        r2=0.5,
        spearman=0.6,
        intervalCoverage=0.9,
        meanIntervalWidth=2.0,
        calibratedRadius=1.0,
    )


def test_applicability_domain_accepts_covered_training_combination() -> None:
    features, _, _, _, _, model = domain_fixture()
    evidence = model.assess(features.iloc[[30]])[0]
    assert evidence.status == DomainStatus.IN_DOMAIN
    assert evidence.model_usable is True
    assert evidence.nearest_distance == pytest.approx(0.0)
    assert evidence.nearest_training_row_id == "row-30"


def test_applicability_domain_marks_numeric_extrapolation_for_fallback() -> None:
    features, _, _, _, _, model = domain_fixture()
    candidate = features.iloc[[30]].copy()
    candidate["numeric"] = 100.0
    evidence = model.assess(candidate)[0]
    assert evidence.status == DomainStatus.OUT_OF_DOMAIN
    assert evidence.model_usable is False
    assert evidence.outside_observed_range == ["numeric"]
    assert "NUMERIC_OUTSIDE_OBSERVED_RANGE" in evidence.reasons


def test_applicability_domain_rejects_unknown_category() -> None:
    features, _, _, _, _, model = domain_fixture()
    candidate = features.iloc[[30]].copy()
    candidate["category"] = "UNKNOWN"
    evidence = model.assess(candidate)[0]
    assert evidence.status == DomainStatus.OUT_OF_DOMAIN
    assert evidence.unknown_categories == ["category"]
    assert "UNKNOWN_CATEGORY" in evidence.reasons


def test_applicability_domain_can_be_disabled_by_profile() -> None:
    features, layout, row_ids, lineages, sheets, _ = domain_fixture()
    model = fit_applicability_domain(
        features,
        layout,
        row_ids,
        lineages,
        sheets,
        ApplicabilityDomainPolicy(enabled=False),
    )
    candidate = features.iloc[[0]].copy()
    candidate["numeric"] = 100.0
    candidate["category"] = "UNKNOWN"
    evidence = model.assess(candidate)[0]
    assert evidence.status == DomainStatus.IN_DOMAIN
    assert evidence.model_usable is True
    assert evidence.reasons == ["APPLICABILITY_DOMAIN_DISABLED"]


def test_nested_conformal_radius_does_not_see_its_outer_validation_targets() -> None:
    rows = 48
    features = pd.DataFrame(
        {"numeric": np.linspace(0.0, 1.0, rows), "category": ["A"] * rows}
    )
    layout = FeatureLayout(numeric=["numeric"], categorical=["category"])
    groups = np.repeat(np.arange(12), 4)
    folds = group_folds(groups, 4)
    target = np.linspace(0.0, 5.0, rows)
    original = evaluate_adapter(
        MeanAdapter(1), features, target, groups, folds, layout, 11, 0.90
    )
    changed = target.copy()
    changed[folds[0].validation] += 10_000.0
    perturbed = evaluate_adapter(
        MeanAdapter(1), features, changed, groups, folds, layout, 11, 0.90
    )
    assert perturbed.fold_calibration_radii[0] == pytest.approx(
        original.fold_calibration_radii[0]
    )
    assert original.metrics.calibration_sample_source == "INNER_GROUP_OOF"
    assert len(original.fold_calibration_radii) == len(folds)


def test_nested_conformal_reports_honest_outer_fold_interval_statistics() -> None:
    rows = 48
    features = pd.DataFrame(
        {"numeric": np.linspace(0.0, 1.0, rows), "category": ["A"] * rows}
    )
    layout = FeatureLayout(numeric=["numeric"], categorical=["category"])
    groups = np.repeat(np.arange(12), 4)
    result = evaluate_adapter(
        MeanAdapter(1),
        features,
        np.sin(np.linspace(0.0, 4.0, rows)),
        groups,
        group_folds(groups, 4),
        layout,
        12,
        0.90,
    )
    expected_width = np.mean(result.calibrated_upper - result.calibrated_lower)
    assert result.metrics.mean_interval_width == pytest.approx(expected_width)
    assert result.metrics.evaluation_radius_mean == pytest.approx(
        np.mean(result.fold_calibration_radii)
    )


def test_validation_manifest_binds_effective_rows_and_both_fold_sets() -> None:
    _, _, row_ids, lineages, sheets, _ = domain_fixture()
    lineage_folds = group_folds(lineages, 5)
    sheet_folds = group_folds(sheets, 5)
    first = _validation_manifest(row_ids, lineage_folds, sheet_folds)
    repeated = _validation_manifest(row_ids, lineage_folds, sheet_folds)
    changed = _validation_manifest(row_ids[::-1], lineage_folds, sheet_folds)
    assert first == repeated
    assert first.effective_row_count == 60
    assert first.effective_row_ids_sha256 != changed.effective_row_ids_sha256
    assert sum(first.lineage_fold_sizes) == 60
    assert sum(first.sheet_fold_sizes) == 60


def test_model_card_excludes_nondeterministic_timings() -> None:
    candidate = CandidateModelMetric(
        model_type=ModelType.RANDOM_FOREST,
        status="ELIGIBLE",
        lineage_cv=metric(),
        sheet_cv=metric(),
        training_time_seconds=12.3,
        prediction_time_seconds=4.5,
        algorithm=AlgorithmMetadata(
            model_type=ModelType.RANDOM_FOREST,
            library="scikit-learn",
            library_version="test",
            seed=7,
        ),
    )
    result = TargetTrainingResult(
        target_code="Y",
        target_type=ValueType.CONTINUOUS,
        status="READY",
        scorer_type=ModelType.RANDOM_FOREST,
        model_selection=ModelSelectionSummary(
            eligible_models=[ModelType.RANDOM_FOREST],
            candidate_metrics=[candidate],
            champion_model=ModelType.RANDOM_FOREST,
            selection_reason="test",
        ),
    )
    card = _model_card_target(result)
    persisted = card["models"]["candidateMetrics"][0]
    assert persisted["trainingTimeSeconds"] == 0.0
    assert persisted["predictionTimeSeconds"] == 0.0
    assert candidate.training_time_seconds == 12.3


def test_algorithm_equivalence_hash_is_independent_of_observational_metadata() -> None:
    left = {
        "reproducibility": {"algorithmEquivalenceSha256": "a" * 64},
        "targets": [{"targetCode": "Y", "scorerType": "RANDOM_FOREST"}],
        "run": {"seconds": 1.0},
    }
    right = deepcopy(left)
    right["run"]["seconds"] = 999.0
    comparison = compare_model_cards(left, right)
    assert comparison.equivalent is True
    assert comparison.differing_targets == ()


def test_score_contract_allows_20000_rows_but_not_more(profile) -> None:
    formula = {item.code: 0.0 for item in profile.formula.materials}
    formula[profile.formula.main_resin_codes[0]] = 58.0
    formula["DSP-3315"] = 1.5
    formula["SS059/PC=1/1"] = 1.2
    formula["S-48"] = 39.3
    context = {item.code: "x" for item in profile.context_features}
    for item in profile.context_features:
        if item.value_type == "NUMERIC":
            context[item.code] = 1.0
    base = {
        "requestId": "batch-limit",
        "taskProfileHash": "a" * 64,
        "snapshotHash": "b" * 64,
        "modelBundleHash": "c" * 64,
        "modelBundle": {"url": "https://example.invalid/model.zip", "sha256": "c" * 64},
        "taskProfile": profile.model_dump(mode="json", by_alias=True),
        "targetCodes": [profile.targets[0].code],
    }
    row = {"rowId": "batch", "formula": formula, "context": context}
    assert len(ScoreRequest.model_validate({**base, "rows": [row] * 20_000}).rows) == 20_000
    with pytest.raises(ValidationError):
        ScoreRequest.model_validate({**base, "rows": [row] * 20_001})


def test_alternate_seed_point_cv_is_reproducible() -> None:
    rows = 48
    features = pd.DataFrame(
        {"numeric": np.linspace(0.0, 1.0, rows), "category": ["A"] * rows}
    )
    target = np.sin(np.linspace(0.0, 4.0, rows))
    groups = np.repeat(np.arange(12), 4)
    layout = FeatureLayout(numeric=["numeric"], categorical=["category"])
    folds = group_folds(groups, 4)
    first = point_cv_diagnostic(
        MeanAdapter(1), features, target, groups, folds, layout, 23, 0.90
    )
    second = point_cv_diagnostic(
        MeanAdapter(1), features, target, groups, folds, layout, 23, 0.90
    )
    assert first.nmae == pytest.approx(second.nmae)
    assert first.predictions == pytest.approx(second.predictions)


def test_point_champion_respects_tie_order_only_inside_tolerance(profile) -> None:
    scores = {
        ModelType.GAUSSIAN_PROCESS: (0.10, 0.10),
        ModelType.CATBOOST: (0.095, 0.095),
        ModelType.XGBOOST: (0.08, 0.08),
    }
    assert choose_point_champion(scores, profile.model_selection) == ModelType.XGBOOST
    tied = {**scores, ModelType.XGBOOST: (0.099, 0.099)}
    assert choose_point_champion(tied, profile.model_selection) == ModelType.GAUSSIAN_PROCESS


def test_prediction_sensitivity_covers_group_deletion_missing_and_outliers() -> None:
    actual = np.linspace(0.0, 10.0, 80)
    predicted = actual + np.sin(actual) * 0.2
    groups = np.repeat(np.arange(20), 4)
    result = prediction_sensitivity(actual, predicted, groups, seed=29, repeats=20)
    assert result["deleteOneGroup"]["maximumAbsoluteDelta"] >= 0.0
    assert result["eightyPercentSubsample"]["stdNmae"] >= 0.0
    assert result["fivePercentTargetMissing"]["maximumAbsoluteDelta"] >= 0.0
    assert result["onePercentTargetOutliersFiveIqr"]["meanNmae"] > result["baselineNmae"]
