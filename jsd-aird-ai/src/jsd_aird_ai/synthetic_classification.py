from __future__ import annotations

import hashlib
from pathlib import Path

import numpy as np
import pandas as pd

from jsd_aird_ai.contracts import (
    Direction,
    SnapshotValidationResponse,
    SourceSheetStats,
    TargetCoverage,
    TargetSpec,
    TaskProfile,
    ValueType,
)
from jsd_aird_ai.snapshot import LoadedSnapshot


BINARY_TARGET = "Y__SYNTHETIC_PASS_FAIL"
CATEGORICAL_TARGET = "Y__SYNTHETIC_DEFECT_MODE"


def build_synthetic_classification_fixture(
    snapshot_root: Path,
    base_profile: TaskProfile,
    *,
    rows: int = 240,
    seed: int = 20260903,
) -> tuple[LoadedSnapshot, TaskProfile]:
    """Derive an in-memory A.5 fixture without changing the immutable 400-row snapshot."""
    measurements = pd.read_parquet(snapshot_root / "measurements.parquet").iloc[:rows].copy()
    source_map = pd.read_parquet(snapshot_root / "source-map.parquet").iloc[:rows].copy()
    if len(measurements) < 80:
        raise ValueError("the A.5 classification fixture requires at least 80 source rows")

    rng = np.random.default_rng(seed)
    main_columns = [
        item.column
        for item in base_profile.formula.materials
        if item.role == "MAIN_RESIN"
    ]
    main_values = measurements[main_columns].to_numpy(dtype=float)
    resin_index = np.argmax(main_values, axis=1)
    main_pct = main_values[np.arange(len(measurements)), resin_index]
    dsp = measurements["X_FORMULA__DSP_3315_PCT"].to_numpy(dtype=float)
    ss059 = measurements["X_FORMULA__SS059_PC_1_1_PCT"].to_numpy(dtype=float)
    energy = measurements["X_PROCESS__UV_ENERGY_MJ_CM2"].to_numpy(dtype=float)
    humidity = measurements["X_ENV__HUMIDITY_RH_PCT"].to_numpy(dtype=float)
    film = measurements["X_PROCESS__FILM_THICKNESS_UM"].to_numpy(dtype=float)

    binary_score = (
        0.055 * (main_pct - np.median(main_pct))
        + 0.90 * (dsp - np.median(dsp))
        - 0.65 * (ss059 - np.median(ss059))
        + 0.0035 * (energy - np.median(energy))
        - 0.035 * (humidity - np.median(humidity))
        + 0.10 * ((resin_index % 3) - 1)
        + rng.normal(0.0, 0.42, len(measurements))
    )
    cutoff = float(np.quantile(binary_score, 0.47))
    measurements[BINARY_TARGET] = np.where(binary_score >= cutoff, "PASS", "FAIL")

    # Four nominal defect outcomes. The independent noise deliberately prevents this
    # fixture from becoming a trivial deterministic lookup problem.
    categorical_logits = np.column_stack(
        [
            0.30 - 0.30 * np.abs(dsp - 1.55) - 0.002 * np.abs(energy - 800),
            0.22 * (humidity - 58) + 0.07 * (film - 18) + 0.18 * (resin_index == 0),
            0.80 * (ss059 - 1.25) - 0.003 * (energy - 800) + 0.20 * (resin_index == 3),
            -0.65 * (dsp - 1.55) + 0.035 * (main_pct - 60) + 0.20 * (resin_index >= 4),
        ]
    )
    categorical_logits += rng.normal(0.0, 0.55, categorical_logits.shape)
    category_labels = np.asarray(["NO_DEFECT", "CRACK", "BLISTER", "PEEL"])
    measurements[CATEGORICAL_TARGET] = category_labels[
        np.argmax(categorical_logits, axis=1)
    ]

    targets = [
        TargetSpec(
            code=BINARY_TARGET,
            target_key="SYNTHETIC.APPLICATION.PASS_FAIL",
            value_type=ValueType.BINARY,
            direction=Direction.MAXIMIZE,
            test_method="SYNTHETIC_PASS_FAIL_FIXTURE",
            substrate="PET_100UM_OPTICAL",
            mandatory=True,
            weight=3.0,
            class_labels=["FAIL", "PASS"],
            positive_class="PASS",
            decision_threshold=0.50,
        ),
        TargetSpec(
            code=CATEGORICAL_TARGET,
            target_key="SYNTHETIC.APPLICATION.DEFECT_MODE",
            value_type=ValueType.CATEGORICAL,
            direction=Direction.MINIMIZE,
            test_method="SYNTHETIC_DEFECT_MODE_FIXTURE",
            substrate="PET_100UM_OPTICAL",
            mandatory=False,
            weight=1.0,
            class_labels=category_labels.tolist(),
        ),
    ]
    profile = base_profile.model_copy(
        update={
            "code": "UVPU_APPLICATION_FORMULATION_CLASSIFICATION_FIXTURE",
            "version": "1.0-A5-SYNTHETIC",
            "rule_version": "uvpu-application-a5-synthetic-fixture",
            "targets": targets,
        }
    )

    joined = measurements[["experiment_version_id"]].merge(
        source_map,
        on="experiment_version_id",
        how="left",
        validate="one_to_one",
    )
    group_column = profile.validation.group_column
    sheet_column = profile.validation.source_group_column
    lineages = int(joined[group_column].nunique())
    sheets = int(joined[sheet_column].nunique())
    lineage_counts = joined.groupby(sheet_column)[group_column].nunique()
    target_coverages = []
    for target in targets:
        target_coverages.append(
            TargetCoverage(
                target_code=target.code,
                value_type=target.value_type,
                valid_rows=len(measurements),
                missing_rows=0,
                groups=lineages,
                source_groups=sheets,
                status="SUPPORTED",
            )
        )
    digest = hashlib.sha256(
        pd.util.hash_pandas_object(
            measurements[["experiment_version_id", BINARY_TARGET, CATEGORICAL_TARGET]],
            index=False,
        ).values.tobytes()
    ).hexdigest()
    response = SnapshotValidationResponse(
        request_id="t07-a5-synthetic-fixture",
        snapshot_id="SYNTH-UVPU-CLASSIFICATION-A5",
        snapshot_hash=digest,
        valid=True,
        production_eligible=False,
        rows=len(measurements),
        groups=lineages,
        source_sheets=sheets,
        source_sheet_stats=SourceSheetStats(
            total_sheets=sheets,
            sheets_with_multiple_lineages=int((lineage_counts > 1).sum()),
            max_lineages_per_sheet=int(lineage_counts.max()),
        ),
        data_nature="SYNTHETIC",
        snapshot_purpose="DEVELOPMENT",
        targets=target_coverages,
        warnings=[
            "A.5 classification fixture is synthetic and development-only",
            "the immutable 400-row continuous/ordinal snapshot was not modified",
        ],
    )
    manifest = {
        "snapshot_id": response.snapshot_id,
        "data_nature": "SYNTHETIC",
        "snapshot_purpose": "DEVELOPMENT",
        "production_eligible": False,
        "generator": {
            "name": "T07_A5_IN_MEMORY_CLASSIFICATION_FIXTURE",
            "source": "immutable UV/PU 400-row snapshot X values",
            "seed": seed,
            "row_count": len(measurements),
        },
    }
    return LoadedSnapshot(manifest, measurements, source_map, joined, response), profile
