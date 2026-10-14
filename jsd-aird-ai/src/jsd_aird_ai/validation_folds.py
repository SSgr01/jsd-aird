from __future__ import annotations

import json
from dataclasses import dataclass

import numpy as np
import pandas as pd

from jsd_aird_ai.artifacts import ArtifactClient
from jsd_aird_ai.contracts import (
    BaselineMetrics,
    BaselineSummary,
    BaselineType,
    GenerateValidationFoldsRequest,
    GenerateValidationFoldsResponse,
    T06BaselineDocument,
    TaskProfile,
    ValidationFoldAssignment,
    ValidationFoldScheme,
    ValidationFoldsDocument,
    ValidationFoldTargetSummary,
    ValueType,
)
from jsd_aird_ai.digests import canonical_json_bytes, canonical_sha256, sha256_bytes
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.features import FeatureBuilder
from jsd_aird_ai.snapshot import LoadedSnapshot, require_valid_snapshot
from jsd_aird_ai.validation import FoldDefinition, group_folds


FORMULA_LINEAGE = "FORMULA_LINEAGE"
SOURCE_CONTEXT = "SOURCE_CONTEXT"


@dataclass(frozen=True)
class LoadedValidationArtifacts:
    folds: ValidationFoldsDocument
    baseline: T06BaselineDocument | None
    folds_artifact_hash: str
    baseline_artifact_hash: str | None
    content_hash_by_row: dict[str, str]

    def folds_for(
        self,
        *,
        target_key: str,
        validation_scheme: str,
        row_ids: np.ndarray,
        groups: np.ndarray,
    ) -> tuple[FoldDefinition, ...]:
        scheme = next(
            (
                item
                for item in self.folds.schemes
                if item.validation_scheme == validation_scheme
            ),
            None,
        )
        if scheme is None:
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                f"validation folds are missing scheme {validation_scheme}",
            )
        assignments = [
            item for item in scheme.assignments if item.target_key == target_key
        ]
        expected_ids = [str(item) for item in row_ids]
        by_id = {item.analysis_row_id: item for item in assignments}
        if len(by_id) != len(assignments) or set(by_id) != set(expected_ids):
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "validation folds do not match the target cohort",
                details={"targetKey": target_key, "validationScheme": validation_scheme},
            )
        ordered = [by_id[row_id] for row_id in expected_ids]
        fold_indices = sorted({item.fold_index for item in ordered})
        if len(fold_indices) < 2 or fold_indices != list(range(len(fold_indices))):
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "validation fold indexes must be contiguous and contain at least two folds",
            )
        result: list[FoldDefinition] = []
        indexes = np.arange(len(expected_ids), dtype=int)
        for fold_index in fold_indices:
            validation = np.asarray(
                [index for index, item in enumerate(ordered) if item.fold_index == fold_index],
                dtype=int,
            )
            train = np.setdiff1d(indexes, validation, assume_unique=True)
            if not len(train) or not len(validation):
                raise FormulaModelError(
                    ErrorCode.INVALID_SNAPSHOT,
                    "validation fold contains an empty training or validation side",
                )
            if set(groups[train]).intersection(groups[validation]):
                raise FormulaModelError(
                    ErrorCode.INVALID_SNAPSHOT,
                    "validation folds split a protected group across train and validation",
                )
            for row_index, assignment in enumerate(ordered):
                if str(assignment.group_key) != str(groups[row_index]):
                    raise FormulaModelError(
                        ErrorCode.INVALID_SNAPSHOT,
                        "validation fold group key does not match snapshot provenance",
                    )
            result.append(FoldDefinition(train=train, validation=validation))
        return tuple(result)


