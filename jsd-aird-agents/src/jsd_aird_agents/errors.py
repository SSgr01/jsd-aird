from typing import Any


class AgentError(Exception):
    def __init__(
        self, code: str, message: str, status: int = 422, *, retryable: bool = False, detail: Any = None
    ):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status
        self.retryable, self.detail = retryable, detail
