from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = PROJECT_ROOT.parent
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.contracts import TaskProfile  # noqa: E402
from jsd_aird_ai.digests import canonical_sha256, sha256_bytes  # noqa: E402
from jsd_aird_ai.modeling import ModelTrainer  # noqa: E402
from jsd_aird_ai.synthetic_classification import (  # noqa: E402
    BINARY_TARGET,
    CATEGORICAL_TARGET,
    build_synthetic_classification_fixture,
)


DEFAULT_SNAPSHOT = (
    REPOSITORY_ROOT
    / "docs"
    / "AI实验优化、配方预测"
    / "UVPU_APPLICATION_FORMULATION_Synthetic_Shared_Process_V3_Package"
    / "UVPU_APPLICATION_FORMULATION_Synthetic_TrainingSnapshot_V2_Shared_Process_PyArrow"
)
DEFAULT_PROFILE = (
    PROJECT_ROOT
    / "src"
    / "jsd_aird_ai"
    / "task_profiles"
    / "uvpu_application_formulation.v1.json"
)
DEFAULT_OUTPUT = PROJECT_ROOT / ".runtime" / "t07-a5-classification"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Train the isolated T07-A.5 synthetic classification fixture"
    )
    parser.add_argument("--snapshot", type=Path, default=DEFAULT_SNAPSHOT)
    parser.add_argument("--profile", type=Path, default=DEFAULT_PROFILE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--rows", type=int, default=120)
    parser.add_argument("--seed", type=int, default=20260903)
    parser.add_argument("--threads", type=int, default=2)
    args = parser.parse_args()

    base_profile = TaskProfile.model_validate_json(args.profile.read_bytes())
    snapshot, profile = build_synthetic_classification_fixture(
        args.snapshot,
        base_profile,
        rows=args.rows,
        seed=args.seed,
    )
    profile_hash = canonical_sha256(profile)
    bundle_bytes, results, warnings = ModelTrainer(thread_count=args.threads).train(
        snapshot,
        profile,
        profile_hash,
        snapshot.response.snapshot_hash,
        args.seed,
    )
    args.output.mkdir(parents=True, exist_ok=True)
    bundle_path = args.output / "classification-model-bundle.zip"
    report_path = args.output / "classification-training-report.json"
    bundle_path.write_bytes(bundle_bytes)
    class_counts = {
        BINARY_TARGET: snapshot.measurements[BINARY_TARGET].value_counts().to_dict(),
        CATEGORICAL_TARGET: snapshot.measurements[CATEGORICAL_TARGET]
        .value_counts()
        .to_dict(),
    }
    report = {
        "status": "SYNTHETIC_DEVELOPMENT_ONLY",
        "dataNature": snapshot.response.data_nature,
        "snapshotPurpose": snapshot.response.snapshot_purpose,
        "productionEligible": snapshot.response.production_eligible,
        "snapshotId": snapshot.response.snapshot_id,
        "snapshotHash": snapshot.response.snapshot_hash,
        "taskProfileHash": profile_hash,
        "seed": args.seed,
        "rows": len(snapshot.measurements),
        "classCounts": class_counts,
        "modelBundlePath": str(bundle_path.resolve()),
        "modelBundleSha256": sha256_bytes(bundle_bytes),
        "modelBundleSize": len(bundle_bytes),
        "targets": [item.model_dump(mode="json", by_alias=True) for item in results],
        "warnings": warnings,
    }
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "report": str(report_path.resolve()),
        "bundle": str(bundle_path.resolve()),
        "bundleSha256": report["modelBundleSha256"],
        "champions": {
            item.target_code: item.scorer_type for item in results
        },
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