class ValidationFoldService:
    def __init__(self, artifacts: ArtifactClient) -> None:
        self._artifacts = artifacts

    def generate(
        self,
        request: GenerateValidationFoldsRequest,
        snapshot: LoadedSnapshot,
    ) -> GenerateValidationFoldsResponse:
        require_valid_snapshot(snapshot)
        if snapshot.schema_version != "1.1":
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "immutable validation folds require snapshot schema 1.1",
            )
        profile = request.task_profile
        builder = FeatureBuilder(profile)
        features = builder.from_snapshot(snapshot.measurements)
        feature_valid = builder.valid_feature_mask(features)
        row_ids = snapshot.measurements[snapshot.identity_column].astype(str)
        schemes = {
            FORMULA_LINEAGE: profile.validation.group_column,
            SOURCE_CONTEXT: profile.validation.source_group_column,
        }
        assignments_by_scheme: dict[str, list[ValidationFoldAssignment]] = {
            name: [] for name in schemes
        }
        summaries: list[ValidationFoldTargetSummary] = []

        for target in profile.targets:
            mask = _target_mask(snapshot.measurements, target.code, target.value_type)
            mask = feature_valid & mask
            target_rows = row_ids.loc[mask].reset_index(drop=True)
            fold_counts: dict[str, int] = {}
            for scheme_name, group_column in schemes.items():
                groups = snapshot.joined.loc[mask.to_numpy(), group_column].astype(str).to_numpy()
                if len(np.unique(groups)) < 2:
                    fold_counts[scheme_name] = 0
                    continue
                folds = group_folds(groups, profile.validation.folds)
                fold_counts[scheme_name] = len(folds)
                for fold_index, fold in enumerate(folds):
                    for row_index in fold.validation:
                        assignments_by_scheme[scheme_name].append(
                            ValidationFoldAssignment(
                                analysis_row_id=str(target_rows.iloc[row_index]),
                                target_key=target.target_key,
                                fold_index=fold_index,
                                group_key=str(groups[row_index]),
                            )
                        )
            summaries.append(
                ValidationFoldTargetSummary(
                    target_key=target.target_key,
                    eligible_rows=int(mask.sum()),
                    formula_lineage_folds=fold_counts.get(FORMULA_LINEAGE, 0),
                    source_context_folds=fold_counts.get(SOURCE_CONTEXT, 0),
                )
            )

        body = {
            "schemaVersion": "validation-folds.v1",
            "snapshotHash": request.snapshot_hash,
            "taskProfileHash": request.task_profile_hash,
            "seed": request.seed,
            "schemes": [
                ValidationFoldScheme(
                    validation_scheme=scheme_name,
                    fold_count=profile.validation.folds,
                    assignments=sorted(
                        assignments_by_scheme[scheme_name],
                        key=lambda item: (
                            item.target_key,
                            item.fold_index,
                            item.analysis_row_id,
                        ),
                    ),
                ).model_dump(mode="json", by_alias=True)
                for scheme_name in (FORMULA_LINEAGE, SOURCE_CONTEXT)
            ],
        }
        body["contentHash"] = canonical_sha256(body)
        document = ValidationFoldsDocument.model_validate(body)
        payload = canonical_json_bytes(document)
        digest = sha256_bytes(payload)
        self._artifacts.write(request.output, payload)
        return GenerateValidationFoldsResponse(
            request_id=request.request_id,
            snapshot_hash=request.snapshot_hash,
            validation_folds_sha256=digest,
            validation_folds_size=len(payload),
            uploaded=True,
            targets=summaries,
        )


def load_validation_artifacts(
    artifacts: ArtifactClient,
    *,
    snapshot: LoadedSnapshot,
    task_profile: TaskProfile,
    task_profile_hash: str,
    snapshot_hash: str,
    seed: int,
    validation_folds_ref,
    baseline_ref,
) -> LoadedValidationArtifacts | None:
    if validation_folds_ref is None:
        if snapshot.schema_version == "1.1":
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "snapshot schema 1.1 training requires immutable validation folds",
            )
        return None
    folds = ValidationFoldsDocument.model_validate_json(
        artifacts.read(validation_folds_ref)
    )
    _validate_content_hash(folds)
    if (
        folds.snapshot_hash != snapshot_hash
        or folds.task_profile_hash != task_profile_hash
        or folds.seed != seed
    ):
        raise FormulaModelError(
            ErrorCode.HASH_MISMATCH,
            "validation folds do not belong to this training request",
        )
    if {item.validation_scheme for item in folds.schemes} != {
        FORMULA_LINEAGE,
        SOURCE_CONTEXT,
    }:
        raise FormulaModelError(
            ErrorCode.INVALID_SNAPSHOT,
            "validation folds must contain formula-lineage and source-context schemes",
        )

    baseline = None
    if baseline_ref is not None:
        baseline = T06BaselineDocument.model_validate_json(artifacts.read(baseline_ref))
        _validate_content_hash(baseline)
        if (
            baseline.snapshot_hash != snapshot_hash
            or baseline.task_profile_hash != task_profile_hash
            or baseline.validation_folds_hash != validation_folds_ref.sha256
        ):
            raise FormulaModelError(
                ErrorCode.HASH_MISMATCH,
                "T06 baseline does not belong to the immutable validation folds",
            )
    elif snapshot.response.production_eligible:
        raise FormulaModelError(
            ErrorCode.INVALID_SNAPSHOT,
            "production-eligible training requires a T06_SIMILAR_CASE baseline",
        )
    return LoadedValidationArtifacts(
        folds,
        baseline,
        validation_folds_ref.sha256,
        baseline_ref.sha256 if baseline_ref is not None else None,
        dict(
            zip(
                snapshot.source_map[snapshot.identity_column].astype(str),
                snapshot.source_map["content_hash"].astype(str),
                strict=True,
            )
        ),
    )


def _target_mask(frame: pd.DataFrame, target_code: str, value_type: ValueType) -> pd.Series:
    raw = frame[target_code]
    if value_type in {ValueType.BINARY, ValueType.CATEGORICAL}:
        values = raw.astype("string").str.strip()
        return raw.notna() & values.ne("")
    numeric = pd.to_numeric(raw, errors="coerce")
    return numeric.notna() & np.isfinite(numeric)


def _validate_content_hash(document) -> None:
    # Java omits optional null properties from immutable JSON artifacts while
    # Pydantic materializes them during validation. Hash the language-neutral
    # semantic document by omitting nulls on both sides; non-null evidence,
    # assignments and values remain covered by the content hash.
    payload = document.model_dump(mode="json", by_alias=True, exclude_none=True)
    actual = payload.pop("contentHash")
    expected = canonical_sha256(payload)
    if actual != expected:
        raise FormulaModelError(
            ErrorCode.HASH_MISMATCH,
            "immutable validation artifact content hash mismatch",
        )


