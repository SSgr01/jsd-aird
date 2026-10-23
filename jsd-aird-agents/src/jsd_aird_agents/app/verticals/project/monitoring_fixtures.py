"""Opt-in monitoring dataset, separate from the original deadline-change demo."""

from datetime import timedelta

from jsd_aird_agents.app.verticals.project.fixtures import ACTOR, SECOND_PROJECT

MILESTONE = "80000000-0000-0000-0000-000000000001"
OVERDUE = "51000000-0000-0000-0000-000000000001"
DUE = "51000000-0000-0000-0000-000000000002"
COMPLETED = "51000000-0000-0000-0000-000000000003"


async def seed_monitoring(db, now):
    from jsd_aird_agents.app.verticals.project.fixtures import seed

    await seed(db)
    async with db.pool.connection() as conn, conn.transaction():
        await conn.execute(
            """UPDATE mock_domain.projects SET progress_percent=coalesce(progress_percent,40),
            planned_progress_percent=coalesce(planned_progress_percent,75) WHERE id=%s""",
            (SECOND_PROJECT,),
        )
        stage = (
            await (
                await conn.execute(
                    "SELECT id FROM mock_domain.stages WHERE project_id=%s ORDER BY id LIMIT 1",
                    (SECOND_PROJECT,),
                )
            ).fetchone()
        )["id"]
        for tid, name, offset, status, progress, stale, completed in (
            (OVERDUE, "巡检样例：复测排期", -2, "BLOCKED", 30, 6, None),
            (DUE, "巡检样例：交付物核验", 2, "IN_PROGRESS", 60, 1, None),
            (COMPLETED, "巡检样例：性能初测", -1, "DONE", 100, 0, now),
        ):
            await conn.execute(
                """INSERT INTO mock_domain.tasks
                (id,project_id,stage_id,name,owner_name,owner_id,planned_date,status,progress_percent,last_progress_at,completed_at)
                VALUES(%s,%s,%s,%s,'模拟研发负责人',%s,%s,%s,%s,%s,%s) ON CONFLICT(id) DO NOTHING""",
                (
                    tid,
                    SECOND_PROJECT,
                    stage,
                    name,
                    ACTOR,
                    now.date() + timedelta(days=offset),
                    status,
                    progress,
                    now - timedelta(days=stale),
                    completed,
                ),
            )
        await conn.execute(
            "INSERT INTO mock_domain.task_dependencies(task_id,depends_on) VALUES(%s,%s) ON CONFLICT DO NOTHING",
            (DUE, OVERDUE),
        )
        await conn.execute(
            """INSERT INTO mock_domain.milestones(id,project_id,name,due_date)
            VALUES(%s,%s,'样例阶段验收',%s) ON CONFLICT DO NOTHING""",
            (MILESTONE, SECOND_PROJECT, now.date() + timedelta(days=5)),
        )
        for rid, kind, resource in (
            ("81000000-0000-0000-0000-000000000001", "TASK", OVERDUE),
            ("81000000-0000-0000-0000-000000000002", "DOCUMENT", "60000000-0000-0000-0000-000000000002"),
        ):
            await conn.execute(
                "INSERT INTO mock_domain.milestone_requirements(id,milestone_id,resource_kind,resource_id) VALUES(%s,%s,%s,%s) ON CONFLICT DO NOTHING",
                (rid, MILESTONE, kind, resource),
            )
