from __future__ import annotations

import json
import math
import time
import warnings
from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd
from scipy.stats import spearmanr
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import GroupKFold, KFold
from sklearn.neighbors import KNeighborsRegressor

from jsd_aird_ai.contracts import (
    BaselineMetrics,
    BaselineSummary,
    BaselineType,
    CandidateModelMetric,
    ContinuousReadinessThreshold,
    MetricSet,
    ModelSelectionPolicy,
    ModelSelectionSummary,
    ModelType,
)
from jsd_aird_ai.features import FeatureLayout
from jsd_aird_ai.model_adapters import ModelAdapter, common_numeric_preprocessor


@dataclass(frozen=True)
class FoldDefinition:
    train: np.ndarray
    validation: np.ndarray


@dataclass(frozen=True)
class CvEvaluation:
    metrics: MetricSet
    predictions: np.ndarray
    calibrated_lower: np.ndarray
    calibrated_upper: np.ndarray
    raw_lower: np.ndarray | None
    raw_upper: np.ndarray | None
    selected_params: dict[str, Any]
    training_time_seconds: float
    prediction_time_seconds: float
    folds: tuple[FoldDefinition, ...]
    fold_calibration_radii: tuple[float, ...]
    fold_nmae: tuple[float, ...]


def group_folds(groups: np.ndarray, folds: int) -> tuple[FoldDefinition, ...]:
    unique = np.unique(groups)
    if len(unique) < 2:
        raise ValueError("group CV requires at least two independent groups")
    splitter = GroupKFold(n_splits=min(folds, len(unique)))
    result = tuple(
        FoldDefinition(train=np.asarray(train), validation=np.asarray(validation))
        for train, validation in splitter.split(np.zeros(len(groups)), groups=groups)
    )
    for fold in result:
        if set(groups[fold.train]).intersection(groups[fold.validation]):
            raise AssertionError("group leakage detected in generated fold")
    return result


def random_folds(rows: int, folds: int, seed: int) -> tuple[FoldDefinition, ...]:
    """Diagnostic-only splits. Never pass these to readiness or champion selection."""
    splitter = KFold(n_splits=min(folds, rows), shuffle=True, random_state=seed)
    return tuple(
        FoldDefinition(train=np.asarray(train), validation=np.asarray(validation))
        for train, validation in splitter.split(np.zeros(rows))
    )


def conformal_radius(actual: np.ndarray, predicted: np.ndarray, coverage: float) -> float:
    residuals = np.abs(np.asarray(actual, dtype=float) - np.asarray(predicted, dtype=float))
    if len(residuals) == 0 or not np.all(np.isfinite(residuals)):
        raise ValueError("conformal calibration requires finite group-isolated OOF residuals")
    finite_sample_level = min(1.0, math.ceil((len(residuals) + 1) * coverage) / len(residuals))
    return float(np.quantile(residuals, finite_sample_level, method="higher"))


def _point_metrics(actual: np.ndarray, predicted: np.ndarray) -> tuple[float, float, float, float, float]:
    scale = max(
        float(np.nanpercentile(actual, 95) - np.nanpercentile(actual, 5)),
        1e-12,
    )
    mae = float(mean_absolute_error(actual, predicted))
    correlation = spearmanr(actual, predicted, nan_policy="omit").statistic
    if not np.isfinite(correlation):
        correlation = 0.0
    r2 = r2_score(actual, predicted)
    if not np.isfinite(r2):
        r2 = 0.0
    return (
        mae,
        float(math.sqrt(mean_squared_error(actual, predicted))),
        mae / scale,
        float(r2),
        float(correlation),
    )


def continuous_metrics(
    actual: np.ndarray,
    predicted: np.ndarray,
    calibrated_lower: np.ndarray,
    calibrated_upper: np.ndarray,
    calibrated_radius_value: float,
    raw_lower: np.ndarray | None,
    raw_upper: np.ndarray | None,
    evaluation_radii: tuple[float, ...] | None = None,
) -> MetricSet:
    mae, rmse, nmae, r2, correlation = _point_metrics(actual, predicted)
    raw_coverage = None
    raw_width = None
    if raw_lower is not None and raw_upper is not None:
        raw_coverage = float(np.mean((actual >= raw_lower) & (actual <= raw_upper)))
        raw_width = float(np.mean(raw_upper - raw_lower))
    return MetricSet(
        mae=mae,
        rmse=rmse,
        nmae=nmae,
        r2=r2,
        spearman=correlation,
        interval_coverage=float(
            np.mean((actual >= calibrated_lower) & (actual <= calibrated_upper))
        ),
        mean_interval_width=float(np.mean(calibrated_upper - calibrated_lower)),
        raw_interval_coverage=raw_coverage,
        raw_mean_interval_width=raw_width,
        calibrated_radius=calibrated_radius_value,
        evaluation_radius_mean=float(np.mean(evaluation_radii or (calibrated_radius_value,))),
        evaluation_radius_max=float(np.max(evaluation_radii or (calibrated_radius_value,))),
    )


def _parameter_key(params: dict[str, Any]) -> str:
    return json.dumps(params, sort_keys=True, separators=(",", ":"))


