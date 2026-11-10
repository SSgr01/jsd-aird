"""Reusable file execution and registration, with injected host and source policy."""

import base64
import hashlib
from pathlib import PurePosixPath

from fastapi import HTTPException

from aird_agent.capabilities.files.definitions import FILE_TOOLS
from aird_agent.capabilities.files.quality import validate_format
from aird_agent.core.contracts import digest
from aird_agent.core.workspace import Workspace

FILE_TOOL_NAMES = frozenset(item["name"] for item in FILE_TOOLS)


class FileTools:
    def __init__(self, settings, store, *, host_call, sandbox, character_budget, source_policy):
        self.settings, self.store = settings, store
        self.host_call, self.sandbox = host_call, sandbox
        self.character_budget, self.source_policy = character_budget, source_policy

    async def dispatch(self, principal, run, name, args, call_id):
        workspace = Workspace(self.settings.storage_root / "workspaces", str(principal.organization_id), run["id"])
        workspace.prepare()
        if name != "artifact_save":
            raise HTTPException(400, "UNBOUND_FILE_TOOL")
        path = workspace.resolve(args["path"], writable=True)
        if not args["path"].startswith("output/") or not path.is_file() or path.stat().st_size > 10 * 1024 * 1024:
            raise HTTPException(400, "Register a bounded file from output")
        content = path.read_bytes()
        if len(content) > 10 * 1024 * 1024:
            raise HTTPException(400, "Register a bounded file from output")
        execution, inputs, input_contents = await self.lineage(principal, run, workspace, args["path"], content)
        if PurePosixPath(args["path"]).suffix.lower() in {".docx", ".xlsx", ".pdf"} and not execution:
            raise HTTPException(422, "ARTIFACT_FORMAT_REQUIRES_SANDBOX: generate and validate documents in the sandbox")
        parsed = validate_format(args["path"], content)
        source_inputs = inputs
        # Validate citations in the delivered text as well as the execution's
        # original inputs. A native write may have no execution receipt at all.
        if PurePosixPath(args["path"]).suffix.lower() in {".md", ".txt", ".csv", ".tsv", ".json", ".html"}:
            input_contents = [*input_contents, content]
            source_inputs = {**inputs, "files": [*inputs.get("files", []), {"path": args["path"]}]}
        provenance = self.source_policy(run, args, source_inputs, input_contents, parsed)
        native_sources = await self.attachment_sources(principal, run, inputs)
        provenance["source_refs"] = list({digest(source): source for source in [
            *provenance.get("source_refs", []), *native_sources,
        ]}.values())
        return await self.host_call(
            principal, "artifact_save",
            {
                "topic_id": run.get("topic_id"), "conversation_id": run["conversation_id"],
                **provenance, "run_id": run["id"], "name": path.name,
                **({"execution_receipt": execution} if execution else {}),
                "base64": base64.b64encode(content).decode(), "key": digest([run["id"], call_id]),
            },
            run["id"],
        )

    async def attachment_sources(self, principal, run, inputs):
        """Bind native binary inputs to real, authorized parsed versions.

        These are execution inputs, not model citations or a claim that the
        model read every node. No synthetic text hash or reading receipt is made.
        """
        hashes = {entry["sha256"] for entry in inputs.get("files", [])}
        if not hashes:
            return []
        conversation = await self.store.get(principal, "conversation", run["conversation_id"])
        sources = []
        for record in conversation.get("native_attachments", []):
            if record["sha256"] not in hashes or not record.get("id"):
                continue
            metadata = await self.host_call(principal, "private_source_get", {"document_id": record["id"]}, run["id"])
            if (metadata.get("sha256") != record["sha256"]
                    or metadata.get("version_id") != record.get("version_id")):
                raise HTTPException(409, "NATIVE_ATTACHMENT_SOURCE_CHANGED")
            if metadata.get("status") != "READY":
                continue  # exact original input hashes remain in the execution receipt
            offset = 0
            while True:
                page = await self.host_call(principal, "private_source_read", {
                    "document_id": record["id"], "conversation_id": run["conversation_id"],
                    "offset": offset, "limit": 12,
                }, run["id"])
                from aird_agent.evidence.registry import canonical_candidate
                for candidate in page.get("candidates", []):
                    source = canonical_candidate(candidate)["source"]
                    if source["version_id"] != record["version_id"]:
                        raise HTTPException(409, "NATIVE_ATTACHMENT_SOURCE_CHANGED")
                    sources.append(source)
                next_offset = page.get("next_offset")
                if next_offset is None:
                    break
                if not isinstance(next_offset, int) or next_offset <= offset or len(sources) > 500:
                    raise HTTPException(502, "INVALID_DOCUMENT_PAGINATION")
                offset = next_offset
        return sources

    async def lineage(self, principal, run, workspace, path, content):
        async with self.store.pool.connection() as conn:
            pending = await (
                await conn.execute(
                    "SELECT c.call_id FROM agent_runtime.harness_calls c "
                    "JOIN agent_runtime.harness_records r ON r.id=c.run_id AND r.kind='run' "
                    "WHERE c.run_id=%s AND r.organization_id=%s AND r.actor_id=%s "
                    "AND c.tool_name='dsh_execute' AND c.status IN ('RUNNING','UNKNOWN')",
                    (run["id"], *principal.key),
                )
            ).fetchall()
            if pending:
                raise HTTPException(409, "SANDBOX_RESULT_UNKNOWN: reconcile execution before registering outputs")
            receipts = await (
                await conn.execute(
                    "SELECT call_id,payload FROM agent_runtime.harness_receipts WHERE run_id=%s "
                    "AND organization_id=%s AND actor_id=%s ORDER BY created_at DESC",
                    (run["id"], *principal.key),
                )
            ).fetchall()
        sha = hashlib.sha256(content).hexdigest()
        for item in receipts:
            payload = item["payload"]
            outputs = [a for a in payload.get("artifacts", []) if a.get("path") == path]
            if not outputs:
                continue
            if payload.get("receipt", {}).get("status") != "SUCCEEDED" or payload.get("exit_code") != 0:
                raise HTTPException(422, "SANDBOX_EXECUTION_NOT_SUCCEEDED: regenerate the file")
            if not any(a.get("sha256") == sha for a in outputs):
                raise HTTPException(422, "ARTIFACT_EXECUTION_HASH_MISMATCH: regenerate the file with native bash")
            inputs = payload.get("inputs") or {}
            contents = []
            for entry in inputs.get("files", []):
                try:
                    original = workspace.resolve(entry["path"]).read_bytes()
                except OSError as error:
                    raise HTTPException(422, "ARTIFACT_INPUT_CHANGED: regenerate with available inputs") from error
                if hashlib.sha256(original).hexdigest() != entry["sha256"]:
                    raise HTTPException(422, "ARTIFACT_INPUT_CHANGED: regenerate after changing inputs")
                contents.append(original)
            execution = {
                "call_id": item["call_id"], **payload["receipt"],
                "inputs": inputs, "artifact_sha256": sha,
            }
            return execution, inputs, contents
        return None, {}, []
