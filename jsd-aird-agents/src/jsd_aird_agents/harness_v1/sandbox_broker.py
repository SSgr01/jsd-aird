"""Separately deployed OCI broker; never import business configuration."""

import asyncio
import base64
import contextlib
import hmac
import json
import shutil
from pathlib import Path
from typing import Literal
from uuid import UUID

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

from .workspace import Workspace


class BrokerSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="JSD_AIRD_SANDBOX_", extra="ignore")
    token: SecretStr = Field(min_length=32)
    workspace_root: Path
    image: str = "jsd-aird-analysis:1"
    memory: str = "1g"
    cpus: str = "1"
    timeout_seconds: int = Field(default=120, ge=1, le=600)
    parallelism: int = Field(default=4, ge=1, le=8)
    oci_runtime: str | None = None


class Execution(BaseModel):
    model_config = ConfigDict(extra="forbid")
    organization_id: UUID
    run_id: UUID
    execution_id: str = Field(pattern="^[a-f0-9]{32}$")
    script: str = Field(max_length=65536)
    language: Literal["python", "shell"]


class ContainerRunner:
    def __init__(self, settings):
        self.settings = settings

    def command(self, workspace, script, language, execution_id):
        if not shutil.which("docker"):
            raise HTTPException(503, "SANDBOX_UNAVAILABLE: no host-execution fallback")
        if language not in {"python", "shell"} or len(script.encode()) > 65536:
            raise HTTPException(400, "Invalid script")
        command = [
            "docker",
            "run",
            "--rm",
            "--name",
            "jsd-analysis-" + execution_id,
            "--network=none",
            "--read-only",
            "--user=10001:10001",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges:true",
            "--pids-limit=64",
            "--memory=" + self.settings.memory,
            "--cpus=" + self.settings.cpus,
            "--ulimit",
            "fsize=10485760:10485760",
        ]
        if self.settings.oci_runtime:
            command += ["--runtime", self.settings.oci_runtime]
        for path in ("/tmp", "/work/scratch", "/work/output"):
            command += ["--tmpfs", path + ":rw,noexec,nosuid,size=64m,uid=10001,gid=10001"]
        # All host mounts are read-only; no symlink race with a writable host dir.
        for source, target in (("input", "input"), ("scratch", "source-scratch")):
            command += [
                "--mount",
                f"type=bind,source={workspace.root / source},target=/work/{target},readonly",
            ]
        payload = base64.b64encode(json.dumps({"script": script, "language": language}).encode()).decode()
        return command + ["--workdir=/work", self.settings.image, payload]

    async def execute(self, workspace, request):
        command = self.command(workspace, request.script, request.language, request.execution_id)
        process = await asyncio.create_subprocess_exec(
            *command, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
        )

        async def bounded(stream, maximum):
            output = bytearray()
            while chunk := await stream.read(8192):
                output.extend(chunk)
                if len(output) > maximum:
                    raise HTTPException(413, "Sandbox transport output limit exceeded")
            return bytes(output)

        try:
            async with asyncio.timeout(self.settings.timeout_seconds):
                stdout, _ = await asyncio.gather(
                    bounded(process.stdout, 16 * 1024 * 1024), bounded(process.stderr, 65536)
                )
                code = await process.wait()
            if code != 0:
                raise HTTPException(503, "SANDBOX_CONTAINER_FAILED")
            result = json.loads(stdout)
            if not isinstance(result.get("exit_code"), int) or not isinstance(result.get("output"), str):
                raise ValueError()
            artifacts, total = [], 0
            if not isinstance(result.get("files"), list) or len(result["files"]) > 50:
                raise ValueError()
            # Validate every path and quota before materializing any output.
            decoded = []
            for item in result["files"]:
                path = "output/" + item["path"]
                workspace.resolve(path, writable=True)
                content = base64.b64decode(item["base64"], validate=True)
                total += len(content)
                if total > 10 * 1024 * 1024:
                    raise HTTPException(413, "Sandbox artifact quota exceeded")
                decoded.append((path, content))
            for path, content in decoded:
                artifacts.append(workspace.write_bytes(path, content))
            text = result["output"][:12000]
            log = workspace.write(
                f"output/execution-{request.execution_id}.json",
                json.dumps({"exit_code": result["exit_code"], "output": text}, ensure_ascii=False),
            )
            return {
                "exit_code": result["exit_code"],
                "output": text,
                "truncated": result.get("truncated", False),
                "artifacts": artifacts,
                "log": log,
            }
        except (ValueError, KeyError, TypeError):
            raise HTTPException(502, "Invalid sandbox result") from None
        except TimeoutError:
            raise HTTPException(504, "SANDBOX_TIMEOUT") from None
        finally:
            if process.returncode is None:
                cleanup = await asyncio.create_subprocess_exec(
                    "docker",
                    "kill",
                    "jsd-analysis-" + request.execution_id,
                    stdout=asyncio.subprocess.DEVNULL,
                    stderr=asyncio.subprocess.DEVNULL,
                )
                with contextlib.suppress(TimeoutError):
                    async with asyncio.timeout(5):
                        await cleanup.wait()
                with contextlib.suppress(ProcessLookupError):
                    process.kill()
                await process.wait()


def create_broker(settings=None):
    settings = settings or BrokerSettings()
    app = FastAPI(title="JSD isolated sandbox broker")
    runner = ContainerRunner(settings)
    limit = asyncio.Semaphore(settings.parallelism)

    def authorize(authorization):
        if not hmac.compare_digest(
            ("Bearer " + settings.token.get_secret_value()).encode(), authorization.encode()
        ):
            raise HTTPException(401, "Invalid sandbox credential")

    @app.get("/health")
    async def health(authorization: str = Header(default="")):
        authorize(authorization)
        return {"available": bool(shutil.which("docker")), "host_execution": False}

    @app.post("/execute")
    async def execute(request: Execution, authorization: str = Header(default="")):
        authorize(authorization)
        workspace = Workspace(settings.workspace_root, str(request.organization_id), str(request.run_id))
        workspace.prepare()
        async with limit:
            return await runner.execute(workspace, request)

    return app


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(create_broker(), host="127.0.0.1", port=8094)
