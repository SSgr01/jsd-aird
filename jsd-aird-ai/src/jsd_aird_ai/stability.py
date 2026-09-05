from __future__ import annotations

import json
import time
import warnings
from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

from jsd_aird_ai.contracts import ModelSelectionPolicy, ModelType
from jsd_aird_ai.features import FeatureLayout
from jsd_aird_ai.model_adapters import ModelAdapter
from jsd_aird_ai.validation import FoldDefinition, _inner_nmae


@dataclass(frozen=True)
class PointCvDiagnostic:
    nmae: float
    predictions: np.ndarray
    selected_params: dict[str, Any]
    elapsed_seconds: float


def normalized_mae(actual: np.ndarray, predicted: np.ndarray) -> float:
    values = np.asarray(actual, dtype=float)
    estimates = np.asarray(predicted, dtype=float)
    scale = max(
        float(np.nanpercentile(values, 95) - np.nanpercentile(values, 5)),
        1e-12,
    )
    return float(np.mean(np.abs(values - estimates)) / scale)


def point_cv_diagnostic(
    adapter: ModelAdapter,
    features: pd.DataFrame,
    target: np.ndarray,
    groups: np.ndarray,
    folds: tuple[FoldDefinition, ...],
    layout: FeatureLayout,
    seed: int,
    interval_level: float,
) -> PointCvDiagnostic:
    """Fast stability replay: same outer point CV, without repeated UQ calibration fits."""
    predictions = np.full(len(target), np.nan)
    choices: list[dict[str, Any]] = []
    started = time.perf_counter()
    candidates = adapter.parameter_candidates()
    for fold_index, fold in enumerate(folds):
        if len(candidates) == 1:
            params = candidates[0]
        else:
            options = []
            for option_index, option in enumerate(candidates):
                score = _inner_nmae(
                    adapter,
                    features.iloc[fold.train].reset_index(drop=True),
                    target[fold.train],
                    groups[fold.train],
                    layout,
                    seed + 10_000 + fold_index * 100 + option_index * 10,
                    option,
                    len(folds),
                    interval_level,
                )
                key = json.dumps(option, sort_keys=True, separators=(",", ":"))
                options.append((score, key, option))
            params = min(options, key=lambda item: (item[0], item[1]))[2]
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
        predictions[fold.validation] = scorer.point_predict(features.iloc[fold.validation])
        choices.append(dict(params))
    if not np.all(np.isfinite(predictions)):
        raise RuntimeError("stability point CV did not generate complete predictions")
    keys = [json.dumps(item, sort_keys=True, separators=(",", ":")) for item in choices]
    selected_key = min(set(keys), key=lambda key: (-keys.count(key), key))
    return PointCvDiagnostic(
        nmae=normalized_mae(target, predictions),
        predictions=predictions,
        selected_params=json.loads(selected_key),
        elapsed_seconds=time.perf_counter() - started,
    )


def choose_point_champion(
    scores: dict[ModelType, tuple[float, float]],
    policy: ModelSelectionPolicy,
) -> ModelType:
    worst = {model: max(values) for model, values in scores.items()}
    best = min(worst.values())
    tied = {
        model
        for model, score in worst.items()
        if score - best < policy.tie_nmae_tolerance
    }
    return next(model for model in policy.model_tie_break_order if model in tied)


def prediction_sensitivity(
    actual: np.ndarray,
    predicted: np.ndarray,
    groups: np.ndarray,
    seed: int,
    repeats: int = 100,
) -> dict[str, Any]:
    """Quantify evaluation sensitivity without mutating the immutable snapshot."""
    actual = np.asarray(actual, dtype=float)
    predicted = np.asarray(predicted, dtype=float)
    groups = np.asarray(groups)
    baseline = normalized_mae(actual, predicted)
    deletion = [
        normalized_mae(actual[groups != group], predicted[groups != group])
        for group in np.unique(groups)
    ]
    rng = np.random.default_rng(seed)
    sample_size = max(2, int(np.floor(len(actual) * 0.80)))
    missing_size = max(1, int(np.ceil(len(actual) * 0.05)))
    outlier_size = max(1, int(np.ceil(len(actual) * 0.01)))
    subsample: list[float] = []
    missing: list[float] = []
    outlier: list[float] = []
    target_iqr = max(
        float(np.nanpercentile(actual, 75) - np.nanpercentile(actual, 25)),
        1e-12,
    )
    for _ in range(repeats):
        sample = rng.choice(len(actual), size=sample_size, replace=False)
        subsample.append(normalized_mae(actual[sample], predicted[sample]))
        dropped = rng.choice(len(actual), size=missing_size, replace=False)
        retained = np.ones(len(actual), dtype=bool)
        retained[dropped] = False
        missing.append(normalized_mae(actual[retained], predicted[retained]))
        affected = rng.choice(len(actual), size=outlier_size, replace=False)
        changed = actual.copy()
        changed[affected] += 5.0 * target_iqr
        outlier.append(normalized_mae(changed, predicted))

    def summary(values: list[float]) -> dict[str, float]:
        array = np.asarray(values, dtype=float)
        return {
            "meanNmae": float(np.mean(array)),
            "stdNmae": float(np.std(array)),
            "minimumNmae": float(np.min(array)),
            "maximumNmae": float(np.max(array)),
            "maximumAbsoluteDelta": float(np.max(np.abs(array - baseline))),
        }

    return {
        "baselineNmae": baseline,
        "deleteOneGroup": summary(deletion),
        "eightyPercentSubsample": summary(subsample),
        "fivePercentTargetMissing": summary(missing),
        "onePercentTargetOutliersFiveIqr": summary(outlier),
        "repeats": repeats,
    }
