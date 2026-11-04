import asyncio
import copy
import hashlib
import json

import httpx
import pytest
from pydantic import SecretStr

from aird_agent.capabilities.literature import quality
from aird_agent.capabilities.literature.quality import ReportAuditor, ReviewOutputInvalid


def prepared_review(count=25):
    return {
        "report_hash": "report",
        "matrix_revision": 1,
        "units": [
            {
                "unit_id": f"u{i}",
                "text": "Modulus: 12 kPa.",
                "quotes": [{"quote_id": f"q{i}", "text": "Modulus: 12 kPa."}],
            }
            for i in range(count)
        ],
        "questions": [{"question_id": "modulus", "question": "What is the modulus?"}],
        "required_unit_ids": [f"u{i}" for i in range(count)],
        "required_question_ids": ["modulus"],
        "blocking_findings": [],
    }


def test_coverage_shares_repeated_sources_without_losing_assertion_bindings():
    prepared = prepared_review(25)
    source = {"quote_id": "q-shared", "text": "Exact method and its limitations. " * 80,
              "evidence_id": "e-shared", "source": {"publication_id": "v1", "title": "Method"}}
    prepared.update(review_scope="QUESTION_COVERAGE", units=[], required_unit_ids=[],
                    report_context=[{"text": unit["text"], "quotes": [source]} for unit in prepared["units"]])
    payload, _ = ReportAuditor._model_payload(prepared)
    assert len(payload["source_quotes"]) == 1
    assert payload["source_quotes"][0]["text"] == source["text"]
    assert len(payload["report_context"]) == 25
    assert all(unit["quotes"] == [{"quote_id": "s1"}] for unit in payload["report_context"])
    assert len(json.dumps(payload)) < len(json.dumps(prepared)) / 5
    assert source["quote_id"] == "q-shared"


def test_reviewer_cannot_invent_another_experiments_measured_result():
    prepared = prepared_review(1)
    prepared["units"][0].update(text="Wait 3 min before testing.", quotes=[{"quote_id": "q0", "text": "Tested after 180 seconds."}])
    answer = {
        "units": [{"unit_id": "u0", "verdict": "CONTRADICTED", "quote_ids": ["q0"], "reason": "原文需要约24小时恢复。"}],
        "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}],
    }
    with pytest.raises(ValueError, match="measurement absent"):
        quality.validate_review(prepared, answer)
    answer["units"][0].update(verdict="SUPPORTED", reason="180秒对应3分钟，时间一致。")
    assert quality.validate_review(prepared, answer)["passed"]


@pytest.mark.parametrize("source,reason", [
    ("等待五分钟再测试。", "等待5分钟再测试。"),
    ("保持二十四小时。", "保持1天。"),
    ("保持半天。", "保持12小时。"),
    ("保持三点五分钟。", "保持210秒。"),
    ("应力为一点五MPa。", "应力为1500kPa。"),
])
def test_review_measurement_guard_accepts_equivalent_chinese_notation(source, reason):
    assert quality.measured_quantities(reason)
    assert quality.measured_quantities(reason) <= quality.measured_quantities(source)


@pytest.mark.parametrize("separator", ["-", "‐", "‑", "‒", "–", "—", "−"])
def test_hyphenated_source_units_do_not_turn_a_valid_review_into_a_format_failure(separator):
    prepared = prepared_review(1)
    prepared["units"][0].update(
        text="循环间等待时间不足。",
        quotes=[{"quote_id": "q0", "text": f"The 2{separator}min recovery time was insufficient."}],
    )
    answer = {
        "units": [{"unit_id": "u0", "verdict": "SUPPORTED", "quote_ids": ["q0"],
                   "reason": "原文说明循环间120秒的恢复时间不足。"}],
        "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}],
    }
    assert quality.validate_review(prepared, answer)["passed"]
    answer["units"][0]["reason"] = "原文说明循环间3分钟的恢复时间不足。"
    with pytest.raises(ValueError, match="measurement absent"):
        quality.validate_review(prepared, answer)


