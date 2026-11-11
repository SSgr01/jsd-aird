"""Opt-in real Hindsight 0.8.6 roundtrip; uses a fresh isolated owner and bank."""
import asyncio
import json
import os
import time
from pathlib import Path

import httpx
import pytest

from aird_agent.memory.client import Hindsight
from aird_agent.memory.service import Memory
from harness_tests.test_memory import memory  # noqa: F401, F811 - shared isolated fixture


@pytest.mark.live
@pytest.mark.skipif(os.environ.get("HARNESS_MEMORY_LIVE") != "true", reason="Set HARNESS_MEMORY_LIVE=true for an isolated real Hindsight roundtrip")
async def test_hindsight_roundtrip(memory, principal):  # noqa: F811
    async with httpx.AsyncClient(trust_env=False) as http:
        backend = Hindsight(memory.settings, http)
        memory.backend = backend
        bank = await memory.bank(principal)
        evidence = {"bank_id": bank, "hindsight": "0.8.6", "checks": {}, "operations": []}

        async def complete(receipt):
            deadline = time.monotonic() + 150
            while time.monotonic() < deadline:
                job = await memory.store.get(principal, "memory-job", receipt["operation_id"])
                if job["status"] in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                    assert job["status"] == "SUCCEEDED", job.get("error")
                    evidence["operations"].append({k: job.get(k) for k in ("id", "status", "usage", "usage_pending")})
                    return job
                await memory.process(job)
                await asyncio.sleep(2)
            pytest.fail("Hindsight operation did not settle within 150 seconds")

        started = time.monotonic()
        try:
            receipt = await memory.save(principal, "以后请始终用中文回答研发问题，先列结论，再列依据；对未测和零值加以区分。")
            await complete(receipt)
            memory = Memory(memory.service, backend)  # Reopen durable settings/jobs with no browser.
            recalled = await memory.search(principal, {"preferences_only": True})
            assert recalled["entries"] and "中文" in json.dumps(recalled["entries"], ensure_ascii=False)
            evidence["checks"]["chinese_recall_after_control_reopen"] = True
            evidence["snapshot"] = {k: recalled.get(k) for k in ("elapsed_ms", "usage", "usage_pending")}
            # Exercise the official retrieval/evidence contracts as well as the
            # startup snapshot. Permit cold local embedding initialization here.
            backend.settings = backend.settings.model_copy(update={"memory_recall_timeout_seconds": 30})
            related = await memory.search(principal, {"query": "用户要求使用什么语言回答研发问题？"})
            assert related["entries"]
            evidence["recall"] = {k: related.get(k) for k in ("elapsed_ms", "usage", "usage_pending")}
            reflection = await memory.search(principal, {"query": "根据已有记忆，简述用户对研发回答语言和结构的偏好。", "mode": "reflect"})
            assert reflection["entries"] and reflection.get("reflection"), reflection
            evidence["reflect"] = {k: reflection.get(k) for k in ("elapsed_ms", "usage", "usage_pending")}
            evidence["checks"]["recall_and_reflect_verified_sources"] = True
            entry = await memory.store.get(principal, "memory-entry", receipt["entry_id"])
            changed = await memory.manage(principal, {"action": "correct", "entry_id": entry["id"], "revision": entry["revision"],
                "text": "以后请始终用英文回答研发问题，并将依据放在结论之前。"})
            assert not (await memory.validate(principal, {"entries": [{"id": entry["id"], "content_revision": entry["content_revision"]}]}))["valid"]
            await complete(changed)
            recalled = await memory.search(principal, {"preferences_only": True})
            assert recalled["entries"] and "英文" in json.dumps(recalled["entries"], ensure_ascii=False)
            assert "中文" not in json.dumps(recalled["entries"], ensure_ascii=False)
            evidence["checks"]["revision_correction_excludes_old_fact"] = True
            entry = await memory.store.get(principal, "memory-entry", receipt["entry_id"])
            deleted = await memory.forget(principal, entry)
            assert not (await memory.search(principal, {}))["entries"]
            await complete(deleted)
            assert await backend.document(bank, entry["document_id"]) is None
            evidence["checks"]["forget_exclusion_and_remote_deletion"] = True
            evidence["elapsed_ms"] = round((time.monotonic() - started) * 1000)
        finally:
            await backend.request("DELETE", bank, missing=True)
            evidence["isolated_bank_removed"] = True
            if output := os.environ.get("HARNESS_MEMORY_EVIDENCE"):
                Path(output).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
