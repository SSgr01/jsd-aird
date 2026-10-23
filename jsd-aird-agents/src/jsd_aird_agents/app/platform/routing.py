"""Runtime dispatch only to explicitly registered, run-pinned packs."""

from jsd_aird_agents.contracts import digest
from jsd_aird_agents.errors import AgentError


class GatewayRouter:
    def __init__(self, packs, store):
        self.packs, self.store = packs, store

    async def require(self, ctx, permission, resource_id):
        gateway = self.packs.require(ctx.pack_id).gateway
        await gateway.require(ctx, permission, resource_id)

    async def read(self, name, arguments, ctx):
        return await self.packs.require(ctx.pack_id).gateway.read(name, arguments, ctx)

    async def validate_evidence(self, ctx, item):
        run = await self.store.run(ctx.run_id)
        pid = item.get("pack_id", "project")
        if pid not in run["pack_ids"]:
            raise AgentError("EVIDENCE_SCOPE_MISMATCH", "证据不属于本轮模块", 403)
        pack = self.packs.require(pid).manifest
        rule = next((r for r in pack.evidence_rules if r.source_type == item["source_type"]), None)
        scope = item.get("scope_id") or item.get("project_id")
        if not rule or scope != run["resource_scopes"].get(pid):
            raise AgentError("EVIDENCE_SCOPE_MISMATCH", "证据类型或范围未声明", 403)
        await self.require(ctx.for_pack(pid, scope), rule.permission, scope)


class ActionRouter:
    def __init__(self, packs):
        self.packs = packs

    def gateway(self, pid):
        b = self.packs.require(pid)
        if not any(t.mutation for t in b.tools):
            raise AgentError("ACTION_UNAVAILABLE", "该模块没有业务写工具", 403)
        return b.gateway

    async def preview(self, ctx, step_id, name, args):
        return await self.gateway(ctx.pack_id).preview(ctx, step_id, name, args)

    async def get(self, action_id, principal):
        for b in self.packs.bindings.values():
            if not any(t.mutation for t in b.tools):
                continue
            try:
                return {**await b.gateway.get(action_id, principal), "pack_id": b.manifest.id}
            except AgentError as exc:
                if exc.code != "NOT_FOUND":
                    raise
        raise AgentError("NOT_FOUND", "动作不存在", 404)

    async def decide(self, action_id, principal, decision, sha256):
        action = await self.get(action_id, principal)
        return await self.gateway(action.get("pack_id", "project")).decide(
            action_id, principal, decision, sha256
        )

    async def execute(self, ctx, action_id):
        return await self.gateway(ctx.pack_id).execute(ctx, action_id)

    async def reconcile(self):
        for b in self.packs.bindings.values():
            if any(t.mutation for t in b.tools):
                await b.gateway.reconcile()


class PackScriptedModel:
    def __init__(self, packs):
        self.packs, self.calls = packs, []

    async def generate(self, kind, payload, schema):
        self.calls.append((kind, payload))
        bindings = self.packs.select(payload.get("_pack_ids", ["project"]))
        outputs = [(b, (await b.scripted_model.generate(kind, payload, schema))[0]) for b in bindings]
        if len(outputs) == 1:
            return outputs[0][1], {"reported": False, "scripted": True}
        if kind == "skills":
            value = {
                "names": list(dict.fromkeys(n for _, o in outputs for n in o.names))[
                    : payload["max_selected"]
                ]
            }
        elif kind == "plan":
            steps = []
            resource_refs = {}
            for b, plan in outputs:
                if plan.needs_clarification:
                    return plan, {"reported": False, "scripted": True}
                # Pack IDs allow hyphens and may be long; Step IDs have a different bounded grammar.
                names = {s.id: "s_" + digest([b.manifest.id, s.id])[:24] for s in plan.steps}

                def rebind(value):
                    if isinstance(value, dict):
                        if set(value) == {"$ref"}:
                            head, *tail = value["$ref"].split(".")
                            return {"$ref": ".".join([names.get(head, head), *tail])}
                        return {k: rebind(v) for k, v in value.items()}
                    if isinstance(value, list):
                        return [rebind(v) for v in value]
                    return value

                for s in plan.steps:
                    s = s.model_copy(deep=True)
                    s.id = names[s.id]
                    s.depends_on = [names.get(d, d) for d in s.depends_on]
                    s.arguments = rebind(s.arguments)
                    s.pack_id = b.manifest.id
                    steps.append(s)
                resource_refs.update(plan.resource_refs)
            value = {
                "steps": [s.model_dump() for s in steps],
                "resource_refs": resource_refs,
                "project_ref": next((o.project_ref for _, o in outputs if o.project_ref), None),
            }
        else:
            value = {
                "claims": [c.model_dump() for _, o in outputs for c in o.claims],
                "recommendations": list(dict.fromkeys(r for _, o in outputs for r in o.recommendations)),
            }
        return schema.model_validate(value), {"reported": False, "scripted": True}
