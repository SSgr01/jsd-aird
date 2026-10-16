from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
import pytest
from fastapi.testclient import TestClient

from jsd_aird_ai.api import create_app
from jsd_aird_ai.service_v2 import FormulaModelV2Service
from jsd_aird_ai.settings import Settings


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parquet(rows: list[dict]) -> bytes:
    target = io.BytesIO()
    pq.write_table(pa.Table.from_pylist(rows), target)
    return target.getvalue()


def artifact(path: Path, data: bytes) -> dict:
    path.write_bytes(data)
    return {"url": path.as_uri(), "sha256": sha(data)}


def payload(tmp_path: Path, value_type: str = "CONTINUOUS") -> dict:
    rows = []
    sources = []
    labels = ["LOW", "MEDIUM", "HIGH"] if value_type in {"ORDINAL", "CATEGORICAL"} else ["FAIL", "PASS"]
    for index in range(24):
        total = 98.8 if index == 0 else 100.0
        rows.append({
            "row_id": f"row-{index}",
            "formula_json": json.dumps({"basis": "MASS_PERCENT", "recordedTotal": total,
                                          "components": [{"materialId": "m1", "ratio": 30 + index, "unit": "%", "amountKnown": True}]}),
            "inputs_json": json.dumps({"temperature": 20 + index % 4, "substrate": "PET" if index % 2 else "PC"}),
            "target_numeric": float(index * 1.5) if value_type == "CONTINUOUS" else None,
            "target_label": labels[index % 2] if value_type != "CONTINUOUS" else None,
        })
        sources.append({"row_id": f"row-{index}", "formula_lineage": f"formula-{index // 2}",
                        "source_context": f"source-{index // 2}", "sample_id": f"sample-{index}"})
    rows_ref = artifact(tmp_path / "rows.parquet", parquet(rows))
    source_ref = artifact(tmp_path / "source.parquet", parquet(sources))
    snapshot_hash = "1" * 64
    manifest_ref = artifact(tmp_path / "manifest.json", json.dumps({"snapshotHash": snapshot_hash}).encode())
    classes = [] if value_type == "CONTINUOUS" else labels
    return {
        "contractVersion": "formula-model.v2", "requestId": "runtime-v2", "seed": 17,
        "snapshot": {"id": "snapshot-1", "version": 1, "sha256": snapshot_hash,
                     "manifest": manifest_ref, "rows": rows_ref, "sourceMap": source_ref},
        "context": {
            "targetDefinition": {"id": "target-v1", "version": 1, "sha256": "2" * 64,
                                 "code": "GLOSS" if value_type == "CONTINUOUS" else "UV_DRY",
                                 "valueType": value_type, "unit": "GU" if value_type == "CONTINUOUS" else None,
                                 "classes": classes, "positiveClass": "PASS" if value_type == "BINARY" else None,
                                 "observationSemantics": {}},
            "inputScheme": {"id": "scheme-1", "version": 1, "sha256": "3" * 64,
                            "targetVersionId": "target-v1", "fields": [
                                {"fieldVersionId": "formula-v1", "code": "FORMULA_COMPOSITION",
                                 "valueType": "COMPOSITION", "required": True,
                                 "acquisitionTiming": "PRE_EXPERIMENT", "encoding": {}},
                                {"fieldVersionId": "field-v1", "code": "temperature", "valueType": "NUMBER",
                                 "required": True, "acquisitionTiming": "PRE_EXPERIMENT", "encoding": {}}]},
            "materialDictionary": {"id": "dict-1", "version": 1, "sha256": "4" * 64,
                                   "materials": [{"materialId": "m1", "code": "M1", "role": "RESIN", "encoderIndex": 0}]},
            "preprocessing": {"id": "prep-1", "version": 1, "sha256": "5" * 64, "config": {}},
            "trainingPolicy": {"id": "policy-1", "version": 1, "sha256": "6" * 64,
                               "config": {"candidateAlgorithms": ["ORDINAL_CUMULATIVE_LOGIT"] if value_type == "ORDINAL" else ["RANDOM_FOREST", "LOGISTIC_REGRESSION"],
                                          "replicateHandling": "KEEP_GROUPED"}},
        },
    }


def client(tmp_path: Path) -> TestClient:
    return TestClient(create_app(Settings(allow_file_urls=True, temporary_root=tmp_path)))


