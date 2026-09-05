from __future__ import annotations

import logging
from typing import Annotated

import baybe
import catboost
import lightgbm
import pandas
import pyarrow
import sklearn
import xgboost
from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from jsd_aird_ai import __version__
from jsd_aird_ai.contracts import (
    CONTRACT_VERSION,
    ErrorResponse,
    RecommendRequest,
    RecommendResponse,
    ScoreRequest,
    ScoreResponse,
    SnapshotValidationResponse,
    TrainRequest,
    TrainResponse,
    ValidateSnapshotRequest,
)
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.service import FormulaModelService
from jsd_aird_ai.settings import Settings


log = logging.getLogger("jsd_aird_ai")


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = settings or Settings()
    service = FormulaModelService(resolved)
    app = FastAPI(
        title="JSD-AIRD Formula Model Service",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        openapi_url="/internal/v1/openapi.json",
    )

    def authorize(authorization: Annotated[str | None, Header()] = None) -> None:
        if resolved.internal_token is None:
            return
        if authorization != f"Bearer {resolved.internal_token}":
            raise FormulaModelError(
                ErrorCode.UNSUPPORTED_CONTRACT,
                "internal service authorization failed",
            )

    secured = [Depends(authorize)]

    @app.exception_handler(FormulaModelError)
    async def formula_error(request: Request, exc: FormulaModelError) -> JSONResponse:
        status = 500
        if exc.code in {
            ErrorCode.INVALID_SNAPSHOT,
            ErrorCode.HASH_MISMATCH,
            ErrorCode.UNSUPPORTED_CONTRACT,
            ErrorCode.INSUFFICIENT_DATA,
            ErrorCode.UNSUPPORTED_TARGET_TYPE,
            ErrorCode.CENSORED_MODEL_NOT_IMPLEMENTED,
            ErrorCode.NO_FEASIBLE_CANDIDATE,
        }:
            status = 422
        elif exc.code == ErrorCode.MODEL_NOT_READY:
            status = 409
        request_id = request.headers.get("x-request-id")
        log.warning("formula_model_error code=%s requestId=%s", exc.code, request_id)
        body = ErrorResponse(
            request_id=request_id,
            code=exc.code,
            message=exc.message,
            retryable=exc.retryable,
            details=exc.details,
        )
        return JSONResponse(status_code=status, content=body.model_dump(mode="json", by_alias=True))

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        body = ErrorResponse(
            request_id=request.headers.get("x-request-id"),
            code=ErrorCode.UNSUPPORTED_CONTRACT,
            message="request does not satisfy formula-model.v1",
            details={"errors": exc.errors(include_url=False, include_context=False)},
        )
        return JSONResponse(status_code=422, content=body.model_dump(mode="json", by_alias=True))

    @app.exception_handler(Exception)
    async def unexpected_error(request: Request, exc: Exception) -> JSONResponse:
        request_id = request.headers.get("x-request-id")
        log.exception("formula_model_unexpected_error requestId=%s", request_id)
        body = ErrorResponse(
            request_id=request_id,
            code=ErrorCode.INTERNAL_COMPUTE_ERROR,
            message="formula model computation failed",
            retryable=False,
        )
        return JSONResponse(status_code=500, content=body.model_dump(mode="json", by_alias=True))

    @app.get("/internal/v1/health/live")
    def live() -> dict:
        return {"status": "UP", "serviceVersion": __version__}

    @app.get("/internal/v1/health/ready")
    def ready() -> dict:
        return {
            "status": "UP",
            "contractVersion": CONTRACT_VERSION,
            "dependencies": {
                "baybe": baybe.__version__,
                "pandas": pandas.__version__,
                "pyarrow": pyarrow.__version__,
                "scikitLearn": sklearn.__version__,
                "lightgbm": lightgbm.__version__,
                "xgboost": xgboost.__version__,
                "catboost": catboost.__version__,
            },
        }

    @app.post(
        "/internal/v1/snapshots/validate",
        response_model=SnapshotValidationResponse,
        dependencies=secured,
    )
    def validate_snapshot(payload: ValidateSnapshotRequest) -> SnapshotValidationResponse:
        return service.validate_snapshot(payload)

    @app.post(
        "/internal/v1/models/train",
        response_model=TrainResponse,
        dependencies=secured,
    )
    def train(payload: TrainRequest) -> TrainResponse:
        return service.train(payload)

    @app.post(
        "/internal/v1/models/score",
        response_model=ScoreResponse,
        dependencies=secured,
    )
    def score(payload: ScoreRequest) -> ScoreResponse:
        return service.score(payload)

    @app.post(
        "/internal/v1/models/recommend",
        response_model=RecommendResponse,
        dependencies=secured,
    )
    def recommend(payload: RecommendRequest) -> RecommendResponse:
        return service.recommend(payload)

    return app


app = create_app()
