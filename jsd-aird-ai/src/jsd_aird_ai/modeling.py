from __future__ import annotations

import io
import json
import logging
import platform
import time
import warnings
import zipfile
from dataclasses import dataclass
from typing import Any

import baybe
import catboost
import joblib
import lightgbm
import numpy as np
import pandas as pd
import sklearn
import xgboost

from jsd_aird_ai.contracts import (
    AlgorithmMetadata,
    ApplicabilityDomainSummary,
    CandidateModelMetric,
    ClassificationTrainingSummary,
    FeatureViewSpec,
    GroupStabilitySummary,
    ModelType,
    OrdinalTrainingSummary,
    StabilitySummary,
    TargetTrainingResult,
    TaskProfile,
    UncertaintySummary,
    ValidationManifest,
    ValueType,
)
from jsd_aird_ai.applicability import fit_applicability_domain
from jsd_aird_ai.classification import (
    ClassificationCvEvaluation,
    ClassificationModelAdapter,
    classification_selection_score,
    default_classification_adapters,
    encode_class_labels,
    evaluate_classification_adapter,
    select_classification_champion,
)
from jsd_aird_ai.digests import canonical_json_bytes, canonical_sha256, sha256_bytes
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.features import FeatureBuilder
from jsd_aird_ai.feature_views import base_feature_view, feature_view_hash, feature_view_json
from jsd_aird_ai.model_adapters import ModelAdapter, default_model_adapters
from jsd_aird_ai.ordinal import evaluate_ordinal, fit_ordinal_model
from jsd_aird_ai.readiness import (
    classification_readiness,
    continuous_readiness,
    ordinal_readiness,
)
from jsd_aird_ai.snapshot import LoadedSnapshot, require_valid_snapshot
from jsd_aird_ai.validation import (
    CvEvaluation,
    FoldDefinition,
    development_baseline,
    evaluate_adapter,
    group_folds,
    random_folds,
    select_champion,
)
from jsd_aird_ai.validation_folds import (
    FORMULA_LINEAGE,
    SOURCE_CONTEXT,
    LoadedValidationArtifacts,
    validate_baseline_target,
    validated_baseline_summary,
)


BUNDLE_MANIFEST = "bundle-manifest.json"
BUNDLE_PAYLOAD = "model-payload.joblib"
BUNDLE_CARD = "model-card.json"
BUNDLE_PROFILE = "task-profile.json"
log = logging.getLogger("jsd_aird_ai.training")


@dataclass
class ModelBundle:
    manifest: dict[str, Any]
    profile: TaskProfile
    payload: dict[str, Any]
    card: dict[str, Any]

    @property
    def targets(self) -> dict[str, dict[str, Any]]:
        return self.payload["targets"]

    @property
    def feature_view(self) -> FeatureViewSpec:
        """Return the view captured by this bundle; old bundles default to BASE_V1."""
        value = self.payload.get("feature_view")
        if value is None:
            return base_feature_view(self.profile)
        return FeatureViewSpec.model_validate(value)


@dataclass(frozen=True)
class _ContinuousCandidate:
    adapter: ModelAdapter
    lineage: CvEvaluation
    sheet: CvEvaluation
    final_params: dict[str, Any]


@dataclass(frozen=True)
class _ClassificationCandidate:
    adapter: ClassificationModelAdapter
    lineage: ClassificationCvEvaluation
    sheet: ClassificationCvEvaluation
    final_params: dict[str, Any]


def censored_count_training_block(
    measurements: pd.DataFrame, target_spec: Any
) -> str | None:
    if target_spec.value_type != ValueType.CENSORED_COUNT:
        return None
    if not target_spec.censored_column or target_spec.censored_column not in measurements:
        return "CENSORED_METADATA_MISSING"
    censored = measurements[target_spec.censored_column].fillna(False).astype(bool)
    if censored.any():
        return "CENSORED_MODEL_NOT_IMPLEMENTED"
    # The target keeps its censored-count semantic even if this particular snapshot
    # happens to contain no censored row; ordinary regression must not be selected.
    return "CENSORED_MODEL_NOT_IMPLEMENTED"


def model_eligibility_status(
    profile: TaskProfile, model_type: ModelType | str, valid_rows: int
) -> str:
    minimum = profile.model_eligibility.minimum_for(model_type)
    return "ELIGIBLE" if valid_rows >= minimum else "SKIPPED_INSUFFICIENT_SAMPLES"


