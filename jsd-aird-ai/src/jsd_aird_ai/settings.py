from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _boolean(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    host: str = os.getenv("JSD_AIRD_AI_HOST", "127.0.0.1")
    port: int = int(os.getenv("JSD_AIRD_AI_PORT", "8090"))
    allow_file_urls: bool = _boolean("JSD_AIRD_AI_ALLOW_FILE_URLS")
    artifact_timeout_seconds: float = float(
        os.getenv("JSD_AIRD_AI_ARTIFACT_TIMEOUT_SECONDS", "60")
    )
    maximum_artifact_bytes: int = int(
        os.getenv("JSD_AIRD_AI_MAX_ARTIFACT_BYTES", str(512 * 1024 * 1024))
    )
    model_cache_entries: int = int(os.getenv("JSD_AIRD_AI_MODEL_CACHE_ENTRIES", "4"))
    model_threads: int = max(1, int(os.getenv("JSD_AIRD_AI_MODEL_THREADS", "2")))
    temporary_root: Path = Path(os.getenv("JSD_AIRD_AI_TMP", ".tmp/jsd-aird-ai"))
    internal_token: str | None = os.getenv("JSD_AIRD_AI_INTERNAL_TOKEN") or None
