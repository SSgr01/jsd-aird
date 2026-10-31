"""The control service cannot launch host processes or access a Docker socket."""

import httpx
from fastapi import HTTPException


class Sandbox:
    def __init__(self, settings):
        self.settings = settings

    async def execute(self, workspace, script, language, execution_id):
        if not self.settings.sandbox_enabled:
            raise HTTPException(503, "SANDBOX_UNAVAILABLE: no host-execution fallback")
        if language not in {"python", "shell"} or len(script.encode()) > 65536:
            raise HTTPException(400, "Unsupported script or script too large")
        try:
            async with httpx.AsyncClient(
                timeout=self.settings.sandbox_timeout_seconds + 15, follow_redirects=False
            ) as client:
                response = await client.post(
                    self.settings.sandbox_url + "/execute",
                    headers={"Authorization": "Bearer " + self.settings.sandbox_secret.get_secret_value()},
                    json={
                        "organization_id": workspace.root.parent.name,
                        "run_id": workspace.root.name,
                        "execution_id": execution_id,
                        "script": script,
                        "language": language,
                    },
                )
            if response.status_code != 200:
                raise HTTPException(503, "SANDBOX_EXECUTION_REFUSED")
            return response.json()
        except httpx.HTTPError:
            raise HTTPException(503, "SANDBOX_RESULT_UNKNOWN") from None
