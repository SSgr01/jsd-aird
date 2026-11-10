"""Separately deployed OCI broker; never import business configuration."""

import asyncio
import base64
import hmac
import json
import shutil
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal
from uuid import UUID

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

from aird_agent.core.workspace import Workspace
from aird_agent.sandbox.containers import LABEL, ContainerPool, stage_inputs
from aird_agent.sandbox.jobs import BrokerJobs, TransferFile
from aird_agent.sandbox.streams import ExecutionStreams


class BrokerSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="JSD_AIRD_SANDBOX_", extra="ignore")
    token: SecretStr = Field(min_length=32)
    workspace_root: Path
    image: str = "jsd-aird-analysis:1"
    memory: str = "1g"
    cpus: str = "1"
    timeout_seconds: int = Field(default=600, ge=1, le=600)
    parallelism: int = Field(default=4, ge=1, le=8)
    startup_seconds: int = Field(default=45, ge=1, le=120)
    cleanup_seconds: int = Field(default=10, ge=1, le=30)
    output_bytes_per_stream: int = Field(default=8 * 1024 * 1024, ge=65536, le=64 * 1024 * 1024)
    oci_runtime: str | None = None


class Execution(BaseModel):
    model_config = ConfigDict(extra="forbid")
    organization_id: UUID
    run_id: UUID
    execution_id: str = Field(pattern="^[a-f0-9]{32}$")
    script: str = Field(max_length=65536)
    language: Literal["python", "shell"]
    timeout_ms: int | None = Field(default=None, ge=1, le=600000)


class TransferExecution(Execution):
    files: list[TransferFile] = Field(default_factory=list, max_length=50)


