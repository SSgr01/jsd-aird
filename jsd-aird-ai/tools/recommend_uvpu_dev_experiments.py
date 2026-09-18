from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pandas as pd


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.contracts import RecommendRequest, ValueType  # noqa: E402
from jsd_aird_ai.devtools import (  # noqa: E402
    DEFAULT_MODEL_INDEX,
    DEFAULT_SNAPSHOT_DIR,
    load_indexed_bundle,
)
from jsd_aird_ai.service import FormulaModelService  # noqa: E402
from jsd_aird_ai.settings import Settings  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Recommend a four-role synthetic UV/PU experiment batch"
    )
    parser.add_argument("--index", type=Path, default=DEFAULT_MODEL_INDEX)
    parser.add_argument("--snapshot", type=Path, default=DEFAULT_SNAPSHOT_DIR)
    parser.add_argument("--seed", type=int, default=20260903)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    index, model_path, bundle = load_indexed_bundle(args.index)
    measurements = pd.read_parquet(args.snapshot / "measurements.parquet")
    row = measurements.iloc[0]
    formula = {
        item.code: float(row[item.column]) for item in bundle.profile.formula.materials
    }
    context = {
        item.code: row[item.column] for item in bundle.profile.context_features
    }
    targets = [
        {
            "code": item.code,
            "mode": "MINIMIZE" if item.direction == "MINIMIZE" else "MAXIMIZE",
            "mandatory": False,
            "weight": item.weight,
        }
        for item in bundle.profile.targets
        if item.value_type == ValueType.CONTINUOUS
    ]
    request = RecommendRequest(
        request_id="uvpu-a4-development-recommendation",
        task_profile_hash=index.task_profile_hash,
        snapshot_hash=index.snapshot_hash,
        model_bundle_hash=index.model_bundle_hash,
        seed=args.seed,
        task_profile=bundle.profile,
        model_bundle={"url": model_path.as_uri(), "sha256": index.model_bundle_hash},
        baseline_formula=formula,
        context=context,
        targets=targets,
        count=4,
        recommendation_mode="EXPERIMENT_OPTIMIZATION",
    )
    response = FormulaModelService(Settings(allow_file_urls=True)).recommend(request)
    serialized = response.model_dump_json(by_alias=True, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding="utf-8")
    print(serialized, end="")


if __name__ == "__main__":
    main()
