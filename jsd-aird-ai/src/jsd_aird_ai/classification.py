from __future__ import annotations

import json
import math
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, replace
from typing import Any

import catboost
import lightgbm
import numpy as np
import pandas as pd
import sklearn
import xgboost
from catboost import CatBoostClassifier
from lightgbm import LGBMClassifier
from sklearn.dummy import DummyClassifier
from sklearn.ensemble import RandomForestClassifier
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    balanced_accuracy_score,
    brier_score_loss,
    confusion_matrix,
    f1_score,
    log_loss,
    precision_recall_fscore_support,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.utils.class_weight import compute_sample_weight
from xgboost import XGBClassifier

from jsd_aird_ai.contracts import (
    AlgorithmMetadata,
    BinaryMetricSet,
    CandidateModelMetric,
    CategoricalMetricSet,
    ClassificationModelSelectionPolicy,
    ModelSelectionSummary,
    ModelType,
    PerClassMetric,
    ProbabilityCalibrationPolicy,
    ProbabilityQuality,
    ValueType,
)
from jsd_aird_ai.features import FeatureLayout
from jsd_aird_ai.model_adapters import (
    catboost_native_frame,
    common_numeric_preprocessor,
)
from jsd_aird_ai.validation import FoldDefinition, group_folds


def encode_class_labels(values: np.ndarray, class_labels: list[str]) -> np.ndarray:
    index = {label: position for position, label in enumerate(class_labels)}
    try:
        return np.asarray([index[str(value)] for value in values], dtype=int)
    except KeyError as exc:
        raise ValueError(f"unconfigured classification label: {exc.args[0]}") from exc


@dataclass
class FittedProbabilityCalibrator:
    method: str
    class_count: int
    estimator: Any | None = None
    isotonic_models: tuple[Any | None, ...] = ()
    estimator_classes: tuple[int, ...] = ()

    def calibrate(self, raw_probabilities: np.ndarray) -> np.ndarray:
        raw = _normalized_probabilities(raw_probabilities)
        if self.estimator is None and not self.isotonic_models:
            return raw
        if self.method == "PLATT":
            if self.class_count == 2:
                feature = _binary_logit(raw[:, 1]).reshape(-1, 1)
            else:
                feature = np.log(np.clip(raw, 1e-12, 1.0))
            local = self.estimator.predict_proba(feature)
            result = np.zeros((len(raw), self.class_count), dtype=float)
            for local_index, global_index in enumerate(self.estimator_classes):
                result[:, global_index] = local[:, local_index]
            return _normalized_probabilities(result)

        calibrated = np.zeros_like(raw)
        for class_index, model in enumerate(self.isotonic_models):
            calibrated[:, class_index] = (
                raw[:, class_index]
                if model is None
                else model.predict(raw[:, class_index])
            )
        return _normalized_probabilities(calibrated)


def fit_probability_calibrator(
    raw_probabilities: np.ndarray,
    target: np.ndarray,
    policy: ProbabilityCalibrationPolicy,
    seed: int,
) -> FittedProbabilityCalibrator:
    raw = _normalized_probabilities(raw_probabilities)
    class_count = raw.shape[1]
    observed = tuple(int(item) for item in np.unique(target))
    if len(observed) < 2:
        return FittedProbabilityCalibrator(policy.method, class_count)
    if policy.method == "PLATT":
        feature = (
            _binary_logit(raw[:, 1]).reshape(-1, 1)
            if class_count == 2
            else np.log(np.clip(raw, 1e-12, 1.0))
        )
        estimator = LogisticRegression(
            max_iter=1_000,
            solver="lbfgs",
            random_state=seed,
        )
        estimator.fit(feature, target)
        return FittedProbabilityCalibrator(
            method="PLATT",
            class_count=class_count,
            estimator=estimator,
            estimator_classes=tuple(int(item) for item in estimator.classes_),
        )

    models: list[Any | None] = []
    for class_index in range(class_count):
        binary = (target == class_index).astype(int)
        if len(np.unique(binary)) < 2:
            models.append(None)
        else:
            model = IsotonicRegression(out_of_bounds="clip")
            model.fit(raw[:, class_index], binary)
            models.append(model)
    return FittedProbabilityCalibrator(
        method="ISOTONIC",
        class_count=class_count,
        isotonic_models=tuple(models),
    )


