"""Optional framework bridges; shared Harness remains the sole tool execution boundary."""

from langchain_core.tools import StructuredTool

from jsd_aird_agents.contracts import digest
from jsd_aird_agents.errors import AgentError


async def langchain_tools(harness, ctx):
    result = []
    for description in await harness.describe(ctx):
        spec = harness.registry.require(description["name"])

        def bridge(spec):
            async def invoke(**kwargs):
                output = await harness.invoke(
                    spec.name,
                    kwargs,
                    ctx,
                    "agent_" + digest([spec.name, kwargs])[:32],
                    proposal=spec.mutation,
                )
                return output.model_dump(mode="json")

            return invoke

        result.append(
            StructuredTool.from_function(
                name=spec.name,
                description="Governed read or action proposal; writes require the shared approval pipeline.",
                coroutine=bridge(spec),
                args_schema=spec.schema,
            )
        )
    return result


def deepagents_skills_middleware(*, backend, sources):
    """Explicit optional integration. Never installs DeepAgents or enables shell tools implicitly.

    Callers must supply an approved, read-only backend exposing ONLY run-pinned skill snapshots.
    This is an extension seam, not an enabled production DeepAgents runtime.
    """
    try:
        from deepagents.middleware.skills import SkillsMiddleware
    except ImportError as exc:
        raise AgentError(
            "OPTIONAL_FRAMEWORK_UNAVAILABLE", "DeepAgents 尚未安装或验收；当前使用受治理的标准技能加载器", 503
        ) from exc
    return SkillsMiddleware(backend=backend, sources=sources)