def test_v2_rejects_training_materials_missing_from_the_frozen_dictionary(tmp_path: Path) -> None:
    request = payload(tmp_path)
    request["context"]["materialDictionary"]["materials"][0]["materialId"] = "different-material"
    checked = client(tmp_path).post("/internal/v2/snapshots/validate", json=request)
    assert checked.status_code == 200
    assert checked.json()["status"] == "REJECTED"
    assert any(issue["code"] == "MATERIAL_NOT_IN_MODEL" for issue in checked.json()["issues"])


def test_v2_validate_folds_and_continuous_train_preserve_recorded_total(tmp_path: Path) -> None:
    api = client(tmp_path)
    request = payload(tmp_path)
    service = FormulaModelV2Service(Settings(allow_file_urls=True, temporary_root=tmp_path))
    assert service._features(pd.Series({"formula_json": json.dumps({
        "recordedTotal": 98.8, "components": [{"materialId": "m1", "ratio": 98.8}]}),
        "inputs_json": json.dumps({"temperature": 25})}))["formula:recordedTotal"] == 98.8
    checked = api.post("/internal/v2/snapshots/validate", json=request)
    assert checked.status_code == 200
    assert checked.json()["status"] == "VALID"
    folds_request = request | {"foldCount": 4, "groupFields": ["formula_lineage", "source_context"]}
    folded = api.post("/internal/v2/snapshots/validation-folds", json=folds_request)
    assert folded.status_code == 200
    folds_bytes = json.dumps({"assignments": folded.json()["assignments"]}).encode()
    folds_ref = artifact(tmp_path / "folds.json", folds_bytes)
    output = tmp_path / "model.zip"
    trained = api.post("/internal/v2/models/train", json=request | {
        "jobId": "job-1", "validationFolds": folds_ref, "output": output.as_uri()
    })
    assert trained.status_code == 200, trained.text
    assert trained.json()["status"] == "CANDIDATE"
    assert trained.json()["modelBundle"]["sha256"] == sha(output.read_bytes())
    assert trained.json()["metrics"]["baselines"]["simple"]["status"] == "AVAILABLE"
    assert len(trained.json()["metrics"]["comparisonSetHash"]) == 64

    model = trained.json()
    score_binding = {
        "target": request["context"]["targetDefinition"],
        "modelVersionId": "model-continuous-v1",
        "modelBundle": model["modelBundle"],
        "inputScheme": request["context"]["inputScheme"],
        "materialDictionary": request["context"]["materialDictionary"],
        "preprocessing": request["context"]["preprocessing"],
        "applicabilityDomain": model["applicabilityDomain"] | {"nearBoundaryRatio": 0.1},
    }
    score = api.post("/internal/v2/models/score", json={
        "contractVersion": "formula-model.v2", "requestId": "score-runtime", "seed": 17,
        "runId": "prediction-1",
        "formula": {"basis": "MASS_PERCENT", "compositionComplete": True, "recordedTotal": 98.8,
                    "components": [{"materialId": "m1", "ratio": 31, "unit": "%", "amountKnown": True}]},
        "inputs": {"temperature": 21, "substrate": "PET"}, "modelBindings": [score_binding],
    })
    assert score.status_code == 200, score.text
    assert score.json()["executionStatus"] == "SUCCEEDED"
    assert score.json()["outcomeStatus"] == "SUCCEEDED"
    assert score.json()["results"][0]["result"]["resultType"] == "CONTINUOUS"

    out_of_domain = api.post("/internal/v2/models/score", json={
        "contractVersion": "formula-model.v2", "requestId": "score-ood", "seed": 17,
        "runId": "prediction-ood",
        "formula": {"basis": "MASS_PERCENT", "compositionComplete": True, "recordedTotal": 98.8,
                    "components": [{"materialId": "m1", "ratio": 999, "unit": "%", "amountKnown": True}]},
        "inputs": {"temperature": 21, "substrate": "PET"}, "modelBindings": [score_binding],
    })
    assert out_of_domain.json()["results"][0]["status"] == "BLOCKED"
    assert out_of_domain.json()["results"][0]["code"] == "OUT_OF_DOMAIN"
    violations = out_of_domain.json()["results"][0]["detail"]["violations"]
    assert violations
    assert all(item["value"] < item["minimum"] or item["value"] > item["maximum"] for item in violations)

    extrapolated = api.post("/internal/v2/models/score", json={
        "contractVersion": "formula-model.v2", "requestId": "score-extrapolation", "seed": 17,
        "runId": "prediction-extrapolation", "allowExtrapolation": True,
        "formula": {"basis": "MASS_PERCENT", "compositionComplete": True, "recordedTotal": 98.8,
                    "components": [{"materialId": "m1", "ratio": 31, "unit": "%", "amountKnown": True}]},
        "inputs": {"temperature": 500, "substrate": "PET"}, "modelBindings": [score_binding],
    })
    assert extrapolated.status_code == 200, extrapolated.text
    extrapolated_result = extrapolated.json()["results"][0]
    assert extrapolated_result["status"] == "SUCCEEDED"
    assert extrapolated_result["applicability"]["status"] == "OUT_OF_DOMAIN"
    assert extrapolated_result["warnings"][0]["code"] == "EXTRAPOLATION"

    recommendation = api.post("/internal/v2/models/recommend", json={
        "contractVersion": "formula-model.v2", "requestId": "formula-extrapolation", "seed": 17,
        "runId": "formula-run", "mode": "FORMULA_PREDICTION", "candidateCount": 1,
        "baselineFormula": {"basis": "MASS_PERCENT", "compositionComplete": True, "recordedTotal": 100,
                            "components": [{"materialId": "m1", "ratio": 100, "unit": "%", "amountKnown": True}]},
        "fixedInputs": {"temperature": 35},
        "goals": [{"targetId": "target-v1", "operator": "MAXIMIZE", "mandatory": False, "weight": 1}],
        "constraints": {}, "searchSpace": {"variableMaterials": [{"materialId": "m1", "minimum": 0, "maximum": 100}]},
        "modelBindings": [score_binding],
    })
    assert recommendation.status_code == 200, recommendation.text
    candidate = recommendation.json()["candidates"][0]
    assert candidate["inputs"]["temperature"] == 35
    assert candidate["results"][0]["status"] == "SUCCEEDED"
    assert candidate["results"][0]["applicability"]["status"] == "OUT_OF_DOMAIN"

    damaged = tmp_path / "damaged-model.zip"
    damaged.write_bytes(output.read_bytes() + b"damaged")
    damaged_binding = score_binding | {"modelBundle": {"url": damaged.as_uri(), "sha256": model["modelBundle"]["sha256"]}}
    failed = api.post("/internal/v2/models/score", json={
        "contractVersion": "formula-model.v2", "requestId": "score-failed", "seed": 17,
        "runId": "prediction-failed",
        "formula": {"basis": "MASS_PERCENT", "compositionComplete": True, "recordedTotal": 98.8,
                    "components": [{"materialId": "m1", "ratio": 31, "unit": "%", "amountKnown": True}]},
        "inputs": {"temperature": 21, "substrate": "PET"}, "modelBindings": [damaged_binding],
    })
    assert failed.json()["executionStatus"] == "FAILED"
    assert failed.json()["outcomeStatus"] is None
    assert failed.json()["results"][0]["status"] == "FAILED"


