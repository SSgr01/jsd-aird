from __future__ import annotations

import io
import json
from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

from jsd_aird_ai.artifacts import ArtifactClient
from jsd_aird_ai.contracts import (
    SnapshotRequest,
    SnapshotValidationResponse,
    SourceSheetStats,
    TargetCoverage,
    TaskProfile,
    ValueType,
)
from jsd_aird_ai.digests import canonical_sha256, snapshot_sha256
from jsd_aird_ai.errors import ErrorCode, FormulaModelError


@dataclass(frozen=True)
class LoadedSnapshot:
    manifest: dict[str, Any]
    measurements: pd.DataFrame
    source_map: pd.DataFrame
    joined: pd.DataFrame
    response: SnapshotValidationResponse
    identity_column: str
    schema_version: str


class SnapshotValidator:
    def __init__(self, artifacts: ArtifactClient) -> None:
        self._artifacts = artifacts

    def load_and_validate(self, request: SnapshotRequest) -> LoadedSnapshot:
        if canonical_sha256(request.task_profile) != request.task_profile_hash:
            raise FormulaModelError(
                ErrorCode.HASH_MISMATCH,
                "task profile hash does not match the request",
            )
        expected_snapshot_hash = snapshot_sha256(
            request.snapshot.manifest.sha256,
            request.snapshot.measurements.sha256,
            request.snapshot.source_map.sha256,
        )
        if expected_snapshot_hash != request.snapshot_hash:
            raise FormulaModelError(
                ErrorCode.HASH_MISMATCH,
                "snapshot hash does not match its artifact hashes",
                details={"expected": expected_snapshot_hash, "actual": request.snapshot_hash},
            )

        manifest_bytes = self._artifacts.read(request.snapshot.manifest)
        measurements_bytes = self._artifacts.read(request.snapshot.measurements)
        source_map_bytes = self._artifacts.read(request.snapshot.source_map)
        try:
            manifest = json.loads(manifest_bytes)
            measurements = pd.read_parquet(io.BytesIO(measurements_bytes))
            source_map = pd.read_parquet(io.BytesIO(source_map_bytes))
        except Exception as exc:
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "snapshot artifacts cannot be decoded",
            ) from exc

        response, joined = self._validate_frames(
            request,
            manifest,
            measurements,
            source_map,
        )
        schema_version = str(manifest.get("snapshot_schema_version", ""))
        identity_column = (
            "analysis_row_id" if schema_version == "1.1" else "experiment_version_id"
        )
        return LoadedSnapshot(
            manifest,
            measurements,
            source_map,
            joined,
            response,
            identity_column,
            schema_version,
        )

    def _validate_frames(
        self,
        request: SnapshotRequest,
        manifest: dict[str, Any],
        measurements: pd.DataFrame,
        source_map: pd.DataFrame,
    ) -> tuple[SnapshotValidationResponse, pd.DataFrame]:
        profile = request.task_profile
        errors: list[str] = []
        warnings: list[str] = []

        snapshot_id = str(manifest.get("snapshot_id", ""))
        schema_version = str(manifest.get("snapshot_schema_version", ""))
        if schema_version not in {"1.0", "1.1"}:
            errors.append("snapshot_schema_version must be 1.0 or 1.1")
        if manifest.get("task_profile_code") != profile.code:
            errors.append("manifest task_profile_code does not match task profile")
        if manifest.get("task_profile_version") != profile.version:
            errors.append("manifest task_profile_version does not match task profile")

        manifest_targets = manifest.get("targets", {})
        for target in profile.targets:
            metadata = manifest_targets.get(target.code)
            if not isinstance(metadata, dict):
                errors.append(f"manifest is missing target metadata for {target.code}")
                continue
            expected_type = {
                ValueType.CONTINUOUS: "CONT",
                ValueType.ORDINAL: "ORD",
                ValueType.BINARY: "BINARY",
                ValueType.CATEGORICAL: "CATEGORICAL",
                ValueType.CENSORED_COUNT: "CENSORED_COUNT",
            }[ValueType(target.value_type)]
            expected = {
                "target_key": target.target_key,
                "value_type": expected_type,
                "unit": target.unit,
                "direction": target.direction,
            }
            for field, expected_value in expected.items():
                if metadata.get(field) != expected_value:
                    errors.append(f"manifest target {target.code} has incompatible {field}")

        manifest_artifacts = manifest.get("artifacts", {})
        expected_artifacts = {
            "measurements.parquet": request.snapshot.measurements.sha256,
            "source-map.parquet": request.snapshot.source_map.sha256,
        }
        for name, expected_hash in expected_artifacts.items():
            actual_hash = manifest_artifacts.get(name, {}).get("sha256")
            if actual_hash != expected_hash:
                errors.append(f"manifest hash mismatch for {name}")

        identity = "analysis_row_id" if schema_version == "1.1" else "experiment_version_id"
        if identity not in measurements.columns:
            errors.append(f"measurements is missing {identity}")
        if identity not in source_map.columns:
            errors.append(f"source-map is missing {identity}")
        if len(measurements) != len(source_map):
            errors.append("measurements and source-map row counts differ")

        required_measurement_columns = {
            item.column for item in profile.formula.materials
        } | {item.column for item in profile.context_features} | {
            item.code for item in profile.targets
        }
        required_measurement_columns.update(
            column
            for target in profile.targets
            for column in (target.censored_column, target.censor_type_column)
            if column
        )
        missing_columns = sorted(required_measurement_columns - set(measurements.columns))
        if missing_columns:
            errors.append("measurements missing columns: " + ", ".join(missing_columns))

        required_source_columns = {
            "experiment_id",
            "project_id",
            "organization_id",
            profile.validation.group_column,
            "main_resin_code",
            "source_file",
            profile.validation.source_group_column,
            "source_range",
            "content_hash",
            "permission_scope",
            "data_nature",
            "snapshot_purpose",
        }
        if schema_version == "1.1":
            required_source_columns.add("experiment_version_id")
        missing_source_columns = sorted(required_source_columns - set(source_map.columns))
        if missing_source_columns:
            errors.append("source-map missing columns: " + ", ".join(missing_source_columns))

        joined = pd.DataFrame()
        if identity in measurements.columns and identity in source_map.columns:
            if measurements[identity].astype(str).duplicated().any():
                errors.append(f"measurements contains duplicate {identity}")
            if source_map[identity].astype(str).duplicated().any():
                errors.append(f"source-map contains duplicate {identity}")
            joined = measurements.merge(
                source_map,
                on=identity,
                how="inner",
                validate="one_to_one",
                suffixes=("", "__source"),
            )
            if len(joined) != len(measurements):
                errors.append("measurements and source-map identities are not identical")

        if not missing_columns and not measurements.empty:
            formula_columns = [item.column for item in profile.formula.materials]
            sums = measurements[formula_columns].sum(axis=1, skipna=False)
            invalid_sum = (~np.isfinite(sums)) | (
                (sums - profile.formula.total).abs() > profile.formula.sum_tolerance
            )
            if invalid_sum.any():
                errors.append(f"{int(invalid_sum.sum())} formulas violate total tolerance")

            main_columns = [
                item.column
                for item in profile.formula.materials
                if item.code in profile.formula.main_resin_codes
            ]
            active_main = (measurements[main_columns].fillna(0.0) > 1e-9).sum(axis=1)
            if (active_main != 1).any():
                errors.append(
                    f"{int((active_main != 1).sum())} formulas violate main-resin exclusivity"
                )

            for material in profile.formula.materials:
                values = pd.to_numeric(measurements[material.column], errors="coerce")
                if material.role == "MAIN_RESIN":
                    invalid = (values.abs() > 1e-9) & (
                        (values < material.minimum - 1e-9)
                        | (values > material.maximum + 1e-9)
                    )
                else:
                    invalid = (
                        (values < material.minimum - 1e-9)
                        | (values > material.maximum + 1e-9)
                    )
                invalid = invalid | values.isna()
                if invalid.any():
                    errors.append(
                        f"{int(invalid.sum())} values for {material.code} violate material bounds"
                    )

            for context in profile.context_features:
                values = measurements[context.column]
                if context.value_type == "NUMERIC":
                    numeric = pd.to_numeric(values, errors="coerce")
                    invalid = numeric.isna() | ~np.isfinite(numeric)
                else:
                    invalid = values.isna() | values.astype(str).str.strip().eq("")
                if invalid.any():
                    errors.append(
                        f"{int(invalid.sum())} values for context {context.code} are missing or invalid"
                    )

        if not joined.empty and not missing_source_columns:
            source_group = profile.validation.source_group_column
            empty_provenance = (
                joined[["source_file", source_group, "source_range", "content_hash"]]
                .isna()
                .any(axis=1)
            )
            invalid_hash = ~joined["content_hash"].astype(str).str.fullmatch(r"[0-9a-f]{64}")
            if empty_provenance.any() or invalid_hash.any():
                errors.append("source-map contains invalid provenance anchors or content hashes")
            shared_columns = [
                item.column
                for item in profile.context_features
                if item.shared_within_source_group and item.column in joined.columns
            ]
            for column in shared_columns:
                counts = joined.groupby(source_group, dropna=False)[column].nunique(dropna=False)
                if (counts > 1).any():
                    errors.append(f"shared context {column} varies inside a source sheet")

        coverages: list[TargetCoverage] = []
        group_column = profile.validation.group_column
        substrate_feature = next(
            (item for item in profile.context_features if item.code == "substrate"),
            None,
        )
        for target in profile.targets:
            if target.code not in measurements.columns:
                continue
            raw_target = measurements[target.code]
            classification_target = target.value_type in {
                ValueType.BINARY,
                ValueType.CATEGORICAL,
            }
            if classification_target:
                normalized_target = raw_target.astype("string").str.strip()
                valid_mask = raw_target.notna() & normalized_target.ne("")
                configured_classes = set(target.class_labels or [])
                actual_classes = set(normalized_target.loc[valid_mask].astype(str).unique())
                unexpected_classes = sorted(actual_classes - configured_classes)
                if unexpected_classes:
                    errors.append(
                        f"classification target {target.code} contains unconfigured classes: "
                        f"{unexpected_classes}"
                    )
            else:
                numeric_target = pd.to_numeric(raw_target, errors="coerce")
                invalid_non_missing = raw_target.notna() & (
                    numeric_target.isna() | ~np.isfinite(numeric_target)
                )
                if invalid_non_missing.any():
                    errors.append(
                        f"{int(invalid_non_missing.sum())} values for {target.code} are not finite numbers"
                    )
                valid_mask = numeric_target.notna() & np.isfinite(numeric_target)
            if (
                target.substrate.strip()
                and substrate_feature is not None
                and substrate_feature.column in measurements.columns
            ):
                incompatible = valid_mask & (
                    measurements[substrate_feature.column].astype(str) != target.substrate
                )
                if incompatible.any():
                    errors.append(
                        f"{int(incompatible.sum())} values for {target.code} use an incompatible substrate"
                    )
            valid_rows = int(valid_mask.sum())
            groups = 0
            source_groups = 0
            if not joined.empty and group_column in joined.columns:
                groups = int(joined.loc[valid_mask.to_numpy(), group_column].nunique())
            source_group_column = profile.validation.source_group_column
            if not joined.empty and source_group_column in joined.columns:
                source_groups = int(
                    joined.loc[valid_mask.to_numpy(), source_group_column].nunique()
                )
            if target.value_type == ValueType.ORDINAL:
                if target.ordinal_values:
                    actual = set(numeric_target.loc[valid_mask].astype(float).unique())
                    unexpected = sorted(actual - set(target.ordinal_values))
                    if unexpected:
                        errors.append(
                            f"ordinal target {target.code} contains invalid values: {unexpected}"
                        )
                if (
                    valid_rows < profile.readiness_thresholds.development.min_samples
                    or groups < profile.readiness_thresholds.development.min_groups
                    or source_groups < profile.readiness_thresholds.development.min_groups
                ):
                    status = "INSUFFICIENT_DATA"
                else:
                    status = "SUPPORTED"
            elif target.value_type == ValueType.CENSORED_COUNT:
                status = "CENSORED_MODEL_NOT_IMPLEMENTED"
                if target.censored_column in measurements.columns:
                    censored = measurements[target.censored_column].fillna(False).astype(bool)
                    if censored.any() and target.censor_type_column in measurements.columns:
                        actual_types = set(
                            measurements.loc[censored, target.censor_type_column]
                            .dropna()
                            .astype(str)
                        )
                        if not actual_types.issubset({"RIGHT", "LEFT", "INTERVAL"}):
                            errors.append(
                                f"censored target {target.code} contains invalid censorType"
                            )
            elif target.value_type in {ValueType.BINARY, ValueType.CATEGORICAL}:
                observed_classes = len(actual_classes)
                if observed_classes < 2:
                    status = "MODEL_NOT_READY"
                elif (
                    valid_rows < profile.readiness_thresholds.development.min_samples
                    or groups < profile.readiness_thresholds.development.min_groups
                    or source_groups < profile.readiness_thresholds.development.min_groups
                ):
                    status = "INSUFFICIENT_DATA"
                else:
                    status = "SUPPORTED"
            elif (
                valid_rows < profile.readiness_thresholds.development.min_samples
                or groups < profile.readiness_thresholds.development.min_groups
                or source_groups < profile.readiness_thresholds.development.min_groups
            ):
                status = "INSUFFICIENT_DATA"
            else:
                status = "SUPPORTED"
            coverages.append(
                TargetCoverage(
                    target_code=target.code,
                    value_type=target.value_type,
                    valid_rows=valid_rows,
                    missing_rows=int(len(measurements) - valid_rows),
                    groups=groups,
                    source_groups=source_groups,
                    status=status,
                )
            )

        production_eligible = bool(manifest.get("production_eligible", False))
        if not production_eligible:
            warnings.append("snapshot is development-only and cannot be activated")
        if manifest.get("data_nature") == "SYNTHETIC":
            warnings.append("snapshot contains synthetic data")

        manifest_rows = manifest.get("generator", {}).get("row_count")
        if manifest_rows is not None and int(manifest_rows) != len(measurements):
            errors.append("manifest row_count does not match measurements")

        groups = (
            int(joined[group_column].nunique())
            if not joined.empty and group_column in joined.columns
            else 0
        )
        source_group = profile.validation.source_group_column
        source_sheets = (
            int(joined[source_group].nunique())
            if not joined.empty and source_group in joined.columns
            else 0
        )
        source_sheet_stats = SourceSheetStats(
            total_sheets=source_sheets,
            sheets_with_multiple_lineages=0,
            max_lineages_per_sheet=0,
        )
        if not joined.empty and source_group in joined.columns and group_column in joined.columns:
            lineage_counts = joined.groupby(source_group, dropna=False)[group_column].nunique()
            source_sheet_stats = SourceSheetStats(
                total_sheets=int(len(lineage_counts)),
                sheets_with_multiple_lineages=int((lineage_counts > 1).sum()),
                max_lineages_per_sheet=int(lineage_counts.max()) if len(lineage_counts) else 0,
            )
        response = SnapshotValidationResponse(
            request_id=request.request_id,
            snapshot_id=snapshot_id,
            snapshot_hash=request.snapshot_hash,
            valid=not errors,
            production_eligible=production_eligible,
            rows=len(measurements),
            groups=groups,
            source_sheets=source_sheets,
            source_sheet_stats=source_sheet_stats,
            data_nature=str(manifest.get("data_nature", "UNKNOWN")),
            snapshot_purpose=str(manifest.get("snapshot_purpose", "UNKNOWN")),
            targets=coverages,
            errors=errors,
            warnings=warnings,
        )
        return response, joined


def require_valid_snapshot(snapshot: LoadedSnapshot) -> None:
    if not snapshot.response.valid:
        raise FormulaModelError(
            ErrorCode.INVALID_SNAPSHOT,
            "snapshot validation failed",
            details={"errors": snapshot.response.errors},
        )
