"""JSD host adapter. Another host injects HostGateway, not a Java implementation."""

import logging

logger = logging.getLogger(__name__)

from fastapi import HTTPException

from aird_agent.server.security import issue


class JsdHostGateway:
    def __init__(self, settings, http):
        self.settings, self.http = settings, http

    async def call(self, principal, action, arguments, run_id=None):
        ticket = issue(self.settings.shared_secret.get_secret_value(), principal, "java-tools", run_id=run_id)
        response = await self.http.post(
            self.settings.java_url + "/internal/v1/agent-tools",
            headers={"X-Jsd-Agent-Ticket": ticket},
            json={"action": action, "arguments": arguments or {}},
        )
        if response.status_code >= 400:
            try:
                code = response.json().get("code", "UNAVAILABLE")
            except ValueError:
                code = "UNAVAILABLE"
            # Do not log arguments, response bodies, credentials or source contents.
            logger.warning("Business host refused action=%s status=%s code=%s", action, response.status_code,
                           code if isinstance(code, str) and code.replace("_", "").isalnum() else "UNAVAILABLE")
            # Only expose these fixed recovery instructions; never forward arbitrary host exception text.
            body = response.text
            if "RETRIEVAL_SNAPSHOT_CHANGED" in body:
                raise HTTPException(409, "RETRIEVAL_SNAPSHOT_CHANGED: restart search at offset 0")
            if "RETRIEVAL_REWRITE_BUDGET_REQUIRED" in body:
                raise HTTPException(409, "RETRIEVAL_REWRITE_BUDGET_REQUIRED")
            if "Use at most eight search terms" in body:
                raise HTTPException(
                    422,
                    "RETRIEVAL_QUERY_LIMIT: use at most eight terms; select match_mode any/all instead of OR/AND syntax",
                )
            if response.status_code == 422:
                try:
                    envelope = response.json()
                    message = envelope.get("message", "")
                    if envelope.get("code") == "SYS_VALIDATION_ERROR" and isinstance(message, str):
                        raise HTTPException(422, "BUSINESS_VALIDATION: " + message[:1200])
                except ValueError:
                    pass
            raise HTTPException(response.status_code, "Business tool refused the request")
        return response.json()
