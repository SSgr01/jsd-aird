"""JSD host adapter. Another host injects HostGateway, not a Java implementation."""

from fastapi import HTTPException

from .security import issue


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
            raise HTTPException(response.status_code, "Business tool refused the request")
        return response.json()
