from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Union

from pydantic import TypeAdapter

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from jsd_aird_ai.contracts_v2 import (  # noqa: E402
    ErrorResponseV2, RecommendRequestV2, RecommendResponseV2, ScoreRequestV2,
    ScoreResponseV2, TrainRequestV2, TrainResponseV2, ValidateRequestV2,
    ValidateResponseV2, ValidationFoldsRequestV2, ValidationFoldsResponseV2,
)

ContractV2 = Union[
    ValidateRequestV2, ValidateResponseV2,
    ValidationFoldsRequestV2, ValidationFoldsResponseV2,
    TrainRequestV2, TrainResponseV2,
    ScoreRequestV2, ScoreResponseV2,
    RecommendRequestV2, RecommendResponseV2, ErrorResponseV2,
]


def main() -> None:
    generated = TypeAdapter(ContractV2).json_schema()
    schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://jsd.internal/contracts/formula-model.v2/schema.json",
        "title": "JSD-AIRD Formula Model Contract v2",
        **generated,
    }
    destination = PROJECT_ROOT / "contracts" / "formula-model.v2" / "formula-model.v2.schema.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
