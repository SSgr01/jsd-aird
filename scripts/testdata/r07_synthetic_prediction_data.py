"""Generate the R07 synthetic training and prediction fixture.

This is a data generator, not an acceptance test.  It is intended for a
throw-away local PostgreSQL database used by the R05/R06 worker and the R07
V2 scoring service.  The generated models remain SYNTHETIC candidates and
never become business ACTIVE models.

The script does two things:

* runs the existing R05/R06 seed and the R07 policy fixture idempotently;
* writes a complete ``formula-model.v2`` score request pinned to the frozen
  candidate snapshots and local model bundles.

Example (PowerShell):

    python scripts/testdata/r07_synthetic_prediction_data.py \
      --database-url postgresql://postgres:postgres@127.0.0.1:55434/r0506_browser \
      --storage-root tmp/r07-storage \
      --output tmp/r07-synthetic/prediction-score-request.json
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import uuid
from copy import deepcopy
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
SEED_SQL = ROOT / "scripts" / "testdata" / "r0506_synthetic_training_seed.sql"
POLICY_SQL = ROOT / "scripts" / "testdata" / "r07_synthetic_prediction_fixture.sql"


def psql_command(psql: str, database_url: str, *args: str) -> list[str]:
    return [psql, database_url, "-v", "ON_ERROR_STOP=1", *args]


def run_sql_file(psql: str, database_url: str, path: Path) -> None:
    subprocess.run(
        psql_command(psql, database_url, "-f", str(path)),
        cwd=ROOT,
        check=True,
    )


def fetch_bindings(psql: str, database_url: str) -> list[dict[str, Any]]:
    query = r"""
SELECT jsonb_build_object(
  'target', ts.target_definition_jsonb,
  'modelVersionId', mv.id::text,
  'objectKey', a.object_key,
  'artifactSha256', a.sha256,
  'inputScheme', ts.input_scheme_jsonb,
  'materialDictionary', ts.material_dictionary_jsonb,
  'preprocessing', ts.preprocessing_jsonb,
  'applicabilityDomain', mv.applicability_domain_jsonb,
  'domainPolicy', dp.configuration_jsonb,
  'representativeInputs', (
      SELECT si.row_jsonb->'inputs'
      FROM ai.training_snapshot_item si
      WHERE si.organization_id = mv.organization_id
        AND si.training_snapshot_id = ts.id
      ORDER BY si.ordinal
      LIMIT 1
  )
)::text
FROM ai.prediction_target pt
JOIN ai.model_version mv
  ON mv.organization_id = pt.organization_id
 AND mv.target_id = pt.id
 AND mv.status = 'CANDIDATE'
 AND mv.data_nature = 'SYNTHETIC'
JOIN ai.artifact a
  ON a.organization_id = mv.organization_id
 AND a.id = mv.model_artifact_id
JOIN ai.training_snapshot ts
  ON ts.organization_id = mv.organization_id
 AND ts.id = mv.training_snapshot_id
LEFT JOIN ai.modeling_policy_version dp
  ON dp.organization_id = pt.organization_id
 AND dp.target_id = pt.id
 AND dp.id = pt.current_domain_policy_version_id
WHERE pt.organization_id = '00000000-0000-0000-0000-000000000001'
  AND pt.target_code IN ('SYN_GLOSS_60', 'SYN_UV_SURFACE_DRY', 'SYN_APPEARANCE', 'SYN_HARDNESS')