class ContainerRunner:
    def __init__(self, settings):
        self.settings = settings
        self.pool = ContainerPool(settings, self.command)

    async def start(self):
        await self.pool.start()

    async def close(self):
        await self.pool.close()

    def command(self, workspace, script, language, execution_id, *, prepared=False):
        if not shutil.which("docker"):
            raise HTTPException(503, "SANDBOX_UNAVAILABLE: no host-execution fallback")
        if language not in {"python", "shell"} or len(script.encode()) > 65536:
            raise HTTPException(400, "Invalid script")
        command = [
            "docker",
            "run",
            "--interactive",
            "--rm",
            "--name",
            "jsd-analysis-" + execution_id,
            "--label", LABEL + "=" + self.pool.scope,
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
        return command + ["--workdir=/work", self.settings.image, "--stdin" if prepared else payload]

    async def execute(self, workspace, request):
        if len(request.script.encode()) > 65536:
            raise HTTPException(400, "Invalid script")
        slot = None
        started = time.monotonic()
        timeout = min(self.settings.timeout_seconds, (request.timeout_ms or 600000) / 1000)
        streams = ExecutionStreams(workspace.root / ".streams", self.settings.output_bytes_per_stream) if workspace else None
        try:
            async with asyncio.timeout(timeout):
                slot = await self.pool.acquire()
                acquired = time.monotonic()
                # A cancelled to_thread future does not stop its thread. Finish the
                # bounded copy before releasing (and deleting) its staging tree.
                staging = asyncio.create_task(asyncio.to_thread(stage_inputs, workspace, slot.workspace))
                try:
                    await asyncio.shield(staging)
                except asyncio.CancelledError:
                    await staging
                    raise
                staged = time.monotonic()
                process = slot.process
                process.stdin.write(json.dumps({"script": request.script, "language": request.language}).encode() + b"\n")
                await process.stdin.drain()
                process.stdin.close()
                result = None
                while frame := await process.stdout.readline():
                    if len(frame) > 16 * 1024 * 1024 or result is not None:
                        raise ValueError("Invalid sandbox frame")
                    event = json.loads(frame)
                    if event.get("event") == "output" and event.get("stream") in {"stdout", "stderr"}:
                        content = base64.b64decode(event["base64"], validate=True)
                        if len(content) > 16384:
                            raise ValueError("Invalid output frame")
                        streams.append(event["stream"], content)
                    elif event.get("event") == "result":
                        result = event["result"]
                    else:
                        raise ValueError("Invalid sandbox event")
                await slot.stderr
                code = await process.wait()
            if code != 0:
                raise HTTPException(503, "SANDBOX_CONTAINER_FAILED")
            if not isinstance(result, dict) or not isinstance(result.get("exit_code"), int):
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
            text, truncated = streams.summary()
            log = workspace.write(
                f"output/execution-{request.execution_id}.json",
                json.dumps({"exit_code": result["exit_code"], "output": text}, ensure_ascii=False),
            )
            return {
                "exit_code": result["exit_code"],
                "output": text,
                "truncated": truncated,
                "streams": {name: streams.state(name) for name in ("stdout", "stderr")},
                "artifacts": artifacts,
                "log": log,
                "timings": {"container_startup_seconds": round(slot.startup_seconds, 3),
                    "ready_wait_seconds": round(acquired-started, 3), "input_stage_seconds": round(staged-acquired, 3),
                    "run_and_export_seconds": round(time.monotonic()-staged, 3), **result.get("timings", {})},
            }
        except (ValueError, KeyError, TypeError):
            raise HTTPException(502, "Invalid sandbox result") from None
        except TimeoutError:
            raise HTTPException(504, "SANDBOX_TIMEOUT" if slot else "SANDBOX_STARTUP_TIMEOUT") from None
        finally:
            if slot and not await asyncio.shield(self.pool.release(slot)):
                raise HTTPException(503, "SANDBOX_CLEANUP_PENDING")


def create_broker(settings=None):
    settings = settings or BrokerSettings()
    runner = ContainerRunner(settings)
    jobs = BrokerJobs(settings.workspace_root.resolve() / "executions", runner, settings.parallelism)
    @asynccontextmanager
    async def lifespan(app):
        if hasattr(runner, "start"):
            await runner.start()
        try:
            yield
        finally:
            await jobs.close()
            if hasattr(runner, "close"):
                await runner.close()
    app = FastAPI(title="JSD isolated sandbox broker", lifespan=lifespan)
    app.state.jobs, app.state.runner = jobs, runner
    limit = asyncio.Semaphore(settings.parallelism)

    def authorize(authorization):
        if not hmac.compare_digest(
            ("Bearer " + settings.token.get_secret_value()).encode(), authorization.encode()
        ):
            raise HTTPException(401, "Invalid sandbox credential")

    @app.get("/health")
    async def health(authorization: str = Header(default="")):
        authorize(authorization)
        ready = runner.pool.ready.qsize() if hasattr(runner, "pool") else 0
        return {"available": ready > 0, "ready": ready, "host_execution": False}

    @app.post("/execute")
    async def execute(request: Execution, authorization: str = Header(default="")):
        authorize(authorization)
        workspace = Workspace(settings.workspace_root, str(request.organization_id), str(request.run_id))
        workspace.prepare()
        async with limit:
            return await runner.execute(workspace, request)

    @app.post("/executions", status_code=202)
    async def submit(request: TransferExecution, authorization: str = Header(default="")):
        authorize(authorization)
        execution = Execution.model_validate(request.model_dump(exclude={"files"}))
        return await jobs.submit(execution, request.files)

    @app.get("/executions/{execution_id}")
    async def status(execution_id: str, authorization: str = Header(default="")):
        authorize(authorization)
        return jobs.get(execution_id)

    @app.get("/executions/{execution_id}/output")
    async def output(execution_id: str, stdout_offset: int = 0, stderr_offset: int = 0,
                     authorization: str = Header(default="")):
        authorize(authorization)
        return jobs.output(execution_id, stdout_offset, stderr_offset)

    @app.delete("/executions/{execution_id}")
    async def cancel(execution_id: str, authorization: str = Header(default="")):
        authorize(authorization)
        return await jobs.cancel(execution_id)

    return app


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(create_broker(), host="127.0.0.1", port=8094)
