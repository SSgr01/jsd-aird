"""Generate 400 logical samples and train/score 15 semantically typed Y targets.

The target list mirrors the current UV/PU catalog.  The final categorical target
is explicitly synthetic and is not a reinterpretation of the catalog's free
text appearance field.  This script is a local data/compute fixture, not an
acceptance test or a production model publisher.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import uuid
from copy import deepcopy
from pathlib import Path
from typing import Any

import httpx
import pandas as pd

from r07_synthetic_prediction_data import psql_command, run_sql_file


ROOT = Path(__file__).resolve().parents[2]
BASE_SEED = ROOT / "scripts" / "testdata" / "r0506_synthetic_training_seed.sql"
EXPAND_SQL = ROOT / "scripts" / "testdata" / "r07_expand_synthetic_training.sql"


def sha(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def base_context(psql: str, database_url: str) -> dict[str, Any]:
    query = r"""
SELECT jsonb_build_object(
  'inputScheme', ts.input_scheme_jsonb,
  'materialDictionary', ts.material_dictionary_jsonb,
  'preprocessing', ts.preprocessing_jsonb
)::text
FROM ai.training_snapshot ts
JOIN ai.prediction_target pt ON pt.organization_id=ts.organization_id AND pt.id=ts.target_id
WHERE ts.organization_id='00000000-0000-0000-0000-000000000001'
  AND pt.target_code='SYN_GLOSS_60'
