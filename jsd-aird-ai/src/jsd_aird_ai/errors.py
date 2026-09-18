from __future__ import annotations

from enum import StrEnum
from typing import Any


class ErrorCode(StrEnum):
    INVALID_SNAPSHOT = "INVALID_SNAPSHOT"
    HASH_MISMATCH = "HASH_MISMATCH"
    UNSUPPORTED_CONTRACT = "UNSUPPORTED_CONTRACT"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    UNSUPPORTED_TARGET_TYPE = "UNSUPPORTED_TARGET_TYPE"
    CENSORED_MODEL_NOT_IMPLEMENTED = "CENSORED_MODEL_NOT_IMPLEMENTED"
    MODEL_NOT_READY = "MODEL_NOT_READY"
    NO_FEASIBLE_CANDIDATE = "NO_FEASIBLE_CANDIDATE"
    INTERNAL_COMPUTE_ERROR = "INTERNAL_COMPUTE_ERROR"


class FormulaModelError(RuntimeError):
    def __init__(
        self,
        code: ErrorCode,
        message: str,
        *,
        details: dict[str, Any] | None = None,
        retryable: bool = False,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details or {}
        self.retryable = retryable