class ModelTrainer:
    def __init__(
        self,
        thread_count: int = 2,
        adapters: list[ModelAdapter] | None = None,
        classification_adapters: list[ClassificationModelAdapter] | None = None,
    ) -> None:
        self.thread_count = max(1, int(thread_count))
        self.adapters = (
            default_model_adapters(self.thread_count) if adapters is None else adapters
        )
        self.classification_adapters = (
            default_classification_adapters(self.thread_count)
            if classification_adapters is None
            else classification_adapters
        )

    def train(
        self,
        snapshot: LoadedSnapshot,
        profile: TaskProfile,
        task_profile_hash: str,
        snapshot_hash: str,
        seed: int,
        validation_artifacts: LoadedValidationArtifacts | None = None,
        feature_view: FeatureViewSpec | None = None,
    ) -> tuple[bytes, list[TargetTrainingResult], list[str]]:
        require_valid_snapshot(snapshot)
        effective_view = feature_view or base_feature_view(profile)
        builder = FeatureBuilder(profile, effective_view)
        all_features = builder.from_snapshot(snapshot.measurements)
        feature_valid = builder.valid_feature_mask(all_features)
        lineage_values = snapshot.joined[profile.validation.group_column].to_numpy()
        sheet_values = snapshot.joined[profile.validation.source_group_column].to_numpy()
        target_payloads: dict[str, dict[str, Any]] = {}
        results: list[TargetTrainingResult] = []

        for target_spec in profile.targets:
            if target_spec.value_type == ValueType.CENSORED_COUNT:
                reason = censored_count_training_block(snapshot.measurements, target_spec)
                results.append(
                    TargetTrainingResult(
                        target_code=target_spec.code,
                        target_type=target_spec.value_type,
                        status="UNSUPPORTED_TARGET_TYPE",
                        reasons=[reason or "CENSORED_MODEL_NOT_IMPLEMENTED"],
                    )
                )
                continue
            classification_target = target_spec.value_type in {
                ValueType.BINARY,
                ValueType.CATEGORICAL,
            }
            if classification_target:
                target_series = snapshot.measurements[target_spec.code].astype("string").str.strip()
                mask = feature_valid & target_series.notna() & target_series.ne("")
            else:
                target_series = pd.to_numeric(
                    snapshot.measurements[target_spec.code], errors="coerce"
                )
                mask = feature_valid & target_series.notna() & np.isfinite(target_series)
            valid_rows = int(mask.sum())
            target_lineages = lineage_values[mask.to_numpy()]
            target_sheets = sheet_values[mask.to_numpy()]
            target_row_ids = snapshot.measurements.loc[
                mask, snapshot.identity_column
            ].astype(str).to_numpy()
            lineage_groups = int(pd.Series(target_lineages).nunique())
            sheet_groups = int(pd.Series(target_sheets).nunique())
            development = profile.readiness_thresholds.development
            development_eligible = (
                valid_rows >= development.min_samples
                and lineage_groups >= development.min_groups
                and sheet_groups >= development.min_groups
            )
            if not development_eligible:
                results.append(
                    TargetTrainingResult(
                        target_code=target_spec.code,
                        target_type=target_spec.value_type,
                        status="INSUFFICIENT_DATA",
                        valid_rows=valid_rows,
                        groups=lineage_groups,
                        source_groups=sheet_groups,
                        reasons=["DEVELOPMENT_SAMPLE_OR_GROUP_THRESHOLD_NOT_MET"],
                    )
                )
                continue

            features = all_features.loc[mask].reset_index(drop=True)
            target = (
                target_series.loc[mask].astype(str).to_numpy()
                if classification_target
                else target_series.loc[mask].to_numpy(dtype=float)
            )
            if validation_artifacts is None:
                lineage_folds = group_folds(target_lineages, profile.validation.folds)
                sheet_folds = group_folds(target_sheets, profile.validation.folds)
            else:
                lineage_folds = validation_artifacts.folds_for(
                    target_key=target_spec.target_key,
                    validation_scheme=FORMULA_LINEAGE,
                    row_ids=target_row_ids,
                    groups=target_lineages,
                )
                sheet_folds = validation_artifacts.folds_for(
                    target_key=target_spec.target_key,
                    validation_scheme=SOURCE_CONTEXT,
                    row_ids=target_row_ids,
                    groups=target_sheets,
                )
                validate_baseline_target(
                    validation_artifacts,
                    target_key=target_spec.target_key,
                    target_code=target_spec.code,
                    row_ids=target_row_ids,
                    actual_values=target,
                )

            if classification_target:
                result, payload = self._train_classification_target(
                    snapshot=snapshot,
                    profile=profile,
                    builder=builder,
                    target_spec=target_spec,
                    features=features,
                    target=target,
                    target_row_ids=target_row_ids,
                    target_lineages=target_lineages,
                    target_sheets=target_sheets,
                    lineage_folds=lineage_folds,
                    sheet_folds=sheet_folds,
                    valid_rows=valid_rows,
                    lineage_groups=lineage_groups,
                    sheet_groups=sheet_groups,
                    seed=seed,
                )
            elif target_spec.value_type == ValueType.ORDINAL:
                result, payload = self._train_ordinal_target(
                    snapshot=snapshot,
                    profile=profile,
                    builder=builder,
                    target_spec=target_spec,
                    features=features,
                    target=target,
                    target_row_ids=target_row_ids,
                    target_lineages=target_lineages,
                    target_sheets=target_sheets,
                    lineage_folds=lineage_folds,
                    sheet_folds=sheet_folds,
                    valid_rows=valid_rows,
                    lineage_groups=lineage_groups,
                    sheet_groups=sheet_groups,
                    seed=seed,
                )
            else:
                result, payload = self._train_continuous_target(
                    snapshot=snapshot,
                    profile=profile,
                    builder=builder,
                    target_spec=target_spec,
                    features=features,
                    target=target,
                    target_row_ids=target_row_ids,
                    target_lineages=target_lineages,
                    target_sheets=target_sheets,
                    lineage_folds=lineage_folds,
                    sheet_folds=sheet_folds,
                    valid_rows=valid_rows,
                    lineage_groups=lineage_groups,
                    sheet_groups=sheet_groups,
                    seed=seed,
                    validation_artifacts=validation_artifacts,
                )
            results.append(result)
            if payload is not None:
                target_payloads[target_spec.code] = payload

        if not target_payloads:
            raise FormulaModelError(
                ErrorCode.INSUFFICIENT_DATA,
                "no target passed the development training pipeline",
            )

        target_columns = [
            item.code for item in profile.targets if item.code in snapshot.measurements
        ]
        history_columns = [*builder.layout.all, *target_columns]
        history = pd.concat(
            [
                all_features.reset_index(drop=True),
                snapshot.measurements[target_columns].reset_index(drop=True),
            ],
            axis=1,
        )[history_columns]
        payload = {
            "contract_version": "formula-model.v1",
            "task_profile_hash": task_profile_hash,
            "snapshot_hash": snapshot_hash,
            "snapshot_id": snapshot.response.snapshot_id,
            "seed": seed,
            "feature_view": feature_view_json(effective_view),
            "layout": builder.layout,
            "targets": target_payloads,
            "history": history,
        }
        card = {
            "contractVersion": "formula-model.v1",
            "snapshotId": snapshot.response.snapshot_id,
            "snapshotHash": snapshot_hash,
            "taskProfileHash": task_profile_hash,
            "seed": seed,
            "featureViewCode": effective_view.code,
            "featureViewHash": feature_view_hash(effective_view),
            "featureViewScope": effective_view.scope,
            "featureViewDevelopmentOnly": effective_view.development_only,
            "featureView": feature_view_json(effective_view),
            "dataNature": snapshot.response.data_nature,
            "snapshotPurpose": snapshot.response.snapshot_purpose,
            "sourceSheetStats": snapshot.response.source_sheet_stats.model_dump(
                mode="json", by_alias=True
            ),
            "targets": [_model_card_target(item) for item in results],
            "runtime": {
                "python": platform.python_version(),
                "baybe": baybe.__version__,
                "scikitLearn": sklearn.__version__,
                "lightgbm": lightgbm.__version__,
                "xgboost": xgboost.__version__,
                "catboost": catboost.__version__,
            },
            "reproducibility": {
                "nonDeterministicRuntimeMetricsExcluded": True,
            },
        }
        if validation_artifacts is not None:
            card["validationFoldsHash"] = validation_artifacts.folds_artifact_hash
            card["t06BaselineHash"] = validation_artifacts.baseline_artifact_hash
        identity = {
            "contractVersion": card["contractVersion"],
            "snapshotHash": snapshot_hash,
            "taskProfileHash": task_profile_hash,
            "featureViewHash": feature_view_hash(effective_view),
            "seed": seed,
            "targets": card["targets"],
            "runtime": card["runtime"],
        }
        card["reproducibility"]["algorithmEquivalenceSha256"] = sha256_bytes(
            canonical_json_bytes(identity)
        )
        return create_bundle(profile, payload, card), results, list(snapshot.response.warnings)

    def _train_continuous_target(
        self,
        *,
        snapshot: LoadedSnapshot,
        profile: TaskProfile,
        builder: FeatureBuilder,
        target_spec: Any,
        features: pd.DataFrame,
        target: np.ndarray,
        target_row_ids: np.ndarray,
        target_lineages: np.ndarray,
        target_sheets: np.ndarray,
        lineage_folds: Any,
        sheet_folds: Any,
        valid_rows: int,
        lineage_groups: int,
        sheet_groups: int,
        seed: int,
        validation_artifacts: LoadedValidationArtifacts | None,
    ) -> tuple[TargetTrainingResult, dict[str, Any] | None]:
        baseline = validated_baseline_summary(
            validation_artifacts,
            target_key=target_spec.target_key,
            target_code=target_spec.code,
            row_ids=target_row_ids,
            actual_values=target,
        )
        if baseline is None:
            baseline = development_baseline(
                features,
                target,
                lineage_folds,
                sheet_folds,
                builder.layout,
            )
        candidate_metrics: list[CandidateModelMetric] = []
        evaluations: dict[ModelType, _ContinuousCandidate] = {}
        diagnostic_folds = (
            random_folds(len(target), profile.validation.folds, seed)
            if profile.validation.random_kfold_diagnostic
            else None
        )

        for adapter_index, adapter in enumerate(self.adapters):
            minimum = profile.model_eligibility.minimum_for(adapter.model_type)
            if model_eligibility_status(profile, adapter.model_type, valid_rows) != "ELIGIBLE":
                log.info(
                    "model_training_skipped target=%s model=%s reason=insufficient_samples",
                    target_spec.code,
                    adapter.model_type,
                )
                candidate_metrics.append(
                    CandidateModelMetric(
                        model_type=adapter.model_type,
                        status="SKIPPED_INSUFFICIENT_SAMPLES",
                        failure_reason=f"requires at least {minimum} valid samples",
                    )
                )
                continue
            try:
                log.info(
                    "model_training_started target=%s model=%s rows=%s",
                    target_spec.code,
                    adapter.model_type,
                    valid_rows,
                )
                lineage = evaluate_adapter(
                    adapter,
                    features,
                    target,
                    target_lineages,
                    lineage_folds,
                    builder.layout,
                    seed + adapter_index * 100_000,
                    profile.validation.interval_level,
                )
                sheet = evaluate_adapter(
                    adapter,
                    features,
                    target,
                    target_sheets,
                    sheet_folds,
                    builder.layout,
                    seed + adapter_index * 100_000,
                    profile.validation.interval_level,
                )
                final_params = (
                    lineage.selected_params
                    if lineage.metrics.nmae >= sheet.metrics.nmae
                    else sheet.selected_params
                )
                evaluations[adapter.model_type] = _ContinuousCandidate(
                    adapter=adapter,
                    lineage=lineage,
                    sheet=sheet,
                    final_params=final_params,
                )
                diagnostic = None
                if diagnostic_folds is not None:
                    diagnostic = evaluate_adapter(
                        adapter,
                        features,
                        target,
                        np.arange(len(target)),
                        diagnostic_folds,
                        builder.layout,
                        seed + adapter_index * 100_000,
                        profile.validation.interval_level,
                    ).metrics
                candidate_metrics.append(
                    CandidateModelMetric(
                        model_type=adapter.model_type,
                        status="ELIGIBLE",
                        lineage_cv=lineage.metrics,
                        sheet_cv=sheet.metrics,
                        random_kfold_diagnostic=diagnostic,
                        final_params=final_params,
                        training_time_seconds=(
                            lineage.training_time_seconds + sheet.training_time_seconds
                        ),
                        prediction_time_seconds=(
                            lineage.prediction_time_seconds + sheet.prediction_time_seconds
                        ),
                        algorithm=adapter.metadata(final_params, seed),
                        lineage_fold_nmae=list(lineage.fold_nmae),
                        sheet_fold_nmae=list(sheet.fold_nmae),
                    )
                )
                log.info(
                    "model_training_completed target=%s model=%s lineageNmae=%.6f sheetNmae=%.6f",
                    target_spec.code,
                    adapter.model_type,
                    lineage.metrics.nmae,
                    sheet.metrics.nmae,
                )
            except Exception as exc:
                log.warning(
                    "model_training_failed target=%s model=%s errorType=%s",
                    target_spec.code,
                    adapter.model_type,
                    type(exc).__name__,
                )
                candidate_metrics.append(
                    CandidateModelMetric(
                        model_type=adapter.model_type,
                        status="TRAINING_FAILED",
                        failure_reason=f"{type(exc).__name__}: {exc}",
                    )
                )

        selection = select_champion(
            candidate_metrics,
            baseline,
            profile.model_selection,
            profile.readiness_thresholds.production_continuous,
        )
        if selection.champion_model is None:
            return (
                TargetTrainingResult(
                    target_code=target_spec.code,
                    target_type=target_spec.value_type,
                    status="FAILED",
                    baseline=baseline,
                    model_selection=selection,
                    valid_rows=valid_rows,
                    groups=lineage_groups,
                    source_groups=sheet_groups,
                    development_eligible=True,
                    reasons=["NO_CHALLENGER_PASSED_MODEL_SELECTION_GATES"],
                ),
                None,
            )

        chosen = evaluations[ModelType(selection.champion_model)]
        started = time.perf_counter()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            fitted = chosen.adapter.fit(
                features,
                target,
                builder.layout,
                seed,
                chosen.final_params,
                profile.validation.interval_level,
            )
        final_training_time = time.perf_counter() - started
        calibrated_radius = max(
            chosen.lineage.metrics.calibrated_radius,
            chosen.sheet.metrics.calibrated_radius,
        )
        fitted = fitted.calibrated(calibrated_radius)
        applicability = fit_applicability_domain(
            features,
            builder.layout,
            target_row_ids,
            target_lineages,
            target_sheets,
            profile.applicability_domain,
        )
        started = time.perf_counter()
        fitted.predict(features.iloc[: min(32, len(features))])
        final_prediction_time = time.perf_counter() - started
        for metric in selection.candidate_metrics:
            if metric.model_type == selection.champion_model:
                metric.training_time_seconds += final_training_time
                metric.prediction_time_seconds += final_prediction_time

        lineage_improvement = (
            baseline.metrics.lineage_nmae - chosen.lineage.metrics.nmae
        ) / max(baseline.metrics.lineage_nmae, 1e-12)
        sheet_improvement = (
            baseline.metrics.sheet_nmae - chosen.sheet.metrics.nmae
        ) / max(baseline.metrics.sheet_nmae, 1e-12)
        readiness = continuous_readiness(
            thresholds=profile.readiness_thresholds,
            data_nature=snapshot.response.data_nature,
            snapshot_purpose=snapshot.response.snapshot_purpose,
            snapshot_production_eligible=snapshot.response.production_eligible,
            context_compatible=snapshot.response.valid,
            sample_count=valid_rows,
            lineage_groups=lineage_groups,
            sheet_groups=sheet_groups,
            lineage_cv=chosen.lineage.metrics,
            sheet_cv=chosen.sheet.metrics,
            baseline=baseline,
        )
        uncertainty = UncertaintySummary(
            raw_coverage=chosen.lineage.metrics.raw_interval_coverage,
            calibrated_coverage=min(
                chosen.lineage.metrics.interval_coverage,
                chosen.sheet.metrics.interval_coverage,
            ),
            calibrated_radius=calibrated_radius,
        )
        validation_manifest = _validation_manifest(
            target_row_ids,
            lineage_folds,
            sheet_folds,
        )
        stability = StabilitySummary(
            lineage_cv=_group_stability(chosen.lineage.fold_nmae),
            sheet_cv=_group_stability(chosen.sheet.fold_nmae),
        )
        applicability_summary = _applicability_summary(applicability)
        result = TargetTrainingResult(
            target_code=target_spec.code,
            target_type=target_spec.value_type,
            status="READY",
            scorer_type=selection.champion_model,
            metrics=chosen.lineage.metrics,
            lineage_cv=chosen.lineage.metrics,
            sheet_cv=chosen.sheet.metrics,
            baseline_nmae=baseline.metrics.lineage_nmae,
            baseline_improvement=min(lineage_improvement, sheet_improvement),
            source_group_audit_nmae=chosen.sheet.metrics.nmae,
            baseline=baseline,
            model_selection=selection,
            uncertainty=uncertainty,
            readiness=readiness,
            validation_manifest=validation_manifest,
            stability=stability,
            applicability_domain=applicability_summary,
            valid_rows=valid_rows,
            groups=lineage_groups,
            source_groups=sheet_groups,
            development_eligible=True,
            production_eligible=not readiness.blocking_reasons,
            reasons=readiness.blocking_reasons,
        )
        payload = {
            "target_type": target_spec.value_type,
            "scorer": fitted,
            "scorer_type": selection.champion_model,
            "unit": target_spec.unit,
            "direction": target_spec.direction,
            "lineage_cv": chosen.lineage.metrics.model_dump(mode="json", by_alias=True),
            "sheet_cv": chosen.sheet.metrics.model_dump(mode="json", by_alias=True),
            "baseline": baseline.model_dump(mode="json", by_alias=True),
            "model_selection": _deterministic_model_selection(selection),
            "uncertainty": uncertainty.model_dump(mode="json", by_alias=True),
            "readiness": readiness.model_dump(mode="json", by_alias=True),
            "validation_manifest": validation_manifest.model_dump(
                mode="json", by_alias=True
            ),
            "stability": stability.model_dump(mode="json", by_alias=True),
            "applicability_domain": applicability,
            "applicability_domain_summary": applicability_summary.model_dump(
                mode="json", by_alias=True
            ),
            "valid_rows": valid_rows,
            "lineage_groups": lineage_groups,
            "sheet_groups": sheet_groups,
            "observed_lower": float(np.nanpercentile(target, 5)),
            "observed_upper": float(np.nanpercentile(target, 95)),
        }
        return result, payload

    def _train_classification_target(
        self,
        *,
        snapshot: LoadedSnapshot,
        profile: TaskProfile,
        builder: FeatureBuilder,
        target_spec: Any,
        features: pd.DataFrame,
        target: np.ndarray,
        target_row_ids: np.ndarray,
        target_lineages: np.ndarray,
        target_sheets: np.ndarray,
        lineage_folds: tuple[FoldDefinition, ...],
        sheet_folds: tuple[FoldDefinition, ...],
        valid_rows: int,
        lineage_groups: int,
        sheet_groups: int,
        seed: int,
    ) -> tuple[TargetTrainingResult, dict[str, Any] | None]:
        configured_classes = list(target_spec.class_labels or [])
        observed_set = set(str(item) for item in target)
        unexpected = sorted(observed_set - set(configured_classes))
        observed_classes = [item for item in configured_classes if item in observed_set]
        if unexpected:
            return (
                TargetTrainingResult(
                    target_code=target_spec.code,
                    target_type=target_spec.value_type,
                    status="MODEL_NOT_READY",
                    valid_rows=valid_rows,
                    groups=lineage_groups,
                    source_groups=sheet_groups,
                    development_eligible=True,
                    reasons=[f"UNCONFIGURED_CLASS_LABELS: {unexpected}"],
                ),
                None,
            )
        if len(observed_classes) < 2:
            return (
                TargetTrainingResult(
                    target_code=target_spec.code,
                    target_type=target_spec.value_type,
                    status="MODEL_NOT_READY",
                    valid_rows=valid_rows,
                    groups=lineage_groups,
                    source_groups=sheet_groups,
                    development_eligible=True,
                    reasons=["CLASSIFICATION_OBSERVED_CLASSES_LESS_THAN_TWO"],
                ),
                None,
            )

        class_counts = pd.Series(target).value_counts()
        minimum_class_samples = int(class_counts.min())
        if target_spec.value_type == ValueType.BINARY:
            configured = profile.readiness_thresholds.production_binary
            class_gate = configured.min_minority_class_samples
            class_reason = "BINARY_MINORITY_CLASS_THRESHOLD_NOT_MET"
            # A binary task is semantically incomplete unless both configured classes exist.
            valid_class_shape = len(observed_classes) == 2
        else:
            configured = profile.readiness_thresholds.production_categorical
            class_gate = configured.min_samples_per_observed_class
            class_reason = "CATEGORICAL_CLASS_SAMPLE_THRESHOLD_NOT_MET"
            valid_class_shape = len(observed_classes) >= configured.min_observed_classes
        if not valid_class_shape or minimum_class_samples < class_gate:
            reasons = []
            if not valid_class_shape:
                reasons.append("CLASSIFICATION_OBSERVED_CLASS_THRESHOLD_NOT_MET")
            if minimum_class_samples < class_gate:
                reasons.append(class_reason)
            return (
                TargetTrainingResult(
                    target_code=target_spec.code,
                    target_type=target_spec.value_type,
                    status="MODEL_NOT_READY",
                    valid_rows=valid_rows,
                    groups=lineage_groups,
                    source_groups=sheet_groups,
                    development_eligible=True,
                    reasons=reasons,
                ),
                None,
            )

        encoded = encode_class_labels(target, observed_classes)
        candidate_metrics: list[CandidateModelMetric] = []
        evaluations: dict[ModelType, _ClassificationCandidate] = {}
        for adapter_index, adapter in enumerate(self.classification_adapters):
            minimum = profile.classification_model_eligibility.minimum_for(
                adapter.model_type
            )
            if valid_rows < minimum:
                candidate_metrics.append(
                    CandidateModelMetric(
                        model_type=adapter.model_type,
                        status="SKIPPED_INSUFFICIENT_SAMPLES",
                        failure_reason=f"requires at least {minimum} valid samples",
                    )
                )
                continue
            try:
                log.info(
                    "classification_training_started target=%s model=%s rows=%s",
                    target_spec.code,
                    adapter.model_type,
                    valid_rows,
                )
                adapter_seed = seed + adapter_index * 100_000
                lineage = evaluate_classification_adapter(
                    adapter,
                    features,
                    encoded,
                    target_lineages,
                    lineage_folds,
                    builder.layout,
                    adapter_seed,
                    target_spec.value_type,
                    observed_classes,
                    target_spec.positive_class,
                    target_spec.decision_threshold,
                    profile.probability_calibration,
                )
                sheet = evaluate_classification_adapter(
                    adapter,
                    features,
                    encoded,
                    target_sheets,
                    sheet_folds,
                    builder.layout,
                    adapter_seed,
                    target_spec.value_type,
                    observed_classes,
                    target_spec.positive_class,
                    target_spec.decision_threshold,
                    profile.probability_calibration,
                )
                lineage_score = classification_selection_score(lineage.metrics)
                sheet_score = classification_selection_score(sheet.metrics)
                final_params = (
                    lineage.selected_params
                    if lineage_score >= sheet_score
                    else sheet.selected_params
                )
                evaluations[adapter.model_type] = _ClassificationCandidate(
                    adapter=adapter,
                    lineage=lineage,
                    sheet=sheet,
                    final_params=final_params,
                )
                candidate_metrics.append(
                    CandidateModelMetric(
                        model_type=adapter.model_type,
                        status="ELIGIBLE",
                        lineage_cv=lineage.metrics,
                        sheet_cv=sheet.metrics,
                        final_params=final_params,
                        training_time_seconds=(
                            lineage.training_time_seconds + sheet.training_time_seconds
                        ),
                        prediction_time_seconds=(
                            lineage.prediction_time_seconds
                            + sheet.prediction_time_seconds
                        ),
                        algorithm=adapter.metadata(final_params, seed),
                        lineage_fold_scores=list(lineage.fold_scores),
                        sheet_fold_scores=list(sheet.fold_scores),
                    )
                )
                log.info(
                    "classification_training_completed target=%s model=%s "
                    "lineageScore=%.6f sheetScore=%.6f",
                    target_spec.code,
                    adapter.model_type,
                    lineage_score,
                    sheet_score,
                )
            except Exception as exc:
                log.warning(
                    "classification_training_failed target=%s model=%s errorType=%s",
                    target_spec.code,
                    adapter.model_type,
                    type(exc).__name__,
                )
                candidate_metrics.append(
                    CandidateModelMetric(
                        model_type=adapter.model_type,
                        status="TRAINING_FAILED",
                        failure_reason=f"{type(exc).__name__}: {exc}",
                    )
                )

        selection = select_classification_champion(
            candidate_metrics,
            target_spec.value_type,
            profile.classification_model_selection,
        )
        if selection.champion_model is None:
            return (
                TargetTrainingResult(
                    target_code=target_spec.code,
                    target_type=target_spec.value_type,
                    status="FAILED",
                    model_selection=selection,
                    valid_rows=valid_rows,
                    groups=lineage_groups,
                    source_groups=sheet_groups,
                    development_eligible=True,
                    reasons=["NO_CLASSIFICATION_CHALLENGER_COMPLETED_BOTH_GROUP_CVS"],
                ),
                None,
            )

        chosen = evaluations[ModelType(selection.champion_model)]
        lineage_score = classification_selection_score(chosen.lineage.metrics)
        sheet_score = classification_selection_score(chosen.sheet.metrics)
        deployment_calibrator = (
            chosen.lineage.deployment_calibrator
            if lineage_score >= sheet_score
            else chosen.sheet.deployment_calibrator
        )
        started = time.perf_counter()
        fitted = chosen.adapter.fit(
            features,
            encoded,
            builder.layout,
            seed,
            chosen.final_params,
            observed_classes,
            target_spec.value_type,
            target_spec.positive_class,
            target_spec.decision_threshold,
            profile.probability_calibration.method,
        ).calibrated(deployment_calibrator)
        final_training_time = time.perf_counter() - started
        started = time.perf_counter()
        fitted.predict(features.iloc[: min(32, len(features))])
        final_prediction_time = time.perf_counter() - started
        for metric in selection.candidate_metrics:
            if metric.model_type == selection.champion_model:
                metric.training_time_seconds += final_training_time
                metric.prediction_time_seconds += final_prediction_time

        readiness = classification_readiness(
            thresholds=profile.readiness_thresholds,
            data_nature=snapshot.response.data_nature,
            snapshot_purpose=snapshot.response.snapshot_purpose,
            snapshot_production_eligible=snapshot.response.production_eligible,
            context_compatible=snapshot.response.valid,
            target_type=target_spec.value_type,
            target_values=encoded,
            lineage_groups=lineage_groups,
            sheet_groups=sheet_groups,
            lineage_cv=chosen.lineage.metrics,
            sheet_cv=chosen.sheet.metrics,
        )
        applicability = fit_applicability_domain(
            features,
            builder.layout,
            target_row_ids,
            target_lineages,
            target_sheets,
            profile.applicability_domain,
        )
        validation_manifest = _validation_manifest(
            target_row_ids,
            lineage_folds,
            sheet_folds,
        )
        applicability_summary = _applicability_summary(applicability)
        summary = ClassificationTrainingSummary(
            target_type=target_spec.value_type,
            configured_classes=configured_classes,
            observed_classes=observed_classes,
            positive_class=target_spec.positive_class,
            decision_threshold=(
                target_spec.decision_threshold
                if target_spec.value_type == ValueType.BINARY
                else None
            ),
            calibration_method=profile.probability_calibration.method,
            lineage_cv=chosen.lineage.metrics,
            sheet_cv=chosen.sheet.metrics,
        )
        result = TargetTrainingResult(
            target_code=target_spec.code,
            target_type=target_spec.value_type,
            status="READY",
            scorer_type=selection.champion_model,
            lineage_cv=chosen.lineage.metrics,
            sheet_cv=chosen.sheet.metrics,
            model_selection=selection,
            classification=summary,
            readiness=readiness,
            validation_manifest=validation_manifest,
            applicability_domain=applicability_summary,
            valid_rows=valid_rows,
            groups=lineage_groups,
            source_groups=sheet_groups,
            development_eligible=True,
            production_eligible=not readiness.blocking_reasons,
            reasons=readiness.blocking_reasons,
        )
        payload = {
            "target_type": target_spec.value_type,
            "scorer": fitted,
            "scorer_type": selection.champion_model,
            "unit": target_spec.unit,
            "direction": target_spec.direction,
            "classification": summary.model_dump(mode="json", by_alias=True),
            "model_selection": _deterministic_model_selection(selection),
            "readiness": readiness.model_dump(mode="json", by_alias=True),
            "validation_manifest": validation_manifest.model_dump(
                mode="json", by_alias=True
            ),
            "applicability_domain": applicability,
            "applicability_domain_summary": applicability_summary.model_dump(
                mode="json", by_alias=True
            ),
            "valid_rows": valid_rows,
            "lineage_groups": lineage_groups,
            "sheet_groups": sheet_groups,
        }
        return result, payload

    def _train_ordinal_target(
        self,
        *,
        snapshot: LoadedSnapshot,
        profile: TaskProfile,
        builder: FeatureBuilder,
        target_spec: Any,
        features: pd.DataFrame,
        target: np.ndarray,
        target_row_ids: np.ndarray,
        target_lineages: np.ndarray,
        target_sheets: np.ndarray,
        lineage_folds: Any,
        sheet_folds: Any,
        valid_rows: int,
        lineage_groups: int,
        sheet_groups: int,
        seed: int,
    ) -> tuple[TargetTrainingResult, dict[str, Any] | None]:
        if len(np.unique(target)) < 2:
            return (
                TargetTrainingResult(
                    target_code=target_spec.code,
                    target_type=target_spec.value_type,
                    status="MODEL_NOT_READY",
                    valid_rows=valid_rows,
                    groups=lineage_groups,
                    source_groups=sheet_groups,
                    development_eligible=True,
                    reasons=["ORDINAL_OBSERVED_CLASSES_LESS_THAN_TWO"],
                ),
                None,
            )
        try:
            lineage = evaluate_ordinal(
                features,
                target,
                lineage_folds,
                builder.layout,
                target_spec,
                seed,
            )
            sheet = evaluate_ordinal(
                features,
                target,
                sheet_folds,
                builder.layout,
                target_spec,
                seed,
            )
            started = time.perf_counter()
            fitted = fit_ordinal_model(
                features,
                target,
                builder.layout,
                target_spec,
                seed,
            )
            final_training_time = time.perf_counter() - started
        except FormulaModelError as exc:
            return (
                TargetTrainingResult(
                    target_code=target_spec.code,
                    target_type=target_spec.value_type,
                    status="MODEL_NOT_READY",
                    valid_rows=valid_rows,
                    groups=lineage_groups,
                    source_groups=sheet_groups,
                    development_eligible=True,
                    reasons=[f"ORDINAL_MODEL_NOT_READY: {exc.message}"],
                ),
                None,
            )
        except Exception as exc:
            return (
                TargetTrainingResult(
                    target_code=target_spec.code,
                    target_type=target_spec.value_type,
                    status="FAILED",
                    valid_rows=valid_rows,
                    groups=lineage_groups,
                    source_groups=sheet_groups,
                    development_eligible=True,
                    reasons=[f"ORDINAL_TRAINING_FAILED: {type(exc).__name__}: {exc}"],
                ),
                None,
            )
        value_to_label = {
            float(value): label
            for value, label in zip(
                target_spec.ordinal_values or [],
                target_spec.ordinal_labels or [],
                strict=True,
            )
        }
        observed_labels = [value_to_label[float(value)] for value in sorted(np.unique(target))]
        algorithm = AlgorithmMetadata(
            model_type=ModelType.ORDINAL_CUMULATIVE_LOGIT,
            library="scikit-learn",
            library_version=sklearn.__version__,
            final_params={"baseClassifier": "LogisticRegression", "maxIter": 1000},
            seed=seed,
        )
        ordinal_summary = OrdinalTrainingSummary(
            ordered_classes=list(target_spec.ordinal_labels or []),
            observed_classes=observed_labels,
            lineage_cv=lineage.metrics,
            sheet_cv=sheet.metrics,
            algorithm=algorithm,
            training_time_seconds=(
                lineage.training_time_seconds
                + sheet.training_time_seconds
                + final_training_time
            ),
            prediction_time_seconds=(
                lineage.prediction_time_seconds + sheet.prediction_time_seconds
            ),
        )
        readiness = ordinal_readiness(
            thresholds=profile.readiness_thresholds,
            data_nature=snapshot.response.data_nature,
            snapshot_purpose=snapshot.response.snapshot_purpose,
            snapshot_production_eligible=snapshot.response.production_eligible,
            context_compatible=snapshot.response.valid,
            target_values=target,
            lineage_groups=lineage_groups,
            sheet_groups=sheet_groups,
            lineage_cv=lineage.metrics,
            sheet_cv=sheet.metrics,
        )
        applicability = fit_applicability_domain(
            features,
            builder.layout,
            target_row_ids,
            target_lineages,
            target_sheets,
            profile.applicability_domain,
        )
        validation_manifest = _validation_manifest(
            target_row_ids,
            lineage_folds,
            sheet_folds,
        )
        applicability_summary = _applicability_summary(applicability)
        result = TargetTrainingResult(
            target_code=target_spec.code,
            target_type=target_spec.value_type,
            status="READY",
            scorer_type=ModelType.ORDINAL_CUMULATIVE_LOGIT,
            ordinal=ordinal_summary,
            readiness=readiness,
            validation_manifest=validation_manifest,
            applicability_domain=applicability_summary,
            valid_rows=valid_rows,
            groups=lineage_groups,
            source_groups=sheet_groups,
            development_eligible=True,
            production_eligible=not readiness.blocking_reasons,
            reasons=readiness.blocking_reasons,
        )
        payload = {
            "target_type": target_spec.value_type,
            "scorer": fitted,
            "scorer_type": ModelType.ORDINAL_CUMULATIVE_LOGIT,
            "unit": target_spec.unit,
            "direction": target_spec.direction,
            "ordinal": _deterministic_ordinal_summary(ordinal_summary),
            "readiness": readiness.model_dump(mode="json", by_alias=True),
            "validation_manifest": validation_manifest.model_dump(
                mode="json", by_alias=True
            ),
            "applicability_domain": applicability,
            "applicability_domain_summary": applicability_summary.model_dump(
                mode="json", by_alias=True
            ),
            "valid_rows": valid_rows,
            "lineage_groups": lineage_groups,
            "sheet_groups": sheet_groups,
        }
        return result, payload