def test_modeling_workbench_trains_from_freely_selected_columns(tmp_path: Path) -> None:
    api = client(tmp_path)
    output = tmp_path / "workbench-model.zip"
    rows = [
        {"id": f"row-{index}", "values": {
            "temperature": 18 + index,
            "substrate": "PET" if index % 2 else "PC",
            "gloss": float(index * 1.7 + (index % 3)),
        }}
        for index in range(18)
    ]
    response = api.post("/internal/v2/modeling/workbench/train", json={
        "requestId": "workbench-run-1",
        "seed": 17,
        "xColumns": [
            {"code": "temperature", "name": "温度", "kind": "NUMERIC"},
            {"code": "substrate", "name": "基材", "kind": "CATEGORICAL"},
        ],
        "yColumn": {"code": "gloss", "name": "光泽", "kind": "NUMERIC", "unit": "GU"},
        "rows": rows,
        "output": output.as_uri(),
    })

    assert response.status_code == 200, response.text
    result = response.json()
    assert result["status"] == "SUCCEEDED"
    assert result["rowCount"] == len(rows)
    assert result["metrics"]["selectedAlgorithm"]
    assert result["metrics"]["mae"] >= 0
    assert result["metrics"]["comparison"]
    assert result["modelBundle"]["sha256"] == sha(output.read_bytes())