def baseline_for_target(
    artifacts: LoadedValidationArtifacts | None,
    *,
    target_key: str,
):
    if artifacts is None or artifacts.baseline is None:
        return None
    return next(
        (item for item in artifacts.baseline.targets if item.target_key == target_key),
        None,
    )


def validated_baseline_summary(
    artifacts: LoadedValidationArtifacts | None,
    *,
    target_key: str,
    target_code: str,
    row_ids: np.ndarray,
    actual_values: np.ndarray,
) -> BaselineSummary | None:
    target = validate_baseline_target(
        artifacts,
        target_key=target_key,
        target_code=target_code,
        row_ids=row_ids,
        actual_values=actual_values,
    )
    if target is None:
        return None

    try:
        lineage_nmae = float(target.metrics_by_scheme[FORMULA_LINEAGE]["nmae"])
        source_nmae = float(target.metrics_by_scheme[SOURCE_CONTEXT]["nmae"])
    except (KeyError, TypeError, ValueError) as exc:
        raise FormulaModelError(
            ErrorCode.INVALID_SNAPSHOT,
            "continuous T06 baseline is missing NMAE metrics",
        ) from exc
    return BaselineSummary(
        type=BaselineType.T06_SIMILAR_CASE,
        version=artifacts.baseline.baseline_version,
        metrics=BaselineMetrics(
            lineage_nmae=lineage_nmae,
            sheet_nmae=source_nmae,
        ),
    )


def validate_baseline_target(
    artifacts: LoadedValidationArtifacts | None,
    *,
    target_key: str,
    target_code: str,
    row_ids: np.ndarray,
    actual_values: np.ndarray,
):
    """Validate a formal T06 target against the exact snapshot cohort and folds.

    This validation is target-type agnostic. Continuous training additionally reads
    NMAE through ``validated_baseline_summary``; ordinal and classification targets
    still must prove that their T06 observations were produced out-of-fold on the
    same immutable assignments.
    """
    target = baseline_for_target(artifacts, target_key=target_key)
    if target is None:
        return None
    if artifacts is None or artifacts.baseline is None:
        return None
    if target.target_code != target_code:
        raise FormulaModelError(
            ErrorCode.INVALID_SNAPSHOT,
            "T06 baseline target code does not match the task profile",
        )
    expected_actual = {
        str(row_id): _canonical_actual(actual)
        for row_id, actual in zip(row_ids, actual_values, strict=True)
    }
    expected_content_hash = {
        row_id: artifacts.content_hash_by_row[row_id] for row_id in expected_actual
    }
    expected_sample_hash = canonical_sha256(
        [
            {
                "analysisRowId": row_id,
                "targetKey": target_key,
                "actual": expected_actual[row_id],
                "analysisRowContentHash": expected_content_hash[row_id],
            }
            for row_id in sorted(expected_actual)
        ]
    )
    if target.sample_hash != expected_sample_hash:
        raise FormulaModelError(
            ErrorCode.HASH_MISMATCH,
            "T06 baseline sample hash does not match the target cohort",
        )

    fold_assignment = {
        (scheme.validation_scheme, item.analysis_row_id): item
        for scheme in artifacts.folds.schemes
        for item in scheme.assignments
        if item.target_key == target_key
    }
    for scheme_name in (FORMULA_LINEAGE, SOURCE_CONTEXT):
        observations = [
            item for item in target.observations if item.validation_scheme == scheme_name
        ]
        if {item.analysis_row_id for item in observations} != set(expected_actual):
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "T06 baseline observations do not match the target cohort",
                details={"targetKey": target_key, "validationScheme": scheme_name},
            )
        for observation in observations:
            assignment = fold_assignment.get((scheme_name, observation.analysis_row_id))
            if assignment is None or (
                assignment.fold_index != observation.fold_index
                or assignment.group_key != observation.group_key
            ):
                raise FormulaModelError(
                    ErrorCode.INVALID_SNAPSHOT,
                    "T06 baseline observation does not match immutable validation folds",
                )
            if _canonical_actual(observation.actual) != expected_actual[observation.analysis_row_id]:
                raise FormulaModelError(
                    ErrorCode.INVALID_SNAPSHOT,
                    "T06 baseline actual value does not match snapshot",
                )
            expected_observation_hash = canonical_sha256(
                {
                    "analysisRowId": observation.analysis_row_id,
                    "targetKey": target_key,
                    "actual": expected_actual[observation.analysis_row_id],
                    "analysisRowContentHash": expected_content_hash[
                        observation.analysis_row_id
                    ],
                }
            )
            if observation.sample_hash != expected_observation_hash:
                raise FormulaModelError(
                    ErrorCode.HASH_MISMATCH,
                    "T06 baseline sample hash does not match snapshot provenance",
                )

    return target


def _canonical_actual(value):
    if isinstance(value, (np.floating, float, np.integer, int)):
        return float(value)
    return str(value)
