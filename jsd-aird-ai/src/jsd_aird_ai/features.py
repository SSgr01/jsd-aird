from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

from jsd_aird_ai.contracts import FeatureType, FeatureViewSpec, TaskProfile
from jsd_aird_ai.feature_views import validate_feature_view
from jsd_aird_ai.errors import ErrorCode, FormulaModelError


MAIN_RESIN_CODE = "__MAIN_RESIN_CODE"
MAIN_RESIN_PCT = "__MAIN_RESIN_PCT"


@dataclass(frozen=True)
class FeatureLayout:
    numeric: list[str]
    categorical: list[str]

    @property
    def all(self) -> list[str]:
        return [*self.numeric, *self.categorical]


class FeatureBuilder:
    def __init__(self, profile: TaskProfile, feature_view: FeatureViewSpec | None = None) -> None:
        self.profile = profile
        self.feature_view = feature_view
        if feature_view is not None:
            validate_feature_view(feature_view, profile)
        self.material_by_code = {item.code: item for item in profile.formula.materials}
        self.context_by_code = {item.code: item for item in profile.context_features}
        self.main_materials = [
            self.material_by_code[code] for code in profile.formula.main_resin_codes
        ]
        self.non_main_materials = [
            item for item in profile.formula.materials if item.role != "MAIN_RESIN"
        ]
        numeric_context = [
            item.column
            for item in profile.context_features
            if item.value_type == FeatureType.NUMERIC
        ]
        categorical_context = [
            item.column
            for item in profile.context_features
            if item.value_type == FeatureType.CATEGORICAL
        ]
        extra_numeric = [
            item.column
            for item in (feature_view.additional_features if feature_view is not None else [])
            if item.value_type == FeatureType.NUMERIC
        ]
        extra_categorical = [
            item.column
            for item in (feature_view.additional_features if feature_view is not None else [])
            if item.value_type == FeatureType.CATEGORICAL
        ]
        self.additional_features = list(feature_view.additional_features) if feature_view is not None else []
        self.layout = FeatureLayout(
            numeric=[
                MAIN_RESIN_PCT,
                *[item.column for item in self.non_main_materials],
                *numeric_context,
                *extra_numeric,
            ],
            categorical=[MAIN_RESIN_CODE, *categorical_context, *extra_categorical],
        )

    def from_snapshot(self, measurements: pd.DataFrame) -> pd.DataFrame:
        result = pd.DataFrame(index=measurements.index)
        main_columns = [item.column for item in self.main_materials]
        active = measurements[main_columns].fillna(0.0).to_numpy(dtype=float) > 1e-9
        counts = active.sum(axis=1)
        if np.any(counts != 1):
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "snapshot contains formulas without exactly one active main resin",
            )
        indices = active.argmax(axis=1)
        result[MAIN_RESIN_CODE] = [self.main_materials[index].code for index in indices]
        main_values = measurements[main_columns].to_numpy(dtype=float)
        result[MAIN_RESIN_PCT] = main_values[np.arange(len(measurements)), indices]
        for material in self.non_main_materials:
            result[material.column] = measurements[material.column]
        for context in self.profile.context_features:
            result[context.column] = measurements[context.column]
        for feature in self.additional_features:
            if feature.column not in measurements:
                result[feature.column] = np.nan
            elif feature.value_type == FeatureType.NUMERIC:
                result[feature.column] = pd.to_numeric(measurements[feature.column], errors="coerce")
            else:
                result[feature.column] = measurements[feature.column]
        return result[self.layout.all]

    def from_api_rows(
        self,
        formulas: list[dict[str, float]],
        contexts: list[dict[str, Any]],
    ) -> pd.DataFrame:
        if len(formulas) != len(contexts):
            raise ValueError("formulas and contexts must have the same length")
        rows = [
            self._from_api_row(formula, context)
            for formula, context in zip(formulas, contexts, strict=True)
        ]
        result = pd.DataFrame(rows)
        return result[self.layout.all]

    def _from_api_row(
        self,
        formula: dict[str, float],
        context: dict[str, Any],
    ) -> dict[str, Any]:
        expected_materials = set(self.material_by_code)
        actual_materials = set(formula)
        if actual_materials != expected_materials:
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "formula must contain every task-profile material",
                details={
                    "missing": sorted(expected_materials - actual_materials),
                    "unexpected": sorted(actual_materials - expected_materials),
                },
            )
        values = {code: float(value) for code, value in formula.items()}
        if any(not np.isfinite(value) for value in values.values()):
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "formula contains non-finite value")
        active_main = [code for code in self.profile.formula.main_resin_codes if values[code] > 1e-9]
        if len(active_main) != 1:
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "formula must contain exactly one active main resin",
            )
        active_code = active_main[0]
        for code in self.profile.formula.main_resin_codes:
            value = values[code]
            spec = self.material_by_code[code]
            if code == active_code:
                if value < spec.minimum - 1e-9 or value > spec.maximum + 1e-9:
                    raise FormulaModelError(
                        ErrorCode.INVALID_SNAPSHOT,
                        f"active main resin {code} is outside configured bounds",
                    )
            elif abs(value) > 1e-9:
                raise FormulaModelError(
                    ErrorCode.INVALID_SNAPSHOT,
                    f"inactive main resin {code} must be zero",
                )
        for material in self.non_main_materials:
            value = values[material.code]
            if value < material.minimum - 1e-9 or value > material.maximum + 1e-9:
                raise FormulaModelError(
                    ErrorCode.INVALID_SNAPSHOT,
                    f"material {material.code} is outside configured bounds",
                )
        total = sum(values.values())
        if abs(total - self.profile.formula.total) > self.profile.formula.sum_tolerance:
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "formula total is outside configured tolerance",
                details={"total": total},
            )

        expected_context = set(self.context_by_code) | {item.code for item in self.additional_features}
        actual_context = set(context)
        if actual_context != expected_context:
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "context must contain every task-profile feature",
                details={
                    "missing": sorted(expected_context - actual_context),
                    "unexpected": sorted(actual_context - expected_context),
                },
            )
        row: dict[str, Any] = {
            MAIN_RESIN_CODE: active_code,
            MAIN_RESIN_PCT: values[active_code],
        }
        for material in self.non_main_materials:
            row[material.column] = values[material.code]
        for code, spec in self.context_by_code.items():
            value = context[code]
            if value is None or (isinstance(value, float) and not np.isfinite(value)):
                raise FormulaModelError(
                    ErrorCode.INVALID_SNAPSHOT,
                    f"context feature {code} is missing",
                )
            row[spec.column] = value
        for feature in self.additional_features:
            value = context[feature.code]
            if feature.value_type == FeatureType.NUMERIC:
                value = float(value)
                if not np.isfinite(value):
                    raise FormulaModelError(
                        ErrorCode.INVALID_SNAPSHOT,
                        f"feature view value {feature.code} is missing or invalid",
                    )
            elif value is None or not str(value).strip():
                raise FormulaModelError(
                    ErrorCode.INVALID_SNAPSHOT,
                    f"feature view value {feature.code} is missing or invalid",
                )
            row[feature.column] = value
        return row

    def valid_feature_mask(self, features: pd.DataFrame) -> pd.Series:
        mask = pd.Series(True, index=features.index)
        for column in self.layout.numeric:
            numeric = pd.to_numeric(features[column], errors="coerce")
            mask &= numeric.notna() & np.isfinite(numeric)
        for column in self.layout.categorical:
            mask &= features[column].notna() & features[column].astype(str).str.len().gt(0)
        return mask

    def full_formula(self, main_resin_code: str, main_resin_pct: float, additives: dict[str, float]) -> dict[str, float]:
        values = {code: 0.0 for code in self.material_by_code}
        values[main_resin_code] = float(main_resin_pct)
        for code, value in additives.items():
            values[code] = float(value)
        balance_code = self.profile.formula.balance_material_code
        values[balance_code] = self.profile.formula.total - sum(
            value for code, value in values.items() if code != balance_code
        )
        return {code: round(value, 6) for code, value in values.items()}