def test_modeling_workbench_rejects_y_with_one_observed_class() -> None:
    service = FormulaModelV2Service(Settings())
    with pytest.raises(Exception, match="Y 列只有一种结果"):
        service.train_workbench({
            "requestId": "workbench-one-class",
            "seed": 17,
            "xColumns": [{"code": "x", "name": "X", "kind": "NUMERIC"}],
            "yColumn": {"code": "y", "name": "Y", "kind": "CATEGORICAL"},
            "rows": [{"values": {"x": index, "y": "A"}} for index in range(8)],
            "output": "file:///tmp/unused.zip",
        })


def test_v2_binary_training_and_v1_payload_rejection(tmp_path: Path) -> None:
    api = client(tmp_path)
    request = payload(tmp_path, "BINARY")
    folded = api.post("/internal/v2/snapshots/validation-folds",
                      json=request | {"foldCount": 4, "groupFields": ["formula_lineage"]})
    assert folded.status_code == 200, folded.text
    folds_ref = artifact(tmp_path / "folds-binary.json",
                         json.dumps({"assignments": folded.json()["assignments"]}).encode())
    trained = api.post("/internal/v2/models/train", json=request | {
        "jobId": "job-binary", "validationFolds": folds_ref,
        "output": (tmp_path / "binary.zip").as_uri()
    })
    assert trained.status_code == 200, trained.text
    assert trained.json()["status"] == "CANDIDATE"
    invalid = request | {"contractVersion": "formula-model.v1"}
    rejected = api.post("/internal/v2/snapshots/validate", json=invalid)
    assert rejected.status_code == 422
    assert rejected.json()["contractVersion"] == "formula-model.v2"


def test_v2_ordinal_uses_cumulative_logit_and_multiclass_is_typed(tmp_path: Path) -> None:
    api = client(tmp_path)
    for value_type, algorithm in [("ORDINAL", "ORDINAL_CUMULATIVE_LOGIT"), ("CATEGORICAL", "RANDOM_FOREST")]:
        request = payload(tmp_path, value_type)
        request["context"]["trainingPolicy"]["config"]["candidateAlgorithms"] = [algorithm]
        folded = api.post("/internal/v2/snapshots/validation-folds",
                          json=request | {"foldCount": 4, "groupFields": ["formula_lineage", "source_context"]})
        assert folded.status_code == 200, folded.text
        folds_ref = artifact(tmp_path / f"folds-{value_type}.json",
                             json.dumps({"assignments": folded.json()["assignments"]}).encode())
        trained = api.post("/internal/v2/models/train", json=request | {
            "jobId": f"job-{value_type}", "validationFolds": folds_ref,
            "output": (tmp_path / f"{value_type}.zip").as_uri(),
        })
        assert trained.status_code == 200, trained.text
        assert trained.json()["status"] == "CANDIDATE"
        assert trained.json()["metrics"]["selectedAlgorithm"] == algorithm
        if value_type == "ORDINAL":
            assert "gradeMae" in trained.json()["metrics"]


def test_v2_model_without_composition_scores_without_formula(tmp_path: Path) -> None:
    api = client(tmp_path)
    request = payload(tmp_path)
    request["context"]["inputScheme"]["fields"] = [
        field for field in request["context"]["inputScheme"]["fields"]
        if field["valueType"] != "COMPOSITION"
    ]
    folded = api.post("/internal/v2/snapshots/validation-folds", json=request | {
        "foldCount": 4, "groupFields": ["formula_lineage", "source_context"]
    })
    assert folded.status_code == 200, folded.text
    folds_ref = artifact(tmp_path / "folds-no-formula.json",
                         json.dumps({"assignments": folded.json()["assignments"]}).encode())
    trained = api.post("/internal/v2/models/train", json=request | {
        "jobId": "job-no-formula", "validationFolds": folds_ref,
        "output": (tmp_path / "no-formula.zip").as_uri(),
    })
    assert trained.status_code == 200, trained.text
    assert trained.json()["status"] == "CANDIDATE"
    assert all(not name.startswith(("formula:", "material:"))
               for name in trained.json()["applicabilityDomain"]["features"])

    binding = {
        "target": request["context"]["targetDefinition"],
        "modelVersionId": "model-no-formula-v1",
        "modelBundle": trained.json()["modelBundle"],
        "inputScheme": request["context"]["inputScheme"],
        "materialDictionary": request["context"]["materialDictionary"],
        "preprocessing": request["context"]["preprocessing"],
        "applicabilityDomain": trained.json()["applicabilityDomain"] | {"nearBoundaryRatio": 0.1},
    }
    score = api.post("/internal/v2/models/score", json={
        "contractVersion": "formula-model.v2", "requestId": "score-no-formula", "seed": 17,
        "runId": "prediction-no-formula", "inputs": {"temperature": 21},
        "modelBindings": [binding],
    })
    assert score.status_code == 200, score.text
    assert score.json()["executionStatus"] == "SUCCEEDED"
    assert score.json()["results"][0]["status"] == "SUCCEEDED"