def test_review_restores_omitted_method_conditions_from_only_the_cited_version():
    text = (
        "Samples were held together for 4 min. "
        "The samples were mounted in the tester. "
        "They were tested after another 6 min. "
        "Mounting took several minutes, preventing a quantitative rate measurement."
    )
    source = {"publication_id": "v1", "content_sha256": hashlib.sha256(text.encode()).hexdigest()}
    eid = "e" + "a" * 24
    candidate = {"evidence_id": eid, "text": text, "source": source}
    spans = [{**q, "source": source} for q in quality.quotes(candidate)]
    # The author selected the two waiting times but omitted the mounting limit.
    selected = [spans[0], spans[2]]
    unrelated = "A different publication claims immediate recovery."
    other_id = "e" + "b" * 24
    run = {"request": "What is the full testing timeline?", "evidence": {
        eid: candidate,
        other_id: {"evidence_id": other_id, "text": unrelated, "source": {
            "publication_id": "v2", "content_sha256": hashlib.sha256(unrelated.encode()).hexdigest(),
        }},
    }, "research_matrix": {"revision": 1, "questions": {"time": {
        "question_id": "time", "question": "What is the full testing timeline?", "gap_note": "",
        "claims": [{"claim": "Total time", "quotes": selected}],
    }}}}
    original = copy.deepcopy(run)
    prepared = quality.prepare_review(run, "Testing timeline", f"测试总时长为10分钟。[E:{eid}]")
    unit = prepared["units"][0]
    assert spans[3] in unit["quotes"]
    assert all(q["evidence_id"] == eid and q["source"]["publication_id"] == "v1" for q in unit["quotes"])
    assert unrelated not in json.dumps(prepared)
    payload, _ = ReportAuditor._model_payload(prepared)
    assert any(q["text"] == spans[3]["text"] for q in payload["units"][0]["quotes"])
    assert run == original
    # A reviewer can cite the restored limitation using its real immutable ID.
    answer = {
        "units": [
            {"unit_id": unit["unit_id"], "verdict": "CONTRADICTED", "quote_ids": [spans[3]["quote_id"]],
             "reason": "额外装夹时间未计入，不能将两段等待之和当作全过程。"},
            {"unit_id": prepared["units"][1]["unit_id"], "verdict": "NON_FACTUAL", "quote_ids": [], "reason": "标题"},
        ],
        "questions": [{"question_id": "time", "verdict": "MISSING", "reason": "未完整交代操作耗时。"}],
    }
    assert quality.validate_review(prepared, answer)["passed"] is False


def mock_provider(monkeypatch, transform=lambda answer, _: answer):
    original_client = httpx.AsyncClient
    calls = []
    concurrency = {"active": 0, "maximum": 0}

    async def respond(request):
        body = json.loads(request.content)
        payload = json.loads(body["messages"][1]["content"])
        calls.append((body, payload))
        concurrency["active"] += 1
        concurrency["maximum"] = max(concurrency["maximum"], concurrency["active"])
        await asyncio.sleep(0)
        concurrency["active"] -= 1
        answer = {
            "units": [
                {
                    "unit_id": unit["unit_id"],
                    "verdict": "SUPPORTED" if unit["quotes"] else "NON_FACTUAL",
                    "quote_ids": [unit["quotes"][0]["quote_id"]] if unit["quotes"] else [],
                    "reason": "Exact source",
                }
                for unit in payload["units"]
            ],
            "questions": [
                {"question_id": row["question_id"], "verdict": "COVERED", "reason": "Answered"}
                for row in payload["questions"]
            ],
        }
        return httpx.Response(
            200,
            json={
                "choices": [
                    {"finish_reason": "stop", "message": {"content": json.dumps(transform(answer, payload))}}
                ]
            },
        )

    monkeypatch.setattr(
        quality.httpx,
        "AsyncClient",
        lambda **kwargs: original_client(transport=httpx.MockTransport(respond), **kwargs),
    )
    return calls, concurrency


async def test_hallucinated_handle_on_uncited_assertion_fails_with_missing_evidence_instead_of_format_loop(settings, monkeypatch):
    settings.paperqa_model = "openai/qwen3.7-flash-test"
    settings.paperqa_api_key = SecretStr("synthetic-key")
    settings.paperqa_base_url = "http://provider.test/v1"
    prepared = prepared_review(2)
    prepared["units"][1]["quotes"] = []

    def hallucinate(answer, payload):
        answer["units"][1].update(verdict="SUPPORTED", quote_ids=["q1"])
        return answer

    calls, _ = mock_provider(monkeypatch, hallucinate)
    review = await ReportAuditor(settings).review(prepared)
    assert not review["passed"]
    assert len(calls) == 1
    assert review["units"][1]["verdict"] == "INSUFFICIENT"
    assert review["units"][1]["quote_ids"] == []
    assert "未附原文引用" in review["units"][1]["reason"]


