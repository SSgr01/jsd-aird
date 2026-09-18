from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

import pandas as pd
import pytest
from jsonschema import Draft202012Validator

from jsd_aird_ai.contracts import (
    CandidatePolicy,
    TaskProfile,
    TrainRequest,
    TrainResponse,
    ValidateSnapshotRequest,
)
from jsd_aird_ai.digests import canonical_sha256, snapshot_sha256
from jsd_aird_ai.devtools import load_indexed_bundle
from jsd_aird_ai.service import FormulaModelService
from jsd_aird_ai.settings import Settings


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
PROFILE_PATH = (
    REPOSITORY_ROOT
    / "jsd-aird-ai"
    / "src"
    / "jsd_aird_ai"
    / "task_profiles"
    / "uvpu_application_formulation.v1.json"
)
SCHEMA_PATH = (
    REPOSITORY_ROOT
    / "jsd-aird-ai"
    / "contracts"
    / "formula-model.v1"
    / "formula-model.v1.schema.json"
)
CONTRACT_EXAMPLES = SCHEMA_PATH.parent / "examples"
GOLDEN_SCORE_REQUEST = CONTRACT_EXAMPLES / "score-request.uvpu-synthetic.golden.json"
GOLDEN_SCORE_RESPONSE = CONTRACT_EXAMPLES / "score-response.uvpu-synthetic.golden.json"
PACKAGE_ROOT = (
    REPOSITORY_ROOT
    / "docs"
    / "AI实验优化、配方预测"
    / "UVPU_APPLICATION_FORMULATION_Synthetic_Shared_Process_V3_Package"
)
REPAIRED_SNAPSHOT_ROOT = (
    PACKAGE_ROOT
    / "UVPU_APPLICATION_FORMULATION_Synthetic_TrainingSnapshot_V2_Shared_Process_PyArrow"
)
ORIGINAL_SNAPSHOT_ROOT = (
    PACKAGE_ROOT
    / "UVPU_APPLICATION_FORMULATION_Synthetic_TrainingSnapshot_V2_Shared_Process"
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_contract_definition(name: str, payload: dict) -> None:
    schema = json.loads(SCHEMA_PATH.read_bytes())
    Draft202012Validator(
        {
            "$schema": schema["$schema"],
            "$defs": schema["$defs"],
            "$ref": f"#/$defs/{name}",
        }
    ).validate(payload)


def artifact(path: Path, name: str) -> dict:
    return {"name": name, "url": path.resolve().as_uri(), "sha256": sha256(path)}


def request_payload(profile: TaskProfile, snapshot_root: Path, request_id: str = "test") -> dict:
    manifest = artifact(snapshot_root / "manifest.json", "manifest.json")
    measurements = artifact(snapshot_root / "measurements.parquet", "measurements.parquet")
    source_map = artifact(snapshot_root / "source-map.parquet", "source-map.parquet")
    return {
        "contractVersion": "formula-model.v1",
        "requestId": request_id,
        "taskProfileHash": canonical_sha256(profile),
        "snapshotHash": snapshot_sha256(
            manifest["sha256"], measurements["sha256"], source_map["sha256"]
        ),
        "seed": 20260903,
        "taskProfile": profile.model_dump(mode="json", by_alias=True),
        "snapshot": {
            "manifest": manifest,
            "measurements": measurements,
            "sourceMap": source_map,
        },
    }


@pytest.fixture(scope="session")
def profile() -> TaskProfile:
    return TaskProfile.model_validate_json(PROFILE_PATH.read_bytes())


@pytest.fixture(scope="session")
def test_profile(profile: TaskProfile) -> TaskProfile:
    return profile.model_copy(
        update={
            "candidate": CandidatePolicy(
                maximum_pool_size=128,
                minimum_l1_distance=profile.candidate.minimum_l1_distance,
                conservative_maximum_l1_distance=(
                    profile.candidate.conservative_maximum_l1_distance
                ),
                default_count=profile.candidate.default_count,
            )
        }
    )


@pytest.fixture(scope="session")
def validation_request(profile: TaskProfile) -> ValidateSnapshotRequest:
    return ValidateSnapshotRequest.model_validate(
        request_payload(profile, REPAIRED_SNAPSHOT_ROOT, "validate-golden")
    )


@pytest.fixture(scope="session")
def service(tmp_path_factory: pytest.TempPathFactory) -> FormulaModelService:
    return FormulaModelService(
        Settings(
            allow_file_urls=True,
            temporary_root=tmp_path_factory.mktemp("service-tmp"),
            model_cache_entries=2,
        )
    )


@pytest.fixture(scope="session")
def trained_bundle(
    service: FormulaModelService,
    test_profile: TaskProfile,
    tmp_path_factory: pytest.TempPathFactory,
) -> dict:
    reusable_index = os.getenv("JSD_AIRD_AI_GOLDEN_BUNDLE_INDEX")
    if reusable_index:
        index, model_path, bundle = load_indexed_bundle(Path(reusable_index))
        response = TrainResponse(
            request_id="train-golden-reused",
            snapshot_id=index.snapshot_id,
            snapshot_hash=index.snapshot_hash,
            status=index.status,
            model_bundle_sha256=index.model_bundle_hash,
            model_bundle_size=index.model_bundle_size,
            uploaded=True,
            targets=index.targets,
            warnings=index.warnings,
        )
        return {
            "profile": bundle.profile,
            "profile_hash": index.task_profile_hash,
            "snapshot_hash": index.snapshot_hash,
            "path": model_path,
            "sha256": index.model_bundle_hash,
            "response": response,
        }
    output_path = tmp_path_factory.mktemp("model-bundle") / "uvpu-model.zip"
    payload = request_payload(test_profile, REPAIRED_SNAPSHOT_ROOT, "train-golden")
    payload["output"] = {
        "url": output_path.resolve().as_uri(),
        "contentType": "application/zip",
    }
    request = TrainRequest.model_validate(payload)
    response = service.train(request)
    return {
        "profile": test_profile,
        "profile_hash": request.task_profile_hash,
        "snapshot_hash": request.snapshot_hash,
        "path": output_path,
        "sha256": response.model_bundle_sha256,
        "response": response,
    }


def first_api_row(profile: TaskProfile) -> tuple[dict[str, float], dict]:
    frame = pd.read_parquet(REPAIRED_SNAPSHOT_ROOT / "measurements.parquet")
    row = frame.iloc[0]
    formula = {item.code: float(row[item.column]) for item in profile.formula.materials}
    context = {item.code: row[item.column] for item in profile.context_features}
    return formula, context