def _validation_manifest(
    row_ids: np.ndarray,
    lineage_folds: tuple[FoldDefinition, ...],
    sheet_folds: tuple[FoldDefinition, ...],
) -> ValidationManifest:
    identifiers = [str(item) for item in row_ids]

    def fold_payload(folds: tuple[FoldDefinition, ...]) -> list[list[str]]:
        return [
            [identifiers[int(index)] for index in fold.validation]
            for fold in folds
        ]

    lineage_payload = fold_payload(lineage_folds)
    sheet_payload = fold_payload(sheet_folds)
    return ValidationManifest(
        effective_row_count=len(identifiers),
        effective_row_ids_sha256=sha256_bytes(canonical_json_bytes(identifiers)),
        lineage_folds_sha256=sha256_bytes(canonical_json_bytes(lineage_payload)),
        sheet_folds_sha256=sha256_bytes(canonical_json_bytes(sheet_payload)),
        lineage_fold_sizes=[len(item) for item in lineage_payload],
        sheet_fold_sizes=[len(item) for item in sheet_payload],
    )


def _group_stability(values: tuple[float, ...]) -> GroupStabilitySummary:
    metrics = np.asarray(values, dtype=float)
    return GroupStabilitySummary(
        fold_nmae_mean=float(np.mean(metrics)),
        fold_nmae_std=float(np.std(metrics)),
        fold_nmae_min=float(np.min(metrics)),
        fold_nmae_max=float(np.max(metrics)),
    )


