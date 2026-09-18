from __future__ import annotations

from collections import Counter

import numpy as np

from jsd_aird_ai.contracts import (
    BaselineSummary,
    BinaryMetricSet,
    CategoricalMetricSet,
    MetricSet,
    OrdinalMetricSet,
    ReadinessThresholdProfile,
    TargetReadiness,
    ValueType,
)


def _record(
    checks: dict[str, bool],
    reasons: list[str],
    name: str,
    passed: bool,
    blocking_reason: str,
) -> None:
    checks[name] = bool(passed)
    if not passed:
        reasons.append(blocking_reason)


def _snapshot_checks(
    *,
    data_nature: str,
    snapshot_purpose: str,
    production_eligible: bool,
    context_compatible: bool,
) -> tuple[dict[str, bool], list[str]]:
    checks: dict[str, bool] = {}
    reasons: list[str] = []
    _record(
        checks,
        reasons,
        "dataNature",
        data_nature == "REAL",
        "SYNTHETIC_DATA_NOT_PRODUCTION_ELIGIBLE"
        if data_nature == "SYNTHETIC"
        else "DATA_NATURE_NOT_PRODUCTION_ELIGIBLE",
    )
    _record(
        checks,
        reasons,
        "snapshotPurpose",
        snapshot_purpose in {"TRAINING", "PRODUCTION"},
        "SNAPSHOT_PURPOSE_NOT_PRODUCTION_ELIGIBLE",
    )
    _record(
        checks,
        reasons,
        "productionEligible",
        production_eligible,
        "SNAPSHOT_PRODUCTION_ELIGIBLE_FALSE",
    )
    _record(
        checks,
        reasons,
        "contextCompatibility",
        context_compatible,
        "CONTEXT_INCOMPATIBLE",
    )
    return checks, reasons


def continuous_readiness(
    *,
    thresholds: ReadinessThresholdProfile,
    data_nature: str,
    snapshot_purpose: str,
    snapshot_production_eligible: bool,
    context_compatible: bool,
    sample_count: int,
    lineage_groups: int,
    sheet_groups: int,
    lineage_cv: MetricSet,
    sheet_cv: MetricSet,
    baseline: BaselineSummary,
) -> TargetReadiness:
    checks, reasons = _snapshot_checks(
        data_nature=data_nature,
        snapshot_purpose=snapshot_purpose,
        production_eligible=snapshot_production_eligible,
        context_compatible=context_compatible,
    )
    configured = thresholds.production_continuous
    lineage_improvement = (
        (baseline.metrics.lineage_nmae - lineage_cv.nmae)
        / max(baseline.metrics.lineage_nmae, 1e-12)
    )
    sheet_improvement = (
        (baseline.metrics.sheet_nmae - sheet_cv.nmae)
        / max(baseline.metrics.sheet_nmae, 1e-12)
    )
    _record(checks, reasons, "targetType", True, "TARGET_TYPE_INCOMPATIBLE")
    _record(
        checks,
        reasons,
        "sampleCount",
        sample_count >= configured.min_samples,
        "PRODUCTION_SAMPLE_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "lineageIndependentGroupCount",
        lineage_groups >= configured.min_groups,
        "LINEAGE_GROUP_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "sheetIndependentGroupCount",
        sheet_groups >= configured.min_groups,
        "SHEET_GROUP_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "lineageCv",
        lineage_cv.nmae <= configured.max_nmae,
        "LINEAGE_CV_NMAE_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "sheetCv",
        sheet_cv.nmae <= configured.max_nmae,
        "SHEET_CV_NMAE_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "lineageBaselineImprovement",
        lineage_improvement >= configured.min_baseline_improvement,
        "LINEAGE_BASELINE_IMPROVEMENT_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "sheetBaselineImprovement",
        sheet_improvement >= configured.min_baseline_improvement,
        "SHEET_BASELINE_IMPROVEMENT_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "lineageIntervalCalibration",
        configured.min_interval_coverage
        <= lineage_cv.interval_coverage
        <= configured.max_interval_coverage,
        "LINEAGE_INTERVAL_CALIBRATION_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "sheetIntervalCalibration",
        configured.min_interval_coverage
        <= sheet_cv.interval_coverage
        <= configured.max_interval_coverage,
        "SHEET_INTERVAL_CALIBRATION_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "formalBaseline",
        baseline.type == configured.required_baseline_type,
        "FORMAL_BASELINE_REQUIRED",
    )
    return TargetReadiness(
        threshold_profile=thresholds,
        checks=checks,
        blocking_reasons=list(dict.fromkeys(reasons)),
    )


