from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Union

from pydantic import TypeAdapter


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.contracts import (  # noqa: E402
    ErrorResponse,
    RecommendRequest,
    RecommendResponse,
    ScoreRequest,
    ScoreResponse,
    SnapshotValidationResponse,
    TrainRequest,
    TrainResponse,
    ValidateSnapshotRequest,
)


Contract = Union[
    ValidateSnapshotRequest,
    TrainRequest,
    ScoreRequest,
    RecommendRequest,
    SnapshotValidationResponse,
    TrainResponse,
    ScoreResponse,
    RecommendResponse,
    ErrorResponse,
]


def main() -> None:
    generated = TypeAdapter(Contract).json_schema()
    schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://jsd.internal/contracts/formula-model.v1/schema.json",
        "title": "JSD-AIRD Formula Model Contract v1",
        **generated,
    }
    destination = (
        PROJECT_ROOT
        / "contracts"
        / "formula-model.v1"
        / "formula-model.v1.schema.json"
    )
    destination.write_text(
        json.dumps(schema, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
