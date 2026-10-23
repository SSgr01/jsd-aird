from uuid import UUID

from jsd_aird_agents.contracts import Principal

ORG = "10000000-0000-0000-0000-000000000001"
OTHER_ORG = "10000000-0000-0000-0000-000000000002"
ACTOR = "20000000-0000-0000-0000-000000000001"
APPROVER = "20000000-0000-0000-0000-000000000002"
OUTSIDER = "20000000-0000-0000-0000-000000000003"
PROJECT = "30000000-0000-0000-0000-000000000001"
SECOND_PROJECT = "30000000-0000-0000-0000-000000000002"
HIDDEN_PROJECT = "30000000-0000-0000-0000-000000000003"
OTHER_PROJECT = "30000000-0000-0000-0000-000000000004"
TASK = "50000000-0000-0000-0000-000000000001"
DOCUMENT = "60000000-0000-0000-0000-000000000001"


def principals():
    return {
        ACTOR: Principal(
            ORG,
            ACTOR,
            (PROJECT, SECOND_PROJECT),
            permissions=("project.view", "project.update", "ai.use", "quality.view"),
            resource_scopes=(("quality", "70000000-0000-0000-0000-000000000001"),),
        ),
        APPROVER: Principal(ORG, APPROVER, (PROJECT, SECOND_PROJECT), can_approve=True),
        OUTSIDER: Principal(OTHER_ORG, OUTSIDER, (OTHER_PROJECT,)),
    }


async def seed(db):
    from jsd_aird_agents.app.verticals.quality.gateway import seed as quality_seed

    await quality_seed(db)
    async with db.pool.connection() as conn, conn.transaction():
        for i, pid in enumerate((PROJECT, SECOND_PROJECT, HIDDEN_PROJECT, OTHER_PROJECT), 1):
            org = OTHER_ORG if pid == OTHER_PROJECT else ORG
            name = "材料验证项目" if i < 3 else f"隔离项目{i}"
            sid = str(UUID(int=UUID("40000000-0000-0000-0000-000000000000").int + i))
            tid = str(UUID(int=UUID(TASK).int + i - 1))
            did = str(UUID(int=UUID(DOCUMENT).int + i - 1))
            await conn.execute(
                """INSERT INTO mock_domain.projects(id,organization_id,name) VALUES(%s,%s,%s)
                ON CONFLICT DO NOTHING""",
                (pid, org, name),
            )
            await conn.execute(
                "INSERT INTO mock_domain.stages(id,project_id,name) VALUES(%s,%s,'测试验证') ON CONFLICT DO NOTHING",
                (sid, pid),
            )
            await conn.execute(
                """INSERT INTO mock_domain.tasks
                (id,project_id,stage_id,name,owner_name,planned_date,status)
                VALUES(%s,%s,%s,'耐候测试验证','研发工程师','2026-10-20','IN_PROGRESS') ON CONFLICT DO NOTHING""",
                (tid, pid, sid),
            )
            await conn.execute(
                """INSERT INTO mock_domain.documents(id,project_id,title,content)
                VALUES(%s,%s,'测试验证会议纪要','耐候测试增加复测，验证周期延长；尚未完成最终验收。')
                ON CONFLICT DO NOTHING""",
                (did, pid),
            )
