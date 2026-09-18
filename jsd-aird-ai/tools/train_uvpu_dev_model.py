from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.devtools import (  # noqa: E402
    DEFAULT_OUTPUT_DIR,
    DEFAULT_PROFILE_PATH,
    DEFAULT_SNAPSHOT_DIR,
    train_uvpu_development_model,
)


def main() -> None:
    logging.basicConfig(
        level="INFO",
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    parser = argparse.ArgumentParser(
        description="Build the content-addressed UV/PU synthetic development model."
    )
    parser.add_argument("--profile", type=Path, default=DEFAULT_PROFILE_PATH)
    parser.add_argument("--snapshot-dir", type=Path, default=DEFAULT_SNAPSHOT_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--seed", type=int, default=20260903)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    index, reused = train_uvpu_development_model(
        profile_path=args.profile,
        snapshot_dir=args.snapshot_dir,
        output_dir=args.output_dir,
        seed=args.seed,
        force=args.force,
    )
    output = index.model_dump(mode="json", by_alias=True)
    output["reused"] = reused
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