def _applicability_summary(domain: Any) -> ApplicabilityDomainSummary:
    return ApplicabilityDomainSummary(
        reference_rows=len(domain.reference_row_ids),
        near_boundary_threshold=domain.near_boundary_threshold,
        out_of_domain_threshold=domain.out_of_domain_threshold,
        numeric_features=list(domain.layout.numeric),
        categorical_features=list(domain.layout.categorical),
    )


def _model_card_target(result: TargetTrainingResult) -> dict[str, Any]:
    """Expose the A.2 model-card sections without breaking formula-model.v1 fields."""
    card = result.model_dump(mode="json", by_alias=True)
    if card.get("modelSelection") is not None:
        for candidate in card["modelSelection"]["candidateMetrics"]:
            candidate["trainingTimeSeconds"] = 0.0
            candidate["predictionTimeSeconds"] = 0.0
    if card.get("ordinal") is not None:
        card["ordinal"]["trainingTimeSeconds"] = 0.0
        card["ordinal"]["predictionTimeSeconds"] = 0.0
    if result.ordinal is not None:
        lineage_cv: Any = result.ordinal.lineage_cv.model_dump(
            mode="json", by_alias=True
        )
        sheet_cv: Any = result.ordinal.sheet_cv.model_dump(mode="json", by_alias=True)
        algorithm: Any = result.ordinal.algorithm.model_dump(mode="json", by_alias=True)
        models: Any = {
            "eligibleModels": [ModelType.ORDINAL_CUMULATIVE_LOGIT],
            "skippedModels": [],
            "failedModels": [],
            "candidateMetrics": [],
            "championModel": ModelType.ORDINAL_CUMULATIVE_LOGIT,
            "selectionReason": "ORDINAL target uses its dedicated cumulative-logit pipeline",
        }
    else:
        lineage_cv = (
            result.lineage_cv.model_dump(mode="json", by_alias=True)
            if result.lineage_cv is not None
            else None
        )
        sheet_cv = (
            result.sheet_cv.model_dump(mode="json", by_alias=True)
            if result.sheet_cv is not None
            else None
        )
        models = (
            _deterministic_model_selection(result.model_selection)
            if result.model_selection is not None
            else None
        )
        algorithm = None
        if result.model_selection is not None:
            champion = result.model_selection.champion_model
            champion_metric = next(
                (
                    item
                    for item in result.model_selection.candidate_metrics
                    if item.model_type == champion
                ),
                None,
            )
            if champion_metric is not None and champion_metric.algorithm is not None:
                algorithm = champion_metric.algorithm.model_dump(
                    mode="json", by_alias=True
                )
    card.update(
        {
            "target": {
                "targetCode": result.target_code,
                "targetType": result.target_type,
            },
            "validation": {
                "lineageCv": lineage_cv,
                "sheetCv": sheet_cv,
            },
            "models": models,
            "algorithm": algorithm,
        }
    )
    return card


