from __future__ import annotations

import hashlib
import json
from typing import Any

from pydantic import BaseModel


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    if isinstance(value, BaseModel):
        value = value.model_dump(mode="json", by_alias=True)
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def canonical_sha256(value: Any) -> str:
    return sha256_bytes(canonical_json_bytes(value))


def snapshot_sha256(manifest_sha: str, measurements_sha: str, source_map_sha: str) -> str:
    return canonical_sha256(
        {
            "manifest.json": manifest_sha,
            "measurements.parquet": measurements_sha,
            "source-map.parquet": source_map_sha,
        }
    )
