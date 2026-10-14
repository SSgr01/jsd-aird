"""Versioned feature-view definitions used by development and production models.

Feature views are an overlay on a task profile.  The task profile remains the
authority for formula and ordinary context columns; a view may only add
explicitly named, auditable columns.  Keeping this separate prevents an
offline experiment (for example the resin-batch enhancement) from silently
changing the production task profile.
"""

from __future__ import annotations

from typing import Any

from jsd_aird_ai.contracts import FeatureViewSpec, TaskProfile
from jsd_aird_ai.digests import canonical_sha256
from jsd_aird_ai.errors import ErrorCode, FormulaModelError


def base_feature_view(profile: TaskProfile) -> FeatureViewSpec:
    """Build the explicit BASE_V1 view from a task profile."""
    numeric = ["__MAIN_RESIN_PCT"]
    numeric.extend(item.column for item in profile.formula.materials if item.role != "MAIN_RESIN")
    numeric.extend(item.column for item in profile.context_features if item.value_type == "NUMERIC")
    categorical = ["__MAIN_RESIN_CODE"]
    categorical.extend(item.column for item in profile.context_features if item.value_type == "CATEGORICAL")
    return FeatureViewSpec(
        code="BASE_V1",
        task_profile_code=profile.code,
        task_profile_version=profile.version,
        task_profile_hash=canonical_sha256(profile),
        scope="BASE",
        development_only=False,
        included_numeric_columns=numeric,
        included_categorical_columns=categorical,
        additional_features=[],
    )


def validate_feature_view(view: FeatureViewSpec, profile: TaskProfile) -> None:
    """Reject a view that could change the meaning of an existing profile."""
    expected_hash = canonical_sha256(profile)
    if (
        view.task_profile_code != profile.code
        or view.task_profile_version != profile.version
        or view.task_profile_hash != expected_hash
    ):
        raise FormulaModelError(
            ErrorCode.HASH_MISMATCH,
            "feature view does not belong to the task profile",
        )
    if view.content_hash is not None and view.content_hash != feature_view_hash(view):
        raise FormulaModelError(ErrorCode.HASH_MISMATCH, "feature view content hash mismatch")
    all_columns = [*view.included_numeric_columns, *view.included_categorical_columns]
    if len(all_columns) != len(set(all_columns)):
        raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "feature view contains duplicate columns")
    base = base_feature_view(profile)
    if set(base.included_numeric_columns) - set(view.included_numeric_columns):
        raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "feature view omits task-profile features")
    if set(base.included_categorical_columns) - set(view.included_categorical_columns):
        raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "feature view omits task-profile features")
    additional_codes = [item.code for item in view.additional_features]
    if len(additional_codes) != len(set(additional_codes)):
        raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "feature view contains duplicate feature codes")


def feature_view_hash(view: FeatureViewSpec) -> str:
    """Return the stable content hash (excluding the optional self hash)."""
    return canonical_sha256(view.model_dump(mode="json", by_alias=True, exclude_none=True, exclude={"content_hash"}))


def feature_view_json(view: FeatureViewSpec) -> dict[str, Any]:
    """Serialize a view with its deterministic content hash."""
    payload = view.model_dump(mode="json", by_alias=True, exclude_none=True)
    payload["contentHash"] = feature_view_hash(view)
    return payload
