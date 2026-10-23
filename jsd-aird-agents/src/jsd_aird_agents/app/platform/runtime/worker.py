from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from uuid import uuid4

from jsd_aird_agents.contracts import (
    GRAPH_VERSION,
    LEGACY_GRAPH_VERSION,
    SKILLS_GRAPH_VERSION,
    Principal,
    RunContext,
)
from jsd_aird_agents.errors import AgentError

log = logging.getLogger(__name__)


def pending_interrupts(snapshot):
    return [i for task in snapshot.tasks for i in task.interrupts]


class Worker:
    def __init__(self, services, worker_id=None):
        self.services = services
        self.store, self.db = services.store, services.db
        self.settings, self.graph = services.settings, services.graph
        self.worker_id = worker_id or str(uuid4())

    async def reconcile_decisions(self):
        await self.services.actions.reconcile()

    async def tick(self):
        await self.db.execute(
            """INSERT INTO agent_runtime.workers(id) VALUES(%s)
            ON CONFLICT(id) DO UPDATE SET heartbeat_at=now()""",
            (self.worker_id,),
        )
        await self.reconcile_decisions()
        await self.services.monitoring.dispatch()
        run = await self.store.claim(self.worker_id)
        if not run:
            return False
        try:
            principal = self.services.identity.lookup(run["actor_id"])
        except AgentError as exc:
            # A disabled fixture user has no execution context. Construct only enough identity to finalize its lease.
            dead = RunContext(
                Principal(run["organization_id"], run["actor_id"], (), ()),
                run["conversation_id"],
                run["id"],
                run["project_id"],
                self.worker_id,
                run["lease_epoch"],
            )
            await self.store.finish(
                dead, "FAILED", error={"code": exc.code, "message": exc.message, "retryable": False}
            )
            return True
        ctx = RunContext(
            principal,
            run["conversation_id"],
            run["id"],
            run["project_id"],
            self.worker_id,
            run["lease_epoch"],
            pack_id=run["pack_ids"][0],
            pack_ids=tuple(run["pack_ids"]),
            scopes=run["resource_scopes"],
        )
        async with self.db.pool.connection() as guard:
            lock = await (
                await guard.execute(
                    "SELECT pg_try_advisory_lock(hashtextextended(%s,1)) AS locked", (ctx.conversation_id,)
                )
            ).fetchone()
            if not lock["locked"]:
                await self.db.execute(
                    """UPDATE agent_runtime.runs SET status='QUEUED',worker_id=NULL,lease_until=NULL
                    WHERE id=%s AND worker_id=%s AND lease_epoch=%s""",
                    (ctx.run_id, self.worker_id, ctx.lease_epoch),
                )
                return True
            try:
                await self._execute(ctx, run)
            finally:
                await guard.execute(
                    "SELECT pg_advisory_unlock(hashtextextended(%s,1))", (ctx.conversation_id,)
                )
        await self.services.monitoring.dispatch()
        return True

    async def _heartbeat(self, ctx):
        while True:
            await self.store.heartbeat(ctx.run_id, self.worker_id, ctx.lease_epoch)
            await asyncio.sleep(self.settings.worker_heartbeat_seconds)

    async def _execute_task(self, ctx, run):
        from jsd_aird_agents.app.platform.runtime.execution import require_model_config, requires_model

        started = time.monotonic()
        task = heartbeat = None
        try:
            await self.store.check_active(ctx)
            require_model_config(self.settings, run)
            if requires_model(run):
                await self.services.skills.ensure_usable(ctx)
            binding = self.services.task_binding(run)
            remaining = self.settings.max_active_seconds - run["active_seconds"]
            if remaining <= 0:
                raise AgentError("BUDGET_EXCEEDED", "Run 活动时长预算耗尽", 409)
            await self.store.event(
                ctx.run_id,
                "run.started",
                {"worker_id": self.worker_id, "execution_kind": "task"},
                key=f"run.started:{ctx.lease_epoch}",
            )
            heartbeat = asyncio.create_task(self._heartbeat(ctx))
            task = asyncio.create_task(binding.factory(self.services).execute(ctx))
            async with asyncio.timeout(remaining):
                done, _ = await asyncio.wait({task, heartbeat}, return_when=asyncio.FIRST_COMPLETED)
                if heartbeat in done:
                    await heartbeat
                result = await task
            await self.store.check_active(ctx)
            await self._complete(ctx, result, time.monotonic() - started)
        except asyncio.CancelledError:
            await self.db.execute(
                """UPDATE agent_runtime.runs SET status='QUEUED',worker_id=NULL,lease_until=NULL
                WHERE id=%s AND worker_id=%s AND lease_epoch=%s""",
                (ctx.run_id, self.worker_id, ctx.lease_epoch),
            )
            raise
        except Exception as exc:
            if isinstance(exc, AgentError):
                error = exc
            elif isinstance(exc, TimeoutError):
                error = AgentError("BUDGET_EXCEEDED", "Run 活动时长预算耗尽", 409)
            else:
                log.error("task_failure run_id=%s exception_type=%s", ctx.run_id, type(exc).__name__)
                error = AgentError("INTERNAL_ERROR", "固定任务可从已完成步骤重试", 500, retryable=True)
            await self.store.finish(
                ctx,
                "CANCELLED" if error.code == "CANCELLED" else "FAILED",
                error={"code": error.code, "message": error.message, "retryable": error.retryable},
                elapsed=time.monotonic() - started,
            )
        finally:
            for pending in (task, heartbeat):
                if pending:
                    pending.cancel()
                    with contextlib.suppress(asyncio.CancelledError, Exception):
                        await pending

    async def _execute(self, ctx, run):
        if run["execution_kind"] == "task":
            return await self._execute_task(ctx, run)
        return await self._execute_graph(ctx, run)

    async def _execute_graph(self, ctx, run):
        from langgraph.types import Command

        from jsd_aird_agents.app.platform.runtime.execution import require_model_config

        config = {"configurable": {"thread_id": ctx.conversation_id}, "recursion_limit": 150}
        started = time.monotonic()
        heartbeat = task = None
        try:
            await self.store.check_active(ctx)
            await self.services.skills.ensure_usable(ctx)
            require_model_config(self.settings, run)
            graph = await self.services.ensure_graph(run)
            if run["graph_version"] not in {GRAPH_VERSION, LEGACY_GRAPH_VERSION, SKILLS_GRAPH_VERSION}:
                raise AgentError("GRAPH_VERSION_MISMATCH", "该执行需要对应版本的 Graph", 409)
            snapshot = await graph.aget_state(config)
            same_run = snapshot.values.get("run_id") == ctx.run_id
            interrupts = pending_interrupts(snapshot) if same_run else []
            if interrupts and not run["resume"]:
                await self._project_interrupt(ctx, interrupts[0])
                return
            if same_run and not snapshot.next and snapshot.values.get("final_result"):
                await self._complete(ctx, snapshot.values["final_result"], 0)
                return
            if interrupts:
                value = Command(resume=run["resume"])
            elif same_run:
                value = None
            else:
                value = {"run_id": ctx.run_id, "conversation_id": ctx.conversation_id}
            await self.store.event(
                ctx.run_id, "run.started", {"worker_id": self.worker_id}, key=f"run.started:{ctx.lease_epoch}"
            )
            remaining = self.settings.max_active_seconds - run["active_seconds"]
            if remaining <= 0:
                raise AgentError("BUDGET_EXCEEDED", "Run 活动时长预算耗尽", 409)
            heartbeat = asyncio.create_task(self._heartbeat(ctx))
            task = asyncio.create_task(graph.ainvoke(value, config, context=ctx, durability="sync"))
            async with asyncio.timeout(remaining):
                done, _ = await asyncio.wait({task, heartbeat}, return_when=asyncio.FIRST_COMPLETED)
                if heartbeat in done:
                    await heartbeat
                await task
            await self.store.check_active(ctx)
            snapshot = await graph.aget_state(config)
            interrupts = pending_interrupts(snapshot)
            elapsed = time.monotonic() - started
            if interrupts:
                await self._project_interrupt(ctx, interrupts[0], elapsed)
            elif snapshot.values.get("final_result"):
                await self._complete(ctx, snapshot.values["final_result"], elapsed)
            else:
                raise AgentError("INCOMPLETE_GRAPH", "工作流未产生终态", 500, retryable=True)
        except asyncio.CancelledError:
            await self.db.execute(
                """UPDATE agent_runtime.runs SET status='QUEUED',worker_id=NULL,lease_until=NULL
                WHERE id=%s AND worker_id=%s AND lease_epoch=%s""",
                (ctx.run_id, self.worker_id, ctx.lease_epoch),
            )
            raise
        except Exception as exc:
            if isinstance(exc, AgentError):
                error = exc
            elif isinstance(exc, TimeoutError):
                error = AgentError("BUDGET_EXCEEDED", "Run 活动时长预算耗尽", 409)
            else:
                log.error("run_failure run_id=%s exception_type=%s", ctx.run_id, type(exc).__name__)
                error = AgentError(
                    "INTERNAL_ERROR", "执行出现内部错误，可从持久状态重试", 500, retryable=True
                )
            await self.store.finish(
                ctx,
                "CANCELLED" if error.code == "CANCELLED" else "FAILED",
                error={"code": error.code, "message": error.message, "retryable": error.retryable},
                elapsed=time.monotonic() - started,
            )
        finally:
            for pending in (task, heartbeat):
                if pending:
                    pending.cancel()
                    with contextlib.suppress(asyncio.CancelledError, Exception):
                        await pending

    async def _project_interrupt(self, ctx, item, elapsed=0):
        status = "WAITING_APPROVAL" if item.value["type"] == "APPROVAL_REQUIRED" else "WAITING_CLARIFICATION"
        await self.store.finish(ctx, status, interrupt={"id": item.id, "value": item.value}, elapsed=elapsed)

    async def _complete(self, ctx, result, elapsed):
        await self.store.finish(ctx, "SUCCEEDED", result=result, elapsed=elapsed)

    async def run_forever(self):
        try:
            while True:
                try:
                    worked = await self.tick()
                except Exception as exc:
                    log.error("worker_iteration_failed exception_type=%s", type(exc).__name__)
                    worked = False
                if not worked:
                    await asyncio.sleep(self.settings.worker_poll_seconds)
        finally:
            await self.db.execute("DELETE FROM agent_runtime.workers WHERE id=%s", (self.worker_id,))
