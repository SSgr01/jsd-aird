"""Ordinary monitoring operations: authorized reads, rules, reports and delivery staging."""

from dataclasses import replace
from datetime import datetime

from psycopg.types.json import Jsonb

from jsd_aird_agents.app.platform.artifacts.evidence import build_evidence
from jsd_aird_agents.app.platform.artifacts.store import ArtifactStore
from jsd_aird_agents.app.platform.monitoring.contracts import MonitorOptions, profile_for
from jsd_aird_agents.app.platform.runtime.validation import render_answer, validate_citations
from jsd_aird_agents.contracts import AnswerDocument, Claim
from jsd_aird_agents.errors import AgentError


class MonitoringOperations:
    def __init__(self, services):
        self.services = services
        self.service = services.monitoring
        self.store = services.store

    async def context(self, ctx):
        await self.store.check_active(ctx)
        run = await self.store.run(ctx.run_id)
        principal = self.services.identity.lookup(run["actor_id"])
        ctx = replace(ctx, principal=principal)
        pid, profile = profile_for(self.services.packs, run["workflow_id"])
        ctx = ctx.for_pack(pid, run["resource_scopes"][pid])
        await self.store.check_active(ctx)
        await self.services.packs.authorize(principal, run["pack_ids"], run["resource_scopes"])
        return ctx, run, profile

    async def begin(self, state, ctx):
        ctx, run, profile = await self.context(ctx)
        result = {
            "run_id": ctx.run_id,
            "conversation_id": ctx.conversation_id,
            "current_request": run["request"],
            "project_id": ctx.project_id,
            "pack_ids": run["pack_ids"],
            "resource_scopes": run["resource_scopes"],
            "monitoring_input": {},
            "monitoring_report": {},
            "active_skills": [],
            "evidence": {},
            "final_result": None,
        }
        if run["monitoring"]["options"]["explain_with_model"]:
            offered = await self.services.skills.catalog(enabled_only=True, ctx=ctx)
            if await self.services.skills.selection(ctx) is None:
                await self.services.skills.pin(
                    ctx, [profile.skill] if any(s["name"] == profile.skill for s in offered) else [], offered
                )
            result["active_skills"] = await self.services.skills.instructions(ctx)
        plan = {
            "steps": [
                {"id": "snapshot", "tool": profile.tool},
                {"id": "checks"},
                {"id": "report"},
                {"id": "persist"},
            ],
            "profile": profile.id,
            "rule_version": profile.version,
            "simulated": True,
        }
        await self.store.db.execute(
            "UPDATE agent_runtime.runs SET plan=%s WHERE id=%s",
            (Jsonb(plan), ctx.run_id),
        )
        await self.store.event(ctx.run_id, "monitoring.started", {"profile": profile.id})
        return result

    async def read(self, state, ctx):
        ctx, run, profile = await self.context(ctx)
        await self.store.step(ctx.run_id, "snapshot", "RUNNING")
        result = await self.services.harness.invoke(profile.tool, {}, ctx, "monitor_snapshot")
        return {
            "monitoring_input": result.data,
            "evidence": {e.id: e.model_dump(mode="json") for e in result.evidence},
        }

    async def check(self, state, ctx):
        ctx, run, profile = await self.context(ctx)
        await self.store.step(ctx.run_id, "checks", "RUNNING")
        options = MonitorOptions.model_validate(run["monitoring"]["options"])
        as_of = datetime.fromisoformat(run["monitoring"]["as_of"])
        baseline = (
            await self.service.baseline(ctx, as_of, options.timezone, profile.baseline_workflow)
            if profile.kind == "draft"
            else None
        )
        ids = list(state["evidence"])
        evidence = {}
        if baseline:
            e = build_evidence(
                ctx,
                "MONITORING_BASELINE",
                baseline["run_id"],
                baseline["observed_at"],
                "本周开始前的成功巡检基线",
                baseline["report"],
            )
            await self.store.save_evidence([e])
            evidence[e.id] = e.model_dump(mode="json")
            ids.append(e.id)
        report = profile.evaluate(state["monitoring_input"], options, as_of, ids, baseline)
        report["observations"] = [o for o in report["observations"] if o["rule_id"] in profile.rules]
        report.update(
            profile=profile.id,
            name=profile.name,
            rule_version=profile.version,
            publication_status="DRAFT",
            delivery="simulated_in_app",
        )
        failed = [o for o in report["observations"] if o["status"] == "FAIL"]
        unknown = [o for o in report["observations"] if o["status"] == "UNKNOWN"]
        report["summary"] = f"{profile.name}：发现 {len(failed)} 项异常，{len(unknown)} 项无法判断。"
        if profile.kind == "draft":
            report["summary"] += f"本周已完成 {report['counts']['completed_this_week']} 项任务。"
            report["summary"] += (
                f"逾期任务较周初变化 {report['overdue_change']} 项。"
                if baseline
                else "缺少周初历史基线，无法比较变化。"
            )
        report["coverage"] = {"checked": len(report["observations"]), "unknown": len(unknown)}
        report["recommendations"] = list(dict.fromkeys(o["recommendation"] for o in failed))
        report["summary"] += "报告为模拟草稿。"
        e = build_evidence(ctx, "MONITORING_REPORT", ctx.run_id, profile.version, profile.name, report)
        await self.store.save_evidence([e])
        evidence[e.id] = e.model_dump(mode="json")
        return {"monitoring_report": report, "evidence": evidence}

    async def report(self, state, ctx):
        ctx, run, profile = await self.context(ctx)
        report = state["monitoring_report"]
        await self.store.step(ctx.run_id, "report", "RUNNING")
        report_ev = next(e for e in state["evidence"].values() if e["source_type"] == "MONITORING_REPORT")
        fixed = Claim(text=report["summary"], evidence_ids=[report_ev["id"]])
        answer = AnswerDocument(claims=[fixed], recommendations=report["recommendations"])
        if run["monitoring"]["options"]["explain_with_model"]:
            payload = {
                "request": state["current_request"],
                "monitoring_report": report,
                "evidence": list(state["evidence"].values()),
                "skill_guidance": state["active_skills"],
            }
            for repair in range(2):
                answer = await self.services.model.generate(
                    ctx, "monitor_report", "answer", payload, AnswerDocument
                )
                errors = await validate_citations(
                    answer, state["evidence"], ctx, self.store, self.services.harness.policy
                )
                if not errors:
                    break
                if repair:
                    raise AgentError("CITATION_INVALID", "巡检引用校验失败，未发布正文", 502, detail=errors)
                payload["citation_errors"] = errors
            # The computed status is always shown alongside model commentary.
            answer.claims.insert(0, fixed)
        errors = await validate_citations(
            answer, state["evidence"], ctx, self.store, self.services.harness.policy
        )
        if errors:
            raise AgentError("CITATION_INVALID", "巡检证据失效", 502, detail=errors)
        result = render_answer(answer, state["evidence"], [], False)
        result.update(
            monitoring=report,
            publication_status="DRAFT",
            trace={
                "pack_versions": run["pack_versions"],
                "graph_version": run["graph_version"] if run["execution_kind"] == "graph" else None,
                "execution_kind": run["execution_kind"],
                "execution_version": run["execution_version"],
                "skills": await self.services.skills.selection(ctx)
                if run["monitoring"]["options"]["explain_with_model"]
                else [],
            },
        )
        result["artifact"] = await ArtifactStore(self.store).put_report(ctx, result)
        return {"final_result": result}

    async def persist(self, state, ctx):
        ctx, run, profile = await self.context(ctx)
        await self.service.stage(ctx, run, profile, state["monitoring_input"], state["monitoring_report"])
        return {"run_status": "SUCCEEDED"}
