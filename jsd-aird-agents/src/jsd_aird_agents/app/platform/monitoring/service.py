"""Durable observations, alert lifecycle and simulated in-app delivery.

Only successful Runs can publish findings or notifications. Projection and delivery
are replayable PostgreSQL transactions, separate from model/checkpoint work.
"""

from datetime import datetime, time, timedelta, timezone
from uuid import NAMESPACE_URL, uuid5
from zoneinfo import ZoneInfo

from psycopg.types.json import Jsonb

from jsd_aird_agents.app.platform.monitoring.contracts import MonitorOptions
from jsd_aird_agents.contracts import digest
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.persistence.store import wire


def quiet_until(now, options):
    if options.quiet_start is None:
        return now
    local = now.astimezone(ZoneInfo(options.timezone))
    start, end, wall = options.quiet_start, options.quiet_end, local.time().replace(tzinfo=None)
    inside = start <= wall < end if start < end else wall >= start or wall < end
    if not inside:
        return now
    day = local.date() + timedelta(days=1 if start > end and wall >= start else 0)
    return datetime.combine(day, end, local.tzinfo).astimezone(timezone.utc)


class MonitoringService:
    def __init__(self, services, after_commit=None):
        self.services = services
        self.store, self.db = services.store, services.db
        self.after_commit = after_commit

    async def baseline(self, ctx, as_of, zone, workflow):
        local = as_of.astimezone(ZoneInfo(zone))
        monday = datetime.combine(local.date() - timedelta(days=local.weekday()), time(), local.tzinfo)
        row = await self.db.one(
            """SELECT b.* FROM agent_runtime.monitoring_batches b
            JOIN agent_runtime.runs r ON r.id=b.run_id WHERE b.organization_id=%s AND b.actor_id=%s
            AND b.pack_id=%s AND b.scope_id=%s AND b.workflow_id=%s
            AND r.status='SUCCEEDED' AND b.observed_at<=%s AND b.observed_at>=%s
            ORDER BY b.observed_at DESC LIMIT 1""",
            (
                ctx.principal.organization_id,
                ctx.principal.actor_id,
                ctx.pack_id,
                ctx.resource_id,
                workflow,
                monday,
                monday - timedelta(days=14),
            ),
        )
        return wire(row) if row else None

    async def stage(self, ctx, run, profile, snapshot, report):
        await self.store.check_active(ctx)
        async with self.db.pool.connection() as conn, conn.transaction():
            await conn.execute(
                """INSERT INTO agent_runtime.monitoring_batches
                (run_id,organization_id,actor_id,pack_id,scope_id,workflow_id,rule_version,config_sha256,observed_at,snapshot,report)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(run_id) DO NOTHING""",
                (
                    ctx.run_id,
                    ctx.principal.organization_id,
                    ctx.principal.actor_id,
                    ctx.pack_id,
                    ctx.resource_id,
                    profile.id,
                    profile.version,
                    digest(run["monitoring"]),
                    run["monitoring"]["as_of"],
                    Jsonb(snapshot),
                    Jsonb(report),
                ),
            )
            await self.store.event(
                ctx.run_id,
                "monitoring.staged",
                {"profile": profile.id, "publication_status": "DRAFT"},
                conn=conn,
            )
        if self.after_commit:
            await self.after_commit(ctx.run_id)

    async def authorize(self, actor, organization, pack, scope):
        principal = self.services.identity.lookup(str(actor))
        if principal.organization_id != str(organization):
            raise AgentError("PERMISSION_DENIED", "提醒主体组织不一致", 403)
        await self.services.packs.authorize(principal, [pack], {pack: str(scope)})
        return principal

    async def recipients(self, batch, observation, options):
        # Pack policy resolves recipients using current server identities, never model input.
        binding = self.services.packs.require(batch["pack_id"])
        owner = self.services.identity.lookup(str(batch["actor_id"]))
        return await binding.business.notification_recipients(
            owner, str(batch["scope_id"]), observation, options, self.services.identity, binding.gateway
        )

    async def project(self, conn, batch):
        run = await (
            await conn.execute("SELECT * FROM agent_runtime.runs WHERE id=%s FOR UPDATE", (batch["run_id"],))
        ).fetchone()
        if run["status"] != "SUCCEEDED" or run["cancel_requested"]:
            return
        await self.authorize(batch["actor_id"], batch["organization_id"], batch["pack_id"], batch["scope_id"])
        options = MonitorOptions.model_validate(run["monitoring"]["options"])
        # Serialize competing checks for this owner and scope, preserving observation time ordering.
        await conn.execute(
            "SELECT pg_advisory_xact_lock(hashtextextended(%s,7))",
            (f"{batch['actor_id']}:{batch['pack_id']}:{batch['scope_id']}",),
        )
        now = self.services.clock.now()
        for observation in batch["report"]["observations"]:
            identity = [
                str(batch[k]) for k in ("organization_id", "actor_id", "pack_id", "scope_id", "rule_version")
            ]
            fid = str(
                uuid5(
                    NAMESPACE_URL,
                    digest(
                        identity
                        + [observation["rule_id"], observation["resource_id"], observation["policy_sha256"]]
                    ),
                )
            )
            old = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.monitoring_findings WHERE id=%s FOR UPDATE", (fid,)
                )
            ).fetchone()
            if old and old["last_seen_at"] > batch["observed_at"]:
                continue
            status = observation["status"]
            if status == "UNKNOWN" or (not old and status == "PASS"):
                continue
            episode = (
                old["episode"] + 1
                if old and old["status"] == "RESOLVED" and status == "FAIL"
                else old["episode"]
                if old
                else 1
            )
            state = "OPEN" if status == "FAIL" else "RESOLVED"
            await conn.execute(
                """INSERT INTO agent_runtime.monitoring_findings
                (id,organization_id,actor_id,pack_id,scope_id,rule_id,rule_version,resource_id,status,severity,episode,
                 first_seen_at,last_seen_at,last_run_id,payload) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT(id) DO UPDATE SET status=excluded.status,severity=excluded.severity,episode=excluded.episode,
                last_seen_at=excluded.last_seen_at,last_run_id=excluded.last_run_id,payload=excluded.payload""",
                (
                    fid,
                    batch["organization_id"],
                    batch["actor_id"],
                    batch["pack_id"],
                    batch["scope_id"],
                    observation["rule_id"],
                    batch["rule_version"],
                    observation["resource_id"],
                    state,
                    observation["severity"],
                    episode,
                    batch["observed_at"],
                    batch["observed_at"],
                    batch["run_id"],
                    Jsonb(observation),
                ),
            )
            if state == "RESOLVED":
                await conn.execute(
                    "UPDATE agent_runtime.notification_outbox SET status='SUPPRESSED',reason='FINDING_RESOLVED' WHERE finding_id=%s AND status='PENDING'",
                    (fid,),
                )
            if status != "FAIL" or not options.notify:
                continue
            for recipient in await self.recipients(batch, observation, options):
                prior = await (
                    await conn.execute(
                        """SELECT * FROM agent_runtime.notification_outbox
                    WHERE finding_id=%s AND recipient_id=%s AND status IN ('PENDING','DELIVERED') ORDER BY created_at DESC LIMIT 1""",
                        (fid, recipient),
                    )
                ).fetchone()
                escalated = old and {"INFO": 0, "WARNING": 1, "HIGH": 2}.get(observation["severity"], 0) > {
                    "INFO": 0,
                    "WARNING": 1,
                    "HIGH": 2,
                }.get(old["severity"], 0)
                if (
                    prior
                    and old
                    and episode == old["episode"]
                    and not escalated
                    and (
                        prior["status"] == "PENDING"
                        or now < prior["created_at"] + timedelta(hours=options.cooldown_hours)
                    )
                ):
                    continue
                key = digest([fid, episode, recipient, str(batch["run_id"])])
                notice = {
                    "title": observation["title"],
                    "rule_id": observation["rule_id"],
                    "severity": observation["severity"],
                    "finding_id": fid,
                    "scope_id": str(batch["scope_id"]),
                    "simulated": True,
                    "channel": "in_app",
                }
                if observation["rule_id"] == "approval.waiting":
                    notice["action_id"] = observation["resource_id"]
                else:
                    notice.update(run_id=str(batch["run_id"]), metrics=observation["metrics"])
                await conn.execute(
                    """INSERT INTO agent_runtime.notification_outbox
                    (id,organization_id,actor_id,recipient_id,pack_id,scope_id,run_id,finding_id,dedupe_key,audience,
                    available_at,expires_at,payload,policy,created_at) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                    ON CONFLICT(dedupe_key) DO NOTHING""",
                    (
                        str(uuid5(NAMESPACE_URL, key)),
                        batch["organization_id"],
                        batch["actor_id"],
                        recipient,
                        batch["pack_id"],
                        batch["scope_id"],
                        batch["run_id"],
                        fid,
                        key,
                        options.notify_to,
                        quiet_until(now, options),
                        observation["metrics"].get("expires_at"),
                        Jsonb(notice),
                        Jsonb(options.model_dump(mode="json")),
                        now,
                    ),
                )
        await conn.execute(
            "UPDATE agent_runtime.monitoring_batches SET projected_at=%s WHERE run_id=%s",
            (now, batch["run_id"]),
        )
        await self.projection_audit(conn, batch)

    async def projection_audit(self, conn, batch, error=None):
        await conn.execute(
            """INSERT INTO agent_runtime.calls(run_id,step_id,call_kind,request_hash,status,result,error_code)
            VALUES(%s,'monitor_projection','audit:monitoring',%s,%s,%s,%s)
            ON CONFLICT(run_id,step_id,call_kind,request_hash) DO NOTHING""",
            (
                batch["run_id"],
                batch["config_sha256"],
                "FAILED" if error else "SUCCEEDED",
                Jsonb({"simulated": True, "publication_status": "DRAFT"}),
                error,
            ),
        )

    async def dispatch(self):
        # Repeated by every Worker; database row locks provide cross-process exclusion.
        batches = await self.db.all("""SELECT b.run_id FROM agent_runtime.monitoring_batches b JOIN agent_runtime.runs r ON r.id=b.run_id
            WHERE b.projected_at IS NULL AND r.status='SUCCEEDED' AND NOT r.cancel_requested ORDER BY b.observed_at LIMIT 50""")
        for item in batches:
            async with self.db.pool.connection() as conn, conn.transaction():
                batch = await (
                    await conn.execute(
                        "SELECT * FROM agent_runtime.monitoring_batches WHERE run_id=%s AND projected_at IS NULL FOR UPDATE SKIP LOCKED",
                        (item["run_id"],),
                    )
                ).fetchone()
                if batch:
                    try:
                        await self.project(conn, batch)
                    except AgentError as exc:
                        await conn.execute(
                            "UPDATE agent_runtime.monitoring_batches SET projected_at=%s WHERE run_id=%s",
                            (self.services.clock.now(), batch["run_id"]),
                        )
                        await self.projection_audit(conn, batch, exc.code)
        now = self.services.clock.now()
        async with self.db.pool.connection() as conn, conn.transaction():
            notices = await (
                await conn.execute(
                    "SELECT * FROM agent_runtime.notification_outbox WHERE status='PENDING' AND available_at<=%s ORDER BY created_at,id LIMIT 100 FOR UPDATE SKIP LOCKED",
                    (now,),
                )
            ).fetchall()
            for notice in notices:
                reason = None
                run = await (
                    await conn.execute(
                        "SELECT status,cancel_requested FROM agent_runtime.runs WHERE id=%s FOR UPDATE",
                        (notice["run_id"],),
                    )
                ).fetchone()
                try:
                    if run["status"] != "SUCCEEDED" or run["cancel_requested"]:
                        raise AgentError("RUN_NOT_SUCCESSFUL", "运行已停止")
                    await self.authorize(
                        notice["actor_id"], notice["organization_id"], notice["pack_id"], notice["scope_id"]
                    )
                    recipient = await self.authorize(
                        notice["recipient_id"],
                        notice["organization_id"],
                        notice["pack_id"],
                        notice["scope_id"],
                    )
                    await self.services.packs.require(notice["pack_id"]).business.validate_notification(
                        conn, notice, recipient, now
                    )
                    if notice["expires_at"] and notice["expires_at"] <= now:
                        raise AgentError("NOTIFICATION_EXPIRED", "提醒已失效")
                except AgentError as exc:
                    reason = exc.code
                if reason:
                    await conn.execute(
                        "UPDATE agent_runtime.notification_outbox SET status='SUPPRESSED',reason=%s WHERE id=%s",
                        (reason, notice["id"]),
                    )
                    continue
                options = MonitorOptions.model_validate(notice["policy"])
                available = quiet_until(now, options)
                if available > now:
                    await conn.execute(
                        "UPDATE agent_runtime.notification_outbox SET available_at=%s WHERE id=%s",
                        (available, notice["id"]),
                    )
                    continue
                await conn.execute(
                    """INSERT INTO agent_runtime.notifications(id,organization_id,recipient_id,pack_id,scope_id,payload,created_at)
                    VALUES(%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(id) DO NOTHING""",
                    (
                        notice["id"],
                        notice["organization_id"],
                        notice["recipient_id"],
                        notice["pack_id"],
                        notice["scope_id"],
                        Jsonb(notice["payload"]),
                        now,
                    ),
                )
                await conn.execute(
                    "UPDATE agent_runtime.notification_outbox SET status='DELIVERED',delivered_at=%s WHERE id=%s",
                    (now, notice["id"]),
                )
        return bool(batches)

    async def findings(self, principal, pack, scope):
        await self.authorize(principal.actor_id, principal.organization_id, pack, scope)
        return wire(
            await self.db.all(
                "SELECT * FROM agent_runtime.monitoring_findings WHERE organization_id=%s AND actor_id=%s AND pack_id=%s AND scope_id=%s ORDER BY last_seen_at DESC,id LIMIT 200",
                (principal.organization_id, principal.actor_id, pack, scope),
            )
        )

    async def inbox(self, principal):
        rows = await self.db.all(
            "SELECT * FROM agent_runtime.notifications WHERE organization_id=%s AND recipient_id=%s ORDER BY created_at DESC,id LIMIT 200",
            (principal.organization_id, principal.actor_id),
        )
        visible = []
        for row in rows:
            try:
                await self.authorize(
                    principal.actor_id, principal.organization_id, row["pack_id"], row["scope_id"]
                )
                visible.append(wire(row))
            except AgentError:
                continue
        return visible

    async def mark_read(self, principal, notification_id):
        row = next((r for r in await self.inbox(principal) if r["id"] == notification_id), None)
        if not row:
            raise AgentError("NOT_FOUND", "提醒不存在", 404)
        await self.db.execute(
            "UPDATE agent_runtime.notifications SET read_at=coalesce(read_at,%s) WHERE id=%s AND recipient_id=%s",
            (self.services.clock.now(), notification_id, principal.actor_id),
        )
        return {"id": notification_id, "read": True, "simulated": True}
