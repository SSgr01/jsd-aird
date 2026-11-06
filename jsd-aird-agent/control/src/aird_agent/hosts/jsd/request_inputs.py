"""Native claimed-message inputs. References and filters never become session defaults."""
import base64
import hashlib
import json
import re
from pathlib import PurePath

from fastapi import HTTPException
import httpx
from pydantic import ValidationError
from aird_agent.server.contracts import ContextRef
from aird_agent.core.contracts import digest

MARKER = re.compile(r"\[([^\]\n]{1,240})\]\(jsd-resource:([A-Za-z0-9_-]{1,2048})\)")

def request_inputs(text):
    refs, documents = [], []
    def resolve(match):
        try:
            token = match[2]
            data = json.loads(base64.b64decode(token + "=" * (-len(token) % 4), altchars=b"-_", validate=True))
            if not isinstance(data, dict):
                raise ValueError("A reference must be an object")
            limit = data.pop("only", False)
            if not isinstance(limit, bool):
                raise ValueError("Invalid filter")
            ref = ContextRef.model_validate(data).model_dump(mode="json", exclude_none=True)
            if ref not in refs:
                refs.append(ref)
            if limit:
                if ref["kind"] != "KB_PUBLICATION":
                    raise ValueError("Only publication filters are supported")
                item = {"document_id": ref["resource_id"], "publication_id": ref["publication_id"]}
                if item not in documents:
                    documents.append(item)
            return "资料引用：" + match[1]
        except (ValueError, TypeError, KeyError, ValidationError) as error:
            raise HTTPException(422, "INVALID_RESOURCE_REFERENCE") from error
    cleaned = MARKER.sub(resolve, text)
    if "jsd-resource:" in cleaned or len(refs) > 100:
        raise HTTPException(422, "INVALID_RESOURCE_REFERENCE")
    return {"request": cleaned.strip() or "请处理这些资料", "context_refs": refs,
            "retrieval_scope": {"knowledge_documents": documents} if documents else {}}

async def native_documents(service, principal, conversation, attachments):
    """Parse document attachments from exact native bytes; general files stay with dsh."""
    if len(attachments) > 20:
        raise HTTPException(413, "ATTACHMENT_COUNT_EXCEEDED")
    documents, records = [], []
    for attachment in attachments:
        name = attachment["name"]
        media = {".pdf": "application/pdf", ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 ".txt": "text/plain"}.get(PurePath(name).suffix.lower())
        if media is None:
            continue
        data = base64.b64decode(attachment["base64"], validate=True)
        fingerprint = hashlib.sha256(data).hexdigest()
        if len(data) > 10 * 1024 * 1024 or "sha256:" + fingerprint != attachment["attachment_id"]:
            raise HTTPException(422, "NATIVE_ATTACHMENT_MISMATCH")
        # Java owns parsing reuse, audit and idempotency. No upload-panel scope mutation.
        try:
            result = await service.host_call(principal, "private_source_upload", {
                "conversation_id": conversation["id"], "name": name, "content_type": media,
                "base64": attachment["base64"], "key": "native:" + digest([conversation["id"], fingerprint, name, media])})
        except (HTTPException, httpx.HTTPError) as error:
            # Parsing enriches native attachments. An infrastructure outage must
            # not remove the original bytes or block native reading. IAM denials
            # remain fatal; they are never converted into a parser outage.
            if isinstance(error, HTTPException) and error.status_code < 500:
                raise
            result = {"status": "UNAVAILABLE"}
        records.append({"sha256": fingerprint, "name": name, "bytes": len(data),
            **{key: result[key] for key in ("id", "version_id", "status") if key in result}})
        if result.get("status") == "READY" and result["id"] not in documents:
            documents.append(result["id"])
    if records:
        async with service.store.transaction(principal, conversation["id"]) as conn:
            current = await service.store.get(principal, "conversation", conversation["id"], conn)
            current["private_source_ids"] = list(dict.fromkeys([*(current.get("private_source_ids") or []), *documents]))
            ledger = {(item["sha256"], item["name"]): item for item in current.get("native_attachments", [])}
            ledger.update({(item["sha256"], item["name"]): item for item in records})
            current["native_attachments"] = list(ledger.values())
            await service.store.update(principal, "conversation", current, conn)
    return documents
