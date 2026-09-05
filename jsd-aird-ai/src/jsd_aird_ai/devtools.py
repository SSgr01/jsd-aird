from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any, Literal

import httpx
from pydantic import Field, ValidationError

from jsd_aird_ai.contracts import (
    CONTRACT_VERSION,
    ArtifactReadRef,
    ContractModel,
    ScoreRequest,
    ScoreResponse,
    ScoreRow,
    SnapshotArtifacts,
    TargetTrainingResult,
    TaskProfile,
    TrainRequest,
)
from jsd_aird_ai.digests import canonical_sha256, sha256_bytes, snapshot_sha256
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.modeling import ModelBundle, load_bundle
from jsd_aird_ai.service import FormulaModelService
from jsd_aird_ai.settings import Settings


PROJECT_ROOT = Path(__file__).resolve().parents[2]
REPOSITORY_ROOT = PROJECT_ROOT.parent
DEFAULT_PROFILE_PATH = (
    PROJECT_ROOT
    / "src"
    / "jsd_aird_ai"
    / "task_profiles"
    / "uvpu_application_formulation.v1.json"
)
DEFAULT_SNAPSHOT_DIR = (
    REPOSITORY_ROOT
    / "docs"
    / "AI实验优化、配方预测"
    / "UVPU_APPLICATION_FORMULATION_Synthetic_Shared_Process_V3_Package"
    / "UVPU_APPLICATION_FORMULATION_Synthetic_TrainingSnapshot_V2_Shared_Process_PyArrow"
)
DEFAULT_OUTPUT_DIR = (
    REPOSITORY_ROOT
    / ".runtime"
    / "formula-models"
    / "UVPU_APPLICATION_FORMULATION"
    / "1.0"
    / "synthetic-development"
)
DEFAULT_MODEL_INDEX = DEFAULT_OUTPUT_DIR / "active-dev-model.json"
DEFAULT_SCORE_INPUT = PROJECT_ROOT / "examples" / "uvpu-score-input.json"


class DevModelIndex(ContractModel):
    artifact_kind: Literal["FORMULA_MODEL_DEV_INDEX"] = "FORMULA_MODEL_DEV_INDEX"
    contract_version: Literal[CONTRACT_VERSION] = CONTRACT_VERSION
    development_only: Literal[True] = True
    production_eligible: Literal[False] = False
    task_profile_code: str
    task_profile_version: str
    task_profile_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    schema_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    snapshot_id: str
    snapshot_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    model_bundle_path: str
    model_bundle_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    model_bundle_size: int = Field(ge=1)
    seed: int
    status: Literal["READY", "PARTIAL"]
    targets: list[TargetTrainingResult]
    warnings: list[str] = Field(default_factory=list)


class DevScoreInput(ContractModel):
    request_id: str = Field(min_length=1, max_length=120)
    rows: list[ScoreRow] = Field(min_length=1, max_length=20_000)
    target_codes: list[str] | None = None


