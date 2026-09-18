from __future__ import annotations

import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.devtools import (  # noqa: E402
    DEFAULT_MODEL_INDEX,
    DEFAULT_SCORE_INPUT,
    build_indexed_score_request,
    read_score_input,
    score_indexed_model,
    write_json,
)


def main() -> None:
    score_input = read_score_input(DEFAULT_SCORE_INPUT)
    request = build_indexed_score_request(score_input, DEFAULT_MODEL_INDEX)
    response = score_indexed_model(score_input, DEFAULT_MODEL_INDEX)
    examples = PROJECT_ROOT / "contracts" / "formula-model.v1" / "examples"
    write_json(
        examples / "score-request.uvpu-synthetic.golden.json",
        request.model_dump(mode="json", by_alias=True),
    )
    write_json(
        examples / "score-response.uvpu-synthetic.golden.json",
        response.model_dump(mode="json", by_alias=True),
    )
    print(f"request={request.request_id}")
    print(f"bundle={response.model_bundle_sha256}")
    print(f"rows={len(response.rows)}")


if __name__ == "__main__":
    main()