def _deterministic_model_selection(selection: Any) -> dict[str, Any]:
    value = selection.model_dump(mode="json", by_alias=True)
    for candidate in value["candidateMetrics"]:
        candidate["trainingTimeSeconds"] = 0.0
        candidate["predictionTimeSeconds"] = 0.0
    return value


def _deterministic_ordinal_summary(summary: OrdinalTrainingSummary) -> dict[str, Any]:
    value = summary.model_dump(mode="json", by_alias=True)
    value["trainingTimeSeconds"] = 0.0
    value["predictionTimeSeconds"] = 0.0
    return value


def create_bundle(profile: TaskProfile, payload: dict[str, Any], card: dict[str, Any]) -> bytes:
    payload_stream = io.BytesIO()
    joblib.dump(payload, payload_stream, compress=3)
    payload_bytes = payload_stream.getvalue()
    profile_bytes = canonical_json_bytes(profile)
    card_bytes = canonical_json_bytes(card)
    manifest = {
        "contractVersion": "formula-model.v1",
        "format": "JSD_FORMULA_MODEL_BUNDLE_V1",
        "payloadSha256": sha256_bytes(payload_bytes),
        "profileSha256": sha256_bytes(profile_bytes),
        "cardSha256": sha256_bytes(card_bytes),
    }
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        _write_deterministic_zip_entry(
            archive, BUNDLE_MANIFEST, canonical_json_bytes(manifest)
        )
        _write_deterministic_zip_entry(archive, BUNDLE_PAYLOAD, payload_bytes)
        _write_deterministic_zip_entry(archive, BUNDLE_CARD, card_bytes)
        _write_deterministic_zip_entry(archive, BUNDLE_PROFILE, profile_bytes)
    return stream.getvalue()


