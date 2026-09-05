from __future__ import annotations

from collections import OrderedDict
from pathlib import Path
from threading import RLock
from typing import Generic, TypeVar
from urllib.parse import unquote, urlparse

import httpx

from jsd_aird_ai.contracts import ArtifactReadRef, ArtifactWriteRef, ModelBundleRef
from jsd_aird_ai.digests import sha256_bytes
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.settings import Settings


T = TypeVar("T")


class ArtifactClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def read(self, artifact: ArtifactReadRef | ModelBundleRef) -> bytes:
        url = str(artifact.url)
        data = self._read_url(url)
        actual = sha256_bytes(data)
        if actual != artifact.sha256:
            raise FormulaModelError(
                ErrorCode.HASH_MISMATCH,
                "artifact SHA-256 does not match the request",
                details={"expected": artifact.sha256, "actual": actual},
            )
        return data

    def write(self, target: ArtifactWriteRef, data: bytes) -> None:
        if len(data) > self._settings.maximum_artifact_bytes:
            raise FormulaModelError(
                ErrorCode.INTERNAL_COMPUTE_ERROR,
                "generated artifact exceeds configured size limit",
            )
        parsed = urlparse(str(target.url))
        if parsed.scheme == "file":
            path = self._file_path(parsed)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            return
        if parsed.scheme not in {"http", "https"}:
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                f"unsupported artifact URL scheme: {parsed.scheme}",
            )
        try:
            response = httpx.put(
                str(target.url),
                content=data,
                headers={"Content-Type": target.content_type},
                timeout=self._settings.artifact_timeout_seconds,
                follow_redirects=False,
            )
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise FormulaModelError(
                ErrorCode.INTERNAL_COMPUTE_ERROR,
                "unable to upload generated artifact",
                retryable=True,
            ) from exc

    def _read_url(self, url: str) -> bytes:
        parsed = urlparse(url)
        if parsed.scheme == "file":
            path = self._file_path(parsed)
            try:
                size = path.stat().st_size
                if size > self._settings.maximum_artifact_bytes:
                    raise FormulaModelError(
                        ErrorCode.INVALID_SNAPSHOT,
                        "artifact exceeds configured size limit",
                    )
                return path.read_bytes()
            except OSError as exc:
                raise FormulaModelError(
                    ErrorCode.INVALID_SNAPSHOT,
                    "unable to read local artifact",
                    details={"name": path.name},
                ) from exc
        if parsed.scheme not in {"http", "https"}:
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                f"unsupported artifact URL scheme: {parsed.scheme}",
            )
        try:
            with httpx.stream(
                "GET",
                url,
                timeout=self._settings.artifact_timeout_seconds,
                follow_redirects=False,
            ) as response:
                response.raise_for_status()
                chunks: list[bytes] = []
                size = 0
                for chunk in response.iter_bytes():
                    size += len(chunk)
                    if size > self._settings.maximum_artifact_bytes:
                        raise FormulaModelError(
                            ErrorCode.INVALID_SNAPSHOT,
                            "artifact exceeds configured size limit",
                        )
                    chunks.append(chunk)
                return b"".join(chunks)
        except FormulaModelError:
            raise
        except httpx.HTTPError as exc:
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "unable to download artifact",
                retryable=True,
            ) from exc

    def _file_path(self, parsed) -> Path:
        if not self._settings.allow_file_urls:
            raise FormulaModelError(
                ErrorCode.INVALID_SNAPSHOT,
                "file URLs are disabled",
            )
        raw = unquote(parsed.path)
        if parsed.netloc:
            raw = f"//{parsed.netloc}{raw}"
        if len(raw) >= 3 and raw[0] == "/" and raw[2] == ":":
            raw = raw[1:]
        return Path(raw).resolve(strict=False)


class LruCache(Generic[T]):
    def __init__(self, maximum_entries: int) -> None:
        self._maximum_entries = max(1, maximum_entries)
        self._values: OrderedDict[str, T] = OrderedDict()
        self._lock = RLock()

    def get(self, key: str) -> T | None:
        with self._lock:
            value = self._values.get(key)
            if value is not None:
                self._values.move_to_end(key)
            return value

    def put(self, key: str, value: T) -> None:
        with self._lock:
            self._values[key] = value
            self._values.move_to_end(key)
            while len(self._values) > self._maximum_entries:
                self._values.popitem(last=False)
