from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from jsd_aird_ai.artifacts import ArtifactClient
from jsd_aird_ai.contracts import (
    BaselineMetrics,
    BaselineSummary,
    BaselineType,
    CandidateModelMetric,
    ContinuousReadinessThreshold,
    MetricSet,
    ModelType,
    TargetTrainingResult,
    TargetSpec,
    TaskProfile,
    ValueType,
)
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.features import FeatureLayout
from jsd_aird_ai.model_adapters import (
    CatBoostAdapter,
    GaussianProcessAdapter,
    LightGBMAdapter,
    RandomForestAdapter,
    XGBoostAdapter,
    catboost_native_frame,
    default_model_adapters,
)
from jsd_aird_ai.modeling import (
    _model_card_target,
    censored_count_training_block,
    model_eligibility_status,
)
from jsd_aird_ai.ordinal import evaluate_ordinal, fit_ordinal_model, ordinal_metrics
from jsd_aird_ai.readiness import continuous_readiness
from jsd_aird_ai.settings import Settings
from jsd_aird_ai.snapshot import SnapshotValidator
from jsd_aird_ai.validation import (
    conformal_radius,
    continuous_metrics,
    evaluate_adapter,
    group_folds,
    select_champion,
)


def metric(nmae: float, coverage: float = 0.90) -> MetricSet:
    return MetricSet(
        mae=nmae,
        rmse=nmae,
        nmae=nmae,
        r2=0.8,
        spearman=0.8,
        interval_coverage=coverage,
        mean_interval_width=1.0,
        calibrated_radius=0.5,
    )


def baseline(lineage: float = 0.50, sheet: float = 0.50) -> BaselineSummary:
    return BaselineSummary(
        type=BaselineType.DEVELOPMENT_KNN,
        version="test",
        metrics=BaselineMetrics(lineage_nmae=lineage, sheet_nmae=sheet),
    )


def permissive_threshold() -> ContinuousReadinessThreshold:
    return ContinuousReadinessThreshold(
        min_samples=1,
        min_groups=2,
        min_baseline_improvement=0.0,
        max_nmae=1.0,
        min_interval_coverage=0.85,
        max_interval_coverage=0.95,
        required_baseline_type=BaselineType.DEVELOPMENT_KNN,
    )


def candidate(model_type: ModelType, nmae: float) -> CandidateModelMetric:
    return CandidateModelMetric(
        model_type=model_type,
        status="ELIGIBLE",
        lineage_cv=metric(nmae),
        sheet_cv=metric(nmae + 0.001),
    )


def small_features(rows: int = 60) -> tuple[pd.DataFrame, np.ndarray, FeatureLayout]:
    x = np.linspace(0.0, 1.0, rows)
    frame = pd.DataFrame(
        {
            "amount": x,
            "energy": 0.4 + x * 0.5,
            "resin": np.where(np.arange(rows) % 2 == 0, "A", "B"),
        }
    )
    target = 2.0 * x + (frame["resin"] == "B").astype(float).to_numpy() * 0.2
    return frame, target, FeatureLayout(
        numeric=["amount", "energy"],
        categorical=["resin"],
    )


def test_source_sheet_group_kfold_has_no_leakage(validation_request) -> None:
    snapshot = SnapshotValidator(ArtifactClient(Settings(allow_file_urls=True))).load_and_validate(
        validation_request
    )
    groups = snapshot.joined["source_sheet"].astype(str).to_numpy()
    folds = group_folds(groups, 5)
    for fold in folds:
        assert set(groups[fold.train]).isdisjoint(set(groups[fold.validation]))


def test_lineage_group_kfold_has_no_leakage(validation_request) -> None:
    snapshot = SnapshotValidator(ArtifactClient(Settings(allow_file_urls=True))).load_and_validate(
        validation_request
    )
    groups = snapshot.joined["formula_lineage_group"].astype(str).to_numpy()
    folds = group_folds(groups, 5)
    for fold in folds:
        assert set(groups[fold.train]).isdisjoint(set(groups[fold.validation]))


