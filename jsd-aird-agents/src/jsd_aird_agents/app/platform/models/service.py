import asyncio
import time

from jsd_aird_agents.contracts import digest
from jsd_aird_agents.errors import AgentError


class ModelService:
    def __init__(self, adapter, store, settings, *, run_guard=None, packs=None):
        self.adapter, self.store, self.settings = adapter, store, settings
        self.run_guard = run_guard
        self.packs = packs

    async def generate(self, ctx, step_id, kind, payload, schema):
        await self.store.check_active(ctx)
        if self.run_guard:
            await self.run_guard(ctx)
        if self.packs:
            run = await self.store.run(ctx.run_id)
            payload = {
                **payload,
                "_pack_ids": run["pack_ids"],
                "_pack_prompt": "\n".join(
                    self.packs.require(pid).prompts.get(kind, "") for pid in run["pack_ids"]
                ),
            }
        request_hash = digest(
            [
                kind,
                payload,
                schema.model_json_schema(),
                self.settings.model_backend,
                self.settings.model_name,
                "prompts-v3-skills",
            ]
        )
        call_kind = f"model:{kind}"
        prior = await self.store.call(ctx.run_id, step_id, call_kind, request_hash)
        if prior and prior["status"] == "SUCCEEDED":
            return schema.model_validate(prior["result"])
        if self.adapter is None:
            raise AgentError("MODEL_NOT_CONFIGURED", "未配置模型，不会自动降级为模拟模型", 503)
        for attempt in range(self.settings.model_attempts):
            await self.store.check_active(ctx)
            await self.store.budget(ctx.run_id, "model_calls", self.settings.max_model_calls)
            await self.store.save_call(ctx.run_id, step_id, call_kind, request_hash, "RUNNING")
            started = time.monotonic()
            try:
                async with asyncio.timeout(self.settings.model_timeout_seconds):
                    result, usage = await self.adapter.generate(kind, payload, schema)
                result = schema.model_validate(result)
                await self.store.save_call(
                    ctx.run_id,
                    step_id,
                    call_kind,
                    request_hash,
                    "SUCCEEDED",
                    result.model_dump(mode="json"),
                    usage,
                )
                await self.store.event(
                    ctx.run_id,
                    "model.completed",
                    {
                        "step_id": step_id,
                        "kind": kind,
                        "usage": usage,
                        "latency_ms": round((time.monotonic() - started) * 1000),
                    },
                    key=f"model:{step_id}:{request_hash}:completed",
                )
                return result
            except (AgentError, TimeoutError) as exc:
                error = (
                    exc
                    if isinstance(exc, AgentError)
                    else AgentError("MODEL_TIMEOUT", "模型调用超时", 504, retryable=True)
                )
                await self.store.save_call(
                    ctx.run_id, step_id, call_kind, request_hash, "FAILED", error=error.code
                )
                if not error.retryable or attempt == self.settings.model_attempts - 1:
                    raise error
                retry_after = (error.detail or {}).get("retry_after")
                await asyncio.sleep(
                    min(retry_after, 30) if retry_after is not None else min(0.25 * (2**attempt), 2)
                )
