from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import TypeAdapter, ValidationError

from jsd_aird_ai.contracts_v2 import (
    RecommendRequestV2,
    ScoreRequestV2,
    ScoreResponseV2,
)


ROOT = Path(__file__).resolve().parents[1]
GOLDEN = json.loads(
    (ROOT / "contracts/formula-model.v2/examples/formula-model.v2.golden.json").read_text(encoding="utf-8")
)


def binding(index: int) -> dict:
    compact = GOLDEN["modelBindings"][index]
    return {
        "target": GOLDEN["targets"][compact["targetIndex"]],
        "modelVersionId": compact["modelVersionId"],
        "modelBundle": compact["modelBundle"],
        "inputScheme": GOLDEN["inputScheme"],
        "materialDictionary": GOLDEN["materialDictionary"],
        "preprocessing": GOLDEN["preprocessing"],
        "applicabilityDomain": {"method":"ROBUST_FEATURE_RANGE", "features":[], "minimum":[],
                                  "maximum":[], "nearBoundaryRatio":0.1},
    }


def score_request() -> dict:
    return {
        "contractVersion": GOLDEN["contractVersion"], "requestId": GOLDEN["requestId"],
        "seed": GOLDEN["seed"], "runId": "score-run-v2", "formula": GOLDEN["formula"],
        "inputs": {"substrate": "PET"}, "modelBindings": [binding(i) for i in range(4)],
    }


def test_v2_golden_preserves_988_and_all_result_types() -> None:
    request = ScoreRequestV2.model_validate(score_request())
    assert request.formula.recorded_total == 98.8
    assert sum(item.ratio or 0 for item in request.formula.components) == pytest.approx(98.8)
    assert [item.model_version_id for item in request.model_bindings] == [
        "model-gloss-v1", "model-hardness-v3", "model-uv-dry-v2", "model-appearance-v4"
    ]
    response = ScoreResponseV2.model_validate({
        "contractVersion": "formula-model.v2", "requestId": GOLDEN["requestId"],
        "runId": "score-run-v2", "executionStatus": "SUCCEEDED", "outcomeStatus": "SUCCEEDED",
        "results": GOLDEN["typedResults"],
    })
    assert [item.result.result_type for item in response.results] == [
        "CONTINUOUS", "ORDINAL", "BINARY", "CATEGORICAL"
    ]


def test_recommend_binds_multiple_y_models() -> None:
    request = RecommendRequestV2.model_validate({
        "contractVersion": "formula-model.v2", "requestId": GOLDEN["requestId"], "seed": GOLDEN["seed"],
        "runId": "recommend-run-v2", "mode": "FORMULA_PREDICTION", "baselineFormula": GOLDEN["formula"],
        "fixedInputs": {"substrate": "PET"},
        "goals": [{"targetId": target["id"], "operator": "MAXIMIZE", "mandatory": True, "weight": 1}
                  for target in GOLDEN["targets"]],
        "constraints": {"preserveRecordedTotal": True}, "modelBindings": [binding(i) for i in range(4)],
        "candidateCount": 4,
    })
    assert len(request.model_bindings) == 4


def test_v1_payload_is_rejected_by_v2_schema_source() -> None:
    payload = score_request()
    payload["contractVersion"] = "formula-model.v1"
    with pytest.raises(ValidationError):
        ScoreRequestV2.model_validate(payload)


def test_blocked_outcome_cannot_contain_prediction() -> None:
    payload = {
        "contractVersion": "formula-model.v2", "requestId": "blocked", "runId": "run",
        "executionStatus": "SUCCEEDED", "outcomeStatus": "BLOCKED",
        "results": [{"status":"BLOCKED", "targetId":"target-gloss",
        "code":"NO_ACTIVE_MODEL", "message":"no model", "result":{"resultType":"CONTINUOUS","value":1}}],
    }
    with pytest.raises(ValidationError):
        ScoreResponseV2.model_validate(payload)


def test_generated_schema_has_v2_discriminator() -> None:
    schema = TypeAdapter(ScoreRequestV2).json_schema()
    assert schema["properties"]["contractVersion"]["const"] == "formula-model.v2"