def rebind_bundle_schema(
    data: bytes,
    expected_hash: str,
    updated_profile: TaskProfile,
) -> bytes:
    """Repackage unchanged fitted scorers after a schema-only profile update."""
    bundle = load_bundle(data, expected_hash)
    previous = bundle.profile.model_dump(mode="json", by_alias=True)
    updated = updated_profile.model_dump(mode="json", by_alias=True)
    previous_schema_hash = previous.pop("schemaHash")
    updated_schema_hash = updated.pop("schemaHash")
    if previous != updated or previous_schema_hash == updated_schema_hash:
        raise FormulaModelError(
            ErrorCode.HASH_MISMATCH,
            "model bundle can only be rebound when schemaHash is the sole profile change",
        )

    task_profile_hash = canonical_sha256(updated_profile)
    payload = dict(bundle.payload)
    payload["task_profile_hash"] = task_profile_hash
    card = json.loads(json.dumps(bundle.card))
    card["taskProfileHash"] = task_profile_hash
    identity = {
        "contractVersion": card["contractVersion"],
        "snapshotHash": card["snapshotHash"],
        "taskProfileHash": task_profile_hash,
        "featureViewHash": card.get("featureViewHash"),
        "seed": card["seed"],
        "targets": card["targets"],
        "runtime": card["runtime"],
    }
    card.setdefault("reproducibility", {})[
        "algorithmEquivalenceSha256"
    ] = sha256_bytes(canonical_json_bytes(identity))
    card["reproducibility"]["schemaOnlyRepackagedFromBundleSha256"] = expected_hash
    return create_bundle(updated_profile, payload, card)