def _mode_params(parameters: list[dict[str, Any]]) -> dict[str, Any]:
    keys = [_parameter_key(item) for item in parameters]
    selected = min(set(keys), key=lambda key: (-keys.count(key), key))
    return json.loads(selected)


def _inner_oof_predictions(
    adapter: ModelAdapter,
    features: pd.DataFrame,
    target: np.ndarray,
    groups: np.ndarray,
    layout: FeatureLayout,
    seed: int,
    params: dict[str, Any],
    folds: int,
    interval_level: float,
) -> np.ndarray:
    predictions = np.full(len(target), np.nan)
    for fold_index, fold in enumerate(group_folds(groups, min(3, folds))):
        scorer = adapter.fit(
            features.iloc[fold.train],
            target[fold.train],
            layout,
            seed + fold_index,
            params,
            interval_level,
        )
        predictions[fold.validation] = scorer.point_predict(features.iloc[fold.validation])
    if not np.all(np.isfinite(predictions)):
        raise RuntimeError("inner group CV did not generate a complete OOF prediction")
    return predictions


def _inner_nmae(
    adapter: ModelAdapter,
    features: pd.DataFrame,
    target: np.ndarray,
    groups: np.ndarray,
    layout: FeatureLayout,
    seed: int,
    params: dict[str, Any],
    folds: int,
    interval_level: float,
) -> float:
    predictions = _inner_oof_predictions(
        adapter,
        features,
        target,
        groups,
        layout,
        seed,
        params,
        folds,
        interval_level,
    )
    return _point_metrics(target, predictions)[2]


def evaluate_adapter(
    adapter: ModelAdapter,
    features: pd.DataFrame,
    target: np.ndarray,
    groups: np.ndarray,
    outer_folds: tuple[FoldDefinition, ...],
    layout: FeatureLayout,
    seed: int,
    interval_level: float,
) -> CvEvaluation:
    predicted = np.full(len(target), np.nan)
    calibrated_lower = np.full(len(target), np.nan)
    calibrated_upper = np.full(len(target), np.nan)
    raw_lower = np.full(len(target), np.nan) if adapter.model_type == ModelType.GAUSSIAN_PROCESS else None
    raw_upper = np.full(len(target), np.nan) if adapter.model_type == ModelType.GAUSSIAN_PROCESS else None
    selected_params: list[dict[str, Any]] = []
    training_seconds = 0.0
    prediction_seconds = 0.0
    candidates = adapter.parameter_candidates()
    fold_radii: list[float] = []
    fold_nmae: list[float] = []

    for fold_index, fold in enumerate(outer_folds):
        train_features = features.iloc[fold.train].reset_index(drop=True)
        train_target = target[fold.train]
        train_groups = groups[fold.train]
        started = time.perf_counter()
        scores = []
        for option_index, option in enumerate(candidates):
            inner_predictions = _inner_oof_predictions(
                adapter,
                train_features,
                train_target,
                train_groups,
                layout,
                seed + 10_000 + fold_index * 100 + option_index * 10,
                option,
                len(outer_folds),
                interval_level,
            )
            score = _point_metrics(train_target, inner_predictions)[2]
            scores.append((score, _parameter_key(option), option, inner_predictions))
        _, _, params, calibration_predictions = min(
            scores, key=lambda item: (item[0], item[1])
        )
        fold_radius = conformal_radius(
            train_target,
            calibration_predictions,
            interval_level,
        )
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            scorer = adapter.fit(
                features.iloc[fold.train],
                target[fold.train],
                layout,
                seed + fold_index,
                params,
                interval_level,
            )
        training_seconds += time.perf_counter() - started
        selected_params.append(dict(params))

        started = time.perf_counter()
        validation_features = features.iloc[fold.validation]
        predicted[fold.validation] = scorer.point_predict(validation_features)
        calibrated_lower[fold.validation] = predicted[fold.validation] - fold_radius
        calibrated_upper[fold.validation] = predicted[fold.validation] + fold_radius
        raw = scorer.raw_interval(validation_features)
        prediction_seconds += time.perf_counter() - started
        if raw is not None and raw_lower is not None and raw_upper is not None:
            raw_lower[fold.validation], raw_upper[fold.validation] = raw
        fold_radii.append(fold_radius)
        fold_nmae.append(
            _point_metrics(target[fold.validation], predicted[fold.validation])[2]
        )

    if not np.all(np.isfinite(predicted)):
        raise RuntimeError("outer group CV did not generate a complete OOF prediction")
    if not np.all(np.isfinite(calibrated_lower)) or not np.all(np.isfinite(calibrated_upper)):
        raise RuntimeError("outer group CV did not generate complete nested conformal intervals")
    # This radius is for the final full-data scorer. It is calibrated from outer OOF
    # residuals, but is deliberately not used to measure validation PICP above.
    deployment_radius = conformal_radius(target, predicted, interval_level)
    metrics = continuous_metrics(
        target,
        predicted,
        calibrated_lower,
        calibrated_upper,
        deployment_radius,
        raw_lower,
        raw_upper,
        tuple(fold_radii),
    )
    return CvEvaluation(
        metrics=metrics,
        predictions=predicted,
        calibrated_lower=calibrated_lower,
        calibrated_upper=calibrated_upper,
        raw_lower=raw_lower,
        raw_upper=raw_upper,
        selected_params=_mode_params(selected_params),
        training_time_seconds=training_seconds,
        prediction_time_seconds=prediction_seconds,
        folds=outer_folds,
        fold_calibration_radii=tuple(fold_radii),
        fold_nmae=tuple(fold_nmae),
    )