def test_source_sheet_multi_lineage_statistics_are_reported(validation_request) -> None:
    response = SnapshotValidator(ArtifactClient(Settings(allow_file_urls=True))).load_and_validate(
        validation_request
    ).response
    joined = SnapshotValidator(ArtifactClient(Settings(allow_file_urls=True))).load_and_validate(
        validation_request
    ).joined
    actual = joined.groupby("source_sheet")["formula_lineage_group"].nunique()
    assert response.source_sheet_stats.total_sheets == len(actual)
    assert response.source_sheet_stats.sheets_with_multiple_lineages == int((actual > 1).sum())
    assert response.source_sheet_stats.max_lineages_per_sheet == int(actual.max())


def test_production_readiness_requires_both_group_cv(profile: TaskProfile) -> None:
    result = continuous_readiness(
        thresholds=profile.readiness_thresholds,
        data_nature="REAL",
        snapshot_purpose="TRAINING",
        snapshot_production_eligible=True,
        context_compatible=True,
        sample_count=200,
        lineage_groups=20,
        sheet_groups=20,
        lineage_cv=metric(0.10),
        sheet_cv=metric(0.30),
        baseline=BaselineSummary(
            type=BaselineType.T06_SIMILAR_CASE,
            version="test",
            metrics=BaselineMetrics(lineage_nmae=0.20, sheet_nmae=0.40),
        ),
    )
    assert result.checks["lineageCv"] is True
    assert result.checks["sheetCv"] is False
    assert "SHEET_CV_NMAE_THRESHOLD_NOT_MET" in result.blocking_reasons


def test_synthetic_data_is_an_explicit_production_block(profile: TaskProfile) -> None:
    result = continuous_readiness(
        thresholds=profile.readiness_thresholds,
        data_nature="SYNTHETIC",
        snapshot_purpose="DEVELOPMENT",
        snapshot_production_eligible=False,
        context_compatible=True,
        sample_count=400,
        lineage_groups=40,
        sheet_groups=67,
        lineage_cv=metric(0.10),
        sheet_cv=metric(0.10),
        baseline=baseline(0.20, 0.20),
    )
    assert "SYNTHETIC_DATA_NOT_PRODUCTION_ELIGIBLE" in result.blocking_reasons
    assert "FORMAL_BASELINE_REQUIRED" in result.blocking_reasons


def test_thresholds_are_loaded_from_task_profile(profile: TaskProfile) -> None:
    assert profile.readiness_thresholds.development.min_samples == 30
    assert profile.readiness_thresholds.production_continuous.min_samples == 80
    assert profile.readiness_thresholds.production_ordinal.min_samples == 100
    assert profile.readiness_thresholds.production_binary.min_samples == 80


@pytest.mark.parametrize(
    "model_type",
    [ModelType.LIGHTGBM, ModelType.XGBOOST, ModelType.CATBOOST],
)
def test_boosters_skip_below_configured_sample_eligibility(
    profile: TaskProfile, model_type: ModelType
) -> None:
    assert model_eligibility_status(profile, model_type, 149) == (
        "SKIPPED_INSUFFICIENT_SAMPLES"
    )
    assert model_eligibility_status(profile, model_type, 150) == "ELIGIBLE"


def test_gp_and_rf_are_eligible_at_development_minimum(profile: TaskProfile) -> None:
    assert model_eligibility_status(profile, ModelType.GAUSSIAN_PROCESS, 30) == "ELIGIBLE"
    assert model_eligibility_status(profile, ModelType.RANDOM_FOREST, 30) == "ELIGIBLE"


@pytest.mark.parametrize(
    "adapter_factory,params_override",
    [
        (LightGBMAdapter, {"n_estimators": 30}),
        (XGBoostAdapter, {"n_estimators": 30}),
        (CatBoostAdapter, {"iterations": 30}),
    ],
)
def test_booster_fixed_seed_is_reproducible(adapter_factory, params_override) -> None:
    features, target, layout = small_features()
    adapter = adapter_factory(1)
    params = {**adapter.parameter_candidates()[0], **params_override}
    first = adapter.fit(features, target, layout, 1234, params, 0.90)
    second = adapter.fit(features, target, layout, 1234, params, 0.90)
    assert first.point_predict(features) == pytest.approx(second.point_predict(features), abs=1e-12)