def ordinal_readiness(
    *,
    thresholds: ReadinessThresholdProfile,
    data_nature: str,
    snapshot_purpose: str,
    snapshot_production_eligible: bool,
    context_compatible: bool,
    target_values: np.ndarray,
    lineage_groups: int,
    sheet_groups: int,
    lineage_cv: OrdinalMetricSet,
    sheet_cv: OrdinalMetricSet,
) -> TargetReadiness:
    checks, reasons = _snapshot_checks(
        data_nature=data_nature,
        snapshot_purpose=snapshot_purpose,
        production_eligible=snapshot_production_eligible,
        context_compatible=context_compatible,
    )
    configured = thresholds.production_ordinal
    counts = Counter(float(item) for item in target_values)
    minimum_class_samples = min(counts.values()) if counts else 0
    _record(checks, reasons, "targetType", True, "TARGET_TYPE_INCOMPATIBLE")
    _record(
        checks,
        reasons,
        "sampleCount",
        len(target_values) >= configured.min_samples,
        "PRODUCTION_SAMPLE_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "samplesPerObservedClass",
        minimum_class_samples >= configured.min_samples_per_observed_class,
        "ORDINAL_CLASS_SAMPLE_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "lineageIndependentGroupCount",
        lineage_groups >= configured.min_groups,
        "LINEAGE_GROUP_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "sheetIndependentGroupCount",
        sheet_groups >= configured.min_groups,
        "SHEET_GROUP_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "lineageCv",
        lineage_cv.grade_mae <= configured.max_grade_mae
        and lineage_cv.plus_minus_one_accuracy >= configured.min_plus_minus_one_accuracy,
        "LINEAGE_ORDINAL_CV_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "sheetCv",
        sheet_cv.grade_mae <= configured.max_grade_mae
        and sheet_cv.plus_minus_one_accuracy >= configured.min_plus_minus_one_accuracy,
        "SHEET_ORDINAL_CV_THRESHOLD_NOT_MET",
    )
    return TargetReadiness(
        threshold_profile=thresholds,
        checks=checks,
        blocking_reasons=list(dict.fromkeys(reasons)),
    )


