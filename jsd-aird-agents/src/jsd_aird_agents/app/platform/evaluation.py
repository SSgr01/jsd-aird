"""One structural acceptance runner; semantic grounding is measured separately by golden tests."""

from jsd_aird_agents.errors import AgentError


def evaluate(pack, case_id, result):
    case = next((c for c in pack.evals if c.id == case_id), None)
    if not case:
        raise AgentError("EVAL_UNREGISTERED", "未注册的模块验收用例")
    sources = {e["source_type"] for e in result.get("citations", [])}
    failures = ["missing_source:" + s for s in case.required_sources if s not in sources]
    failures += ["missing_text:" + text for text in case.must_contain if text not in result.get("answer", "")]
    if case.forbid_receipts and result.get("receipts"):
        failures.append("unexpected_write")
    return {
        "pack_id": pack.id,
        "pack_version": pack.version,
        "case_id": case_id,
        "passed": not failures,
        "failures": failures,
        "semantic_grounding_evaluated": False,
    }