def test_xgboost_uses_common_numeric_one_hot_pipeline() -> None:
    features, target, layout = small_features()
    adapter = XGBoostAdapter(1)
    params = {**adapter.parameter_candidates()[0], "n_estimators": 20}
    fitted = adapter.fit(features, target, layout, 11, params, 0.90)
    assert fitted.native_categorical is False
    assert fitted.preprocessor is not None
    assert len(fitted.preprocessor.get_feature_names_out()) > len(layout.numeric)


def test_catboost_uses_native_categorical_pipeline() -> None:
    features, target, layout = small_features()
    adapter = CatBoostAdapter(1)
    params = {**adapter.parameter_candidates()[0], "iterations": 20}
    fitted = adapter.fit(features, target, layout, 11, params, 0.90)
    native = catboost_native_frame(features, layout)
    assert fitted.native_categorical is True
    assert fitted.preprocessor is None
    assert native["resin"].dtype == object


def test_all_continuous_adapters_share_supplied_outer_fold_rows() -> None:
    features, target, layout = small_features(50)
    groups = np.repeat(np.arange(10), 5)
    folds = group_folds(groups, 5)
    for adapter in [
        GaussianProcessAdapter(1),
        RandomForestAdapter(1),
        LightGBMAdapter(1),
        XGBoostAdapter(1),
        CatBoostAdapter(1),
    ]:
        first = dict(adapter.parameter_candidates()[0])
        if "n_estimators" in first:
            first["n_estimators"] = 10
        if "iterations" in first:
            first["iterations"] = 10
        adapter.parameter_candidates = lambda selected=first: [selected]  # type: ignore[method-assign]
        evaluation = evaluate_adapter(
            adapter, features, target, groups, folds, layout, 99, 0.90
        )
        assert evaluation.folds is folds
        assert sorted(np.concatenate([fold.validation for fold in evaluation.folds])) == list(
            range(len(features))
        )


@pytest.mark.parametrize(
    "winner",
    [
        ModelType.GAUSSIAN_PROCESS,
        ModelType.RANDOM_FOREST,
        ModelType.LIGHTGBM,
        ModelType.XGBOOST,
        ModelType.CATBOOST,
    ],
)
def test_champion_selection_can_choose_every_continuous_model(winner: ModelType) -> None:
    all_types = [
        ModelType.GAUSSIAN_PROCESS,
        ModelType.RANDOM_FOREST,
        ModelType.LIGHTGBM,
        ModelType.XGBOOST,
        ModelType.CATBOOST,
    ]
    candidates = [candidate(item, 0.05 if item == winner else 0.20) for item in all_types]
    policy_order = [winner, *[item for item in all_types if item != winner]]
    from jsd_aird_ai.contracts import ModelSelectionPolicy

    selected = select_champion(
        candidates,
        baseline(),
        ModelSelectionPolicy(
            tie_nmae_tolerance=0.01,
            model_tie_break_order=policy_order,
        ),
        permissive_threshold(),
    )
    assert selected.champion_model == winner


def test_failed_challenger_does_not_block_other_models(profile: TaskProfile) -> None:
    failed = CandidateModelMetric(
        model_type=ModelType.XGBOOST,
        status="TRAINING_FAILED",
        failure_reason="expected test failure",
    )
    selected = select_champion(
        [failed, candidate(ModelType.RANDOM_FOREST, 0.1)],
        baseline(),
        profile.model_selection,
        permissive_threshold(),
    )
    assert selected.champion_model == ModelType.RANDOM_FOREST
    assert selected.failed_models == [ModelType.XGBOOST]


def test_tie_break_order_only_applies_inside_tolerance(profile: TaskProfile) -> None:
    close = select_champion(
        [
            candidate(ModelType.GAUSSIAN_PROCESS, 0.105),
            candidate(ModelType.XGBOOST, 0.100),
        ],
        baseline(),
        profile.model_selection,
        permissive_threshold(),
    )
    clear = select_champion(
        [
            candidate(ModelType.GAUSSIAN_PROCESS, 0.120),
            candidate(ModelType.XGBOOST, 0.100),
        ],
        baseline(),
        profile.model_selection,
        permissive_threshold(),
    )
    assert close.champion_model == ModelType.GAUSSIAN_PROCESS
    assert clear.champion_model == ModelType.XGBOOST