def classification_readiness(
    *,
    thresholds: ReadinessThresholdProfile,
    data_nature: str,
    snapshot_purpose: str,
    snapshot_production_eligible: bool,
    context_compatible: bool,
    target_type: ValueType,
    target_values: np.ndarray,
    lineage_groups: int,
    sheet_groups: int,
    lineage_cv: BinaryMetricSet | CategoricalMetricSet,
    sheet_cv: BinaryMetricSet | CategoricalMetricSet,
) -> TargetReadiness:
    """Apply target-type-specific gates to both isolated group validations."""
    checks, reasons = _snapshot_checks(
        data_nature=data_nature,
        snapshot_purpose=snapshot_purpose,
        production_eligible=snapshot_production_eligible,
        context_compatible=context_compatible,
    )
    counts = Counter(int(item) for item in target_values)
    minimum_class_samples = min(counts.values()) if counts else 0
    _record(checks, reasons, "targetType", True, "TARGET_TYPE_INCOMPATIBLE")

    if target_type == ValueType.BINARY:
        configured = thresholds.production_binary
        _record(
            checks,
            reasons,
            "observedClasses",
            len(counts) == 2,
            "BINARY_OBSERVED_CLASSES_NOT_TWO",
        )
        _record(
            checks,
            reasons,
            "sampleCount",
            len(target_values) >= configured.min_samples,
            "PRODUCTION_SAMPLE_THRESHOLD_NOT_MET",
        )
        _record(
            checks,
            reasons,
            "minorityClassSamples",
            minimum_class_samples >= configured.min_minority_class_samples,
            "BINARY_MINORITY_CLASS_THRESHOLD_NOT_MET",
        )
        _classification_group_checks(
            checks,
            reasons,
            lineage_groups,
            sheet_groups,
            configured.min_groups,
        )
        assert isinstance(lineage_cv, BinaryMetricSet)
        assert isinstance(sheet_cv, BinaryMetricSet)
        _binary_metric_gate(checks, reasons, "lineage", lineage_cv, configured)
        _binary_metric_gate(checks, reasons, "sheet", sheet_cv, configured)
    elif target_type == ValueType.CATEGORICAL:
        configured = thresholds.production_categorical
        _record(
            checks,
            reasons,
            "observedClasses",
            len(counts) >= configured.min_observed_classes,
            "CATEGORICAL_OBSERVED_CLASS_THRESHOLD_NOT_MET",
        )
        _record(
            checks,
            reasons,
            "sampleCount",
            len(target_values) >= configured.min_samples,
            "PRODUCTION_SAMPLE_THRESHOLD_NOT_MET",
        )
        _record(
            checks,
            reasons,
            "samplesPerObservedClass",
            minimum_class_samples >= configured.min_samples_per_observed_class,
            "CATEGORICAL_CLASS_SAMPLE_THRESHOLD_NOT_MET",
        )
        _classification_group_checks(
            checks,
            reasons,
            lineage_groups,
            sheet_groups,
            configured.min_groups,
        )
        assert isinstance(lineage_cv, CategoricalMetricSet)
        assert isinstance(sheet_cv, CategoricalMetricSet)
        _categorical_metric_gate(checks, reasons, "lineage", lineage_cv, configured)
        _categorical_metric_gate(checks, reasons, "sheet", sheet_cv, configured)
    else:
        _record(checks, reasons, "targetType", False, "TARGET_TYPE_INCOMPATIBLE")

    return TargetReadiness(
        threshold_profile=thresholds,
        checks=checks,
        blocking_reasons=list(dict.fromkeys(reasons)),
    )


def _classification_group_checks(
    checks: dict[str, bool],
    reasons: list[str],
    lineage_groups: int,
    sheet_groups: int,
    minimum: int,
) -> None:
    _record(
        checks,
        reasons,
        "lineageIndependentGroupCount",
        lineage_groups >= minimum,
        "LINEAGE_GROUP_THRESHOLD_NOT_MET",
    )
    _record(
        checks,
        reasons,
        "sheetIndependentGroupCount",
        sheet_groups >= minimum,
        "SHEET_GROUP_THRESHOLD_NOT_MET",
    )


def _binary_metric_gate(checks, reasons, prefix, metrics, configured) -> None:
    passed = (
        metrics.roc_auc >= configured.min_roc_auc
        and metrics.pr_auc >= configured.min_pr_auc
        and metrics.f1 >= configured.min_f1
        and metrics.calibrated_probability_quality.log_loss <= configured.max_log_loss
        and metrics.calibrated_probability_quality.brier_score
        <= configured.max_brier_score
    )
    _record(
        checks,
        reasons,
        f"{prefix}Cv",
        passed,
        f"{prefix.upper()}_BINARY_CV_THRESHOLD_NOT_MET",
    )


def _categorical_metric_gate(checks, reasons, prefix, metrics, configured) -> None:
    passed = (
        metrics.macro_f1 >= configured.min_macro_f1
        and metrics.balanced_accuracy >= configured.min_balanced_accuracy
        and metrics.calibrated_probability_quality.log_loss <= configured.max_log_loss
    )
    _record(
        checks,
        reasons,
        f"{prefix}Cv",
        passed,
        f"{prefix.upper()}_CATEGORICAL_CV_THRESHOLD_NOT_MET",
    )
