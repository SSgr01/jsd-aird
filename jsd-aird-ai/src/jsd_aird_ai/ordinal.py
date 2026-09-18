from __future__ import annotations

import time
from dataclasses import dataclass

import numpy as np
import pandas as pd
from scipy.stats import spearmanr
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import confusion_matrix

from jsd_aird_ai.contracts import ModelType, OrdinalMetricSet, TargetSpec
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.features import FeatureLayout
from jsd_aird_ai.model_adapters import common_numeric_preprocessor
from jsd_aird_ai.validation import FoldDefinition


@dataclass(frozen=True)
class ConstantThreshold:
    probability: float

    def predict_probability(self, rows: int) -> np.ndarray:
        return np.full(rows, self.probability, dtype=float)


@dataclass
class FittedOrdinalModel:
    model_type: ModelType
    layout: FeatureLayout
    preprocessor: object
    thresholds: list[object]
    ordered_values: list[float]
    ordered_labels: list[str]

    def class_probabilities(self, features: pd.DataFrame) -> np.ndarray:
        transformed = self.preprocessor.transform(features)
        cumulative_columns = []
        for threshold in self.thresholds:
            if isinstance(threshold, ConstantThreshold):
                cumulative_columns.append(threshold.predict_probability(len(features)))
            else:
                cumulative_columns.append(threshold.predict_proba(transformed)[:, 1])
        cumulative = np.column_stack(cumulative_columns)
        cumulative = np.clip(cumulative, 0.0, 1.0)
        # P(Y > level_i) must be non-increasing as the threshold grows.
        cumulative = np.minimum.accumulate(cumulative, axis=1)
        probabilities = np.column_stack(
            [
                1.0 - cumulative[:, 0],
                *[
                    cumulative[:, index - 1] - cumulative[:, index]
                    for index in range(1, cumulative.shape[1])
                ],
                cumulative[:, -1],
            ]
        )
        probabilities = np.clip(probabilities, 0.0, 1.0)
        totals = probabilities.sum(axis=1, keepdims=True)
        return probabilities / np.where(totals > 0.0, totals, 1.0)

    def predict(self, features: pd.DataFrame) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
        probabilities = self.class_probabilities(features)
        predicted = probabilities.argmax(axis=1)
        lower, upper = ordinal_probability_interval(probabilities, coverage=0.90)
        return predicted, probabilities, lower, upper


@dataclass(frozen=True)
class OrdinalCvEvaluation:
    metrics: OrdinalMetricSet
    probabilities: np.ndarray
    predicted_indices: np.ndarray
    lower_indices: np.ndarray
    upper_indices: np.ndarray
    training_time_seconds: float
    prediction_time_seconds: float
    folds: tuple[FoldDefinition, ...]


def ordinal_probability_interval(
    probabilities: np.ndarray, coverage: float
) -> tuple[np.ndarray, np.ndarray]:
    tail = (1.0 - coverage) / 2.0
    cumulative = np.cumsum(probabilities, axis=1)
    lower = np.array(
        [int(np.searchsorted(row, tail, side="left")) for row in cumulative],
        dtype=int,
    )
    upper = np.array(
        [int(np.searchsorted(row, 1.0 - tail, side="left")) for row in cumulative],
        dtype=int,
    )
    maximum = probabilities.shape[1] - 1
    return np.clip(lower, 0, maximum), np.clip(upper, 0, maximum)


def _value_indices(values: np.ndarray, ordered_values: list[float]) -> np.ndarray:
    index = {float(value): position for position, value in enumerate(ordered_values)}
    try:
        return np.array([index[float(value)] for value in values], dtype=int)
    except KeyError as exc:
        raise FormulaModelError(
            ErrorCode.INVALID_SNAPSHOT,
            f"ordinal value {exc.args[0]} is not configured",
        ) from exc