def test_random_kfold_diagnostic_is_not_a_champion_gate(profile: TaskProfile) -> None:
    gp = candidate(ModelType.GAUSSIAN_PROCESS, 0.10)
    gp.random_kfold_diagnostic = metric(999.0, coverage=0.0)
    selected = select_champion(
        [gp, candidate(ModelType.RANDOM_FOREST, 0.20)],
        baseline(),
        profile.model_selection,
        permissive_threshold(),
    )
    assert selected.champion_model == ModelType.GAUSSIAN_PROCESS


def test_model_card_exposes_required_a2_sections(profile: TaskProfile) -> None:
    selection = select_champion(
        [candidate(ModelType.GAUSSIAN_PROCESS, 0.10)],
        baseline(),
        profile.model_selection,
        permissive_threshold(),
    )
    card = _model_card_target(
        TargetTrainingResult(
            target_code="Y__TEST",
            target_type=ValueType.CONTINUOUS,
            status="READY",
            scorer_type=ModelType.GAUSSIAN_PROCESS,
            lineage_cv=metric(0.10),
            sheet_cv=metric(0.11),
            model_selection=selection,
        )
    )
    assert card["target"]["targetType"] == ValueType.CONTINUOUS
    assert card["validation"]["lineageCv"]["nmae"] == pytest.approx(0.10)
    assert card["validation"]["sheetCv"]["nmae"] == pytest.approx(0.11)
    assert card["models"]["championModel"] == ModelType.GAUSSIAN_PROCESS
    assert card["algorithm"] is None  # synthetic unit candidate omits metadata intentionally


def test_conformal_radius_uses_supplied_oof_residuals() -> None:
    actual = np.arange(20, dtype=float)
    in_sample = actual.copy()
    oof = actual + np.linspace(-2.0, 2.0, len(actual))
    assert conformal_radius(actual, in_sample, 0.90) == 0.0
    assert conformal_radius(actual, oof, 0.90) > 1.0


def test_interval_coverage_is_computed_from_bounds() -> None:
    actual = np.array([0.0, 1.0, 2.0, 3.0])
    predicted = np.array([0.0, 1.0, 2.0, 2.0])
    lower = np.array([-0.1, 0.9, 1.9, 1.9])
    upper = np.array([0.1, 1.1, 2.1, 2.1])
    result = continuous_metrics(actual, predicted, lower, upper, 0.1, None, None)
    assert result.interval_coverage == 0.75
    assert result.raw_interval_coverage is None


def test_gp_preserves_raw_and_calibrated_intervals() -> None:
    features, target, layout = small_features(40)
    groups = np.repeat(np.arange(8), 5)
    folds = group_folds(groups, 4)
    result = evaluate_adapter(
        GaussianProcessAdapter(1), features, target, groups, folds, layout, 7, 0.90
    )
    assert result.metrics.raw_interval_coverage is not None
    assert result.metrics.calibrated_radius > 0.0
    assert result.metrics.interval_coverage >= 0.90


@pytest.mark.parametrize("adapter_factory", [LightGBMAdapter, XGBoostAdapter, CatBoostAdapter])
def test_boosters_do_not_fabricate_raw_probability_intervals(adapter_factory) -> None:
    features, target, layout = small_features(30)
    adapter = adapter_factory(1)
    params = adapter.parameter_candidates()[0]
    if "n_estimators" in params:
        params = {**params, "n_estimators": 20}
    if "iterations" in params:
        params = {**params, "iterations": 20}
    fitted = adapter.fit(features, target, layout, 3, params, 0.90)
    assert fitted.raw_interval(features) is None


def test_ordinal_hardness_trains_without_unobserved_4h(profile: TaskProfile) -> None:
    features, _, layout = small_features(60)
    target = np.tile(np.array([1.0, 2.0, 3.0]), 20)
    spec = profile.targets[2]
    model = fit_ordinal_model(features, target, layout, spec, 8)
    predicted, probabilities, lower, upper = model.predict(features)
    assert model.ordered_labels == ["H", "2H", "3H", "4H"]
    assert probabilities.sum(axis=1) == pytest.approx(np.ones(len(features)))
    assert np.all((predicted >= 0) & (predicted <= 3))
    assert np.all(lower <= upper)


