from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.artifacts import ArtifactClient  # noqa: E402
from jsd_aird_ai.contracts import TaskProfile, ValidateSnapshotRequest, ValueType  # noqa: E402
from jsd_aird_ai.devtools import (  # noqa: E402
    DEFAULT_MODEL_INDEX,
    DEFAULT_PROFILE_PATH,
    DEFAULT_SNAPSHOT_DIR,
    _snapshot_artifacts,
    load_indexed_bundle,
)
from jsd_aird_ai.digests import canonical_sha256, snapshot_sha256  # noqa: E402
from jsd_aird_ai.features import FeatureBuilder  # noqa: E402
from jsd_aird_ai.model_adapters import default_model_adapters  # noqa: E402
from jsd_aird_ai.modeling import model_eligibility_status  # noqa: E402
from jsd_aird_ai.settings import Settings  # noqa: E402
from jsd_aird_ai.snapshot import SnapshotValidator  # noqa: E402
from jsd_aird_ai.stability import (  # noqa: E402
    choose_point_champion,
    point_cv_diagnostic,
    prediction_sensitivity,
)
from jsd_aird_ai.validation import group_folds  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run a point-CV alternate-seed and perturbation stability diagnostic"
    )
    parser.add_argument("--profile", type=Path, default=DEFAULT_PROFILE_PATH)
    parser.add_argument("--snapshot", type=Path, default=DEFAULT_SNAPSHOT_DIR)
    parser.add_argument("--index", type=Path, default=DEFAULT_MODEL_INDEX)
    parser.add_argument("--seed", type=int, default=20260904)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--repeats", type=int, default=100)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    profile = TaskProfile.model_validate_json(args.profile.read_bytes())
    artifacts = _snapshot_artifacts(args.snapshot.resolve(strict=True))
    snapshot_hash = snapshot_sha256(
        artifacts.manifest.sha256,
        artifacts.measurements.sha256,
        artifacts.source_map.sha256,
    )
    request = ValidateSnapshotRequest(
        request_id="uvpu-stability-diagnostic",
        task_profile_hash=canonical_sha256(profile),
        snapshot_hash=snapshot_hash,
        seed=args.seed,
        task_profile=profile,
        snapshot=artifacts,
    )
    loaded = SnapshotValidator(
        ArtifactClient(Settings(allow_file_urls=True))
    ).load_and_validate(request)
    index, _, bundle = load_indexed_bundle(args.index)
    base_champions = {
        item["targetCode"]: item.get("scorerType") for item in bundle.card["targets"]
    }

    builder = FeatureBuilder(profile)
    all_features = builder.from_snapshot(loaded.measurements)
    valid_features = builder.valid_feature_mask(all_features)
    lineage = loaded.joined[profile.validation.group_column].to_numpy()
    sheets = loaded.joined[profile.validation.source_group_column].to_numpy()
    targets = []
    for spec in profile.targets:
        if spec.value_type != ValueType.CONTINUOUS:
            continue
        values = pd.to_numeric(loaded.measurements[spec.code], errors="coerce")
        mask = valid_features & values.notna() & np.isfinite(values)
        features = all_features.loc[mask].reset_index(drop=True)
        target = values.loc[mask].to_numpy(dtype=float)
        target_lineage = lineage[mask.to_numpy()]
        target_sheets = sheets[mask.to_numpy()]
        lineage_folds = group_folds(target_lineage, profile.validation.folds)
        sheet_folds = group_folds(target_sheets, profile.validation.folds)
        evaluations = {}
        scores = {}
        for adapter_index, adapter in enumerate(default_model_adapters(args.threads)):
            if model_eligibility_status(profile, adapter.model_type, len(target)) != "ELIGIBLE":
                continue
            model_seed = args.seed + adapter_index * 100_000
            lineage_result = point_cv_diagnostic(
                adapter,
                features,
                target,
                target_lineage,
                lineage_folds,
                builder.layout,
                model_seed,
                profile.validation.interval_level,
            )
            sheet_result = point_cv_diagnostic(
                adapter,
                features,
                target,
                target_sheets,
                sheet_folds,
                builder.layout,
                model_seed,
                profile.validation.interval_level,
            )
            evaluations[adapter.model_type] = (lineage_result, sheet_result)
            scores[adapter.model_type] = (lineage_result.nmae, sheet_result.nmae)
        champion = choose_point_champion(scores, profile.model_selection)
        champion_lineage, champion_sheet = evaluations[champion]
        targets.append(
            {
                "targetCode": spec.code,
                "baseSeed": index.seed,
                "baseChampion": base_champions[spec.code],
                "alternateSeed": args.seed,
                "alternateChampion": champion,
                "championStable": base_champions[spec.code] == champion,
                "candidateNmae": {
                    model: {"lineage": pair[0], "sheet": pair[1]}
                    for model, pair in scores.items()
                },
                "lineageSensitivity": prediction_sensitivity(
                    target,
                    champion_lineage.predictions,
                    target_lineage,
                    args.seed,
                    args.repeats,
                ),
                "sheetSensitivity": prediction_sensitivity(
                    target,
                    champion_sheet.predictions,
                    target_sheets,
                    args.seed + 1,
                    args.repeats,
                ),
                "diagnosticSeconds": sum(
                    item.elapsed_seconds
                    for pair in evaluations.values()
                    for item in pair
                ),
            }
        )
    output = {
        "method": "ALTERNATE_SEED_POINT_CV_AND_OOF_METRIC_PERTURBATION",
        "snapshotHash": snapshot_hash,
        "taskProfileHash": canonical_sha256(profile),
        "targets": targets,
        "allChampionsStable": all(item["championStable"] for item in targets),
        "limitations": [
            "diagnostic reuses outer point CV but does not rerun nested conformal calibration",
            "perturbations quantify OOF metric sensitivity and do not mutate the source snapshot",
        ],
    }
    serialized = json.dumps(output, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding="utf-8")
    print(serialized, end="")


if __name__ == "__main__":
    main()
