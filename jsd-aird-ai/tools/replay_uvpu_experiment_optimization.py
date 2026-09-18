from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.contracts import TaskProfile  # noqa: E402
from jsd_aird_ai.devtools import DEFAULT_PROFILE_PATH, DEFAULT_SNAPSHOT_DIR  # noqa: E402
from jsd_aird_ai.optimization_replay import run_synthetic_optimization_replay  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Replay 5-10 synthetic experiment-optimization rounds without mutating the snapshot"
    )
    parser.add_argument("--profile", type=Path, default=DEFAULT_PROFILE_PATH)
    parser.add_argument("--snapshot", type=Path, default=DEFAULT_SNAPSHOT_DIR)
    parser.add_argument("--rounds", type=int, default=5, choices=range(5, 11))
    parser.add_argument("--initial-rows", type=int, default=100)
    parser.add_argument("--experiments-per-round", type=int, default=4)
    parser.add_argument("--pool-size", type=int, default=512)
    parser.add_argument("--seed", type=int, default=20260904)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    profile = TaskProfile.model_validate_json(args.profile.read_bytes())
    result = run_synthetic_optimization_replay(
        measurements_path=args.snapshot / "measurements.parquet",
        profile=profile,
        rounds=args.rounds,
        initial_rows=args.initial_rows,
        experiments_per_round=args.experiments_per_round,
        pool_size=args.pool_size,
        seed=args.seed,
        threads=args.threads,
    )
    serialized = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding="utf-8")
    print(serialized, end="")


if __name__ == "__main__":
    main()
