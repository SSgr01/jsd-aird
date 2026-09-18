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
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.contracts_v2 import (
    ErrorResponseV2, ScoreRequestV2, ScoreResponseV2, TrainRequestV2, TrainResponseV2, ValidateRequestV2,
    ValidateResponseV2, ValidationFoldsRequestV2, ValidationFoldsResponseV2,
    RecommendRequestV2, RecommendResponseV2,
)
from jsd_aird_ai.service_v2 import FormulaModelV2Service
from jsd_aird_ai.settings import Settings


log = logging.getLogger("jsd_aird_ai")


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = settings or Settings()
    service_v2 = FormulaModelV2Service(resolved)
    app = FastAPI(
        title="JSD-AIRD Formula Model Service",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        openapi_url="/internal/v2/openapi.json",
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
        body = ErrorResponseV2(request_id=request_id or "unknown", code=str(exc.code),
                               message=exc.message, retryable=exc.retryable, detail=exc.details)
        return JSONResponse(status_code=status, content=body.model_dump(mode="json", by_alias=True))

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        # Keep signed artifact URLs and request payloads out of logs, but retain
        # field locations/types so Java/Python contract drift can be diagnosed.
        # Pydantic may put a non-JSON-serializable ValueError in ``ctx.error``.
        # Keep the location/message while returning a safe structured error to
        # Java clients and the browser.
        validation_errors = []
        for item in exc.errors():
            safe_item = {key: value for key, value in item.items() if key != "ctx"}
            if item.get("ctx"):
                safe_item["context"] = {
                    str(key): str(value) for key, value in item["ctx"].items()
                }
            validation_errors.append(safe_item)
        log.warning(
            "formula_model_request_validation_failed path=%s errors=%s",
            request.url.path,
            [
                {
                    "loc": item.get("loc"),
                    "type": item.get("type"),
                    "msg": item.get("msg"),
                }
                for item in validation_errors
            ],
        )
        body = ErrorResponseV2(request_id=request.headers.get("x-request-id") or "unknown",
                               code=str(ErrorCode.UNSUPPORTED_CONTRACT),
                               message="request does not satisfy formula-model.v2",
                               detail={"errors": validation_errors})
        return JSONResponse(status_code=422, content=body.model_dump(mode="json", by_alias=True))

    @app.exception_handler(Exception)
    async def unexpected_error(request: Request, exc: Exception) -> JSONResponse:
        request_id = request.headers.get("x-request-id")
        log.exception("formula_model_unexpected_error requestId=%s", request_id)
        body = ErrorResponseV2(request_id=request_id or "unknown",
                               code=str(ErrorCode.INTERNAL_COMPUTE_ERROR),
                               message="formula model computation failed", retryable=False)
        return JSONResponse(status_code=500, content=body.model_dump(mode="json", by_alias=True))

    @app.get("/internal/v2/health/live")
    def live() -> dict:
        return {"status": "UP", "serviceVersion": __version__}

    @app.get("/internal/v2/health/ready")
    def ready() -> dict:
        return {
            "status": "UP",
            "contractVersion": "formula-model.v2",
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
        "/internal/v2/snapshots/validate",
        response_model=ValidateResponseV2,
        dependencies=secured,
    )
    def validate_snapshot_v2(payload: ValidateRequestV2) -> ValidateResponseV2:
        return service_v2.validate_snapshot(payload)

    @app.post(
        "/internal/v2/snapshots/validation-folds",
        response_model=ValidationFoldsResponseV2,
        dependencies=secured,
    )
    def validation_folds_v2(payload: ValidationFoldsRequestV2) -> ValidationFoldsResponseV2:
        return service_v2.validation_folds(payload)

    @app.post(
        "/internal/v2/models/train",
        response_model=TrainResponseV2,
        dependencies=secured,
    )
    def train_v2(payload: TrainRequestV2) -> TrainResponseV2:
        return service_v2.train(payload)

    @app.post(
        "/internal/v2/models/score",
        response_model=ScoreResponseV2,
        dependencies=secured,
    )
    def score_v2(payload: ScoreRequestV2) -> ScoreResponseV2:
        return service_v2.score(payload)

    @app.post(
        "/internal/v2/models/recommend",
        response_model=RecommendResponseV2,
        dependencies=secured,
    )
    def recommend_v2(payload: RecommendRequestV2) -> RecommendResponseV2:
        return service_v2.recommend(payload)

    return app


app = create_app()