ORDER BY ts.frozen_at DESC LIMIT 1
"""
    result = subprocess.run(
        psql_command(psql, database_url, "-At", "-c", query),
        cwd=ROOT, check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    if not lines:
        raise RuntimeError("base synthetic context is missing; run r0506_synthetic_training_seed.sql first")
    return json.loads(lines[0])


TARGETS: list[dict[str, Any]] = [
    {"code": "APP.WARPING.HEIGHT@substrate=PET_100UM;stage=UV_IMMEDIATE", "name": "PET 100μm UV后翘曲高度", "type": "CONTINUOUS", "unit": "cm", "coverage": 280, "metric": "rmse"},
    {"code": "APP.WARPING.HEIGHT@substrate=PET_100UM;stage=ROOM_12H", "name": "PET 100μm 室温12小时翘曲高度", "type": "CONTINUOUS", "unit": "cm", "coverage": 280, "metric": "rmse"},
    {"code": "APP.WARPING.CURL_ANGLE@substrate=PC_FILM_170UM;stage=UV_IMMEDIATE", "name": "PC 170μm UV后卷曲角", "type": "CONTINUOUS", "unit": "deg", "coverage": 350, "metric": "rmse"},
    {"code": "APP.WARPING.CURL_ANGLE@substrate=PET_100UM;stage=UV_IMMEDIATE", "name": "PET 100μm UV后卷曲角", "type": "CONTINUOUS", "unit": "deg", "coverage": 280, "metric": "rmse"},
    {"code": "GLOSS_60_UV_POST", "name": "60°光泽", "type": "CONTINUOUS", "unit": "GU", "coverage": 350, "metric": "rmse"},
    {"code": "APP.HARDNESS.PENCIL@substrate=PET_100UM;load=1KG", "name": "PET 100μm 1kg铅笔硬度", "type": "ORDINAL", "coverage": 280, "metric": "gradeMae", "classes": ["H", "2H", "3H", "4H"]},
    {"code": "APP.ABRASION.STEELWOOL@substrate=PET_100UM;load=500G;contact=1X1_UNSPECIFIED", "name": "PET 100μm 500g钢丝绒耐磨次数", "type": "CONTINUOUS", "unit": "cycles", "coverage": 160, "metric": "rmse"},
    {"code": "APP.ABRASION.STEELWOOL@substrate=PET_100UM;load=1KG;contact=1X1_UNSPECIFIED", "name": "PET 100μm 1kg钢丝绒耐磨次数", "type": "CONTINUOUS", "unit": "cycles", "coverage": 160, "metric": "rmse"},
    {"code": "APP.SURFACE_DRYNESS@stage=UV_CURED", "name": "UV表干", "type": "BINARY", "coverage": 350, "metric": "accuracy", "classes": ["未表干", "表干"], "positive": "表干"},
    {"code": "APP.ELONGATION@substrate=PC_FILM_170UM;method=HOT_DRAW;unit=PCT", "name": "PC 170μm热拉伸率", "type": "CONTINUOUS", "unit": "%", "coverage": 40, "metric": "rmse"},
    {"code": "APP.ADHESION.B_GRADE@substrate=PC_FILM_170UM;condition=INITIAL", "name": "PC 170μm 初始附着力", "type": "ORDINAL", "coverage": 350, "metric": "gradeMae", "classes": ["0B", "1B", "2B", "3B", "4B", "5B"]},
    {"code": "APP.ADHESION.B_GRADE@substrate=PET_100UM;condition=INITIAL", "name": "PET 100μm 初始附着力", "type": "ORDINAL", "coverage": 280, "metric": "gradeMae", "classes": ["0B", "1B", "2B", "3B", "4B", "5B"]},
    {"code": "APP.ADHESION.B_GRADE@substrate=PMMA_PC_COMPOSITE_0_64MM;condition=INITIAL", "name": "PMMA/PC 0.64mm 初始附着力", "type": "ORDINAL", "coverage": 160, "metric": "gradeMae", "classes": ["0B", "1B", "2B", "3B", "4B", "5B"]},
    {"code": "APP.ADHESION.B_GRADE@substrate=PC_FILM_170UM;condition=WATER_85C_1H", "name": "PC 170μm 85℃水煮1小时后附着力", "type": "ORDINAL", "coverage": 280, "metric": "gradeMae", "classes": ["0B", "1B", "2B", "3B", "4B", "5B"]},
    {"code": "SYNTHETIC_MULTICLASS_ACCEPTANCE", "name": "合成多分类能力验证", "type": "CATEGORICAL", "coverage": 160, "metric": "accuracy", "classes": ["类别A", "类别B", "类别C"]},
]


def feature_inputs(index: int) -> dict[str, Any]:
    return {
        "TEST_TEMPERATURE": 20 + index % 12,
        "conditions": {
            "temperature": 20 + index % 12,
            "humidity": 42 + index % 28,
            "substrate": ["PET", "PC", "PMMA/PC"][(index - 1) % 3],
            "testStage": "UV固化后" if index % 2 == 0 else "PU固化后",
        },
        "process": {
            "uvEnergy": 420 + index % 60 * 8,
            "uvIntensity": 62 + index % 28,
            "coatingMethod": "刮涂" if index % 2 == 0 else "线棒涂布",
            "filmThicknessUm": 8 + index % 9,
            "cureTemperature": 22 + index % 10,
        },
        "facts": {
            "solidContent": 38.0 + index % 25 * 0.25,
            "viscosity": 1050 + index % 40 * 22,
            "waterContent": 0.20 + index % 15 * 0.02,
            "molecularWeight": 4700 + index % 60 * 31,
            "gloss60": 35.0 + index * 0.31,
            "pencilHardness": ["H", "2H", "3H", "4H"][(index - 1) % 4],
            "steelWoolCycles": 420 + index % 18 * 35,
        },
    }


def formula(index: int, materials: list[dict[str, Any]]) -> dict[str, Any]:
    total = 98.8 if index % 4 == 0 else 100.0
    ratio = 67.5 + (index * 13 % 250) / 10.0
    return {
        "basis": "MASS_PERCENT", "compositionComplete": True, "recordedTotal": total,
        "components": [
            {"materialId": materials[0]["materialId"], "ratio": round(ratio, 2), "unit": "%", "amountKnown": True},
            {"materialId": materials[1]["materialId"], "ratio": round(total - ratio, 2), "unit": "%", "amountKnown": True},
        ],
    }


def value_for(target: dict[str, Any], index: int) -> tuple[float | None, str | None]:
    kind = target["type"]
    if kind == "CONTINUOUS":
        code = target["code"]
        if "HEIGHT" in code:
            return round(0.65 + index * 0.008 + (index % 7) * 0.02, 4), None
        if "CURL_ANGLE" in code:
            return round(3.0 + index * 0.12 + (index % 5) * 0.4, 4), None
        if "STEELWOOL" in code:
            return round(180 + index * 2.4 + (index % 11) * 8, 4), None
        return round(45 + index * 0.18 + (index % 4) * 0.6, 4), None
    if kind == "BINARY":
        return None, "表干" if index % 5 in (0, 1, 2) else "未表干"
    classes = target["classes"]
    if kind == "CATEGORICAL":
        return None, classes[(index - 1) % len(classes)]
    return None, classes[min(len(classes) - 1, (index + (index // 17)) % len(classes))]


def context_for(base: dict[str, Any], target: dict[str, Any]) -> dict[str, Any]:
    target_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"r07-15y-target:{target['code']}"))
    target_definition: dict[str, Any] = {
        "id": target_id,
        "version": 1,
        "sha256": sha(target["code"].encode()),
        "code": target["code"],
        "valueType": target["type"],
        "unit": target.get("unit"),
        "classes": target.get("classes", []),
        "positiveClass": target.get("positive"),
        "observationSemantics": {"observationType": "EXACT", "synthetic": True},
    }
    if target["type"] == "BINARY":
        target_definition["observationSemantics"]["decisionThreshold"] = 0.5
    scheme = deepcopy(base["inputScheme"])
    scheme["id"] = str(uuid.uuid5(uuid.NAMESPACE_URL, f"r07-15y-scheme:{target['code']}"))
    scheme["targetVersionId"] = target_id
    policy_config = {
        "foldCount": 5,
        "primaryMetric": target["metric"],
        "metricThreshold": 1000 if target["type"] == "CONTINUOUS" else 10,
        "dataNature": "SYNTHETIC",
        "replicateHandling": "MEAN" if target["type"] == "CONTINUOUS" else "MEDIAN_GRADE" if target["type"] == "ORDINAL" else "MAJORITY",
        "candidateAlgorithms": {
            "CONTINUOUS": ["RANDOM_FOREST", "LIGHTGBM", "XGBOOST"],
            "ORDINAL": ["ORDINAL_CUMULATIVE_LOGIT", "RANDOM_FOREST", "LIGHTGBM"],
            "BINARY": ["LOGISTIC_REGRESSION", "RANDOM_FOREST", "LIGHTGBM"],
            "CATEGORICAL": ["LOGISTIC_REGRESSION", "RANDOM_FOREST", "LIGHTGBM"],
        }[target["type"]],
        "seed": 2026,
        "syntheticMarker": "SYNTHETIC_MULTICLASS_ACCEPTANCE" if target["code"] == "SYNTHETIC_MULTICLASS_ACCEPTANCE" else "R07_SYNTHETIC",
    }
    policy = {"id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"r07-15y-policy:{target['code']}")), "version": 1,
              "sha256": sha(json.dumps(policy_config, sort_keys=True).encode()), "config": policy_config}
    return {"targetDefinition": target_definition, "inputScheme": scheme,
            "materialDictionary": deepcopy(base["materialDictionary"]), "preprocessing": deepcopy(base["preprocessing"]),
            "trainingPolicy": policy}


def write_snapshot(target: dict[str, Any], context: dict[str, Any], materials: list[dict[str, Any]], out_dir: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    rows: list[dict[str, Any]] = []
    source: list[dict[str, Any]] = []
    coverage = target["coverage"]
    for index in range(1, coverage + 1):
        row_id = f"{target['code']}-row-{index:03d}"
        f = formula(index, materials)
        x = feature_inputs(index)
        numeric, label = value_for(target, index)
        rows.append({"row_id": row_id, "formula_json": json.dumps(f, ensure_ascii=False, separators=(",", ":")),
                     "inputs_json": json.dumps(x, ensure_ascii=False, separators=(",", ":")),
                     "target_numeric": numeric, "target_label": label})
        source.append({"row_id": row_id, "formula_lineage": f"lineage-{(index - 1) % 25:02d}",
                       "source_context": f"source-group-{(index - 1) % 10:02d}"})
    target_dir = out_dir / "snapshots" / sha(target["code"].encode())[:12]
    target_dir.mkdir(parents=True, exist_ok=True)
    rows_path, source_path = target_dir / "rows.parquet", target_dir / "source-map.parquet"
    pd.DataFrame(rows).to_parquet(rows_path, index=False)
    pd.DataFrame(source).to_parquet(source_path, index=False)
    snapshot_hash = sha(rows_path.read_bytes() + source_path.read_bytes() + target["code"].encode())
    manifest_path = target_dir / "manifest.json"
    manifest_path.write_text(json.dumps({"snapshotHash": snapshot_hash, "rowCount": coverage,
                                         "logicalSampleCount": 400, "coverage": coverage,
                                         "dataNature": "SYNTHETIC", "targetCode": target["code"]}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    refs = {"id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"r07-15y-snapshot:{target['code']}")), "version": 1, "sha256": snapshot_hash,
            "manifest": {"url": manifest_path.resolve().as_uri(), "sha256": sha(manifest_path.read_bytes())},
            "rows": {"url": rows_path.resolve().as_uri(), "sha256": sha(rows_path.read_bytes())},
            "sourceMap": {"url": source_path.resolve().as_uri(), "sha256": sha(source_path.read_bytes())}}
    return {"refs": refs, "dir": target_dir}, rows


def post(client: httpx.Client, url: str, payload: dict[str, Any]) -> dict[str, Any]:
    response = client.post(url, json=payload)
    response.raise_for_status()
    return response.json()


def train_one(client: httpx.Client, base_url: str, target: dict[str, Any], context: dict[str, Any], snapshot: dict[str, Any], out_dir: Path) -> dict[str, Any]:
    base_request = {"contractVersion": "formula-model.v2", "requestId": str(uuid.uuid4()), "seed": 2026,
                    "snapshot": snapshot["refs"], "context": context}
    validated = post(client, f"{base_url}/internal/v2/snapshots/validate", base_request)
    if validated["status"] != "VALID":
        raise RuntimeError(f"{target['code']} validation failed: {validated}")
    folds = post(client, f"{base_url}/internal/v2/snapshots/validation-folds", {**base_request, "requestId": str(uuid.uuid4()),
                                                                                      "foldCount": 5, "groupFields": ["formula_lineage", "source_context"]})
    fold_path = snapshot["dir"] / "validation-folds.json"
    fold_path.write_text(json.dumps(folds, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    model_path = out_dir / "models" / f"{sha(target['code'].encode())[:12]}.zip"
    trained = post(client, f"{base_url}/internal/v2/models/train", {**base_request, "requestId": str(uuid.uuid4()), "jobId": str(uuid.uuid4()),
                                                                       "validationFolds": {"url": fold_path.resolve().as_uri(), "sha256": sha(fold_path.read_bytes())},
                                                                       "output": model_path.resolve().as_uri()})
    if trained["status"] != "CANDIDATE":
        raise RuntimeError(f"{target['code']} training failed: {trained}")
    return {"target": target, "context": context, "snapshot": snapshot, "training": trained}


def score_request(trained: list[dict[str, Any]], rows_by_target: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    # Row 20 exists in every target's coverage, so one input remains inside all domains.
    sample = rows_by_target[trained[0]["target"]["code"]][19]
    formula_payload = json.loads(sample["formula_json"])
    inputs_payload = json.loads(sample["inputs_json"])
    bindings = []
    for item in trained:
        target = deepcopy(item["context"]["targetDefinition"])
        domain = deepcopy(item["training"].get("applicabilityDomain") or {})
        domain["nearBoundaryRatio"] = 0.1
        bindings.append({"target": target, "modelVersionId": f"synthetic-v2-{sha(item['target']['code'].encode())[:12]}",
                         "modelBundle": item["training"]["modelBundle"], "inputScheme": item["context"]["inputScheme"],
                         "materialDictionary": item["context"]["materialDictionary"], "preprocessing": item["context"]["preprocessing"],
                         "applicabilityDomain": domain})
    return {"contractVersion": "formula-model.v2", "requestId": str(uuid.uuid4()), "seed": 2026, "runId": str(uuid.uuid4()),
            "formula": formula_payload, "inputs": inputs_payload, "modelBindings": bindings}


def write_logical_sample_export(materials: list[dict[str, Any]], output_dir: Path) -> Path:
    """Write one row per logical sample with sparse, target-specific observations."""
    records: list[dict[str, Any]] = []
    for index in range(1, 401):
        f = formula(index, materials)
        x = feature_inputs(index)
        record: dict[str, Any] = {
            "logicalSampleId": f"R07-SAMPLE-{index:03d}",
            "resinRatio": f["components"][0]["ratio"],
            "additiveRatio": f["components"][1]["ratio"],
            "recordedTotal": f["recordedTotal"],
            "testTemperature": x["TEST_TEMPERATURE"],
            "humidity": x["conditions"]["humidity"],
            "substrate": x["conditions"]["substrate"],
            "testStage": x["conditions"]["testStage"],
            "uvEnergy": x["process"]["uvEnergy"],
            "uvIntensity": x["process"]["uvIntensity"],
            "coatingMethod": x["process"]["coatingMethod"],
            "filmThicknessUm": x["process"]["filmThicknessUm"],
            "solidContent": x["facts"]["solidContent"],
            "viscosity": x["facts"]["viscosity"],
            "waterContent": x["facts"]["waterContent"],
            "molecularWeight": x["facts"]["molecularWeight"],
            "gloss60": x["facts"]["gloss60"],
            "pencilHardness": x["facts"]["pencilHardness"],
            "steelWoolCycles": x["facts"]["steelWoolCycles"],
        }
        for target in TARGETS:
            if index <= target["coverage"]:
                numeric, label = value_for(target, index)
                record[target["code"]] = numeric if numeric is not None else label
            else:
                record[target["code"]] = ""
        records.append(record)
    path = output_dir / "logical-samples-400.csv"
    pd.DataFrame(records).to_csv(path, index=False, encoding="utf-8-sig")
    return path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", default="postgresql://postgres:postgres@127.0.0.1:55434/r0506_browser")
    parser.add_argument("--psql", default=shutil.which("psql") or "psql")
    parser.add_argument("--base-url", default="http://127.0.0.1:18090")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "tmp" / "r07-synthetic-15y")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    run_sql_file(args.psql, args.database_url, BASE_SEED)
    run_sql_file(args.psql, args.database_url, EXPAND_SQL)
    base = base_context(args.psql, args.database_url)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    materials = base["materialDictionary"]["materials"]
    snapshots: dict[str, dict[str, Any]] = {}
    rows_by_target: dict[str, list[dict[str, Any]]] = {}
    contexts: list[dict[str, Any]] = []
    for target in TARGETS:
        context = context_for(base, target)
        snapshot, rows = write_snapshot(target, context, materials, args.output_dir)
        snapshots[target["code"]] = snapshot
        rows_by_target[target["code"]] = rows
        contexts.append(context)
    trained: list[dict[str, Any]] = []
    with httpx.Client(timeout=240.0) as client:
        for target, context in zip(TARGETS, contexts):
            trained.append(train_one(client, args.base_url, target, context, snapshots[target["code"]], args.output_dir))
        request = score_request(trained, rows_by_target)
        score = post(client, f"{args.base_url}/internal/v2/models/score", request)
    request_path = args.output_dir / "prediction-score-request.json"
    result_path = args.output_dir / "prediction-score-result.json"
    summary_path = args.output_dir / "training-summary.json"
    request_path.write_text(json.dumps(request, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result_path.write_text(json.dumps(score, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary_path.write_text(json.dumps([{"code": item["target"]["code"], "name": item["target"]["name"], "type": item["target"]["type"],
                                         "coverage": item["target"]["coverage"], "status": item["training"]["status"],
                                         "selectedAlgorithm": item["training"].get("metrics", {}).get("selectedAlgorithm"),
                                         "candidateCount": item["training"].get("metrics", {}).get("candidateCount"),
                                         "syntheticMarker": item["context"]["trainingPolicy"]["config"].get("syntheticMarker")}
                                        for item in trained], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # Export the coverage plan as a compact table for R04/R05 review.
    coverage = pd.DataFrame([{"targetCode": t["code"], "targetName": t["name"], "valueType": t["type"],
                              "logicalSamples": 400, "observationsWithY": t["coverage"],
                              "missingY": 400 - t["coverage"], "sourceTemplate": "综合测试报告模板.xlsx"} for t in TARGETS])
    coverage.to_csv(args.output_dir / "target-coverage-15y.csv", index=False, encoding="utf-8-sig")
    logical_export = write_logical_sample_export(materials, args.output_dir)
    print("generated 400 logical samples for 15 Y targets")
    print(f"trained candidates: {sum(item['training'].get('metrics', {}).get('candidateCount', 0) for item in trained)}")
    print(f"scored targets: {len(score['results'])}; execution={score['executionStatus']} outcome={score['outcomeStatus']}")
    print(f"summary: {summary_path.resolve()}")
    print(f"coverage: {(args.output_dir / 'target-coverage-15y.csv').resolve()}")
    print(f"logical samples: {logical_export.resolve()}")
    print(f"result: {result_path.resolve()}")
    print("recordedTotal distribution: 300 rows at 100.0%, 100 rows at 98.8%; original values retained")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
