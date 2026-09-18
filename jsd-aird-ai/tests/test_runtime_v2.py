from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
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
