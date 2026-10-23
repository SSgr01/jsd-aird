import json
from pathlib import Path

import pytest

from jsd_aird_agents.adapters.fixtures import ACTOR, DOCUMENT, PROJECT, TASK, principals
from jsd_aird_agents.contracts import AnswerDocument, RunContext
from jsd_aird_agents.evidence import build_evidence
from jsd_aird_agents.models.adapters import ScriptedModel

CASES = json.loads((Path(__file__).parent / "fixtures/golden_answers.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["name"])
async def test_scripted_answer_semantic_golden_baseline(case):
    ctx = RunContext(principals()[ACTOR], "conversation", "run", PROJECT)
    evidence = []
    for content in case["documents"]:
        evidence.append(build_evidence(ctx, "KNOWLEDGE_DOCUMENT", DOCUMENT, 1, "会议记录", content))
    for task in case["tasks"]:
        evidence.append(
            build_evidence(
                ctx,
                "TASK",
                TASK,
                task["version"],
                "任务",
                {**task, "name": "耐候测试", "status": "IN_PROGRESS"},
            )
        )
    result, _ = await ScriptedModel().generate(
        "answer", {"evidence": [e.model_dump(mode="json") for e in evidence]}, AnswerDocument
    )
    claims = "\n".join(c.text for c in result.claims)
    assert all(term in claims for term in case["expected"])
    assert not any(term in claims for term in case["forbidden"])
    known = {e.id for e in evidence}
    assert all(set(c.evidence_ids) <= known for c in result.claims)
    if not evidence:
        assert not result.claims
