from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.devtools import (  # noqa: E402
    DEFAULT_MODEL_INDEX,
    DEFAULT_SCORE_INPUT,
    read_score_input,
    score_indexed_model_over_http,
    write_json,
)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Call the running FastAPI service with an indexed UV/PU model."
    )
    parser.add_argument("--input", type=Path, default=DEFAULT_SCORE_INPUT)
    parser.add_argument("--model-index", type=Path, default=DEFAULT_MODEL_INDEX)
    parser.add_argument("--base-url", default="http://127.0.0.1:8090")
    parser.add_argument("--token", default=os.getenv("JSD_AIRD_AI_INTERNAL_TOKEN"))
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    response = score_indexed_model_over_http(
        read_score_input(args.input),
        index_path=args.model_index,
        base_url=args.base_url,
        token=args.token,
        timeout_seconds=args.timeout,
    )
    output = response.model_dump(mode="json", by_alias=True)
    if args.output:
        write_json(args.output, output)
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
