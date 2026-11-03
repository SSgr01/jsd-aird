"""Durable broker jobs and bounded file transfer; no shared host filesystem is required."""

import asyncio
import base64
import contextlib
import hashlib
import json
import time
from pathlib import Path

from fastapi import HTTPException
from pydantic import Field

from aird_agent.core.contracts import Contract, digest
from aird_agent.core.workspace import Workspace, relative_path


class TransferFile(Contract):
    path: str
    base64: str = Field(max_length=14_000_000)
    sha256: str = Field(pattern="^[0-9a-f]{64}$")


def decode_files(files):
    decoded, paths, size = [], set(), 0
    if len(files) > 50:
        raise HTTPException(413, "SANDBOX_FILE_COUNT_EXCEEDED")
    for item in files:
        path = relative_path(item.path)
        if path.parts[0] not in {"input", "scratch"} or item.path in paths:
            raise HTTPException(422, "INVALID_SANDBOX_INPUT")
        try:
            content = base64.b64decode(item.base64, validate=True)
        except ValueError:
            raise HTTPException(422, "INVALID_SANDBOX_ENCODING") from None
        size += len(content)
        if size > 10 * 1024 * 1024:
            raise HTTPException(413, "SANDBOX_INPUT_BUDGET_EXCEEDED")
        if hashlib.sha256(content).hexdigest() != item.sha256:
            raise HTTPException(422, "SANDBOX_INPUT_HASH_MISMATCH")
        decoded.append((item.path, content))
        paths.add(item.path)
    return decoded


class BrokerJobs:
    def __init__(self, root: Path, runner, parallelism=4):
        self.root, self.runner = root, runner
        self.tasks = {}
        self.limit = asyncio.Semaphore(parallelism)
        self.lock = asyncio.Lock()
        root.mkdir(parents=True, exist_ok=True)
        # A broker restart never silently re-executes work whose outcome is unknown.
        for path in root.glob("*/receipt.json"):
            record = json.loads(path.read_text(encoding="utf-8"))
            if record["status"] in {"QUEUED", "RUNNING"}:
                record.update(status="UNKNOWN", error="BROKER_RESTART_REQUIRES_RECONCILIATION")
                self.save(path.parent.name, record)

    def path(self, execution_id):
        import re

        if not re.fullmatch(r"[a-f0-9]{32}", execution_id):
            raise HTTPException(422, "INVALID_EXECUTION_ID")
        path = self.root / execution_id
        if path.is_symlink() or path.resolve() != path.absolute():
            raise HTTPException(403, "INVALID_EXECUTION_ROOT")
        return path

    def save(self, execution_id, record):
        root = self.path(execution_id)
        root.mkdir(parents=True, exist_ok=True)
        temporary = root / "receipt.tmp"
        temporary.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
        temporary.replace(root / "receipt.json")

    def get(self, execution_id):
        path = self.path(execution_id) / "receipt.json"
        if not path.is_file():
            raise HTTPException(404, "EXECUTION_NOT_FOUND")
        return json.loads(path.read_text(encoding="utf-8"))

    async def submit(self, request, files):
        decoded = decode_files(files)
        signature = digest(
            {
                "request": request.model_dump(mode="json"),
                "files": [{"path": item.path, "sha256": item.sha256} for item in files],
            }
        )
        async with self.lock:
            root = self.path(request.execution_id)
            if (root / "receipt.json").exists():
                record = self.get(request.execution_id)
                if record["request_hash"] != signature:
                    raise HTTPException(409, "EXECUTION_ID_CONFLICT")
                return record
            workspace = Workspace(root, str(request.organization_id), str(request.run_id))
            workspace.prepare()
            for path, content in decoded:
                if path.startswith("input/"):
                    workspace.materialize(path, content)
                else:
                    workspace.write_bytes(path, content)
            record = {
                "execution_id": request.execution_id,
                "request_hash": signature,
                "status": "QUEUED",
                "inputs": [
                    {"path": f.path, "sha256": f.sha256, "bytes": len(content)}
                    for f, (_, content) in zip(files, decoded)
                ],
                "input_bytes": sum(len(content) for _, content in decoded),
                "created_at": time.time(),
            }
            self.save(request.execution_id, record)
            self.tasks[request.execution_id] = asyncio.create_task(self.execute(workspace, request))
            return record

    async def execute(self, workspace, request):
        record = self.get(request.execution_id)
        try:
            async with self.limit:
                record.update(status="RUNNING", started_at=time.time())
                self.save(request.execution_id, record)
                result = await self.runner.execute(workspace, request)
                files, size = [], 0
                for item in result.get("artifacts", []):
                    path = workspace.resolve(item["path"], writable=True)
                    if not item["path"].startswith("output/") or not path.is_file():
                        raise HTTPException(422, "INVALID_SANDBOX_OUTPUT")
                    content = path.read_bytes()
                    size += len(content)
                    if size > 10 * 1024 * 1024 or len(files) >= 50:
                        raise HTTPException(413, "SANDBOX_OUTPUT_BUDGET_EXCEEDED")
                    files.append(
                        {
                            "path": item["path"],
                            "base64": base64.b64encode(content).decode(),
                            "sha256": hashlib.sha256(content).hexdigest(),
                        }
                    )
                record.update(status="SUCCEEDED", result={**result, "files": files})
        except asyncio.CancelledError:
            record.update(status="CANCELLED")
        except Exception as error:
            record.update(
                status="FAILED", error=error.detail if isinstance(error, HTTPException) else "SANDBOX_FAILED"
            )
        finally:
            record["finished_at"] = time.time()
            self.save(request.execution_id, record)
            self.tasks.pop(request.execution_id, None)

    async def cancel(self, execution_id):
        async with self.lock:
            record = self.get(execution_id)
            task = self.tasks.get(execution_id)
            if task:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await task
                record = self.get(execution_id)
                if record["status"] in {"QUEUED", "RUNNING"}:
                    record.update(status="CANCELLED", finished_at=time.time())
                    self.save(execution_id, record)
            return self.get(execution_id) if task else record