async def test_batched_review_checks_every_assertion_and_full_report_coverage_with_bounded_concurrency(
    settings, monkeypatch
):
    settings.paperqa_model = "openai/qwen3.7-flash-test"
    settings.paperqa_api_key = SecretStr("synthetic-key")
    settings.paperqa_base_url = "http://provider.test/v1"
    settings.parallelism = 2
    calls, concurrency = mock_provider(monkeypatch)
    reserved = []

    async def reserve():
        reserved.append(True)

    prepared = prepared_review()
    prepared["units"][0]["quotes"][0]["source"] = {
        "kind": "KB_PUBLICATION",
        "title": "材料研究",
        "document_id": "doc",
        "publication_id": "pub-v1",
        "version_id": "v1",
        "content_sha256": "a" * 64,
        "anchor": {"original": {"page": 4, "polygon": [0, 0, 1, 1]}},
    }
    result = await ReportAuditor(settings).review(prepared, reserve_model_call=reserve)
    assert result["passed"]
    assert {unit["unit_id"] for unit in result["units"]} == set(prepared["required_unit_ids"])
    assert len(result["questions"]) == 1
    assert len(calls) == len(reserved) == result["reviewer"]["batch_count"] == 4
    assert concurrency["maximum"] == 2
    facts = [payload for _, payload in calls if payload["review_scope"] == "UNIT_FACTS"]
    assert all(len(payload["units"]) <= 12 and payload["questions"] == [] for payload in facts)
    coverage = next(payload for _, payload in calls if payload["review_scope"] == "QUESTION_COVERAGE")
    assert coverage["units"] == []
    assert [unit["text"] for unit in coverage["report_context"]] == [
        unit["text"] for unit in prepared["units"]
    ]
    sources = {quote["quote_id"]: quote for quote in coverage["source_quotes"]}
    assert [sources[quote["quote_id"]]["text"] for unit in coverage["report_context"] for quote in unit["quotes"]] == [
        quote["text"] for unit in prepared["units"] for quote in unit["quotes"]
    ]
    assert result["units"][0]["quote_ids"] == ["q0"]
    assert prepared["units"][0]["quotes"][0]["quote_id"] == "q0"
    projected = facts[0]["units"][0]["quotes"][0]["source"]
    assert projected["publication_id"] == "pub-v1" and projected["page"] == 4
    assert "anchor" not in projected and "content_sha256" not in projected
    assert prepared["units"][0]["quotes"][0]["source"]["anchor"]["original"]["polygon"] == [0, 0, 1, 1]
    assert all(body["enable_thinking"] is False for body, _ in calls)


@pytest.mark.parametrize("defect", ["omitted_unit", "foreign_quote", "contradiction", "missing_question"])
async def test_batching_cannot_hide_missing_facts_borrowed_quotes_or_incomplete_answers(
    settings, monkeypatch, defect
):
    settings.paperqa_model = "openai/qwen3.7-flash-test"
    settings.paperqa_api_key = SecretStr("synthetic-key")
    settings.paperqa_base_url = "http://provider.test/v1"

    def transform(answer, payload):
        if payload["units"] and payload["units"][0]["unit_id"] == "u0":
            if defect == "omitted_unit":
                answer["units"].pop()
            elif defect == "foreign_quote":
                answer["units"][0]["quote_ids"] = ["q24"]
            elif defect == "contradiction":
                answer["units"][0]["verdict"] = "CONTRADICTED"
        if payload["questions"] and defect == "missing_question":
            answer["questions"][0]["verdict"] = "MISSING"
        return answer

    mock_provider(monkeypatch, transform)
    if defect in {"omitted_unit", "foreign_quote"}:
        with pytest.raises(ReviewOutputInvalid):
            await ReportAuditor(settings).review(prepared_review())
    else:
        result = await ReportAuditor(settings).review(prepared_review())
        assert not result["passed"]