def baseline_nmae(
    features: pd.DataFrame,
    target: np.ndarray,
    folds: tuple[FoldDefinition, ...],
    layout: FeatureLayout,
) -> float:
    predictions = np.full(len(target), np.nan)
    for fold in folds:
        processor = common_numeric_preprocessor(layout)
        train_features = processor.fit_transform(features.iloc[fold.train])
        validation_features = processor.transform(features.iloc[fold.validation])
        model = KNeighborsRegressor(
            n_neighbors=min(10, len(fold.train)),
            weights="distance",
            n_jobs=1,
        )
        model.fit(train_features, target[fold.train])
        predictions[fold.validation] = model.predict(validation_features)
    return _point_metrics(target, predictions)[2]


def development_baseline(
    features: pd.DataFrame,
    target: np.ndarray,
    lineage_folds: tuple[FoldDefinition, ...],
    sheet_folds: tuple[FoldDefinition, ...],
    layout: FeatureLayout,
) -> BaselineSummary:
    return BaselineSummary(
        type=BaselineType.DEVELOPMENT_KNN,
        version="group-knn-v1",
        metrics=BaselineMetrics(
            lineage_nmae=baseline_nmae(features, target, lineage_folds, layout),
            sheet_nmae=baseline_nmae(features, target, sheet_folds, layout),
        ),
    )


def _selection_score(candidate: CandidateModelMetric) -> float:
    if candidate.lineage_cv is None or candidate.sheet_cv is None:
        return math.inf
    return max(candidate.lineage_cv.nmae, candidate.sheet_cv.nmae)


def select_champion(
    candidates: list[CandidateModelMetric],
    baseline: BaselineSummary,
    policy: ModelSelectionPolicy,
    thresholds: ContinuousReadinessThreshold,
) -> ModelSelectionSummary:
    completed = [
        item
        for item in candidates
        if item.status == "ELIGIBLE" and item.lineage_cv is not None and item.sheet_cv is not None
    ]
    successful = [
        item
        for item in completed
        if item.lineage_cv.nmae <= thresholds.max_nmae
        and item.sheet_cv.nmae <= thresholds.max_nmae
        and thresholds.min_interval_coverage
        <= item.lineage_cv.interval_coverage
        <= thresholds.max_interval_coverage
        and thresholds.min_interval_coverage
        <= item.sheet_cv.interval_coverage
        <= thresholds.max_interval_coverage
        and (
            baseline.metrics.lineage_nmae - item.lineage_cv.nmae
        )
        / max(baseline.metrics.lineage_nmae, 1e-12)
        >= thresholds.min_baseline_improvement
        and (
            baseline.metrics.sheet_nmae - item.sheet_cv.nmae
        )
        / max(baseline.metrics.sheet_nmae, 1e-12)
        >= thresholds.min_baseline_improvement
    ]
    if not successful:
        return ModelSelectionSummary(
            eligible_models=[item.model_type for item in completed],
            skipped_models=[
                item.model_type
                for item in candidates
                if item.status == "SKIPPED_INSUFFICIENT_SAMPLES"
            ],
            failed_models=[
                item.model_type for item in candidates if item.status == "TRAINING_FAILED"
            ],
            candidate_metrics=candidates,
            selection_reason=(
                "no continuous challenger passed baseline, lineage CV, sheet CV and "
                "calibrated interval gates"
            ),
        )

    best_score = min(_selection_score(item) for item in successful)
    near_tie = [
        item
        for item in successful
        if _selection_score(item) - best_score < policy.tie_nmae_tolerance
    ]
    tie_rank = {model: index for index, model in enumerate(policy.model_tie_break_order)}
    champion = min(near_tie, key=lambda item: tie_rank[item.model_type])
    champion_score = _selection_score(champion)
    if len(near_tie) > 1:
        reason = (
            f"worst-group NMAE {champion_score:.6f}; {len(near_tie)} models were within "
            f"{policy.tie_nmae_tolerance:.6f}, selected by configured tie-break order"
        )
    else:
        reason = (
            f"lowest worst-group NMAE {champion_score:.6f}; difference from all other "
            "successful challengers exceeded the configured tie tolerance"
        )
    return ModelSelectionSummary(
        eligible_models=[item.model_type for item in completed],
        skipped_models=[
            item.model_type
            for item in candidates
            if item.status == "SKIPPED_INSUFFICIENT_SAMPLES"
        ],
        failed_models=[
            item.model_type for item in candidates if item.status == "TRAINING_FAILED"
        ],
        candidate_metrics=candidates,
        champion_model=champion.model_type,
        selection_reason=reason,
    )
