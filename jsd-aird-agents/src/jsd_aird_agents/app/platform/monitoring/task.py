"""Fixed monitoring as ordinary asynchronous code, with durable step results."""

from jsd_aird_agents.app.platform.monitoring.operations import MonitoringOperations

TASK_VERSION = "monitoring-task-v1"


class MonitoringTask:
    version = TASK_VERSION

    def __init__(self, services):
        self.services = services
        self.store = services.store
        self.operations = MonitoringOperations(services)

    async def execute(self, ctx):
        state = {}
        for step_id, operation in (
            ("initialize", "begin"),
            ("snapshot", "read"),
            ("checks", "check"),
            ("report", "report"),
            ("persist", "persist"),
        ):
            await self.operations.context(ctx)  # Fresh scope and identity even on cached steps.
            saved = await self.store.db.one(
                "SELECT status,result FROM agent_runtime.steps WHERE run_id=%s AND step_id=%s",
                (ctx.run_id, step_id),
            )
            if saved and saved["status"] == "SUCCEEDED":
                delta = saved["result"]
            else:
                delta = await getattr(self.operations, operation)(state, ctx)
                await self.store.complete_task_step(ctx, step_id, delta)
            evidence = {**state.get("evidence", {}), **delta.get("evidence", {})}
            state.update(delta)
            state["evidence"] = evidence
        return state["final_result"]