def _binary_logit(probability: np.ndarray) -> np.ndarray:
    clipped = np.clip(probability, 1e-8, 1.0 - 1e-8)
    return np.log(clipped / (1.0 - clipped))


def _normalized_probabilities(probabilities: np.ndarray) -> np.ndarray:
    result = np.clip(np.asarray(probabilities, dtype=float), 1e-12, 1.0)
    totals = result.sum(axis=1, keepdims=True)
    return result / np.where(totals > 0.0, totals, 1.0)


@dataclass
class FittedClassificationModel:
    model_type: ModelType
    estimator: Any
    layout: FeatureLayout
    preprocessor: Any | None
    native_categorical: bool
    class_labels: tuple[str, ...]
    estimator_classes: tuple[int, ...]
    target_type: ValueType
    positive_class: str | None
    decision_threshold: float
    calibration_method: str
    calibrator: FittedProbabilityCalibrator | None = None

    def raw_class_probabilities(self, features: pd.DataFrame) -> np.ndarray:
        model_input = (
            catboost_native_frame(features, self.layout)
            if self.native_categorical
            else self.preprocessor.transform(features)
        )
        local = np.asarray(self.estimator.predict_proba(model_input), dtype=float)
        if local.ndim == 1:
            local = np.column_stack([1.0 - local, local])
        result = np.zeros((len(features), len(self.class_labels)), dtype=float)
        for local_index, global_index in enumerate(self.estimator_classes):
            result[:, global_index] = local[:, local_index]
        return _normalized_probabilities(result)

    def class_probabilities(self, features: pd.DataFrame) -> np.ndarray:
        raw = self.raw_class_probabilities(features)
        return raw if self.calibrator is None else self.calibrator.calibrate(raw)

    def predict(
        self, features: pd.DataFrame
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        raw = self.raw_class_probabilities(features)
        calibrated = raw if self.calibrator is None else self.calibrator.calibrate(raw)
        if self.target_type == ValueType.BINARY:
            positive_index = self.class_labels.index(str(self.positive_class))
            negative_index = 1 - positive_index
            indices = np.where(
                calibrated[:, positive_index] >= self.decision_threshold,
                positive_index,
                negative_index,
            )
        else:
            indices = np.argmax(calibrated, axis=1)
        labels = np.asarray([self.class_labels[int(index)] for index in indices])
        return labels, raw, calibrated

    def calibrated(
        self, calibrator: FittedProbabilityCalibrator
    ) -> "FittedClassificationModel":
        return replace(self, calibrator=calibrator)


class ClassificationModelAdapter(ABC):
    model_type: ModelType
    library: str
    library_version: str
    native_categorical: bool = False

    def __init__(self, thread_count: int) -> None:
        self.thread_count = max(1, int(thread_count))

    @abstractmethod
    def parameter_candidates(self) -> list[dict[str, Any]]:
        raise NotImplementedError

    @abstractmethod
    def _estimator(
        self,
        seed: int,
        params: dict[str, Any],
        dimension: int,
        class_count: int,
    ) -> Any:
        raise NotImplementedError

    def fit(
        self,
        features: pd.DataFrame,
        target: np.ndarray,
        layout: FeatureLayout,
        seed: int,
        params: dict[str, Any],
        class_labels: list[str],
        target_type: ValueType,
        positive_class: str | None,
        decision_threshold: float,
        calibration_method: str,
    ) -> FittedClassificationModel:
        observed_global = np.unique(target).astype(int)
        local_by_global = {
            int(global_index): local_index
            for local_index, global_index in enumerate(observed_global)
        }
        local_target = np.asarray(
            [local_by_global[int(item)] for item in target], dtype=int
        )
        if self.native_categorical:
            preprocessor = None
            model_input: Any = catboost_native_frame(features, layout)
            dimension = len(layout.all)
        else:
            preprocessor = common_numeric_preprocessor(layout)
            model_input = preprocessor.fit_transform(features)
            dimension = int(model_input.shape[1])
        if len(observed_global) < 2:
            estimator: Any = DummyClassifier(strategy="most_frequent")
        else:
            estimator = self._estimator(
                seed, params, dimension, len(observed_global)
            )
        weights = compute_sample_weight(class_weight="balanced", y=local_target)
        if self.native_categorical and not isinstance(estimator, DummyClassifier):
            estimator.fit(
                model_input,
                local_target,
                cat_features=layout.categorical,
                sample_weight=weights,
            )
        else:
            estimator.fit(model_input, local_target, sample_weight=weights)
        estimator_classes = tuple(
            int(observed_global[int(item)]) for item in estimator.classes_
        )
        return FittedClassificationModel(
            model_type=self.model_type,
            estimator=estimator,
            layout=layout,
            preprocessor=preprocessor,
            native_categorical=self.native_categorical,
            class_labels=tuple(class_labels),
            estimator_classes=estimator_classes,
            target_type=target_type,
            positive_class=positive_class,
            decision_threshold=decision_threshold,
            calibration_method=calibration_method,
        )

    def metadata(
        self, final_params: dict[str, Any], seed: int
    ) -> AlgorithmMetadata:
        return AlgorithmMetadata(
            model_type=self.model_type,
            library=self.library,
            library_version=self.library_version,
            final_params=final_params,
            seed=seed,
        )


class LogisticRegressionClassificationAdapter(ClassificationModelAdapter):
    model_type = ModelType.LOGISTIC_REGRESSION
    library = "scikit-learn"
    library_version = sklearn.__version__

    def parameter_candidates(self) -> list[dict[str, Any]]:
        return [{"C": 0.5}, {"C": 2.0}]

    def _estimator(self, seed, params, dimension, class_count):
        return LogisticRegression(
            C=float(params["C"]),
            max_iter=1_000,
            solver="lbfgs",
            random_state=seed,
        )


class RandomForestClassificationAdapter(ClassificationModelAdapter):
    model_type = ModelType.RANDOM_FOREST
    library = "scikit-learn"
    library_version = sklearn.__version__

    def parameter_candidates(self) -> list[dict[str, Any]]:
        return [
            {"n_estimators": 80, "min_samples_leaf": 2, "max_features": 0.7},
            {"n_estimators": 120, "min_samples_leaf": 4, "max_features": 1.0},
        ]

    def _estimator(self, seed, params, dimension, class_count):
        return RandomForestClassifier(
            **params,
            random_state=seed,
            n_jobs=self.thread_count,
        )


class LightGBMClassificationAdapter(ClassificationModelAdapter):
    model_type = ModelType.LIGHTGBM
    library = "lightgbm"
    library_version = lightgbm.__version__

    def parameter_candidates(self) -> list[dict[str, Any]]:
        return [
            {"num_leaves": 15, "learning_rate": 0.03, "n_estimators": 80},
            {"num_leaves": 31, "learning_rate": 0.05, "n_estimators": 120},
        ]

    def _estimator(self, seed, params, dimension, class_count):
        return LGBMClassifier(
            **params,
            objective="binary" if class_count == 2 else "multiclass",
            verbosity=-1,
            random_state=seed,
            n_jobs=self.thread_count,
            deterministic=True,
            force_col_wise=True,
        )


class XGBoostClassificationAdapter(ClassificationModelAdapter):
    model_type = ModelType.XGBOOST
    library = "xgboost"
    library_version = xgboost.__version__

    def parameter_candidates(self) -> list[dict[str, Any]]:
        return [
            {"max_depth": 3, "learning_rate": 0.03, "n_estimators": 80},
            {"max_depth": 5, "learning_rate": 0.05, "n_estimators": 120},
        ]

    def _estimator(self, seed, params, dimension, class_count):
        return XGBClassifier(
            **params,
            objective="binary:logistic" if class_count == 2 else "multi:softprob",
            eval_metric="logloss" if class_count == 2 else "mlogloss",
            random_state=seed,
            n_jobs=self.thread_count,
            verbosity=0,
            tree_method="hist",
        )


class CatBoostClassificationAdapter(ClassificationModelAdapter):
    model_type = ModelType.CATBOOST
    library = "catboost"
    library_version = catboost.__version__
    native_categorical = True

    def parameter_candidates(self) -> list[dict[str, Any]]:
        return [
            {"depth": 4, "learning_rate": 0.03, "iterations": 80},
            {"depth": 6, "learning_rate": 0.05, "iterations": 120},
        ]

    def _estimator(self, seed, params, dimension, class_count):
        return CatBoostClassifier(
            **params,
            loss_function="Logloss" if class_count == 2 else "MultiClass",
            verbose=False,
            allow_writing_files=False,
            random_seed=seed,
            thread_count=self.thread_count,
            random_strength=0.0,
            bootstrap_type="No",
        )


def default_classification_adapters(
    thread_count: int,
) -> list[ClassificationModelAdapter]:
    return [
        LogisticRegressionClassificationAdapter(thread_count),
        RandomForestClassificationAdapter(thread_count),
        LightGBMClassificationAdapter(thread_count),
        XGBoostClassificationAdapter(thread_count),
        CatBoostClassificationAdapter(thread_count),
    ]


@dataclass(frozen=True)
class ClassificationCvEvaluation:
    metrics: BinaryMetricSet | CategoricalMetricSet
    raw_probabilities: np.ndarray
    calibrated_probabilities: np.ndarray
    selected_params: dict[str, Any]
    deployment_calibrator: FittedProbabilityCalibrator
    training_time_seconds: float
    prediction_time_seconds: float
    folds: tuple[FoldDefinition, ...]
    fold_scores: tuple[float, ...]
    calibration_training_rows: tuple[tuple[int, ...], ...]
    validation_rows: tuple[tuple[int, ...], ...]


def _expected_calibration_error(
    actual: np.ndarray,
    probabilities: np.ndarray,
    target_type: ValueType,
    positive_index: int | None,
    bins: int,
) -> float:
    if target_type == ValueType.BINARY:
        confidence = probabilities[:, int(positive_index)]
        outcome = (actual == int(positive_index)).astype(float)
    else:
        predicted = probabilities.argmax(axis=1)
        confidence = probabilities.max(axis=1)
        outcome = (predicted == actual).astype(float)
    edges = np.linspace(0.0, 1.0, bins + 1)
    result = 0.0
    for index in range(bins):
        mask = (confidence >= edges[index]) & (
            confidence <= edges[index + 1]
            if index == bins - 1
            else confidence < edges[index + 1]
        )
        if np.any(mask):
            result += float(np.mean(mask)) * abs(
                float(np.mean(confidence[mask])) - float(np.mean(outcome[mask]))
            )
    return result


def _probability_quality(
    actual: np.ndarray,
    probabilities: np.ndarray,
    target_type: ValueType,
    positive_index: int | None,
    bins: int,
) -> ProbabilityQuality:
    class_count = probabilities.shape[1]
    binary_actual = (
        (actual == int(positive_index)).astype(int)
        if target_type == ValueType.BINARY
        else None
    )
    return ProbabilityQuality(
        log_loss=float(log_loss(actual, probabilities, labels=list(range(class_count)))),
        brier_score=(
            float(
                brier_score_loss(
                    binary_actual, probabilities[:, int(positive_index)]
                )
            )
            if binary_actual is not None
            else None
        ),
        expected_calibration_error=_expected_calibration_error(
            actual, probabilities, target_type, positive_index, bins
        ),
    )


def classification_metrics(
    actual: np.ndarray,
    raw_probabilities: np.ndarray,
    calibrated_probabilities: np.ndarray,
    target_type: ValueType,
    class_labels: list[str],
    positive_class: str | None,
    decision_threshold: float,
    ece_bins: int,
) -> BinaryMetricSet | CategoricalMetricSet:
    raw = _normalized_probabilities(raw_probabilities)
    calibrated = _normalized_probabilities(calibrated_probabilities)
    if target_type == ValueType.BINARY:
        positive_index = class_labels.index(str(positive_class))
        negative_index = 1 - positive_index
        binary_actual = (actual == positive_index).astype(int)
        binary_predicted = (
            calibrated[:, positive_index] >= decision_threshold
        ).astype(int)
        predicted = np.where(binary_predicted == 1, positive_index, negative_index)
        counts = np.bincount(actual, minlength=2)
        minority_index = int(np.argmin(counts))
        minority_recall = recall_score(
            (actual == minority_index).astype(int),
            (predicted == minority_index).astype(int),
            zero_division=0,
        )
        roc_auc = (
            float(roc_auc_score(binary_actual, calibrated[:, positive_index]))
            if len(np.unique(binary_actual)) == 2
            else 0.5
        )
        pr_auc = (
            float(average_precision_score(binary_actual, calibrated[:, positive_index]))
            if len(np.unique(binary_actual)) == 2
            else float(np.mean(binary_actual))
        )
        return BinaryMetricSet(
            roc_auc=roc_auc,
            pr_auc=pr_auc,
            precision=float(precision_score(binary_actual, binary_predicted, zero_division=0)),
            recall=float(recall_score(binary_actual, binary_predicted, zero_division=0)),
            f1=float(f1_score(binary_actual, binary_predicted, zero_division=0)),
            minority_class=class_labels[minority_index],
            minority_class_recall=float(minority_recall),
            confusion_matrix=confusion_matrix(
                actual, predicted, labels=list(range(2))
            ).astype(int).tolist(),
            raw_probability_quality=_probability_quality(
                actual, raw, target_type, positive_index, ece_bins
            ),
            calibrated_probability_quality=_probability_quality(
                actual, calibrated, target_type, positive_index, ece_bins
            ),
            decision_threshold=decision_threshold,
        )

    predicted = calibrated.argmax(axis=1)
    precision, recall, f1, support = precision_recall_fscore_support(
        actual,
        predicted,
        labels=list(range(len(class_labels))),
        zero_division=0,
    )
    per_class = {
        label: PerClassMetric(
            precision=float(precision[index]),
            recall=float(recall[index]),
            f1=float(f1[index]),
            support=int(support[index]),
        )
        for index, label in enumerate(class_labels)
    }
    return CategoricalMetricSet(
        macro_f1=float(f1_score(actual, predicted, average="macro", zero_division=0)),
        weighted_f1=float(
            f1_score(actual, predicted, average="weighted", zero_division=0)
        ),
        balanced_accuracy=float(balanced_accuracy_score(actual, predicted)),
        per_class=per_class,
        confusion_matrix=confusion_matrix(
            actual, predicted, labels=list(range(len(class_labels)))
        ).astype(int).tolist(),
        raw_probability_quality=_probability_quality(
            actual, raw, target_type, None, ece_bins
        ),
        calibrated_probability_quality=_probability_quality(
            actual, calibrated, target_type, None, ece_bins
        ),
    )


def classification_selection_score(
    metrics: BinaryMetricSet | CategoricalMetricSet,
) -> float:
    if isinstance(metrics, BinaryMetricSet):
        return (
            0.35 * metrics.calibrated_probability_quality.log_loss / math.log(2.0)
            + 0.25 * (1.0 - metrics.pr_auc)
            + 0.20 * (1.0 - metrics.f1)
            + 0.20 * (1.0 - metrics.minority_class_recall)
        )
    class_count = max(len(metrics.per_class), 2)
    return (
        0.40 * (1.0 - metrics.macro_f1)
        + 0.25 * (1.0 - metrics.balanced_accuracy)
        + 0.35
        * metrics.calibrated_probability_quality.log_loss
        / math.log(class_count)
    )


def _parameter_key(params: dict[str, Any]) -> str:
    return json.dumps(params, sort_keys=True, separators=(",", ":"))


def _mode_params(parameters: list[dict[str, Any]]) -> dict[str, Any]:
    keys = [_parameter_key(item) for item in parameters]
    selected = min(set(keys), key=lambda key: (-keys.count(key), key))
    return json.loads(selected)


def _inner_raw_probabilities(
    adapter: ClassificationModelAdapter,
    features: pd.DataFrame,
    target: np.ndarray,
    groups: np.ndarray,
    layout: FeatureLayout,
    seed: int,
    params: dict[str, Any],
    class_labels: list[str],
    target_type: ValueType,
    positive_class: str | None,
    decision_threshold: float,
    calibration_method: str,
    folds: int,
) -> np.ndarray:
    probabilities = np.full((len(target), len(class_labels)), np.nan)
    for fold_index, fold in enumerate(group_folds(groups, folds)):
        model = adapter.fit(
            features.iloc[fold.train],
            target[fold.train],
            layout,
            seed + fold_index,
            params,
            class_labels,
            target_type,
            positive_class,
            decision_threshold,
            calibration_method,
        )
        probabilities[fold.validation] = model.raw_class_probabilities(
            features.iloc[fold.validation]
        )
    if not np.all(np.isfinite(probabilities)):
        raise RuntimeError("inner group CV did not generate complete class probabilities")
    return probabilities


def evaluate_classification_adapter(
    adapter: ClassificationModelAdapter,
    features: pd.DataFrame,
    target: np.ndarray,
    groups: np.ndarray,
    outer_folds: tuple[FoldDefinition, ...],
    layout: FeatureLayout,
    seed: int,
    target_type: ValueType,
    class_labels: list[str],
    positive_class: str | None,
    decision_threshold: float,
    calibration_policy: ProbabilityCalibrationPolicy,
) -> ClassificationCvEvaluation:
    raw_oof = np.full((len(target), len(class_labels)), np.nan)
    calibrated_oof = np.full_like(raw_oof, np.nan)
    selected_params: list[dict[str, Any]] = []
    training_seconds = 0.0
    prediction_seconds = 0.0
    fold_scores: list[float] = []

    for fold_index, fold in enumerate(outer_folds):
        train_features = features.iloc[fold.train].reset_index(drop=True)
        train_target = target[fold.train]
        train_groups = groups[fold.train]
        started = time.perf_counter()
        options = []
        for option_index, params in enumerate(adapter.parameter_candidates()):
            inner_raw = _inner_raw_probabilities(
                adapter,
                train_features,
                train_target,
                train_groups,
                layout,
                seed + 10_000 + fold_index * 100 + option_index * 10,
                params,
                class_labels,
                target_type,
                positive_class,
                decision_threshold,
                calibration_policy.method,
                min(calibration_policy.calibration_folds, len(np.unique(train_groups))),
            )
            calibrator = fit_probability_calibrator(
                inner_raw,
                train_target,
                calibration_policy,
                seed + 20_000 + fold_index * 100 + option_index,
            )
            inner_calibrated = calibrator.calibrate(inner_raw)
            metrics = classification_metrics(
                train_target,
                inner_raw,
                inner_calibrated,
                target_type,
                class_labels,
                positive_class,
                decision_threshold,
                calibration_policy.expected_calibration_error_bins,
            )
            options.append(
                (
                    classification_selection_score(metrics),
                    _parameter_key(params),
                    params,
                    calibrator,
                )
            )
        _, _, params, calibrator = min(options, key=lambda item: (item[0], item[1]))
        model = adapter.fit(
            train_features,
            train_target,
            layout,
            seed + fold_index,
            params,
            class_labels,
            target_type,
            positive_class,
            decision_threshold,
            calibration_policy.method,
        )
        training_seconds += time.perf_counter() - started
        selected_params.append(dict(params))

        started = time.perf_counter()
        fold_raw = model.raw_class_probabilities(features.iloc[fold.validation])
        fold_calibrated = calibrator.calibrate(fold_raw)
        prediction_seconds += time.perf_counter() - started
        raw_oof[fold.validation] = fold_raw
        calibrated_oof[fold.validation] = fold_calibrated
        fold_metrics = classification_metrics(
            target[fold.validation],
            fold_raw,
            fold_calibrated,
            target_type,
            class_labels,
            positive_class,
            decision_threshold,
            calibration_policy.expected_calibration_error_bins,
        )
        fold_scores.append(classification_selection_score(fold_metrics))

    if not np.all(np.isfinite(raw_oof)) or not np.all(np.isfinite(calibrated_oof)):
        raise RuntimeError("outer group CV did not generate complete class probabilities")
    deployment_calibrator = fit_probability_calibrator(
        raw_oof,
        target,
        calibration_policy,
        seed + 90_000,
    )
    return ClassificationCvEvaluation(
        metrics=classification_metrics(
            target,
            raw_oof,
            calibrated_oof,
            target_type,
            class_labels,
            positive_class,
            decision_threshold,
            calibration_policy.expected_calibration_error_bins,
        ),
        raw_probabilities=raw_oof,
        calibrated_probabilities=calibrated_oof,
        selected_params=_mode_params(selected_params),
        deployment_calibrator=deployment_calibrator,
        training_time_seconds=training_seconds,
        prediction_time_seconds=prediction_seconds,
        folds=outer_folds,
        fold_scores=tuple(fold_scores),
        calibration_training_rows=tuple(
            tuple(int(index) for index in fold.train) for fold in outer_folds
        ),
        validation_rows=tuple(
            tuple(int(index) for index in fold.validation) for fold in outer_folds
        ),
    )


def select_classification_champion(
    candidates: list[CandidateModelMetric],
    target_type: ValueType,
    policy: ClassificationModelSelectionPolicy,
) -> ModelSelectionSummary:
    completed = [
        item
        for item in candidates
        if item.status == "ELIGIBLE"
        and item.lineage_cv is not None
        and item.sheet_cv is not None
    ]
    if not completed:
        return ModelSelectionSummary(
            eligible_models=[],
            skipped_models=[
                item.model_type
                for item in candidates
                if item.status == "SKIPPED_INSUFFICIENT_SAMPLES"
            ],
            failed_models=[
                item.model_type
                for item in candidates
                if item.status == "TRAINING_FAILED"
            ],
            candidate_metrics=candidates,
            selection_reason="no classification challenger completed both group CVs",
        )

    scores = {
        item.model_type: max(
            classification_selection_score(item.lineage_cv),
            classification_selection_score(item.sheet_cv),
        )
        for item in completed
    }
    best_score = min(scores.values())
    order = (
        policy.binary_tie_break_order
        if target_type == ValueType.BINARY
        else policy.categorical_tie_break_order
    )
    rank = {model: index for index, model in enumerate(order)}

    if target_type == ValueType.CATEGORICAL:
        # A categorical model with a lower worst-side score remains the champion.
        # Approximate score bands are useful diagnostics, but must not let a fixed
        # algorithm-name order replace a numerically better and cheaper model.
        exact_ties = [
            item
            for item in completed
            if math.isclose(
                scores[item.model_type],
                best_score,
                rel_tol=0.0,
                abs_tol=1e-12,
            )
        ]

        def categorical_tie_key(item: CandidateModelMetric) -> tuple[float, float, float, int]:
            def fold_stability(values: list[float]) -> float:
                return float(np.std(values)) if len(values) >= 2 else math.inf

            stability = max(
                fold_stability(item.lineage_fold_scores),
                fold_stability(item.sheet_fold_scores),
            )
            probability_quality = max(
                item.lineage_cv.calibrated_probability_quality.log_loss,
                item.sheet_cv.calibrated_probability_quality.log_loss,
            )
            compute_seconds = item.training_time_seconds + item.prediction_time_seconds
            return stability, probability_quality, compute_seconds, rank[item.model_type]

        champion = min(exact_ties, key=categorical_tie_key)
        if len(exact_ties) == 1:
            reason = (
                f"lowest worst-group classification score "
                f"{scores[champion.model_type]:.6f}; categorical approximate "
                "tie tolerance is diagnostic-only and cannot override the numeric best"
            )
        else:
            stability, probability_quality, compute_seconds, _ = categorical_tie_key(
                champion
            )
            reason = (
                f"equal lowest worst-group classification score "
                f"{scores[champion.model_type]:.6f}; resolved by dual-CV fold "
                f"stability {stability:.6f}, worst calibrated log loss "
                f"{probability_quality:.6f}, compute seconds {compute_seconds:.6f}, "
                "then deterministic model tie-break order"
            )
        return ModelSelectionSummary(
            eligible_models=[item.model_type for item in completed],
            skipped_models=[
                item.model_type
                for item in candidates
                if item.status == "SKIPPED_INSUFFICIENT_SAMPLES"
            ],
            failed_models=[
                item.model_type
                for item in candidates
                if item.status == "TRAINING_FAILED"
            ],
            candidate_metrics=candidates,
            champion_model=champion.model_type,
            selection_reason=reason,
        )

    near_tie = [
        item
        for item in completed
        if scores[item.model_type] - best_score < policy.tie_score_tolerance
    ]
    champion = min(near_tie, key=lambda item: rank[item.model_type])
    reason = (
        f"lowest worst-group classification score {scores[champion.model_type]:.6f}"
        if len(near_tie) == 1
        else (
            f"worst-group classification score {scores[champion.model_type]:.6f}; "
            f"{len(near_tie)} models were within {policy.tie_score_tolerance:.6f}, "
            "selected by the target-type tie-break order"
        )
    )
    return ModelSelectionSummary(
        eligible_models=[item.model_type for item in completed],
        skipped_models=[
            item.model_type
            for item in candidates
            if item.status == "SKIPPED_INSUFFICIENT_SAMPLES"
        ],
        failed_models=[
            item.model_type
            for item in candidates
            if item.status == "TRAINING_FAILED"
        ],
        candidate_metrics=candidates,
        champion_model=champion.model_type,
        selection_reason=reason,
    )
