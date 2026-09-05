from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.devtools import (
    DEFAULT_PROFILE_PATH,
    DEFAULT_SNAPSHOT_DIR,
    load_indexed_bundle,
    train_uvpu_development_model,
)
from jsd_aird_ai.reproducibility import algorithm_equivalence_hash


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Replay the immutable UV/PU snapshot across configured seeds"
    )
    parser.add_argument("--profile", default=str(DEFAULT_PROFILE_PATH))
    parser.add_argument("--snapshot", default=str(DEFAULT_SNAPSHOT_DIR))
    parser.add_argument("--output", default=".runtime/formula-model-replays")
    parser.add_argument("--seeds", default="20260903")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    records = []
    for seed in [int(value) for value in args.seeds.split(",")]:
        output_dir = Path(args.output) / str(seed)
        index, reused = train_uvpu_development_model(
            profile_path=Path(args.profile),
            snapshot_dir=Path(args.snapshot),
            output_dir=output_dir,
            seed=seed,
            force=args.force,
        )
        _, _, bundle = load_indexed_bundle(output_dir / "active-dev-model.json")
        records.append(
            {
                "seed": seed,
                "reused": reused,
                "modelBundleHash": index.model_bundle_hash,
                "algorithmEquivalenceSha256": algorithm_equivalence_hash(bundle.card),
                "champions": {
                    item["targetCode"]: item.get("scorerType")
                    for item in bundle.card["targets"]
                },
            }
        )
    print(json.dumps({"runs": records}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