def _sha256(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def _read_ref(path: Path) -> ArtifactReadRef:
    resolved = path.resolve(strict=True)
    return ArtifactReadRef(
        name=resolved.name,
        url=resolved.as_uri(),
        sha256=_sha256(resolved),
    )


def _snapshot_artifacts(snapshot_dir: Path) -> SnapshotArtifacts:
    return SnapshotArtifacts(
        manifest=_read_ref(snapshot_dir / "manifest.json"),
        measurements=_read_ref(snapshot_dir / "measurements.parquet"),
        source_map=_read_ref(snapshot_dir / "source-map.parquet"),
    )


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _relative_artifact_path(path: Path) -> str:
    try:
        return path.resolve().relative_to(REPOSITORY_ROOT.resolve()).as_posix()
    except ValueError as exc:
        raise FormulaModelError(
            ErrorCode.INVALID_SNAPSHOT,
            "development artifacts must stay inside the repository workspace",
        ) from exc


def _resolve_artifact_path(relative_path: str) -> Path:
    candidate = (REPOSITORY_ROOT / relative_path).resolve(strict=False)
    if not candidate.is_relative_to(REPOSITORY_ROOT.resolve()):
        raise FormulaModelError(
            ErrorCode.MODEL_NOT_READY,
            "development model index contains an unsafe artifact path",
        )
    return candidate


def load_dev_model_index(index_path: Path = DEFAULT_MODEL_INDEX) -> DevModelIndex:
    try:
        return DevModelIndex.model_validate_json(index_path.read_bytes())
    except (OSError, ValidationError) as exc:
        raise FormulaModelError(
            ErrorCode.MODEL_NOT_READY,
            "development model index does not exist; run the training command first",
        ) from exc


def load_indexed_bundle(
    index_path: Path = DEFAULT_MODEL_INDEX,
) -> tuple[DevModelIndex, Path, ModelBundle]:
    index = load_dev_model_index(index_path)
    model_path = _resolve_artifact_path(index.model_bundle_path)
    try:
        data = model_path.read_bytes()
    except OSError as exc:
        raise FormulaModelError(
            ErrorCode.MODEL_NOT_READY,
            "indexed development model bundle does not exist",
        ) from exc
    if len(data) != index.model_bundle_size or sha256_bytes(data) != index.model_bundle_hash:
        raise FormulaModelError(
            ErrorCode.HASH_MISMATCH,
            "development model bundle does not match its active index",
        )
    bundle = load_bundle(data, index.model_bundle_hash)
    if canonical_sha256(bundle.profile) != index.task_profile_hash:
        raise FormulaModelError(ErrorCode.HASH_MISMATCH, "indexed task profile hash mismatch")
    if bundle.payload.get("snapshot_hash") != index.snapshot_hash:
        raise FormulaModelError(ErrorCode.HASH_MISMATCH, "indexed snapshot hash mismatch")
    return index, model_path, bundle


def train_uvpu_development_model(
    *,
    profile_path: Path = DEFAULT_PROFILE_PATH,
    snapshot_dir: Path = DEFAULT_SNAPSHOT_DIR,
    output_dir: Path = DEFAULT_OUTPUT_DIR,
    seed: int = 20260903,
    force: bool = False,
) -> tuple[DevModelIndex, bool]:
    profile = TaskProfile.model_validate_json(profile_path.read_bytes())
    profile_hash = canonical_sha256(profile)
    snapshot = _snapshot_artifacts(snapshot_dir.resolve(strict=True))
    snapshot_hash = snapshot_sha256(
        snapshot.manifest.sha256,
        snapshot.measurements.sha256,
        snapshot.source_map.sha256,
    )
    index_path = output_dir / "active-dev-model.json"
    if not force and index_path.is_file():
        try:
            existing, _, bundle = load_indexed_bundle(index_path)
            if (
                existing.task_profile_hash == profile_hash
                and existing.snapshot_hash == snapshot_hash
                and existing.seed == seed
                and bundle.profile == profile
            ):
                return existing, True
        except FormulaModelError as exc:
            if exc.code != ErrorCode.MODEL_NOT_READY:
                raise

    models_dir = output_dir / "models"
    models_dir.mkdir(parents=True, exist_ok=True)
    descriptor, staging_name = tempfile.mkstemp(
        prefix=".building-",
        suffix=".zip",
        dir=models_dir,
    )
    os.close(descriptor)
    staging_path = Path(staging_name)
    try:
        request = TrainRequest(
            request_id="uvpu-development-model-build",
            task_profile_hash=profile_hash,
            snapshot_hash=snapshot_hash,
            seed=seed,
            task_profile=profile,
            snapshot=snapshot,
            output={"url": staging_path.resolve().as_uri(), "contentType": "application/zip"},
        )
        service = FormulaModelService(
            Settings(
                allow_file_urls=True,
                temporary_root=output_dir / "tmp",
                model_cache_entries=2,
            )
        )
        response = service.train(request)
        data = staging_path.read_bytes()
        if sha256_bytes(data) != response.model_bundle_sha256:
            raise FormulaModelError(
                ErrorCode.HASH_MISMATCH,
                "training response does not match the generated model bundle",
            )
        destination = models_dir / (
            f"{profile.code}-{profile.version}-{response.model_bundle_sha256[:16]}.zip"
        )
        if destination.exists():
            if destination.read_bytes() != data:
                raise FormulaModelError(
                    ErrorCode.HASH_MISMATCH,
                    "content-addressed development model path contains different bytes",
                )
            staging_path.unlink()
        else:
            staging_path.replace(destination)
        index = DevModelIndex(
            task_profile_code=profile.code,
            task_profile_version=profile.version,
            task_profile_hash=profile_hash,
            schema_hash=profile.schema_hash,
            snapshot_id=response.snapshot_id,
            snapshot_hash=response.snapshot_hash,
            model_bundle_path=_relative_artifact_path(destination),
            model_bundle_hash=response.model_bundle_sha256,
            model_bundle_size=response.model_bundle_size,
            seed=seed,
            status=response.status,
            targets=response.targets,
            warnings=response.warnings,
        )
        _write_json(index_path, index.model_dump(mode="json", by_alias=True))
        return index, False
    finally:
        if staging_path.exists():
            staging_path.unlink()


def read_score_input(input_path: Path = DEFAULT_SCORE_INPUT) -> DevScoreInput:
    return DevScoreInput.model_validate_json(input_path.read_bytes())


def build_indexed_score_request(
    score_input: DevScoreInput,
    index_path: Path = DEFAULT_MODEL_INDEX,
) -> ScoreRequest:
    index, model_path, bundle = load_indexed_bundle(index_path)
    target_codes = score_input.target_codes or [item.code for item in bundle.profile.targets]
    return ScoreRequest(
        request_id=score_input.request_id,
        task_profile_hash=index.task_profile_hash,
        snapshot_hash=index.snapshot_hash,
        model_bundle_hash=index.model_bundle_hash,
        seed=index.seed,
        task_profile=bundle.profile,
        model_bundle={"url": model_path.as_uri(), "sha256": index.model_bundle_hash},
        rows=score_input.rows,
        target_codes=target_codes,
    )


def score_indexed_model(
    score_input: DevScoreInput,
    index_path: Path = DEFAULT_MODEL_INDEX,
) -> ScoreResponse:
    request = build_indexed_score_request(score_input, index_path)
    return FormulaModelService(Settings(allow_file_urls=True)).score(request)


def score_indexed_model_over_http(
    score_input: DevScoreInput,
    *,
    index_path: Path = DEFAULT_MODEL_INDEX,
    base_url: str = "http://127.0.0.1:8090",
    token: str | None = None,
    timeout_seconds: float = 60.0,
) -> ScoreResponse:
    request = build_indexed_score_request(score_input, index_path)
    headers = {"X-Request-Id": request.request_id}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = httpx.post(
        f"{base_url.rstrip('/')}/internal/v1/models/score",
        json=request.model_dump(mode="json", by_alias=True),
        headers=headers,
        timeout=timeout_seconds,
    )
    response.raise_for_status()
    return ScoreResponse.model_validate(response.json())


def write_json(path: Path, payload: dict[str, Any]) -> None:
    _write_json(path, payload)
