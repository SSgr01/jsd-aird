"""Durable run admission. This scheduler grants slots; DeepSeek alone drives model execution."""

import asyncio
import contextlib
import logging
import time
from uuid import uuid4

from fastapi import HTTPException
from psycopg.types.json import Jsonb

from aird_agent.core.contracts import Principal

logger = logging.getLogger(__name__)
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}
LOCK = 1847091011


class RunAdmission:
    def __init__(self, service):
        self.service, self.store, self.settings = service, service.store, service.settings
        self.wake = asyncio.Event()
        self.worker_id = str(uuid4())
        self.tasks = set()
        self.inflight = set()

    def start(self, key, operation):
        if key in self.inflight:
            return
        self.inflight.add(key)
        task = asyncio.create_task(operation())
        self.tasks.add(task)

        def finished(task):
            self.tasks.discard(task)
            self.inflight.discard(key)
            if not task.cancelled() and (error := task.exception()):
                logger.warning("Admission operation pending run=%s failure=%s", key[1], type(error).__name__)

        task.add_done_callback(finished)

    async def enqueue(self, principal, run, *, origin):
        async with self.store.transaction(principal, run["id"]) as conn:
            await conn.execute("SELECT pg_advisory_xact_lock(%s)", (LOCK,))
            current = await self.store.get(principal, "run", run["id"], conn)
            if current["status"] in TERMINAL:
                raise HTTPException(409, "RUN_REQUIRES_EXPLICIT_RESUME")
            await conn.execute(
                "INSERT INTO agent_runtime.harness_admission(run_id,organization_id,actor_id,principal,origin,state) "
                "VALUES(%s,%s,%s,%s,%s,'QUEUED') ON CONFLICT(run_id) DO UPDATE SET "
                "principal=EXCLUDED.principal,origin=CASE WHEN harness_admission.origin='api' THEN 'api' ELSE EXCLUDED.origin END,"
                "state=CASE WHEN harness_admission.state IN ('RELEASED','CANCELLED') THEN 'QUEUED' ELSE harness_admission.state END,"
                "enqueued_at=CASE WHEN harness_admission.state IN ('RELEASED','CANCELLED') THEN now() ELSE harness_admission.enqueued_at END,"
                "retry_at=CASE WHEN harness_admission.state IN ('RELEASED','CANCELLED') THEN now() ELSE harness_admission.retry_at END,"
                "dispatch_done=CASE WHEN harness_admission.state IN ('RELEASED','CANCELLED') THEN false ELSE harness_admission.dispatch_done END,"
                "attempts=CASE WHEN harness_admission.state IN ('RELEASED','CANCELLED') THEN 0 ELSE harness_admission.attempts END",
                (run["id"], *principal.key, Jsonb(principal.model_dump(mode="json")), origin),
            )
        self.wake.set()
        return await self.state(principal, run["id"])

    async def state(self, principal, rid):
        run = await self.store.get(principal, "run", rid)
        async with self.store.pool.connection() as conn:
            row = await (
                await conn.execute(
                    "SELECT state,epoch FROM agent_runtime.harness_admission WHERE run_id=%s AND organization_id=%s AND actor_id=%s",
                    (rid, *principal.key),
                )
            ).fetchone()
        return {
            "run_id": rid,
            "status": run["status"],
            "admission": row["state"] if row else "QUEUED",
            "epoch": row["epoch"] if row else run["epoch"],
            "heartbeat_seconds": self.settings.admission_heartbeat_seconds,
            "lease_seconds": self.settings.admission_lease_seconds,
        }

    async def head(self, conn):
        # Persisted service order gives round-robin organizations, then users;
        # a user's eligible requests retain FIFO ordering across processes.
        return await (
            await conn.execute(
                "SELECT q.* FROM agent_runtime.harness_admission q "
                "WHERE q.state='QUEUED' AND q.retry_at<=now() "
                "AND (SELECT count(*) FROM agent_runtime.harness_admission a WHERE a.state IN ('GRANTED','RELEASING'))<%s "
                "AND (SELECT count(*) FROM agent_runtime.harness_admission a WHERE a.state IN ('GRANTED','RELEASING') "
                "AND a.organization_id=q.organization_id)<%s "
                "AND (SELECT count(*) FROM agent_runtime.harness_admission a WHERE a.state IN ('GRANTED','RELEASING') "
                "AND a.organization_id=q.organization_id AND a.actor_id=q.actor_id)<%s "
                "ORDER BY coalesce((SELECT max(a.last_grant_order) FROM agent_runtime.harness_admission a WHERE a.organization_id=q.organization_id),0),"
                "coalesce((SELECT max(a.last_grant_order) FROM agent_runtime.harness_admission a WHERE a.organization_id=q.organization_id AND a.actor_id=q.actor_id),0),"
                "q.enqueued_at,q.run_id LIMIT 1",
                (
                    self.settings.max_running_runs,
                    self.settings.max_running_runs_per_organization or self.settings.max_running_runs,
                    self.settings.max_running_runs_per_user,
                ),
            )
        ).fetchone()

    async def grant(self, principal, rid):
        candidate = await self.store.get(principal, "run", rid)
        await self.service.authorized_topic(principal, candidate, execution=False)
        async with self.store.transaction(principal, rid) as conn:
            await conn.execute("SELECT pg_advisory_xact_lock(%s)", (LOCK,))
            row = await (
                await conn.execute(
                    "SELECT *,lease_until>now() AS live FROM agent_runtime.harness_admission WHERE run_id=%s",
                    (rid,),
                )
            ).fetchone()
            if row and row["state"] == "GRANTED":
                return bool(row["live"])
            first = await self.head(conn)
            if not first or str(first["run_id"]) != rid:
                return False
            run = await self.store.get(principal, "run", rid, conn)
            if run["status"] in TERMINAL:
                await conn.execute(
                    "UPDATE agent_runtime.harness_admission SET state='CANCELLED' WHERE run_id=%s", (rid,)
                )
                return False
            run["epoch"] += 1
            await self.store.update(principal, "run", run, conn)
            await conn.execute(
                "UPDATE agent_runtime.harness_admission SET state='GRANTED',epoch=%s,granted_at=now(),"
                "lease_until=now()+(%s * interval '1 second'),last_grant_order=nextval('agent_runtime.harness_admission_order'),"
                "dispatch_owner=NULL,dispatch_until=NULL,dispatch_done=false,recovery_owner=NULL,recovery_until=NULL WHERE run_id=%s",
                (run["epoch"], self.settings.admission_lease_seconds, rid),
            )
            await self.store.event(principal, rid, "run.admitted", {"status": "QUEUED"}, conn)
        self.wake.set()
        return True

    async def require(self, principal, rid, epoch, conn=None):
        if conn is None:
            async with self.store.pool.connection() as conn:
                return await self.require(principal, rid, epoch, conn)
        row = await (
            await conn.execute(
                "SELECT state,epoch,lease_until>now() AS live FROM agent_runtime.harness_admission "
                "WHERE run_id=%s AND organization_id=%s AND actor_id=%s",
                (rid, *principal.key),
            )
        ).fetchone()
        if not row or row["state"] != "GRANTED" or row["epoch"] != epoch or not row["live"]:
            raise HTTPException(409, "RUN_ADMISSION_REQUIRED")

    async def renew(self, principal, rid, epoch):
        async with self.store.transaction(principal, rid) as conn:
            await self.require(principal, rid, epoch, conn)
            run = await self.store.get(principal, "run", rid, conn)
            if run["status"] in TERMINAL:
                return {"accepted": False, "terminal": True}
            await conn.execute(
                "UPDATE agent_runtime.harness_admission SET lease_until=now()+(%s * interval '1 second') WHERE run_id=%s",
                (self.settings.admission_lease_seconds, rid),
            )
        return {"accepted": True}

    async def release(self, principal, rid, epoch=None, *, cancelled=False, stopped=False):
        async with self.store.transaction(principal, rid) as conn:
            row = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.harness_admission WHERE run_id=%s FOR UPDATE", (rid,)
                )
            ).fetchone()
            if not row:
                return {"accepted": False}
            run = await self.store.get(principal, "run", rid, conn)
            # Cancellation can cross the instant a waiting ticket is granted.
            # Accept that one transition only while no native turn has begun;
            # never let an old generation cancel a recovered/running turn.
            if (
                epoch is not None
                and row["epoch"] != epoch
                and not (
                    cancelled
                    and run["status"] == "QUEUED"
                    and row["state"] == "GRANTED"
                    and row["epoch"] == epoch + 1
                )
            ):
                return {"accepted": False}
            if row["state"] in {"RELEASED", "CANCELLED"}:
                return {"accepted": True}
            if cancelled and run["status"] not in TERMINAL:
                if run.get("started_at"):
                    run["active_seconds"] += max(0, time.time() - run["started_at"])
                run.update(status="CANCELLED", started_at=None, error=None)
                await self.store.update(principal, "run", run, conn)
                await self.store.event(principal, rid, "run.cancelled", {}, conn)
            state = "RELEASED" if stopped or row["state"] == "QUEUED" else "RELEASING"
            await conn.execute(
                "UPDATE agent_runtime.harness_admission SET state=%s WHERE run_id=%s", (state, rid)
            )
        self.wake.set()
        return {"accepted": True}

    async def dispatch(self, row):
        principal = Principal.model_validate(row["principal"])
        rid = str(row["run_id"])
        heartbeat = None
        try:
            # A database claim prevents concurrent workers from issuing prompts;
            # the native requestId deduplicates a lost acknowledgement on retry.
            async with self.store.transaction(principal, rid) as conn:
                claimed = await (
                    await conn.execute(
                        "UPDATE agent_runtime.harness_admission SET dispatch_owner=%s,"
                        "dispatch_until=now()+interval '150 seconds' WHERE run_id=%s AND state='GRANTED' "
                        "AND epoch=%s AND lease_until>now() AND NOT dispatch_done "
                        "AND (dispatch_until IS NULL OR dispatch_until<now()) AND retry_at<=now() RETURNING run_id",
                        (self.worker_id, rid, row["epoch"]),
                    )
                ).fetchone()
                if not claimed:
                    return

            # Session creation or an uncertain prompt acknowledgement can take
            # longer than a lease. Keep admission alive until the host renews it.
            async def keep_alive():
                while True:
                    await asyncio.sleep(self.settings.admission_heartbeat_seconds)
                    try:
                        result = await self.renew(principal, rid, row["epoch"])
                        if result.get("terminal"):
                            return
                    except HTTPException:
                        return

            heartbeat = asyncio.create_task(keep_alive())
            run = await self.store.get(principal, "run", rid)
            if run.get("admission_resume"):
                native = await self.service.runtime(rid, "wake")
                if not native.get("pending") and not native.get("running"):
                    await self.service.runtime(
                        rid,
                        "resume",
                        {
                            "text": run["request"]
                            if not run.get("dsh_generation_started")
                            else "继续当前任务，先核对已有业务回执和已登记成果。",
                            "request_id": run["admission_resume"],
                        },
                    )
                async with self.store.transaction(principal, rid) as conn:
                    run = await self.store.get(principal, "run", rid, conn)
                    await self.require(principal, rid, row["epoch"], conn)
                    run.pop("admission_resume", None)
                    await self.store.update(principal, "run", run, conn)
            else:
                await self.service._dispatch_run(principal, rid)
            # The host now renews the lease; completed dispatch is never replayed.
            async with self.store.pool.connection() as conn:
                await conn.execute(
                    "UPDATE agent_runtime.harness_admission SET diagnostic='{}',dispatch_done=true,dispatch_until=NULL "
                    "WHERE run_id=%s AND dispatch_owner=%s AND epoch=%s",
                    (rid, self.worker_id, row["epoch"]),
                )
        except Exception as error:
            code = str(error.detail) if isinstance(error, HTTPException) else type(error).__name__
            async with self.store.transaction(principal, rid) as conn:
                ticket = await (
                    await conn.execute(
                        "SELECT epoch,attempts,dispatch_owner,state FROM agent_runtime.harness_admission WHERE run_id=%s",
                        (rid,),
                    )
                ).fetchone()
                if (
                    not ticket
                    or ticket["epoch"] != row["epoch"]
                    or str(ticket["dispatch_owner"]) != self.worker_id
                    or ticket["state"] != "GRANTED"
                ):
                    return
                run = await self.store.get(principal, "run", rid, conn)
                permanent = isinstance(error, HTTPException) and error.status_code in {
                    400,
                    401,
                    403,
                    404,
                    422,
                }
                exhausted = ticket["attempts"] >= 5
                terminal = run["status"] in TERMINAL or permanent or exhausted
                diagnostic = {"code": code, "phase": "dispatch", "attempt": ticket["attempts"] + 1}
                if terminal and run["status"] not in TERMINAL:
                    run.update(
                        status="FAILED", error="RUNTIME_EXECUTION_FAILED", failure_diagnostic=diagnostic
                    )
                    self.settle_time(run)
                    await self.store.update(principal, "run", run, conn)
                    await self.store.event(principal, rid, "run.failed", {"error": run["error"]}, conn)
                await conn.execute(
                    "UPDATE agent_runtime.harness_admission SET attempts=attempts+1,diagnostic=%s,dispatch_owner=NULL,"
                    "dispatch_until=NULL,state=%s,retry_at=now()+(least(30,power(2,least(attempts,5))) * interval '1 second'),"
                    "lease_until=now()+(%s * interval '1 second') WHERE run_id=%s",
                    (
                        Jsonb(diagnostic),
                        "RELEASING"
                        if terminal and run.get("dsh_session_id")
                        else "RELEASED"
                        if terminal
                        else "GRANTED",
                        self.settings.admission_lease_seconds,
                        rid,
                    ),
                )
            logger.warning("Run admission dispatch pending run=%s code=%s", rid, code)
        finally:
            if heartbeat:
                heartbeat.cancel()
                await asyncio.gather(heartbeat, return_exceptions=True)

    @staticmethod
    def settle_time(run):
        if run.get("started_at"):
            run["active_seconds"] += max(0, time.time() - run["started_at"])
        run["started_at"] = None

    async def reconcile(self, row):
        principal = Principal.model_validate(row["principal"])
        rid = str(row["run_id"])
        # Only one controller reconciles a ticket. Moving it to RELEASING fences
        # tool/model calls before consulting the host, while retaining its slot.
        async with self.store.transaction(principal, rid) as conn:
            claimed = await (
                await conn.execute(
                    "UPDATE agent_runtime.harness_admission SET state='RELEASING',"
                    "recovery_owner=%s,recovery_until=now()+interval '150 seconds' WHERE run_id=%s AND epoch=%s "
                    "AND (state='RELEASING' OR (state='GRANTED' AND lease_until<=now())) "
                    "AND (recovery_until IS NULL OR recovery_until<now()) "
                    "AND (dispatch_until IS NULL OR dispatch_until<now()) RETURNING run_id",
                    (self.worker_id, rid, row["epoch"]),
                )
            ).fetchone()
            if not claimed:
                return
        try:
            run = await self.store.get(principal, "run", rid)
            state = (
                await self.service.runtime(rid, "state") if run.get("dsh_session_id") else {"running": False}
            )
            if state.get("running"):
                await self.service.runtime(rid, "abort")
                return
            async with self.store.transaction(principal, rid) as conn:
                current = await (
                    await conn.execute(
                        "SELECT state,epoch,recovery_owner FROM agent_runtime.harness_admission WHERE run_id=%s",
                        (rid,),
                    )
                ).fetchone()
                if (
                    current["state"] != "RELEASING"
                    or current["epoch"] != row["epoch"]
                    or str(current["recovery_owner"]) != self.worker_id
                ):
                    return
                run = await self.store.get(principal, "run", rid, conn)
                if run["status"] in TERMINAL:
                    await conn.execute(
                        "UPDATE agent_runtime.harness_admission SET state='RELEASED' WHERE run_id=%s", (rid,)
                    )
                    return
                # Fence stale callbacks and keep unknown side effects out of replay.
                run["epoch"] += 1
                self.settle_time(run)
                await conn.execute(
                    "UPDATE agent_runtime.harness_calls SET status='UNKNOWN',updated_at=now() WHERE run_id=%s AND status='RUNNING'",
                    (rid,),
                )
                unknown = await (
                    await conn.execute(
                        "SELECT 1 FROM agent_runtime.harness_calls WHERE run_id=%s AND status='UNKNOWN' LIMIT 1",
                        (rid,),
                    )
                ).fetchone()
                missing = state.get("missing", False)
                if unknown:
                    run.update(status="FAILED", error="SIDE_EFFECT_RECONCILIATION_REQUIRED")
                elif missing:
                    # Only an explicit, authenticated absence confirms that a
                    # native log is gone. A transport failure never frees slots.
                    run.update(status="FAILED", error="DSH_SESSION_LOST")
                else:
                    run.update(status="QUEUED", error=None)
                    run.pop("dsh_turn", None)
                    run.pop("dsh_completed_turn", None)
                    run.pop("dsh_input_signature", None)
                    if run.get("dsh_session_id"):
                        run["admission_resume"] = run.get("admission_resume") or str(uuid4())
                await self.store.update(principal, "run", run, conn)
                await conn.execute(
                    "UPDATE agent_runtime.harness_admission SET state=%s,origin='api',epoch=%s,dispatch_owner=NULL,"
                    "dispatch_until=NULL,dispatch_done=false,retry_at=now() WHERE run_id=%s",
                    ("RELEASED" if unknown or missing else "QUEUED", run["epoch"], rid),
                )
                await self.store.event(
                    principal, rid, "run.failed" if unknown or missing else "run.queued", {"status": run["status"]}, conn
                )
            self.wake.set()
        finally:
            async with self.store.pool.connection() as conn:
                await conn.execute(
                    "UPDATE agent_runtime.harness_admission SET recovery_owner=NULL,recovery_until=NULL WHERE run_id=%s AND recovery_owner=%s",
                    (rid, self.worker_id),
                )

    async def recover(self):
        # Adopt pre-upgrade records as counted, expired slots before granting any
        # new work. Reconciliation confirms the native host before releasing them.
        async with self.store.pool.connection() as conn:
            rows = await (
                await conn.execute(
                    "SELECT id,payload FROM agent_runtime.harness_records r WHERE kind='run' "
                    "AND payload->>'engine_version'='dsh-v1' AND payload->>'status' IN ('RUNNING','QUEUED','WAITING_APPROVAL') "
                    "AND coalesce(payload->>'session_deleted','false')<>'true' "
                    "AND NOT EXISTS(SELECT 1 FROM agent_runtime.harness_admission a WHERE a.run_id=r.id)"
                )
            ).fetchall()
        for row in rows:
            run = row["payload"]
            principal = Principal.model_validate(run["principal"])
            rid = str(row["id"])
            async with self.store.transaction(principal, rid) as conn:
                await conn.execute(
                    "INSERT INTO agent_runtime.harness_admission(run_id,organization_id,actor_id,principal,origin,state,epoch,lease_until) "
                    "VALUES(%s,%s,%s,%s,%s,%s,%s,now()) ON CONFLICT DO NOTHING",
                    (
                        rid,
                        *principal.key,
                        Jsonb(run["principal"]),
                        "native" if run.get("dsh_dispatched") else "api",
                        "GRANTED"
                        if run["status"] in {"RUNNING", "WAITING_APPROVAL"} or run.get("dsh_dispatched")
                        else "QUEUED",
                        run["epoch"],
                    ),
                )

    async def cycle(self):
        async with self.store.pool.connection() as conn:
            stale = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.harness_admission WHERE state='RELEASING' "
                    "OR (state='GRANTED' AND lease_until<=now())"
                )
            ).fetchall()
        for row in stale:
            # An unreachable host must not stall admission for other owners.
            self.start(("reconcile", str(row["run_id"])), lambda row=row: self.reconcile(row))
        for _ in range(self.settings.max_running_runs):
            async with self.store.pool.connection() as conn:
                first = await self.head(conn)
            if not first:
                break
            principal = Principal.model_validate(first["principal"])
            rid = str(first["run_id"])
            try:
                await self.grant(principal, rid)
            except HTTPException as error:
                if error.status_code not in {401, 403, 404}:
                    raise
                async with self.store.transaction(principal, rid) as conn:
                    run = await self.store.get(principal, "run", rid, conn)
                    run.update(status="FAILED", error="AUTHORIZATION_REQUIRED")
                    await self.store.update(principal, "run", run, conn)
                    await conn.execute(
                        "UPDATE agent_runtime.harness_admission SET state='RELEASED',diagnostic=%s WHERE run_id=%s",
                        (Jsonb({"code": str(error.detail), "phase": "authorize"}), rid),
                    )
                    await self.store.event(
                        principal, rid, "run.failed", {"message": "权限已变化，请重新登录并重试"}, conn
                    )
        async with self.store.pool.connection() as conn:
            pending = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.harness_admission WHERE state='GRANTED' AND origin='api' "
                    "AND NOT dispatch_done AND lease_until>now() AND retry_at<=now() AND (dispatch_until IS NULL OR dispatch_until<now())"
                )
            ).fetchall()
        for row in pending:
            self.start(("dispatch", str(row["run_id"])), lambda row=row: self.dispatch(row))

    async def run(self):
        recovered = False
        try:
            while True:
                self.wake.clear()
                try:
                    if not recovered:
                        await self.recover()
                        recovered = True
                    await self.cycle()
                except Exception as error:
                    logger.warning("Admission scheduler retrying failure=%s", type(error).__name__)
                with contextlib.suppress(TimeoutError):
                    await asyncio.wait_for(self.wake.wait(), timeout=1)
        finally:
            for task in self.tasks:
                task.cancel()
            await asyncio.gather(*self.tasks, return_exceptions=True)
