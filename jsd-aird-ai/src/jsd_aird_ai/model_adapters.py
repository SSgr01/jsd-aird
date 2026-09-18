from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, replace
from statistics import NormalDist
from typing import Any

import catboost
import lightgbm
import numpy as np
import pandas as pd
import sklearn
import xgboost
from catboost import CatBoostRegressor
from lightgbm import LGBMRegressor
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestRegressor
from sklearn.gaussian_process import GaussianProcessRegressor
from sklearn.gaussian_process.kernels import ConstantKernel, Matern, WhiteKernel
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from xgboost import XGBRegressor

from jsd_aird_ai.contracts import AlgorithmMetadata, ModelType
from jsd_aird_ai.features import FeatureLayout


def common_numeric_preprocessor(layout: FeatureLayout) -> ColumnTransformer:
    """Shared leakage-safe representation for GP/RF/LightGBM/XGBoost."""
    return ColumnTransformer(
        transformers=[
            ("numeric", StandardScaler(), layout.numeric),
            (
                "categorical",
                OneHotEncoder(handle_unknown="ignore", sparse_output=False),
                layout.categorical,
            ),
        ],
        remainder="drop",
        verbose_feature_names_out=False,
    )


def catboost_native_frame(features: pd.DataFrame, layout: FeatureLayout) -> pd.DataFrame:
    result = features[layout.all].copy()
    for column in layout.numeric:
        result[column] = pd.to_numeric(result[column], errors="raise").astype(float)
    for column in layout.categorical:
        result[column] = result[column].astype(str)
    return result


@dataclass
class FittedContinuousModel:
    model_type: ModelType
    estimator: Any
    layout: FeatureLayout
    preprocessor: ColumnTransformer | None
    native_categorical: bool
    interval_level: float
    conformal_radius: float = 0.0

    def point_predict(self, features: pd.DataFrame) -> np.ndarray:
        if self.native_categorical:
            model_input: Any = catboost_native_frame(features, self.layout)
        else:
            if self.preprocessor is None:
                raise RuntimeError("numeric model is missing its fitted preprocessor")
            model_input = self.preprocessor.transform(features)
        return np.asarray(self.estimator.predict(model_input), dtype=float)

    def raw_interval(
        self, features: pd.DataFrame
    ) -> tuple[np.ndarray, np.ndarray] | None:
        if self.model_type != ModelType.GAUSSIAN_PROCESS:
            return None
        if self.preprocessor is None:
            raise RuntimeError("GP model is missing its fitted preprocessor")
        transformed = self.preprocessor.transform(features)
        expected, std = self.estimator.predict(transformed, return_std=True)
        quantile = NormalDist().inv_cdf(0.5 + self.interval_level / 2.0)
        radius = quantile * np.asarray(std, dtype=float)
        expected_array = np.asarray(expected, dtype=float)
        return expected_array - radius, expected_array + radius

    def predict(self, features: pd.DataFrame) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        expected = self.point_predict(features)
        return (
            expected,
            expected - self.conformal_radius,
            expected + self.conformal_radius,
        )

    def calibrated(self, radius: float) -> "FittedContinuousModel":
        return replace(self, conformal_radius=float(radius))