def test_ordinal_metrics_use_grade_distance_not_r2(profile: TaskProfile) -> None:
    features, _, layout = small_features(60)
    target = np.tile(np.array([1.0, 2.0, 3.0]), 20)
    groups = np.repeat(np.arange(12), 5)
    result = evaluate_ordinal(
        features,
        target,
        group_folds(groups, 4),
        layout,
        profile.targets[2],
        18,
    )
    assert result.metrics.grade_mae >= 0.0
    assert 0.0 <= result.metrics.plus_minus_one_accuracy <= 1.0
    assert len(result.metrics.confusion_matrix) == 4
    assert not hasattr(result.metrics, "r2")


def test_ordinal_grade_mae_and_plus_minus_one_accuracy_are_exact() -> None:
    actual = np.array([0, 1, 2, 3])
    predicted = np.array([0, 2, 0, 3])
    result = ordinal_metrics(
        actual,
        predicted,
        np.minimum(actual, predicted),
        np.maximum(actual, predicted),
        class_count=4,
    )
    assert result.grade_mae == pytest.approx(0.75)
    assert result.plus_minus_one_accuracy == pytest.approx(0.75)
    assert result.interval_coverage == 1.0


def test_ordinal_with_one_observed_class_is_not_ready(profile: TaskProfile) -> None:
    features, _, layout = small_features(20)
    with pytest.raises(FormulaModelError) as raised:
        fit_ordinal_model(
            features,
            np.ones(len(features)),
            layout,
            profile.targets[2],
            2,
        )
    assert raised.value.code == ErrorCode.MODEL_NOT_READY


def test_target_type_supports_censored_count(profile: TaskProfile) -> None:
    payload = profile.targets[0].model_dump(mode="json", by_alias=True)
    payload.update(
        {
            "valueType": "CENSORED_COUNT",
            "censoredColumn": "Y__TEST__CENSORED",
            "censorTypeColumn": "Y__TEST__CENSOR_TYPE",
        }
    )
    target = TargetSpec.model_validate(payload)
    assert target.value_type == ValueType.CENSORED_COUNT
    assert target.censored_column == "Y__TEST__CENSORED"
    rows = pd.DataFrame(
        {
            "Y__TEST__CENSORED": [False, True],
            "Y__TEST__CENSOR_TYPE": [None, "RIGHT"],
        }
    )
    assert censored_count_training_block(rows, target) == (
        "CENSORED_MODEL_NOT_IMPLEMENTED"
    )


def test_current_synthetic_steel_wool_targets_remain_continuous(profile: TaskProfile) -> None:
    by_code = {item.code: item for item in profile.targets}
    assert by_code["Y__STEEL_WOOL_500G_CYCLES"].value_type == ValueType.CONTINUOUS
    assert by_code["Y__STEEL_WOOL_1KG_CYCLES"].value_type == ValueType.CONTINUOUS


@pytest.mark.parametrize(
    "adapter_factory",
    [LightGBMAdapter, XGBoostAdapter, CatBoostAdapter],
)
def test_boosters_do_not_write_training_files(
    adapter_factory,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    features, target, layout = small_features(30)
    adapter = adapter_factory(1)
    params = dict(adapter.parameter_candidates()[0])
    if "n_estimators" in params:
        params["n_estimators"] = 10
    if "iterations" in params:
        params["iterations"] = 10
    monkeypatch.chdir(tmp_path)
    adapter.fit(features, target, layout, 42, params, 0.90)
    assert list(tmp_path.iterdir()) == []


def test_every_default_adapter_uses_bounded_thread_count() -> None:
    adapters = default_model_adapters(2)
    assert len(adapters) == 5
    assert all(adapter.thread_count == 2 for adapter in adapters)
    assert {adapter.model_type for adapter in adapters} == {
        ModelType.GAUSSIAN_PROCESS,
        ModelType.RANDOM_FOREST,
        ModelType.LIGHTGBM,
        ModelType.XGBOOST,
        ModelType.CATBOOST,
    }
