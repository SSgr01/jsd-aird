"""Deterministic project checks over authorized, bounded mock authority snapshots."""

from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from jsd_aird_agents.app.platform.monitoring.contracts import MonitorOptions, MonitorProfile
from jsd_aird_agents.contracts import ToolResult, digest
from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.persistence.store import wire

DONE = {"DONE", "COMPLETED", "CANCELLED"}


async def read_snapshot(gateway, name, ctx):
    # Harness has already bound the project; authority repeats the check at access time.
    await gateway.require(ctx, "project.view", ctx.project_id)
    async with gateway.db.pool.connection() as conn, conn.transaction():
        await conn.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")

        async def rows(sql, args):
            result = await (await conn.execute(sql, args)).fetchall()
            if len(result) > 500:
                raise AgentError("SNAPSHOT_TOO_LARGE", "巡检范围超过单次上限，请缩小范围", 409)
            return wire(result)

        if name == "get_project_approval_snapshot":
            data = {
                "actions": await rows(
                    """SELECT id,actor_id,project_id,tool,target_id,risk,
                approval_status,execution_status,created_at,expires_at FROM mock_domain.actions
                WHERE organization_id=%s AND project_id=%s AND actor_id=%s ORDER BY created_at LIMIT 501""",
                    (ctx.principal.organization_id, ctx.project_id, ctx.principal.actor_id),
                )
            }
            source = "APPROVAL_SNAPSHOT"
        else:
            data = {
                "project": wire(
                    await (
                        await conn.execute(
                            "SELECT * FROM mock_domain.projects WHERE id=%s AND organization_id=%s",
                            (ctx.project_id, ctx.principal.organization_id),
                        )
                    ).fetchone()
                )
            }
            for table in ("tasks", "stages", "documents", "milestones"):
                data[table] = await rows(
                    f"SELECT * FROM mock_domain.{table} WHERE project_id=%s ORDER BY id LIMIT 501",
                    (ctx.project_id,),
                )
            data["requirements"] = await rows(
                """SELECT r.* FROM mock_domain.milestone_requirements r
                JOIN mock_domain.milestones m ON m.id=r.milestone_id WHERE m.project_id=%s ORDER BY r.id LIMIT 501""",
                (ctx.project_id,),
            )
            data["dependencies"] = await rows(
                """SELECT d.*,p.project_id AS dependency_project_id,p.status AS dependency_status
                FROM mock_domain.task_dependencies d JOIN mock_domain.tasks t ON t.id=d.task_id
                JOIN mock_domain.tasks p ON p.id=d.depends_on WHERE t.project_id=%s AND p.project_id=%s
                ORDER BY d.task_id,d.depends_on LIMIT 501""",
                (ctx.project_id, ctx.project_id),
            )
            source = "PROGRESS_SNAPSHOT"
    evidence = gateway.evidence(ctx, source, ctx.project_id, "snapshot-v1", "授权项目巡检快照", data)
    return ToolResult(data=data, evidence=[evidence])


