import asyncio
import copy
import hashlib
import json

import pytest
from fastapi import HTTPException

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


def test_fact_review_shares_sources_but_never_reuses_an_alias_for_a_different_sample():
    prepared = prepared_review(3)
    prepared["units"][1]["quotes"] = prepared["units"][0]["quotes"]
    prepared["units"][2]["quotes"][0].update(
        text="A typical device reached 18.0%; the average across devices was 17.9%.",
        source={"publication_id": "other-version", "anchor": {"page": 4}},
    )
    before = copy.deepcopy(prepared)
    payload, aliases = ReportAuditor._model_payload(prepared)
    assert len(payload["source_quotes"]) == 2
    assert payload["units"][0]["quotes"] == payload["units"][1]["quotes"] == [{"quote_id": "s1"}]
    assert payload["units"][2]["quotes"] == [{"quote_id": "s2"}]
    assert aliases[("u0", "q0")] != aliases[("u2", "q2")]
    assert "average across devices was 17.9%" in payload["source_quotes"][1]["text"]
    assert prepared == before


def test_numbered_section_labels_do_not_inherit_the_following_measurements_sources():
    text = "二、机械性能（划痕抗性）\n硬度为2H。[E:sample]\n\n三、热性能\n测试温度为25°C。[E:thermal]"
    units = quality.report_units(text)
    assert units[0]["context_evidence_ids"] == units[2]["context_evidence_ids"] == []
    assert units[1]["context_evidence_ids"] == ["sample"]
    assert units[3]["context_evidence_ids"] == ["thermal"]
    factual = quality.report_units("二、硬度提高\n说明。[E:sample]")
    assert factual[0]["context_evidence_ids"] == ["sample"]