def _write_deterministic_zip_entry(
    archive: zipfile.ZipFile,
    name: str,
    data: bytes,
) -> None:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o600 << 16
    archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def load_bundle(data: bytes, expected_hash: str) -> ModelBundle:
    if sha256_bytes(data) != expected_hash:
        raise FormulaModelError(ErrorCode.HASH_MISMATCH, "model bundle hash mismatch")
    try:
        with zipfile.ZipFile(io.BytesIO(data), "r") as archive:
            names = set(archive.namelist())
            required = {BUNDLE_MANIFEST, BUNDLE_PAYLOAD, BUNDLE_CARD, BUNDLE_PROFILE}
            if names != required:
                raise FormulaModelError(
                    ErrorCode.MODEL_NOT_READY,
                    "model bundle contains unexpected entries",
                )
            if any(name.startswith(("/", "\\")) or ".." in name.split("/") for name in names):
                raise FormulaModelError(ErrorCode.MODEL_NOT_READY, "unsafe model bundle path")
            manifest_bytes = archive.read(BUNDLE_MANIFEST)
            payload_bytes = archive.read(BUNDLE_PAYLOAD)
            card_bytes = archive.read(BUNDLE_CARD)
            profile_bytes = archive.read(BUNDLE_PROFILE)
        manifest = json.loads(manifest_bytes)
        if manifest.get("format") != "JSD_FORMULA_MODEL_BUNDLE_V1":
            raise FormulaModelError(ErrorCode.MODEL_NOT_READY, "unsupported model bundle format")
        checks = {
            "payloadSha256": sha256_bytes(payload_bytes),
            "profileSha256": sha256_bytes(profile_bytes),
            "cardSha256": sha256_bytes(card_bytes),
        }
        for field, actual in checks.items():
            if manifest.get(field) != actual:
                raise FormulaModelError(ErrorCode.HASH_MISMATCH, f"model bundle {field} mismatch")
        profile = TaskProfile.model_validate_json(profile_bytes)
        card = json.loads(card_bytes)
        runtime = card.get("runtime", {})
        current_versions = {
            "baybe": baybe.__version__,
            "scikitLearn": sklearn.__version__,
            "lightgbm": lightgbm.__version__,
            "xgboost": xgboost.__version__,
            "catboost": catboost.__version__,
        }
        mismatches = {
            name: {"bundle": runtime.get(name), "runtime": version}
            for name, version in current_versions.items()
            if runtime.get(name) != version
        }
        if mismatches:
            raise FormulaModelError(
                ErrorCode.MODEL_NOT_READY,
                "model bundle algorithm runtime versions do not match",
                details={"versionMismatches": mismatches},
            )
        payload = joblib.load(io.BytesIO(payload_bytes))
    except FormulaModelError:
        raise
    except Exception as exc:
        raise FormulaModelError(ErrorCode.MODEL_NOT_READY, "model bundle cannot be loaded") from exc
    if payload.get("contract_version") != "formula-model.v1":
        raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "model bundle contract mismatch")
    return ModelBundle(manifest, profile, payload, card)
