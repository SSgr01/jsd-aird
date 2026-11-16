"""Keep an observed result separate from the container's cleanup barrier."""
from fastapi import HTTPException


class CleanupPending(HTTPException):
    def __init__(self, container, *, result=None, failure=None, observed_exit_code=None):
        super().__init__(503, 'SANDBOX_CLEANUP_PENDING')
        self.container = container
        self.result = result
        self.failure = failure
        self.observed_exit_code = observed_exit_code