@pytest.mark.parametrize("kind", ["NUMERIC", "CATEGORICAL"])
def test_registered_workbench_scores_frozen_model_without_formula(tmp_path: Path, kind: str) -> None:
    api = client(tmp_path)
    output = tmp_path / "registered.zip"
    x_columns = [{"code": "formula:resin", "name": "树脂比例", "kind": "NUMERIC", "unit": "%"},
                 {"code": "substrate", "name": "基材", "kind": "CATEGORICAL"}]
    trained = api.post("/internal/v2/modeling/workbench/train", json={
        "requestId": "register-workbench", "xColumns": x_columns,
        "yColumn": {"code": "y", "name": "结果", "kind": kind},
        "rows": [{"values": {"formula:resin": 20 + i, "substrate": "PET" if i % 2 else "PC",
                             "y": float(i * 2) if kind == "NUMERIC" else ("优" if i % 2 else "良")}}
                 for i in range(12)], "output": output.as_uri()})
    assert trained.status_code == 200, trained.text
    context = payload(tmp_path)["context"]
    context["targetDefinition"].update(valueType="CONTINUOUS" if kind == "NUMERIC" else "CATEGORICAL",
                                       unit=None, classes=[] if kind == "NUMERIC" else ["优", "良"])
    context["inputScheme"]["fields"] = [
        {"fieldVersionId": str(i), "code": x["code"], "name": x["name"], "unit": x.get("unit"),
         "valueType": "NUMBER" if x["kind"] == "NUMERIC" else "CATEGORY", "required": True,
         "acquisitionTiming": "PRE_EXPERIMENT"} for i, x in enumerate(x_columns)]
    binding = {"target": context["targetDefinition"], "inputScheme": context["inputScheme"],
               "preprocessing": context["preprocessing"], "materialDictionary": context["materialDictionary"],
               "modelVersionId": "registered-model", "modelBundle": trained.json()["modelBundle"],
               "applicabilityDomain": trained.json()["applicabilityDomain"] | {"nearBoundaryRatio": 0.05}}
    request = {"requestId": "score-registered", "seed": 17, "runId": "prediction-1",
               "inputs": {"formula:resin": 25, "substrate": "PET"}, "modelBindings": [binding]}
    scored = api.post("/internal/v2/models/score", json=request)
    assert scored.status_code == 200, scored.text
    assert scored.json()["results"][0]["status"] == "SUCCEEDED", scored.text
    result = scored.json()["results"][0]["result"]
    assert result["resultType"] == ("CONTINUOUS" if kind == "NUMERIC" else "CATEGORICAL")
    if kind == "NUMERIC":
        assert result["unit"] is None
    else:
        assert set(result["probabilities"]) == {"优", "良"}
    outside = api.post("/internal/v2/models/score", json=request | {"inputs": {"formula:resin": 80, "substrate": "PC"}})
    assert outside.json()["results"][0]["status"] == "SUCCEEDED", outside.text
    assert outside.json()["results"][0]["applicability"]["status"] == "OUT_OF_DOMAIN"
    for bad, code in [({"formula:resin": 25, "substrate": "铝"}, "UNKNOWN_CATEGORY"),
                      ({"formula:resin": "abc", "substrate": "PC"}, "INVALID_X_VALUE")]:
        rejected = api.post("/internal/v2/models/score", json=request | {"inputs": bad}).json()["results"][0]
        assert rejected["status"] == "BLOCKED"
        assert rejected["code"] == code
        assert rejected["detail"]["fieldName"] in {"基材", "树脂比例"}
    output.write_bytes(b"corrupted")
    damaged = api.post("/internal/v2/models/score", json=request)
    assert damaged.json()["results"][0]["status"] == "FAILED"