async def test_each_review_request_reserves_budget_before_sending(settings, monkeypatch):
    settings.paperqa_model = "openai/qwen3.7-flash-test"
    settings.paperqa_api_key = SecretStr("synthetic-key")
    settings.paperqa_base_url = "http://provider.test/v1"
    calls, _ = mock_provider(monkeypatch)

    async def exhausted():
        raise RuntimeError("Budget exhausted")

    with pytest.raises(RuntimeError, match="Budget exhausted"):
        await ReportAuditor(settings).review(prepared_review(), reserve_model_call=exhausted)
    assert calls == []


async def test_format_repair_reuses_valid_batches_and_preserves_local_quote_aliases(settings, monkeypatch):
    settings.paperqa_model = "openai/qwen3.7-flash-test"
    settings.paperqa_api_key = SecretStr("synthetic-key")
    settings.paperqa_base_url = "http://provider.test/v1"
    failed_once = False

    def corrupt_once(answer, payload):
        nonlocal failed_once
        if payload["required_unit_ids"][:1] == ["u0"] and not failed_once:
            failed_once = True
            answer["units"][1]["quote_ids"] = ["foreign"]
        return answer

    calls, _ = mock_provider(monkeypatch, corrupt_once)
    reserved = []

    async def reserve():
        reserved.append(True)

    auditor = ReportAuditor(settings)
    prepared = prepared_review()
    with pytest.raises(ReviewOutputInvalid) as invalid:
        await auditor.review(prepared, reserve_model_call=reserve)
    result = await auditor.review(prepared, repair=invalid.value, reserve_model_call=reserve)
    assert result["passed"] and result["reviewer"]["repair_attempted"]
    assert len(calls) == len(reserved) == 5
    assert calls[-1][1]["required_unit_ids"] == prepared["required_unit_ids"][:12]
    previous = json.loads(calls[-1][0]["messages"][2]["content"])
    assert previous["units"][0]["quote_ids"] == ["q1"]
    assert [unit["quote_ids"] for unit in result["units"]] == [[f"q{i}"] for i in range(25)]


async def test_shared_source_quotes_keep_their_identity_across_units_and_batches(settings, monkeypatch):
    settings.paperqa_model = "openai/qwen3.7-flash-test"
    settings.paperqa_api_key = SecretStr("synthetic-key")
    settings.paperqa_base_url = "http://provider.test/v1"
    prepared = prepared_review()
    shared = {"quote_id": "canonical-source-quote", "text": "Modulus: 12 kPa."}
    for unit in prepared["units"]:
        unit["quotes"] = [shared]
    calls, _ = mock_provider(monkeypatch)
    result = await ReportAuditor(settings).review(prepared)
    assert result["passed"]
    assert all(unit["quote_ids"] == ["canonical-source-quote"] for unit in result["units"])
    assert shared["quote_id"] == "canonical-source-quote"
    assert all(
        unit["quotes"][0]["quote_id"] == "q1"
        for _, payload in calls
        for unit in payload["units"]
    )
    coverage = next(payload for _, payload in calls if payload.get("review_scope") == "QUESTION_COVERAGE")
    assert all(unit["quotes"] == [{"quote_id": "s1"}] for unit in coverage["report_context"])
    assert coverage["source_quotes"] == [{"quote_id": "s1", "text": shared["text"]}]


@pytest.mark.parametrize("malformed", [None, "invalid", {"units": ["invalid"], "questions": []}])
async def test_malformed_response_is_a_format_failure(settings, monkeypatch, malformed):
    settings.paperqa_model = "openai/qwen3.7-flash-test"
    settings.paperqa_api_key = SecretStr("synthetic-key")
    settings.paperqa_base_url = "http://provider.test/v1"
    mock_provider(monkeypatch, lambda answer, payload: malformed)
    with pytest.raises(ReviewOutputInvalid):
        await ReportAuditor(settings).review(prepared_review(1))


@pytest.mark.parametrize(
    "model,configured,expected", [("openai/qwen3.7-flash-test", True, True), ("openai/generic", None, None)]
)
async def test_review_honors_explicit_thinking_and_omits_qwen_options_for_other_providers(
    settings, monkeypatch, model, configured, expected
):
    settings.paperqa_model = model
    settings.paperqa_api_key = SecretStr("synthetic-key")
    settings.paperqa_base_url = "http://provider.test/v1"
    settings.paperqa_enable_thinking = configured
    calls, _ = mock_provider(monkeypatch)
    await ReportAuditor(settings).review(prepared_review(1))
    assert calls[0][0].get("enable_thinking") is expected