def fit_ordinal_model(
    features: pd.DataFrame,
    target: np.ndarray,
    layout: FeatureLayout,
    target_spec: TargetSpec,
    seed: int,
) -> FittedOrdinalModel:
    ordered_values = [float(value) for value in target_spec.ordinal_values or []]
    ordered_labels = list(target_spec.ordinal_labels or [])
    if len(np.unique(target)) < 2:
        raise FormulaModelError(
            ErrorCode.MODEL_NOT_READY,
            "ordinal target has fewer than two observed classes",
        )
    processor = common_numeric_preprocessor(layout)
    transformed = processor.fit_transform(features)
    thresholds: list[object] = []
    for threshold_index, threshold_value in enumerate(ordered_values[:-1]):
        binary = (target > threshold_value).astype(int)
        observed = np.unique(binary)
        if len(observed) == 1:
            thresholds.append(ConstantThreshold(float(observed[0])))
            continue
        classifier = LogisticRegression(
            max_iter=1_000,
            solver="lbfgs",
            random_state=seed + threshold_index,
        )
        classifier.fit(transformed, binary)
        thresholds.append(classifier)
    return FittedOrdinalModel(
        model_type=ModelType.ORDINAL_CUMULATIVE_LOGIT,
        layout=layout,
        preprocessor=processor,
        thresholds=thresholds,
        ordered_values=ordered_values,
        ordered_labels=ordered_labels,
    )


def ordinal_metrics(
    actual_indices: np.ndarray,
    predicted_indices: np.ndarray,
    lower_indices: np.ndarray,
    upper_indices: np.ndarray,
    class_count: int,
) -> OrdinalMetricSet:
    correlation = spearmanr(actual_indices, predicted_indices, nan_policy="omit").statistic
    if not np.isfinite(correlation):
        correlation = 0.0
    return OrdinalMetricSet(
        grade_mae=float(np.mean(np.abs(actual_indices - predicted_indices))),
        plus_minus_one_accuracy=float(
            np.mean(np.abs(actual_indices - predicted_indices) <= 1)
        ),
        spearman=float(correlation),
        confusion_matrix=confusion_matrix(
            actual_indices,
            predicted_indices,
            labels=list(range(class_count)),
        ).astype(int).tolist(),
        interval_coverage=float(
            np.mean((actual_indices >= lower_indices) & (actual_indices <= upper_indices))
        ),
        mean_interval_width=float(np.mean(upper_indices - lower_indices)),
    )


def evaluate_ordinal(
    features: pd.DataFrame,
    target: np.ndarray,
    folds: tuple[FoldDefinition, ...],
    layout: FeatureLayout,
    target_spec: TargetSpec,
    seed: int,
) -> OrdinalCvEvaluation:
    if len(np.unique(target)) < 2:
        raise FormulaModelError(
            ErrorCode.MODEL_NOT_READY,
            "ordinal target has fewer than two observed classes",
        )
    class_count = len(target_spec.ordinal_values or [])
    probabilities = np.full((len(target), class_count), np.nan)
    training_seconds = 0.0
    prediction_seconds = 0.0
    for fold_index, fold in enumerate(folds):
        started = time.perf_counter()
        model = fit_ordinal_model(
            features.iloc[fold.train],
            target[fold.train],
            layout,
            target_spec,
            seed + fold_index,
        )
        training_seconds += time.perf_counter() - started
        started = time.perf_counter()
        probabilities[fold.validation] = model.class_probabilities(features.iloc[fold.validation])
        prediction_seconds += time.perf_counter() - started
    if not np.all(np.isfinite(probabilities)):
        raise RuntimeError("ordinal group CV did not generate complete OOF probabilities")
    predicted = probabilities.argmax(axis=1)
    lower, upper = ordinal_probability_interval(probabilities, 0.90)
    actual = _value_indices(target, [float(item) for item in target_spec.ordinal_values or []])
    return OrdinalCvEvaluation(
        metrics=ordinal_metrics(actual, predicted, lower, upper, class_count),
        probabilities=probabilities,
        predicted_indices=predicted,
        lower_indices=lower,
        upper_indices=upper,
        training_time_seconds=training_seconds,
        prediction_time_seconds=prediction_seconds,
        folds=folds,
    )