def evaluate(snapshot, options, as_of, evidence_ids, baseline=None):
    """Each checked resource yields PASS/FAIL/UNKNOWN. UNKNOWN never closes an alert."""
    local = as_of.astimezone(ZoneInfo(options.timezone))
    today = local.date()
    observations = []
    recommendations = {
        "task.overdue": "核对逾期原因、影响和补救排期；计划调整另行提交审批。",
        "task.due-soon": "请负责人确认到期交付物与剩余工作。",
        "task.stalled": "向负责人核实最近进展和阻塞原因。",
        "task.blocked": "核实阻塞原因并协调资源或依赖。",
        "task.dependency": "确认前置任务完成时间及下游影响。",
        "progress.deviation": "核对阶段进度口径并评估赶工或重排建议。",
        "milestone.readiness": "补齐前置任务、已发布交付物和验收证据。",
        "approval.waiting": "请有资格的审批人审阅具体动作；保持人工决策。",
        "approval.expired": "重新读取资源并生成新的动作预览与审批。",
    }

    def add(rule, resource, bad, title, metrics=None, severity="WARNING"):
        parameter_names = {
            "task.due-soon": ("due_soon_days",),
            "task.stalled": ("stalled_days",),
            "milestone.readiness": ("milestone_days",),
            "approval.waiting": ("approval_wait_minutes", "approval_expiry_minutes"),
        }.get(rule, ())
        parameters = {"timezone": options.timezone, **{n: getattr(options, n) for n in parameter_names}}
        observations.append(
            dict(
                rule_id=rule,
                resource_id=str(resource),
                status="UNKNOWN" if bad is None else "FAIL" if bad else "PASS",
                severity=severity,
                title=title,
                metrics=metrics or {},
                evidence_ids=evidence_ids,
                recommendation=recommendations[rule],
                rule_parameters=parameters,
                policy_sha256=digest(parameters),
            )
        )

    tasks = snapshot.get("tasks", [])
    for task in tasks:
        active = task["status"] not in DONE
        due = date.fromisoformat(task["planned_date"])
        days = (today - due).days
        add(
            "task.overdue",
            task["id"],
            active and days > 0,
            f"{task['name']}：逾期检查",
            {"overdue_days": max(days, 0), "owner": task["owner_name"], "due_date": str(due)},
            "HIGH",
        )
        add(
            "task.due-soon",
            task["id"],
            active and 0 <= -days <= options.due_soon_days,
            f"{task['name']}：到期跟进",
            {"days_remaining": -days, "owner": task["owner_name"]},
        )
        updated = datetime.fromisoformat(task["last_progress_at"]) if task.get("last_progress_at") else None
        stale_days = (today - updated.astimezone(local.tzinfo).date()).days if updated else None
        add(
            "task.stalled",
            task["id"],
            False if not active else None if stale_days is None else stale_days >= options.stalled_days,
            f"{task['name']}：停滞检查",
            {"days_without_progress": stale_days, "owner": task["owner_name"]},
        )
        add("task.blocked", task["id"], active and task["status"] == "BLOCKED", f"{task['name']}：阻塞检查")
    for dep in snapshot.get("dependencies", []):
        add(
            "task.dependency",
            f"{dep['task_id']}:{dep['depends_on']}",
            dep["dependency_status"] not in {"DONE", "COMPLETED"},
            "前置任务尚未完成",
            {"task_id": dep["task_id"], "depends_on": dep["depends_on"]},
        )
    for item in [snapshot.get("project", {}), *snapshot.get("stages", [])]:
        if not item:
            continue
        actual, planned = item.get("progress_percent"), item.get("planned_progress_percent")
        known = actual is not None and planned is not None
        gap = round(float(planned) - float(actual), 2) if known else None
        add(
            "progress.deviation",
            item["id"],
            gap > 10 if known else None,
            f"{item['name']}：计划进度偏差",
            {"actual_percent": actual, "planned_percent": planned, "gap_percentage_points": gap},
        )
    by_task, by_doc = {t["id"]: t for t in tasks}, {d["id"]: d for d in snapshot.get("documents", [])}
    for milestone in snapshot.get("milestones", []):
        reqs = [r for r in snapshot.get("requirements", []) if r["milestone_id"] == milestone["id"]]
        missing = []
        for req in reqs:
            obj = (by_task if req["resource_kind"] == "TASK" else by_doc).get(req["resource_id"])
            complete = obj and (
                obj["status"] in {"DONE", "COMPLETED"}
                if req["resource_kind"] == "TASK"
                else obj["publication_status"] == "PUBLISHED"
            )
            if not complete:
                missing.append(
                    {"kind": req["resource_kind"], "id": req["resource_id"], "missing": obj is None}
                )
        remaining = (date.fromisoformat(milestone["due_date"]) - today).days
        relevant = milestone["status"] not in DONE and remaining <= options.milestone_days
        add(
            "milestone.readiness",
            milestone["id"],
            False if not relevant else bool(missing) if reqs else None,
            f"{milestone['name']}：里程碑预检查",
            {"days_remaining": remaining, "gaps": missing, "requirements_defined": bool(reqs)},
            "HIGH",
        )
    for action in snapshot.get("actions", []):
        pending = action["approval_status"] == "PENDING"
        expires = datetime.fromisoformat(action["expires_at"])
        waited = (as_of - datetime.fromisoformat(action["created_at"])).total_seconds() / 60
        expired = expires <= as_of or action["approval_status"] == "EXPIRED"
        add(
            "approval.waiting",
            action["id"],
            pending
            and not expired
            and (
                waited >= options.approval_wait_minutes
                or (expires - as_of).total_seconds() <= options.approval_expiry_minutes * 60
            ),
            "动作等待人工审批",
            {"action_id": action["id"], "wait_minutes": round(waited, 1), "expires_at": action["expires_at"]},
        )
        add(
            "approval.expired",
            action["id"],
            expired and action["approval_status"] in {"PENDING", "EXPIRED"},
            "审批已过期，需要重新预览和审批",
            {"action_id": action["id"]},
        )
    start = (today - timedelta(days=today.weekday())).isoformat()
    completed = [
        t["id"]
        for t in tasks
        if t.get("completed_at")
        and start
        <= datetime.fromisoformat(t["completed_at"]).astimezone(local.tzinfo).date().isoformat()
        <= today.isoformat()
    ]
    counts = {
        "tasks": len(tasks),
        "completed_this_week": len(completed),
        "overdue": sum(o["rule_id"] == "task.overdue" and o["status"] == "FAIL" for o in observations),
    }
    old_overdue = baseline.get("report", {}).get("counts", {}).get("overdue") if baseline else None
    return {
        "as_of": as_of.isoformat(),
        "week_start": start,
        "completed_task_ids": completed,
        "baseline_available": baseline is not None,
        "overdue_change": counts["overdue"] - old_overdue if old_overdue is not None else None,
        "counts": counts,
        "observations": observations,
        "simulated": True,
    }


def profiles():
    specs = [
        (
            "project-progress-inspection",
            "每日健康巡检",
            ("task.overdue", "task.blocked", "task.dependency", "progress.deviation"),
            "inspection",
        ),
        (
            "project-task-followup",
            "到期与停滞跟进",
            ("task.overdue", "task.due-soon", "task.stalled", "task.blocked"),
            "inspection",
        ),
        ("project-milestone-precheck", "里程碑预检查", ("milestone.readiness",), "inspection"),
        (
            "project-weekly-report",
            "周报草稿",
            ("task.overdue", "task.stalled", "progress.deviation", "milestone.readiness"),
            "draft",
        ),
        ("project-approval-reminder", "审批提醒", ("approval.waiting", "approval.expired"), "approval"),
    ]
    return {
        wid: MonitorProfile(
            wid,
            name,
            "get_project_approval_snapshot" if kind == "approval" else "get_project_monitoring_snapshot",
            rules,
            evaluate,
            MonitorOptions(notify_to="approvers" if kind == "approval" else "self"),
            kind=kind,
            version="project-checks-v1",
            skill="project-proactive-monitoring",
            baseline_workflow="project-progress-inspection" if kind == "draft" else None,
        )
        for wid, name, rules, kind in specs
    }