def test_table_column_conditions_are_checked_with_each_independently_cited_row():
    units = quality.report_units("| 指标 | 未改性 | 3% 添加剂 |\n|---|---|---|\n| 平均效率 | 15.6% | 17.9% |[E:average]\n| 单个器件反扫效率 | — | 18.0% |[E:device]")
    assert units[0]["kind"] == "TABLE_HEADER"
    assert units[0]["context_evidence_ids"] == []
    assert units[1]["table_columns"] == ["指标", "未改性", "3% 添加剂"]
    assert units[1]["context_evidence_ids"] == ["average"]
    assert units[2]["context_evidence_ids"] == ["device"]
    prepared = prepared_review(1)
    prepared["units"][0].update(kind="TABLE_HEADER", text=units[0]["text"], quotes=[])
    answer = {"units": [{"unit_id": "u0", "verdict": "NON_FACTUAL", "quote_ids": [], "reason": "列标签，实际数据在行中核对。"}],
              "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}]}
    assert quality.validate_review(prepared, answer)["passed"]
    prepared["units"][0]["text"] = "| 添加后效率提高了30% |"
    assert not quality.validate_review(prepared, answer)["passed"]


def test_numeric_review_reasons_keep_the_rows_column_conditions_without_borrowing_other_rows():
    prepared = prepared_review(1)
    prepared["units"][0].update(text="| 效率 | 18.0% |", table_columns=["参数", "3% 添加剂"],
                                quotes=[{"quote_id": "q0", "text": "The representative device with 3 mol% additive reached 18.0%."}])
    answer = {"units": [{"unit_id": "u0", "verdict": "SUPPORTED", "quote_ids": ["q0"], "reason": "表头3%添加剂对应这一行18.0%的结果。"}],
              "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}]}
    assert quality.validate_review(prepared, answer)["passed"]
    answer["units"][0]["reason"] = "另一行6%添加剂对应这一行结果。"
    with pytest.raises(ValueError, match="Review reason introduced a measurement"):
        quality.validate_review(prepared, answer)
    prepared["units"][0].pop("table_columns")
    answer["units"][0]["reason"] = "对应原文3%添加剂样品的数据，仍应按原文的摩尔基准理解。"
    assert quality.validate_review(prepared, answer)["passed"]


@pytest.mark.parametrize("text,source,blocked", [
    ("典型器件反扫效率为18.0%。", "One typical device exhibited 18.0% PCE at reverse scan.", False),
    ("最高效率为18%。", "One typical device exhibited 18.0% PCE at reverse scan.", True),
    ("最大强度为12 MPa。", "A representative sample had a strength of 12 MPa.", True),
    ("最高效率为18%。", "The best device reached 18.0%; a typical device reached 17.0%.", False),
    ("平均效率为17.9%。", "The average PCE was 17.9%, with one typical device at 18.0%.", False),
    ("最高效率为18%。", "A typical device had 17.0% efficiency.", False),
])
def test_representative_measurements_cannot_be_promoted_to_extrema(text, source, blocked):
    prepared = prepared_review(1)
    prepared["units"][0].update(text=text, quotes=[{"quote_id": "q0", "text": source}])
    answer = {"units": [{"unit_id": "u0", "verdict": "SUPPORTED", "quote_ids": ["q0"], "reason": "数值一致。"}],
              "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}]}
    # Even an overly permissive reviewer cannot accept the observed role mismatch.
    assert bool(quality.validate_review(prepared, answer)["blocking_findings"]) is blocked


def test_review_diagnostics_identify_the_failed_assertion_without_persisting_model_output():
    prepared = prepared_review(3)
    failures = [
        quality.ReviewOutputInvalid("Review cited unknown or unrelated quote aliases for u1; allowed: ['s1']",
                                    {"secret_or_source": "never persist this response"}),
        quality.ReviewOutputInvalid("Review reason introduced a measurement absent from this unit and its sources: u2", {}),
    ]
    result = quality.review_diagnostics(quality.ReviewOutputInvalid("batch failure", {}, batch_results=failures), prepared)
    assert result == [{"code": "UNRELATED_SOURCE_REFERENCE", "unit_ids": ["u1"]},
                      {"code": "REVIEWER_ADDED_MEASUREMENT", "unit_ids": ["u2"]}]
    assert "never persist" not in json.dumps(result)


def test_reviewer_cannot_invent_another_experiments_measured_result():
    prepared = prepared_review(1)
    prepared["units"][0].update(text="Wait 3 min before testing.", quotes=[{"quote_id": "q0", "text": "Tested after 180 seconds."}])
    answer = {
        "units": [{"unit_id": "u0", "verdict": "CONTRADICTED", "quote_ids": ["q0"], "reason": "原文需要约24小时恢复。"}],
        "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}],
    }
    with pytest.raises(ValueError, match="Review reason introduced a measurement"):
        quality.validate_review(prepared, answer)
    answer["units"][0].update(verdict="SUPPORTED", reason="180秒对应3分钟，时间一致。")
    assert quality.validate_review(prepared, answer)["passed"]


@pytest.mark.parametrize("text,passed", [
    ("建议在相同测试条件下比较候选材料，并记录附着与耐磨表现。", True),
    ("下一步可设置空白对照，验证响应是否具有选择性。", True),
    ("建议统一条件，比较候选材料的附着和耐磨表现。[E:e22d5676261ab3e33107f180b]", True),
    ("本次尚未取得足够证据，暂不能确认PET涂层附着是否改善。[E:e22d5676261ab3e33107f180b]", True),
    ("现有资料尚不足以确认量产性能，需要同条件验证。", True),
    ("本次检索中未发现本文包含涂层附着力测试（如ASTM D 3359）的数据，此为证据缺口。", True),
    ("这提示在该涂层中使用添加剂时，可能需要选择可参与固化的结构；此为基于原文的推断，需进一步验证。", True),
    ("基于原文推断，这种涂层必然提升附着力。", False),
    ("本次证据不足，但ASTM D 3359测试已证明附着力提高了30%。", False),
    ("本文未进行任何附着力测试。[E:e22d5676261ab3e33107f180b]", False),
    ("本次证据不足，但已证明附着力提高了30%。", False),
    ("建议先测试相容性，同时注意化学键合是性能提升的前提条件。[E:e22d5676261ab3e33107f180b]", False),
    ("建议验证该材料，实测模量已达到12 kPa。", False),
    ("建议使用30%的添加量开展测试。", False),
    ("建议保证该涂层能满足客户要求，再进行对照测试。", False),
    ("材料的模量提高了，可以进行测试。", False),
])
def test_cited_prospective_tests_are_not_misread_as_measured_results(text, passed):
    prepared = prepared_review(1)
    prepared["units"][0]["text"] = text
    answer = {
        "units": [{"unit_id": "u0", "verdict": "NON_FACTUAL", "quote_ids": [], "reason": "后续验证建议"}],
        "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}],
    }
    if passed:
        assert quality.validate_review(prepared, answer)["passed"]
    else:
        with pytest.raises(ValueError, match="Review skipped a factual assertion"):
            quality.validate_review(prepared, answer)


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


def test_pressure_prefix_case_and_micro_signs_cannot_be_changed_by_the_reviewer():
    assert quality.measured_quantities("1 GPa") == quality.measured_quantities("1000 MPa")
    assert quality.measured_quantities("1 mPa") != quality.measured_quantities("1 MPa")
    assert quality.measured_quantities("1 μPa") == quality.measured_quantities("1 µPa")
    assert quality.measured_quantities("1 μPa") == quality.measured_quantities("0.000001 Pa")
    prepared = prepared_review(1)
    prepared["units"][0].update(text="表面硬度的单位需核实。", quotes=[{"quote_id": "q0", "text": "Surface hardness: 42.4 μPa."}])
    answer = {"units": [{"unit_id": "u0", "verdict": "SUPPORTED", "quote_ids": ["q0"], "reason": "原文为42.4 GPa。"}],
              "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}]}
    with pytest.raises(ValueError, match="Review reason introduced a measurement"):
        quality.validate_review(prepared, answer)


def test_table_review_restores_only_its_confirmed_header_with_separate_quote_identities():
    from aird_agent.evidence.quotes import review_quotes

    def row(key, text, index, version="v1", table="header"):
        return {"evidence_id": key, "text": text,
                "source": {"kind": "KB_PUBLICATION", "document_id": "doc", "publication_id": version,
                           "version_id": version, "source_node_key": key,
                           "content_sha256": hashlib.sha256(text.encode()).hexdigest()},
                "table_context": {"table_key": table, "row_index": index, "header_node_keys": ["header"]}}

    header = row("header", "mol% A | Pencil hardness A | wt% B | Pencil hardness B", 0)
    values = row("data", "1 | 2H | 2.2 | H", 2)
    other = row("other", "1 | 6H", 1, table="other-header")
    evidence = {r["evidence_id"]: r for r in [header, values, other]}
    original = copy.deepcopy(evidence)
    context = review_quotes(evidence, values)
    assert [q["text"] for q in context] == [header["text"], values["text"]]
    assert [q["table_row"]["is_header"] for q in context] == [True, False]
    assert len({q["quote_id"] for q in context}) == 2
    assert context[0]["source"] == header["source"] and context[1]["source"] == values["source"]
    assert evidence == original
    # A matching table/header key at another publication cannot complete it.
    evidence["header"] = row("header", "Wrong version header", 0, version="v2")
    with pytest.raises(HTTPException, match="REPORT_TABLE_HEADER_REQUIRED"):
        review_quotes(evidence, values)
    evidence["header"] = {**header, "table_context": {**header["table_context"], "table_key": "other"}}
    with pytest.raises(HTTPException, match="REPORT_TABLE_HEADER_REQUIRED"):
        review_quotes(evidence, values)


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
    with pytest.raises(ValueError, match="Review reason introduced a measurement"):
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
    assert any(q["text"] == spans[3]["text"] for q in payload["source_quotes"])
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
    # A narrower author-created matrix cannot replace the actual customer's
    # request in the independent coverage check, including an ID collision.
    run["request"] = "Can this material meet our full testing timeline?"
    run["research_matrix"]["questions"]["user_request"] = {
        **run["research_matrix"]["questions"]["time"], "question_id": "user_request",
    }
    narrowed = quality.prepare_review(run, "Testing timeline", f"测试总时长为10分钟。[E:{eid}]")
    assert narrowed["questions"][-1] == {"question_id": "_user_request", "question": run["request"], "gap_note": ""}
    assert narrowed["required_question_ids"] == ["time", "user_request", "_user_request"]


def mock_provider(transform=lambda answer, _: answer, admit=None):
    calls = []
    concurrency = {"active": 0, "maximum": 0}

    async def generate(system, messages):
        if admit:
            await admit()
        body = {"messages": [{"role": "system", "content": system}, *messages]}
        payload = json.loads(messages[0]["content"])
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
        return {"content": json.dumps(transform(answer, payload)),
                "provider": "native-test", "model": "selected-session-model"}

    return calls, concurrency, generate


async def test_hallucinated_handle_on_uncited_assertion_fails_with_missing_evidence_instead_of_format_loop(settings, monkeypatch):
    prepared = prepared_review(2)
    prepared["units"][1]["quotes"] = []

    def hallucinate(answer, payload):
        answer["units"][1].update(verdict="SUPPORTED", quote_ids=["q1"])
        return answer

    calls, _, generate = mock_provider(hallucinate)
    review = await ReportAuditor(settings).review(prepared, generate=generate)
    assert not review["passed"]
    assert len(calls) == 1
    assert review["units"][1]["verdict"] == "INSUFFICIENT"
    assert review["units"][1]["quote_ids"] == []
    assert "未附原文引用" in review["units"][1]["reason"]


@pytest.mark.parametrize("defect", ["invented_measurement", "wrong_quote", "omitted_quote"])
async def test_reviewer_errors_stay_in_bounded_checker_repair_instead_of_author_findings(settings, monkeypatch, defect):

    def mismatched(answer, payload):
        if defect == "invented_measurement":
            answer["units"][0].update(reason="原文测量需24小时，已经满足。")
        elif defect == "wrong_quote":
            answer["units"][1].update(quote_ids=["s1"])
        else:
            answer["units"][0].update(quote_ids=[])
        return answer

    calls, _, generate = mock_provider(mismatched)
    auditor = ReportAuditor(settings)
    prepared = prepared_review(3)
    before = copy.deepcopy(prepared)
    with pytest.raises(ReviewOutputInvalid) as invalid:
        await auditor.review(prepared, generate=generate)
    with pytest.raises(ReviewOutputInvalid):
        await auditor.review(prepared, repair=invalid.value, generate=generate)
    assert len(calls) == 2 and prepared == before
    failure = quality.unavailable_review(prepared, error=invalid.value)
    assert not failure["passed"] and not failure["units"]
    assert failure["review_diagnostics"]
    assert "24小时" not in json.dumps(failure, ensure_ascii=False)


async def test_batched_review_checks_every_assertion_and_full_report_coverage_with_bounded_concurrency(
    settings, monkeypatch
):
    settings.parallelism = 2
    reserved = []

    async def reserve():
        reserved.append(True)

    calls, concurrency, generate = mock_provider(admit=reserve)

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
    result = await ReportAuditor(settings).review(prepared, generate=generate)
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
    projected = facts[0]["source_quotes"][0]["source"]
    assert projected["publication_id"] == "pub-v1" and projected["page"] == 4
    assert "anchor" not in projected and "content_sha256" not in projected
    assert prepared["units"][0]["quotes"][0]["source"]["anchor"]["original"]["polygon"] == [0, 0, 1, 1]


@pytest.mark.parametrize("defect", ["omitted_unit", "foreign_quote", "contradiction", "missing_question"])
async def test_batching_cannot_hide_missing_facts_borrowed_quotes_or_incomplete_answers(
    settings, monkeypatch, defect
):

    def transform(answer, payload):
        if payload["units"] and payload["units"][0]["unit_id"] == "u0":
            if defect == "omitted_unit":
                answer["units"].pop()
            elif defect == "foreign_quote":
                answer["units"][0]["quote_ids"] = ["s12"]
            elif defect == "contradiction":
                answer["units"][0]["verdict"] = "CONTRADICTED"
        if payload["questions"] and defect == "missing_question":
            answer["questions"][0]["verdict"] = "MISSING"
        return answer

    _, _, generate = mock_provider(transform)
    if defect in {"omitted_unit", "foreign_quote"}:
        with pytest.raises(ReviewOutputInvalid):
            await ReportAuditor(settings).review(prepared_review(), generate=generate)
    else:
        result = await ReportAuditor(settings).review(prepared_review(), generate=generate)
        assert not result["passed"]


async def test_native_admission_failure_is_propagated_without_content_verdict(settings):
    async def exhausted():
        raise RuntimeError("Budget exhausted")

    calls, _, generate = mock_provider(admit=exhausted)
    with pytest.raises(RuntimeError, match="Budget exhausted"):
        await ReportAuditor(settings).review(prepared_review(), generate=generate)
    assert calls == []


async def test_format_repair_reuses_valid_batches_and_preserves_local_quote_aliases(settings, monkeypatch):
    failed_once = False

    def corrupt_once(answer, payload):
        nonlocal failed_once
        if payload["required_unit_ids"][:1] == ["u0"] and not failed_once:
            failed_once = True
            answer["units"].pop()
        return answer

    reserved = []

    async def reserve():
        reserved.append(True)

    calls, _, generate = mock_provider(corrupt_once, admit=reserve)
    auditor = ReportAuditor(settings)
    prepared = prepared_review()
    with pytest.raises(ReviewOutputInvalid) as invalid:
        await auditor.review(prepared, generate=generate)
    result = await auditor.review(prepared, repair=invalid.value, generate=generate)
    assert result["passed"] and result["reviewer"]["repair_attempted"]
    assert len(calls) == len(reserved) == 5
    assert calls[-1][1]["required_unit_ids"] == prepared["required_unit_ids"][:12]
    previous = json.loads(calls[-1][0]["messages"][2]["content"])
    assert previous["units"][0]["quote_ids"] == ["s1"]
    assert [unit["quote_ids"] for unit in result["units"]] == [[f"q{i}"] for i in range(25)]


async def test_shared_source_quotes_keep_their_identity_across_units_and_batches(settings, monkeypatch):
    prepared = prepared_review()
    shared = {"quote_id": "canonical-source-quote", "text": "Modulus: 12 kPa."}
    for unit in prepared["units"]:
        unit["quotes"] = [shared]
    calls, _, generate = mock_provider()
    result = await ReportAuditor(settings).review(prepared, generate=generate)
    assert result["passed"]
    assert all(unit["quote_ids"] == ["canonical-source-quote"] for unit in result["units"])
    assert shared["quote_id"] == "canonical-source-quote"
    assert all(
        unit["quotes"][0]["quote_id"] == "s1"
        for _, payload in calls
        for unit in payload["units"]
    )
    coverage = next(payload for _, payload in calls if payload.get("review_scope") == "QUESTION_COVERAGE")
    assert all(unit["quotes"] == [{"quote_id": "s1"}] for unit in coverage["report_context"])
    assert coverage["source_quotes"] == [{"quote_id": "s1", "text": shared["text"]}]


async def test_local_revision_reuses_exact_fact_checks_but_still_checks_the_complete_new_report(settings, monkeypatch):
    calls, _, generate = mock_provider()
    auditor = ReportAuditor(settings)
    original = prepared_review()
    first = await auditor.review(original, generate=generate)
    assert len(calls) == 4

    revised = copy.deepcopy(original)
    # The user-facing finding changes, and all following unit IDs shift. Neither
    # ID changes nor a local correction should force 24 other facts to be checked again.
    revised["units"][0]["text"] = "The sample modulus was 12 kPa."
    for i, unit in enumerate(revised["units"]):
        unit["unit_id"] = f"u{i + 100}"
    revised["required_unit_ids"] = [unit["unit_id"] for unit in revised["units"]]
    revised["reusable_units"] = quality.reusable_unit_reviews(revised, {"old": first})
    result = await auditor.review(revised, generate=generate)
    assert result["passed"] and result["reviewer"]["reused_unit_count"] == 24
    assert len(calls) == 5
    pending = calls[-1][1]
    assert pending["required_unit_ids"] == ["u100"]
    assert pending["required_question_ids"] == ["modulus"]
    assert [unit["text"] for unit in pending["report_context"]] == [unit["text"] for unit in revised["units"]]
    assert len(result["units"]) == 25


@pytest.mark.parametrize("change", ["object", "table_condition", "source_body", "source_version", "user_request"])
def test_review_reuse_invalidates_when_the_object_conditions_or_source_changes(change):
    prepared = prepared_review(1)
    answer = {"units": [{"unit_id": "u0", "verdict": "SUPPORTED", "quote_ids": ["q0"], "reason": "Exact source"}],
              "questions": [{"question_id": "modulus", "verdict": "COVERED", "reason": "Answered"}]}
    previous = quality.validate_review(prepared, answer)
    changed = copy.deepcopy(prepared)
    if change == "object":
        changed["units"][0]["text"] = "A different sample modulus was 12 kPa."
    elif change == "table_condition":
        changed["units"][0]["table_columns"] = ["Sample", "after aging"]
    elif change == "source_body":
        changed["units"][0]["quotes"][0]["text"] = "The value was 12 MPa."
    elif change == "source_version":
        changed["units"][0]["quotes"][0]["source"] = {"publication_id": "new-version"}
    else:
        changed["request"] = "Can this be used on another substrate?"
    assert quality.reusable_unit_reviews(prepared, {"previous": previous})
    assert quality.reusable_unit_reviews(changed, {"previous": previous}) == []


@pytest.mark.parametrize("malformed", [None, "invalid", {"units": ["invalid"], "questions": []}])
async def test_malformed_response_is_a_format_failure(settings, monkeypatch, malformed):
    _, _, generate = mock_provider(lambda answer, payload: malformed)
    with pytest.raises(ReviewOutputInvalid):
        await ReportAuditor(settings).review(prepared_review(1), generate=generate)


async def test_review_records_the_native_session_provider_and_model(settings):
    _, _, generate = mock_provider()
    result = await ReportAuditor(settings).review(prepared_review(1), generate=generate)
    assert result["reviewer"]["provider"] == "native-test"
    assert result["reviewer"]["model"] == "selected-session-model"
