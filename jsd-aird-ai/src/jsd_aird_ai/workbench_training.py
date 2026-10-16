"""Fold-local preprocessing and diagnostics for freely selected table columns."""
from __future__ import annotations

from collections import defaultdict
import re
from typing import Any

import numpy as np
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor
from sklearn.feature_extraction import DictVectorizer
from sklearn.gaussian_process import GaussianProcessRegressor
from sklearn.gaussian_process.kernels import ConstantKernel, Matern, WhiteKernel
from sklearn.linear_model import LinearRegression, LogisticRegression, Ridge
from sklearn.metrics import accuracy_score, f1_score, log_loss, mean_absolute_error, mean_squared_error, r2_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler


def checked_value(value: Any, numeric: bool) -> tuple[Any, str | None]:
    if value is None or isinstance(value, str) and not value.strip():
        return None, "missing"
    if numeric:
        try:
            if isinstance(value, str) and not re.fullmatch(r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?", value.strip()):
                return None, "invalid"
            number = float(value)
            if isinstance(value, bool) or not np.isfinite(number):
                return None, "invalid"
            return number, None
        except (ValueError, TypeError, OverflowError):
            return None, "invalid"
    if isinstance(value, (dict, list, bool)):
        return None, "invalid"
    return str(value).strip(), None


def prepare_data(x_columns: list[dict], y_column: dict, rows: list[dict]) -> tuple[np.ndarray, np.ndarray, dict]:
    columns = x_columns + [y_column]
    fields = [dict(code=c["code"], name=c.get("name") or c["code"], role="Y" if i == len(x_columns) else "X",
                   missingCount=0, invalidCount=0, distinctCount=0)
              for i, c in enumerate(columns)]
    features, targets = [], []
    groups: dict[tuple, list[dict]] = defaultdict(list)
    distinct = [set() for _ in columns]
    for index, row in enumerate(rows):
        values = row.get("values", {}) if isinstance(row, dict) else {}
        if not isinstance(values, dict):
            values = {}
        cleaned = []
        valid = True
        for column, field in zip(columns, fields):
            value, problem = checked_value(values.get(column["code"]), column.get("kind") == "NUMERIC")
            cleaned.append(value)
            if problem:
                field[f"{problem}Count"] += 1
                valid = False
        if not valid:
            continue
        record = dict(zip([c["code"] for c in x_columns], cleaned[:-1]))
        features.append(record)
        targets.append(cleaned[-1])
        for items, value in zip(distinct, cleaned):
            items.add(value)
        groups[tuple(cleaned[:-1])].append(dict(
            label=row.get("label") or row.get("title") or f"第 {index + 1} 条记录",
            source=row.get("sourceDatasetName") or "", result=cleaned[-1],
        ))
    for field, items in zip(fields, distinct):
        field["distinctCount"] = len(items)
    conflicts = [(key, items) for key, items in groups.items() if len({item["result"] for item in items}) > 1]
    checks = dict(
        selectedRowCount=len(rows), usableRowCount=len(targets), excludedRowCount=len(rows) - len(targets),
        fields=fields,
        constantXColumns=[dict(code=f["code"], name=f["name"]) for f in fields[:-1] if f["distinctCount"] == 1],
        duplicateInputGroupCount=sum(len(items) > 1 for items in groups.values()),
        conflictingInputGroupCount=len(conflicts),
        conflictingRowCount=sum(len(items) for _, items in conflicts),
        conflictingExamples=[dict(inputs=dict(zip([c["code"] for c in x_columns], key)),
                                  results=list(dict.fromkeys(item["result"] for item in items)),
                                  records=items[:5], rowCount=len(items)) for key, items in conflicts[:5]],
    )
    return np.asarray(features, dtype=object), np.asarray(targets, dtype=float if y_column["kind"] == "NUMERIC" else str), checks


class NonConstantFeatures(TransformerMixin, BaseEstimator):
    """Remove constant encoded columns, keeping an intercept-only fold trainable."""

    def fit(self, x: np.ndarray, y: Any = None) -> NonConstantFeatures:
        self.support_ = np.ptp(x, axis=0) > 0
        if not self.support_.any():
            self.support_[:] = True
        return self

    def transform(self, x: np.ndarray) -> np.ndarray:
        return np.asarray(x)[:, self.support_]


class WorkbenchGaussianProcess(GaussianProcessRegressor):
    """Build an ARD kernel after this fold's category encoding determines its width."""

    def __init__(self, random_state: int = 17, noise_level: float = .01, n_restarts_optimizer: int = 2):
        self.noise_level = noise_level
        super().__init__(normalize_y=True, random_state=random_state, n_restarts_optimizer=n_restarts_optimizer)

    def fit(self, x: np.ndarray, y: np.ndarray) -> WorkbenchGaussianProcess:
        self.kernel = (ConstantKernel(1, (1e-3, 1e3)) * Matern(length_scale=np.ones(x.shape[1]), nu=2.5)
                       + WhiteKernel(self.noise_level, (1e-8, .5)))
        return super().fit(x, y)


def candidates(numeric: bool, count: int, minimum_train_count: int, seed: int, threads: int,
               feature_count: int = 1) -> list[tuple[str, dict, Pipeline]]:
    result = []

    def add(name: str, params: dict, estimator: Any) -> None:
        # Every learned transformation is cloned and fitted inside each training fold.
        pipeline = Pipeline([("vectorizer", DictVectorizer(sparse=False)), ("nonconstant", NonConstantFeatures()),
                             ("scaler", StandardScaler()), ("estimator", estimator)])
        result.append((name, params, pipeline))

    if numeric:
        add("LINEAR_REGRESSION", {}, LinearRegression())
        for alpha in (.01, 1., 10.):
            add("RIDGE", {"alpha": alpha}, Ridge(alpha=alpha))
        if count <= 200 and feature_count <= 60:
            for noise in (.01, .1):
                add("GAUSSIAN_PROCESS", {"noiseLevel": noise, "optimizerRestarts": 2},
                    WorkbenchGaussianProcess(random_state=seed, noise_level=noise))
    else:
        for c in (1., 10.):
            add("LOGISTIC_REGRESSION", {"C": c}, LogisticRegression(C=c, max_iter=2000, random_state=seed))

    forest = RandomForestRegressor if numeric else RandomForestClassifier
    leaves = sorted({1, max(2, min(8, minimum_train_count // 10))})
    for leaf in leaves:
        add("RANDOM_FOREST", {"minSamplesLeaf": leaf}, forest(
            n_estimators=160, min_samples_leaf=leaf, random_state=seed, n_jobs=threads))

    from lightgbm import LGBMClassifier, LGBMRegressor
    lightgbm = LGBMRegressor if numeric else LGBMClassifier
    minimum_leaf = max(1, min(20, minimum_train_count // 5))
    for leaf in sorted({minimum_leaf, max(2, minimum_leaf)}):
        num_leaves = max(3, min(15, minimum_train_count // max(2, leaf)))
        add("LIGHTGBM", {"minChildSamples": leaf, "numLeaves": num_leaves}, lightgbm(
            n_estimators=100, min_child_samples=leaf, min_data_in_bin=1, num_leaves=num_leaves,
            learning_rate=.05, reg_lambda=1, random_state=seed, n_jobs=threads, verbosity=-1))
    if not numeric:
        from catboost import CatBoostClassifier
        depth = max(2, min(6, int(np.log2(max(2, minimum_train_count)))))
        add("CATBOOST", {"depth": depth}, CatBoostClassifier(
            iterations=160, depth=depth, random_seed=seed, thread_count=threads, verbose=False))
    return result


def fitted_metrics(model: Any, x: np.ndarray, y: np.ndarray, numeric: bool, classes: list[str]) -> dict:
    predicted = model.predict(x)
    if numeric:
        return dict(mae=float(mean_absolute_error(y, predicted)), rmse=float(mean_squared_error(y, predicted) ** .5),
                    r2=float(r2_score(y, predicted)))
    local_classes = list(model.classes_)
    probabilities = model.predict_proba(x)[:, [local_classes.index(label) for label in classes]]
    return dict(accuracy=float(accuracy_score(y, predicted)), macroF1=float(f1_score(y, predicted, average="macro")),
                logLoss=float(log_loss(y, probabilities, labels=classes)))
