"""Short-lived delegated identities, with independent audiences for each service."""

import base64
import hashlib
import hmac
import json
import time

from fastapi import HTTPException

from .contracts import Principal


def encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def issue(
    secret: str, principal: Principal, audience: str, *, run_id: str | None = None, ttl: int = 3600
) -> str:
    now = int(time.time())
    payload = {**principal.model_dump(mode="json"), "aud": audience, "iat": now, "exp": now + ttl}
    if run_id:
        payload["run_id"] = run_id
    body = encode(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode())
    return body + "." + encode(hmac.new(secret.encode(), body.encode(), hashlib.sha256).digest())


def verify(secret: str, ticket: str, audience: str) -> tuple[Principal, dict]:
    try:
        if len(ticket) > 8192:
            raise ValueError()
        body, signature = ticket.split(".")
        expected = encode(hmac.new(secret.encode(), body.encode(), hashlib.sha256).digest())
        if not hmac.compare_digest(expected, signature):
            raise ValueError()
        claims = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
        now = int(time.time())
        if claims["aud"] != audience or claims["exp"] <= now or claims["iat"] > now + 30:
            raise ValueError()
        if claims["exp"] - claims["iat"] > 3600:
            raise ValueError()
        principal = Principal.model_validate({k: claims[k] for k in Principal.model_fields})
        return principal, claims
    except (ValueError, KeyError, TypeError):
        raise HTTPException(401, "Invalid or expired service delegation") from None
