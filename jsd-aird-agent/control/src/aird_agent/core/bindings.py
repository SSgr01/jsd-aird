"""Host policy ports. The business adapter never chooses business authorities or domain tools."""

from fastapi import HTTPException

from aird_agent.core.contracts import digest


class RuntimeBindings:
    activities = {}
    activity_event = "tool.activity"

    def attach(self, runtime):
        self.runtime = runtime
        self.settings, self.store, self.packs = runtime.settings, runtime.store, runtime.packs
        self.active, self.tool, self.callback = runtime.active, runtime.tool, runtime.callback

    def tools_for(self, run):
        return self.packs.tools(run["pack_ids"])

    def mutation_tools(self, run):
        return self.packs.mutations(run["pack_ids"])

    def authorization_sources(self, sources):
        return list(sources)

    def context_request(self, run):
        return {"profile": run.get("profile", "agent"), "scope": run.get("host_scope", run.get("scope", {}))}

    def scope_digest(self, scope):
        return digest(scope.get("scope", {}))

    async def authorized_topic(self, principal, run, *, execution=True):
        try:
            self.packs.verify(run["pack_ids"], run["pack_versions"])
        except ValueError:
            raise HTTPException(409, "PACK_VERSION_MISMATCH") from None
        scope = await self.runtime.host_call(
            principal, "authorize_context", self.context_request(run), run.get("id")
        )
        if set(run["pack_ids"]) - set(scope.get("pack_ids", [])):
            raise HTTPException(403, "CAPABILITY_ACCESS_REVOKED")
        if execution and run["scope_hash"] != self.scope_digest(scope):
            raise HTTPException(409, "SOURCE_SCOPE_CHANGED")
        return scope

    async def authorize_conversation(self, principal, conversation):
        scope = await self.runtime.host_call(
            principal, "authorize_context", self.context_request(conversation)
        )
        if set(conversation["pack_ids"]) - set(scope.get("pack_ids", [])):
            raise HTTPException(403, "CAPABILITY_ACCESS_REVOKED")

    async def prepare_request(self, principal, request):
        payload = request.model_dump(mode="json")
        scope = await self.runtime.host_call(principal, "authorize_context", self.context_request(payload))
        selected = payload.get("pack_ids") or scope.get("pack_ids", [])
        if not selected or set(selected) - set(scope.get("pack_ids", [])):
            raise HTTPException(403, "CAPABILITY_NOT_AUTHORIZED")
        payload.update(
            pack_ids=selected, pack_versions=self.packs.pin(selected), host_scope=scope.get("scope", {})
        )
        return payload, scope

    def conversation_metadata(self, payload):
        return {key: payload.get(key) for key in ("profile", "pack_ids", "pack_versions", "host_scope")}

    def validate_conversation_scope(self, conversation, payload):
        if conversation.get("host_scope") != payload.get("host_scope"):
            raise HTTPException(409, "CONVERSATION_SCOPE_CHANGED")

    def initial_state(self, scope, evidence):
        return {"host_scope": scope.get("scope", {}), "source_documents": [], "source_refs": []}

    async def authorize_tool(self, principal, rid, name, arguments):
        pass

    async def cancel_execution(self, principal, run):
        pass

    def output_receipt(self, run, name, arguments, result):
        return None

    def error_info(self, error):
        return {}

    def bounded_result(self, principal, run, result, call_id):
        import json

        if len(json.dumps(result, ensure_ascii=False)) > self.runtime.tool_character_budget:
            raise HTTPException(413, "TOOL_OUTPUT_TOO_LARGE")
        return result

    async def remember_sources(self, principal, run, result):
        return result

    async def dispatch(self, principal, run, topic, name, args, call_id):
        raise HTTPException(400, "UNBOUND_TOOL")

    def gate_state(self, run):
        return {key: run.get(key) for key in ("completion", "registered_outputs")}

    def evidence_summaries(self, run):
        return []

    def input_context(self, run, scope):
        return scope.get("model_context", {})

    async def runtime_topic(self, principal, run, scope):
        return self.input_context(run, scope)

    def initial_tools(self, run):
        return self.packs.initial_tools(run["pack_ids"])

    async def prepare_run(self, principal, run):
        return run

    def instructions_for(self, run):
        return self.packs.instructions(run["pack_ids"])

    def qa_history(self, conversation, run):
        return []

    async def authorize_history(self, principal, conversation):
        await self.authorize_conversation(principal, conversation)
        return {}

    def normalize_output(self, text, evidence):
        return text

    def validate_output(self, run, text):
        return text, isinstance(text, str)

    def default_completion(self, run):
        return {
            "outcome": "COMPLETE" if run["status"] == "SUCCEEDED" else "PARTIAL",
            "summary": "Execution finished",
            "checks": [],
            "gaps": [],
            "verification": "EXECUTION_ONLY_NO_DOMAIN_VERIFICATION",
        }