ORDER BY pt.target_code
"""
    result = subprocess.run(
        psql_command(psql, database_url, "-At", "-c", query),
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    rows: list[dict[str, Any]] = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    if len(rows) != 4:
        raise RuntimeError(
            f"expected four synthetic candidate bindings after seeding, got {len(rows)}"
        )
    return rows


def local_bundle_url(storage_root: Path, object_key: str) -> str:
    path = (storage_root / Path(object_key)).resolve()
    if not path.is_file():
        raise FileNotFoundError(f"synthetic model bundle does not exist: {path}")
    return path.as_uri()


def build_request(bindings: list[dict[str, Any]], storage_root: Path) -> dict[str, Any]:
    first_materials = bindings[0]["materialDictionary"]["materials"]
    if len(first_materials) < 2:
        raise RuntimeError("synthetic dictionary must contain at least two materials")
    components = [
        {
            "materialId": first_materials[0]["materialId"],
            "ratio": 80.0,
            "unit": "%",
            "amountKnown": True,
        },
        {
            "materialId": first_materials[1]["materialId"],
            "ratio": 18.8,
            "unit": "%",
            "amountKnown": True,
        },
    ]
    model_bindings: list[dict[str, Any]] = []
    for row in bindings:
        # Training stores the learned feature ranges on the model.  The
        # published DOMAIN policy supplies the decision ratio; retain the
        # learned features and bounds so the bundle and policy stay aligned.
        domain = dict(row["applicabilityDomain"] or {})
        policy = row.get("domainPolicy") or {}
        if "nearBoundaryRatio" in policy:
            domain["nearBoundaryRatio"] = policy["nearBoundaryRatio"]
        target = deepcopy(row["target"])
        # The original R05/R06 fixture predates the V2 binary threshold
        # requirement.  Add the explicit synthetic policy value to the
        # generated request so the four result types can be exercised; real
        # targets must carry this value in their published target version.
        if target.get("valueType") == "BINARY":
            target.setdefault("observationSemantics", {})["decisionThreshold"] = 0.5
        model_bindings.append(
            {
                "target": target,
                "modelVersionId": row["modelVersionId"],
                "modelBundle": {
                    "url": local_bundle_url(storage_root, row["objectKey"]),
                    "sha256": row["artifactSha256"],
                },
                "inputScheme": row["inputScheme"],
                "materialDictionary": row["materialDictionary"],
                "preprocessing": row["preprocessing"],
                "applicabilityDomain": domain,
            }
        )
    representative = deepcopy(bindings[0].get("representativeInputs") or {})
    # The current scheme requires this explicit field while the historical
    # synthetic row stores the same value under conditions/facts.  Preserve
    # every richer template field and add the required alias only when needed.
    if "TEST_TEMPERATURE" not in representative:
        temperature = (representative.get("conditions") or {}).get("temperature")
        if temperature is None:
            temperature = (representative.get("facts") or {}).get("temperature")
        representative["TEST_TEMPERATURE"] = temperature if temperature is not None else 25
    return {
        "contractVersion": "formula-model.v2",
        "requestId": str(uuid.uuid4()),
        "seed": 2026,
        "runId": str(uuid.uuid4()),
        "formula": {
            "basis": "MASS_PERCENT",
            "compositionComplete": True,
            "components": components,
            "recordedTotal": 98.8,
        },
        # Keep the nested condition/process/fact fields used by the frozen
        # synthetic snapshots so the generated request is inside their
        # learned domain while retaining the 98.8% recorded total.
        "inputs": representative,
        "modelBindings": model_bindings,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--database-url",
        default="postgresql://postgres:postgres@127.0.0.1:55434/r0506_browser",
        help="isolated PostgreSQL URL; never point this at a shared environment",
    )
    parser.add_argument(
        "--psql",
        default=shutil.which("psql") or "psql",
        help="psql executable",
    )
    parser.add_argument(
        "--storage-root",
        type=Path,
        default=ROOT / "tmp" / "r07-storage",
        help="local object-storage root containing generated model bundles",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "tmp" / "r07-synthetic" / "prediction-score-request.json",
        help="V2 score request JSON to generate",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    run_sql_file(args.psql, args.database_url, SEED_SQL)
    run_sql_file(args.psql, args.database_url, POLICY_SQL)
    bindings = fetch_bindings(args.psql, args.database_url)
    request = build_request(bindings, args.storage_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(request, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"generated {len(bindings)} synthetic model bindings")
    print(f"score request: {args.output.resolve()}")
    print("legacy score request uses its captured 98.8% row; the original value is retained and not normalized")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        print(f"psql failed with exit code {exc.returncode}", file=sys.stderr)
        raise
