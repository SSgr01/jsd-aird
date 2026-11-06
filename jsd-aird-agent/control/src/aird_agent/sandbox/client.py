"""The control service cannot launch host processes or access a Docker socket."""

import asyncio
import base64
import hashlib
import logging
import re

import httpx
from fastapi import HTTPException

logger = logging.getLogger(__name__)


class Sandbox:
    def __init__(self, settings):
        self.settings = settings

    def inputs(self, workspace, paths):
        try:
            return self._inputs(workspace, paths)
        except OSError:
            raise HTTPException(422, "SANDBOX_INPUT_UNREADABLE: no execution was submitted") from None

    def _inputs(self, workspace, paths):
        if (
            not isinstance(paths, list)
            or any(not isinstance(p, str) for p in paths)
            or len(paths) != len(set(paths))
        ):
            raise HTTPException(422, "INVALID_SANDBOX_INPUT_SELECTION")
        entries, files, total = [], [], 0
        for name in paths:
            if (
                not isinstance(name, str)
                or not name.startswith(("input/", "scratch/"))
                or any(c in name for c in "*?[]")
            ):
                raise HTTPException(422, "INVALID_SANDBOX_INPUT_PATH")
            path = workspace.resolve(name)
            if not path.is_file():
                raise HTTPException(422, "SANDBOX_INPUT_FILE_MISSING: " + name)
            entries.append({"path": name, "bytes": path.stat().st_size, "container_path": "/work/" + name})
        total = sum(e["bytes"] for e in entries)
        if len(entries) > 50 or total > 10 * 1024 * 1024:
            raise HTTPException(
                413,
                {
                    "error": "SANDBOX_FILE_COUNT_EXCEEDED"
                    if len(entries) > 50
                    else "SANDBOX_INPUT_BYTES_EXCEEDED",
                    "files": entries,
                    "bytes": total,
                },
            )
        total = 0
        quote_ids = set()
        for entry in entries:
            with workspace.resolve(entry["path"]).open("rb") as stream:
                content = stream.read(10 * 1024 * 1024 + 1)
            total += len(content)
            if total > 10 * 1024 * 1024:
                raise HTTPException(413, "SANDBOX_INPUT_BYTES_EXCEEDED")
            quote_ids.update(
                re.findall(
                    r"(?<![a-zA-Z0-9_-])q[0-9a-f]{24}(?![a-zA-Z0-9_-])",
                    content.decode("utf8", errors="ignore"),
                )
            )
            entry.update(bytes=len(content), sha256=hashlib.sha256(content).hexdigest())
            files.append(
                {
                    "path": entry["path"],
                    "base64": base64.b64encode(content).decode(),
                    "sha256": entry["sha256"],
                }
            )
        return files, {
            "files": entries,
            "bytes": total,
            "file_count": len(entries),
            "quote_ids": sorted(quote_ids),
            "limits": {"files": 50, "bytes": 10 * 1024 * 1024},
            "workdir": "/work",
            "output_dir": "/work/output",
        }

    def prepare(self, workspace, input_paths):
        return self.inputs(workspace, input_paths)[1]

    async def execute(self, workspace, script, language, execution_id, input_paths):
        if not self.settings.sandbox_enabled:
            raise HTTPException(503, "SANDBOX_UNAVAILABLE: no host-execution fallback")
        if language not in {"python", "shell"} or len(script.encode()) > 65536:
            raise HTTPException(400, "Unsupported script or script too large")
        try:
            async with httpx.AsyncClient(
                timeout=self.settings.sandbox_timeout_seconds + 15, follow_redirects=False, trust_env=False
            ) as client:
                headers = {"Authorization": "Bearer " + self.settings.sandbox_secret.get_secret_value()}
                files, manifest = self.inputs(workspace, input_paths)
                endpoint = self.settings.sandbox_url + "/executions"
                async def receipt_response():
                    # Reading a durable receipt is safe after a lost response.
                    # Never resubmit a command whose acceptance is uncertain.
                    for attempt in range(3):
                        try:
                            value = await client.get(endpoint + "/" + execution_id, headers=headers)
                            if value.status_code == 200:
                                return value
                            if value.status_code not in {500, 502, 503, 504}:
                                raise HTTPException(503, "SANDBOX_RESULT_UNKNOWN")
                            failure = "HTTP_" + str(value.status_code)
                        except httpx.HTTPError as error:
                            failure = type(error).__name__
                        logger.warning("Sandbox receipt unavailable execution=%s attempt=%s failure=%s",
                            execution_id, attempt + 1, failure)
                        if attempt < 2:
                            await asyncio.sleep(0.2 * (attempt + 1))
                    raise HTTPException(503, "SANDBOX_RESULT_UNKNOWN")

                try:
                    response = await client.post(endpoint, headers=headers, json={
                        "organization_id": workspace.root.parent.name, "run_id": workspace.root.name,
                        "execution_id": execution_id, "script": script, "language": language, "files": files,
                    })
                except httpx.HTTPError as error:
                    logger.warning("Sandbox submission response lost execution=%s failure=%s",
                        execution_id, type(error).__name__)
                    response = await receipt_response()
                if response.status_code not in {200, 202}:
                    raise HTTPException(503, "SANDBOX_EXECUTION_REFUSED")
                try:
                    async with asyncio.timeout(self.settings.sandbox_timeout_seconds + 10):
                        while True:
                            record = response.json()
                            if record.get("execution_id") != execution_id:
                                raise HTTPException(502, "INVALID_SANDBOX_RESPONSE")
                            if record["status"] == "SUCCEEDED":
                                return {**self.accept_result(workspace, record), "inputs": manifest}
                            if record["status"] in {"FAILED", "CANCELLED"}:
                                return {
                                    "error": "SANDBOX_" + record["status"],
                                    "receipt": {
                                        k: record[k]
                                        for k in ("execution_id", "request_hash", "status", "finished_at")
                                    },
                                    "inputs": manifest,
                                }
                            if record["status"] == "UNKNOWN":
                                raise HTTPException(503, "SANDBOX_UNKNOWN")
                            await asyncio.sleep(0.2)
                            response = await receipt_response()
                except (asyncio.CancelledError, TimeoutError):
                    try:
                        await asyncio.shield(client.delete(endpoint + "/" + execution_id, headers=headers))
                    except httpx.HTTPError:
                        pass
                    raise
        except TimeoutError:
            raise HTTPException(504, "SANDBOX_TIMEOUT_RESULT_UNKNOWN") from None
        except (ValueError, KeyError, TypeError):
            raise HTTPException(502, "INVALID_SANDBOX_RESPONSE") from None
        except httpx.HTTPError:
            raise HTTPException(503, "SANDBOX_RESULT_UNKNOWN") from None

    def accept_result(self, workspace, record):
        result = record["result"]
        decoded, size, paths = [], 0, set()
        for item in result.get("files", []):
            if not item["path"].startswith("output/") or item["path"] in paths:
                raise HTTPException(502, "INVALID_SANDBOX_OUTPUT")
            workspace.resolve(item["path"], writable=True)
            content = base64.b64decode(item["base64"], validate=True)
            size += len(content)
            if len(decoded) >= 50 or size > 10 * 1024 * 1024:
                raise HTTPException(413, "SANDBOX_OUTPUT_BUDGET_EXCEEDED")
            if hashlib.sha256(content).hexdigest() != item["sha256"]:
                raise HTTPException(502, "SANDBOX_OUTPUT_HASH_MISMATCH")
            decoded.append((item["path"], content))
            paths.add(item["path"])
        for path, content in decoded:
            workspace.write_bytes(path, content)
        return (
            {k: v for k, v in result.items() if k != "files"}
            | ({"error": "SANDBOX_SCRIPT_FAILED"} if result.get("exit_code") != 0 else {})
            | {"receipt": {k: record[k] for k in ("execution_id", "request_hash", "status", "finished_at")}}
        )

    async def reconcile(self, workspace, execution_id):
        if not self.settings.sandbox_enabled:
            return {"status": "UNKNOWN"}
        async with httpx.AsyncClient(timeout=10, follow_redirects=False, trust_env=False) as client:
            response = await client.get(
                self.settings.sandbox_url + "/executions/" + execution_id,
                headers={"Authorization": "Bearer " + self.settings.sandbox_secret.get_secret_value()},
            )
        if response.status_code != 200:
            return {"status": "UNKNOWN"}
        record = response.json()
        if record.get("execution_id") != execution_id:
            return {"status": "UNKNOWN"}
        if record.get("status") in {"FAILED", "CANCELLED"}:
            return {"status": "FAILED", "result": {"id": execution_id, "error": "SANDBOX_" + record["status"],
                "receipt": {key: record[key] for key in ("execution_id", "request_hash", "status", "finished_at")}}}
        if record.get("status") != "SUCCEEDED":
            return {"status": "UNKNOWN"}
        workspace.prepare()
        return {
            "status": "SUCCEEDED",
            "result": {"id": execution_id, **self.accept_result(workspace, record)},
        }

    async def cancel(self, execution_id):
        if not self.settings.sandbox_enabled:
            return
        async with httpx.AsyncClient(timeout=5, follow_redirects=False, trust_env=False) as client:
            await client.delete(
                self.settings.sandbox_url + "/executions/" + execution_id,
                headers={"Authorization": "Bearer " + self.settings.sandbox_secret.get_secret_value()},
            )