class ModelAdapter(ABC):
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
    def _estimator(self, seed: int, params: dict[str, Any], dimension: int) -> Any:
        raise NotImplementedError

    def fit(
        self,
        features: pd.DataFrame,
        target: np.ndarray,
        layout: FeatureLayout,
        seed: int,
        params: dict[str, Any],
        interval_level: float,
    ) -> FittedContinuousModel:
        if self.native_categorical:
            processor = None
            model_input: Any = catboost_native_frame(features, layout)
            dimension = len(layout.all)
        else:
            processor = common_numeric_preprocessor(layout)
            model_input = processor.fit_transform(features)
            dimension = int(model_input.shape[1])
        estimator = self._estimator(seed, params, dimension)
        if self.native_categorical:
            estimator.fit(model_input, target, cat_features=layout.categorical)
        else:
            estimator.fit(model_input, target)
        return FittedContinuousModel(
            model_type=self.model_type,
            estimator=estimator,
            layout=layout,
            preprocessor=processor,
            native_categorical=self.native_categorical,
            interval_level=interval_level,
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


class GaussianProcessAdapter(ModelAdapter):
    model_type = ModelType.GAUSSIAN_PROCESS
    library = "scikit-learn"
    library_version = sklearn.__version__

    def parameter_candidates(self) -> list[dict[str, Any]]:
        return [{"n_restarts_optimizer": 0}]

    def _estimator(self, seed: int, params: dict[str, Any], dimension: int) -> Any:
        kernel = ConstantKernel(1.0, (1e-2, 1e2)) * Matern(
            length_scale=np.ones(dimension),
            length_scale_bounds=(1e-2, 1e2),
            nu=2.5,
        ) + WhiteKernel(noise_level=1e-2, noise_level_bounds=(1e-6, 1e1))
        return GaussianProcessRegressor(
            kernel=kernel,
            normalize_y=True,
            random_state=seed,
            n_restarts_optimizer=int(params.get("n_restarts_optimizer", 0)),
        )


class RandomForestAdapter(ModelAdapter):
    model_type = ModelType.RANDOM_FOREST
    library = "scikit-learn"
    library_version = sklearn.__version__

    def parameter_candidates(self) -> list[dict[str, Any]]:
        return [
            {"n_estimators": 160, "min_samples_leaf": 2, "max_features": 0.7},
            {"n_estimators": 240, "min_samples_leaf": 4, "max_features": 1.0},
            {"n_estimators": 320, "min_samples_leaf": 8, "max_features": 0.7},
        ]

    def _estimator(self, seed: int, params: dict[str, Any], dimension: int) -> Any:
        return RandomForestRegressor(
            n_estimators=int(params["n_estimators"]),
            min_samples_leaf=int(params["min_samples_leaf"]),
            max_features=params["max_features"],
            random_state=seed,
            n_jobs=self.thread_count,
        )


class LightGBMAdapter(ModelAdapter):
    model_type = ModelType.LIGHTGBM
    library = "lightgbm"
    library_version = lightgbm.__version__

    def parameter_candidates(self) -> list[dict[str, Any]]:
        return [
            {
                "num_leaves": 15,
                "learning_rate": 0.03,
                "n_estimators": 200,
                "min_child_samples": 10,
                "feature_fraction": 0.8,
                "bagging_fraction": 0.8,
                "reg_lambda": 0.0,
            },
            {
                "num_leaves": 31,
                "learning_rate": 0.05,
                "n_estimators": 400,
                "min_child_samples": 20,
                "feature_fraction": 1.0,
                "bagging_fraction": 1.0,
                "reg_lambda": 1.0,
            },
        ]

    def _estimator(self, seed: int, params: dict[str, Any], dimension: int) -> Any:
        return LGBMRegressor(
            **params,
            objective="regression",
            verbosity=-1,
            bagging_freq=1,
            random_state=seed,
            n_jobs=self.thread_count,
            deterministic=True,
            force_col_wise=True,
        )


class XGBoostAdapter(ModelAdapter):
    model_type = ModelType.XGBOOST
    library = "xgboost"
    library_version = xgboost.__version__

    def parameter_candidates(self) -> list[dict[str, Any]]:
        return [
            {
                "max_depth": 3,
                "learning_rate": 0.03,
                "n_estimators": 200,
                "min_child_weight": 1,
                "subsample": 0.8,
                "colsample_bytree": 0.8,
                "reg_lambda": 1.0,
                "reg_alpha": 0.0,
            },
            {
                "max_depth": 5,
                "learning_rate": 0.05,
                "n_estimators": 400,
                "min_child_weight": 5,
                "subsample": 1.0,
                "colsample_bytree": 1.0,
                "reg_lambda": 5.0,
                "reg_alpha": 0.1,
            },
        ]

    def _estimator(self, seed: int, params: dict[str, Any], dimension: int) -> Any:
        return XGBRegressor(
            **params,
            objective="reg:squarederror",
            eval_metric="mae",
            random_state=seed,
            n_jobs=self.thread_count,
            verbosity=0,
            tree_method="hist",
        )


class CatBoostAdapter(ModelAdapter):
    model_type = ModelType.CATBOOST
    library = "catboost"
    library_version = catboost.__version__
    native_categorical = True

    def parameter_candidates(self) -> list[dict[str, Any]]:
        return [
            {
                "depth": 4,
                "learning_rate": 0.03,
                "iterations": 300,
                "l2_leaf_reg": 3.0,
            },
        ]

    def _estimator(self, seed: int, params: dict[str, Any], dimension: int) -> Any:
        return CatBoostRegressor(
            **params,
            loss_function="RMSE",
            verbose=False,
            allow_writing_files=False,
            random_seed=seed,
            thread_count=self.thread_count,
            random_strength=0.0,
            bootstrap_type="No",
        )


def default_model_adapters(thread_count: int) -> list[ModelAdapter]:
    return [
        GaussianProcessAdapter(thread_count),
        RandomForestAdapter(thread_count),
        LightGBMAdapter(thread_count),
        XGBoostAdapter(thread_count),
        CatBoostAdapter(thread_count),
    ]
