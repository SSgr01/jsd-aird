import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException

from aird_agent.capabilities.business_data import business_pack, report_project_ids
from aird_agent.core.capabilities import ExecutionContext
from aird_agent.core.read_cache import ReadCache
from aird_agent.core.tool_arguments import repair_read_arguments


def test_read_parameter_locations_are_repaired_losslessly_once_and_conflicts_stay_invalid():
    from jsonschema import validate, ValidationError
    schema = next(tool["parameters"] for tool in business_pack().tools if tool["name"] == "business_read_many")
    args = {"requests": [{"ref": {"type": "EXPERIMENT", "id": "a", "section": "content", "offset": 8},
                          "version_id": "fixed"}]}
    fixed, repairs = repair_read_arguments(args, schema, read_only=True)
    validate(fixed, schema)
    assert fixed == {"requests": [{"ref": {"type": "EXPERIMENT", "id": "a", "version_id": "fixed"},
                                  "section": "content", "offset": 8}]}
    assert len(repairs) == 3 and "section" in args["requests"][0]["ref"]
    assert repair_read_arguments(fixed, schema, read_only=True) == (fixed, [])
    assert repair_read_arguments(args, schema, read_only=False) == (args, [])
    conflict = {"requests": [{"ref": {"type": "EXPERIMENT", "id": "a", "section": "content", "version_id": "one"},
                             "section": "record", "version_id": "two"}]}
    unchanged, repairs = repair_read_arguments(conflict, schema, read_only=True)
    assert unchanged == conflict and not repairs
    with pytest.raises(ValidationError):
        validate(unchanged, schema)


def test_empty_batch_only_becomes_server_continuation_for_background_reads():
    from jsonschema import validate, ValidationError
    schema = next(tool["parameters"] for tool in business_pack().tools if tool["name"] == "business_read_many")
    args = {"requests": []}
    fixed, repairs = repair_read_arguments(args, schema, read_only=True, allow_pending_batch=True)
    assert fixed == {} and repairs[0]["rule"] == "continue_pending_batch"
    validate(fixed, schema)
    assert repair_read_arguments(args, schema, read_only=True) == (args, [])
    with pytest.raises(ValidationError):
        validate(args, schema)
from aird_agent.hosts.jsd.briefings import Briefings, READ_TOOLS, ref_key, coverage


@pytest.mark.parametrize("name,arguments", [
    ("business_search", {"types": ["EXPERIMENT", "CUSTOMER"], "query": "part"}),
    ("business_search", {"type": "CUSTOMER", "query": "part"}),
    ("business_read_many", {"requests": [{"ref": {"type": "EXPERIMENT", "id": "a"}},
        {"ref": {"type": "CUSTOMER", "id": "b"}}]}),
])
async def test_background_research_allowlist_covers_every_type_before_dispatch(principal, name, arguments):
    from aird_agent.core.business_service import BusinessService
    service = BusinessService.__new__(BusinessService)
    service.store = SimpleNamespace(get=AsyncMock(return_value={"id": "r", "automation": {"builtin_id": "research-weekly"}}))
    service.tools_for = lambda run: business_pack().tools
    service.authorized_topic = AsyncMock()
    with pytest.raises(HTTPException, match="BACKGROUND_RESEARCH_ONLY"):
        await service._tool(principal, "r", 1, "call", name, arguments)
    service.authorized_topic.assert_not_awaited()


async def test_background_related_project_scope_comes_only_from_frozen_server_refs(principal):
    snapshot = {"frozen": True, "sections": {"PROJECT": [], "EXPERIMENT": [
        {"ref": {"type": "EXPERIMENT", "id": "a", "project_id": "associated"}},
        {"ref": {"type": "EXPERIMENT", "id": "b"}},
    ]}}
    assert report_project_ids(snapshot) == ["associated"]
    host = AsyncMock(return_value={"candidates": []})
    run = {"id": "r", "automation": {}, "briefing_snapshot": snapshot}
    run["automation"] = {"builtin_id": "research-weekly"}
    context = ExecutionContext(principal, run, {}, host, runtime=SimpleNamespace(briefings=Briefings))
    pack = business_pack()
    await pack.handlers["business_read"](context, {"ref": {"type": "PROJECT", "id": "associated"}})
    assert host.call_args.args[2]["report_project_ids"] == ["associated"]
    await pack.handlers["business_search"](context, {"type": "PROJECT", "query": "name", "read_records": False,
        "filters": {"project_id": "unrelated"}})
    assert host.call_args.args[2]["report_project_ids"] == ["associated"]
    assert snapshot["sections"]["PROJECT"] == []


def test_pending_batches_keep_pins_advance_delivered_records_and_find_real_page_gaps():
    refs = [{"type": "EXPERIMENT", "id": str(i), "version_id": "version", "version_hash": "hash"} for i in range(10)]
    snapshot = {"frozen": True, "sections": {"EXPERIMENT": [{"ref": ref} for ref in refs]}, "reads": {}}
    run = {"briefing_snapshot": snapshot}
    assert Briefings.pending_reads(run) == [{"ref": ref, "section": "record"} for ref in refs[:8]]
    for ref in refs[:8]:
        Briefings.record_read(run, "business_read", {"ref": ref}, {"candidates": [{"ref": ref}],
            "reading": {"section": "record", "complete": True, "section_counts": {"testResults": 2}}})
    assert snapshot["seen"]["EXPERIMENT"] == list(range(8))
    assert Briefings.pending_reads(run) == [{"ref": ref, "section": "record"} for ref in refs[8:]]
    snapshot["reads"][ref_key(refs[8])] = {"overview": True, "counts": {"testResults": 7},
        "pages": {"testResults": {"0": 2, "4": None}}}
    assert Briefings.pending_reads(run)[0] == {"ref": refs[8], "section": "testResults", "offset": 2, "limit": 40}
    assert coverage(snapshot)["complete"] is False
    progress = Briefings.reading_progress(run)
    assert progress["remaining_record_count"] == 2 and progress["next_request"] == {}
    for ref in refs[8:]:
        Briefings.record_read(run, "business_read", {"ref": ref}, {"candidates": [{"ref": ref}],
            "reading": {"section": "record", "complete": True}})
    progress = Briefings.reading_progress(run)
    assert progress["complete"] is True and progress["next_request"] is None
    assert progress["remaining_gap_count"] == 0


def test_coverage_progress_tracks_contiguous_pages_without_relaxing_completeness():
    ref = {"type": "EXPERIMENT", "id": "large", "version_id": "fixed"}
    reading = {"overview": True, "counts": {"content": 32}, "pages": {"content": {}}}
    snapshot = {"frozen": True, "sections": {"EXPERIMENT": [{"ref": ref}]},
                "seen": {"EXPERIMENT": [0]}, "reads": {ref_key(ref): reading}}
    run = {"briefing_snapshot": snapshot}
    before = coverage(snapshot)
    cursor = Briefings.reading_progress(run)["cursor"]
    assert before["missing"] == [{"ref": ref, "section": "content", "offset": 0}]
    for start in (0, 8, 16):
        reading["pages"]["content"][str(start)] = start + 8
        after = coverage(snapshot)
        assert not after["complete"] and len(after["missing"]) == len(before["missing"])
        assert after["missing"][0]["offset"] == start + 8
        assert after != before
        next_cursor = Briefings.reading_progress(run)["cursor"]
        assert next_cursor != cursor
        assert Briefings.reading_progress(run)["cursor"] == next_cursor
        cursor = next_cursor
        assert Briefings.pending_reads(run)[0]["offset"] == start + 8
        before = after
    reading["pages"]["content"]["30"] = None
    assert coverage(snapshot) == before  # A disconnected page is no progress.
    reading["pages"]["content"]["24"] = None
    assert coverage(snapshot)["complete"]


def test_pending_large_record_reads_independent_sections_together_without_guessing_pages():
    ref = {"type": "EXPERIMENT", "id": "large", "version_id": "fixed", "version_hash": "pin"}
    snapshot = {"frozen": True, "sections": {"EXPERIMENT": [{"ref": ref}]}, "reads": {
        ref_key(ref): {"overview": True, "counts": {"content": 151, "samples": 3, "testResults": 102},
                      "pages": {"content": {"0": 24}}}}}
    requests = Briefings.pending_reads({"briefing_snapshot": snapshot})
    assert requests == [{"ref": ref, "section": part, "offset": offset, "limit": 40}
                        for part, offset in [("content", 24), ("samples", 0), ("testResults", 0)]]
    assert len({(x["section"], x["offset"]) for x in requests}) == 3


async def test_only_background_can_select_the_next_batch_without_model_supplied_ids(principal):
    pack = business_pack()
    context = ExecutionContext(principal, {"id": "r"}, {}, AsyncMock(), runtime=SimpleNamespace())
    with pytest.raises(HTTPException, match="BUSINESS_READ_REQUESTS_REQUIRED"):
        await pack.handlers["business_read_many"](context, {})
    ref = {"type": "EXPERIMENT", "id": "server-record", "version_id": "fixed"}
    run = {"id": "r", "automation": {"builtin_id": "experiment-review"},
        "briefing_snapshot": {"frozen": True, "sections": {"EXPERIMENT": [{"ref": ref}]}}}
    host = AsyncMock(return_value={"candidates": [{"ref": ref}], "reading": {"section": "record", "complete": True}})
    runtime = SimpleNamespace(store=SimpleNamespace(get=AsyncMock(return_value=run)), briefings=Briefings)
    result = await pack.handlers["business_read_many"](ExecutionContext(principal, run, {}, host, runtime=runtime), {})
    assert host.call_args.args[2]["ref"] == ref
    assert result["items"][0]["request"] == {"ref": ref, "section": "record"}
    Briefings.record_read(run, "business_read_many", {}, result)
    assert Briefings.pending_reads(run) == []


async def test_batch_capacity_never_counts_omitted_bodies_and_continues_pinned_requests(principal):
    refs = [{"type": "EXPERIMENT", "id": str(i), "version_id": "fixed"} for i in range(8)]
    run = {"id": "r", "briefing_snapshot": {"frozen": True,
        "sections": {"EXPERIMENT": [{"ref": ref} for ref in refs]}}}
    async def host(user, action, args, rid):
        return {"candidates": [{"ref": args["ref"], "text": "完整正文🙂" * 2000}],
                "reading": {"section": "record", "complete": True}}
    context = ExecutionContext(principal, run, {}, host)
    pack = business_pack()
    result = await pack.handlers["business_read_many"](context, {"requests": [{"ref": ref} for ref in refs]})
    assert not result["complete"] and 0 < len(result["items"]) < 8
    assert all(candidate["text"] == "完整正文🙂" * 2000 for candidate in result["candidates"])
    Briefings.record_read(run, "business_read_many", {}, result)
    assert len(Briefings.pending_reads(run)) == len(result["remaining_requests"])
    assert Briefings.reading_progress(run)["remaining_record_count"] == len(result["remaining_requests"])
    following = await pack.handlers["business_read_many"](context, {"requests": result["remaining_requests"]})
    assert following["items"][0]["ref"] == result["remaining_requests"][0]["ref"]


async def test_oversize_record_returns_actual_fixed_overview_without_claiming_full_body(principal):
    ref = {"type": "EXPERIMENT", "id": "a", "version_id": "fixed"}
    calls = []
    async def host(user, action, args, rid):
        calls.append(args)
        section = args["section"]
        return {"candidates": [{"ref": ref, "text": "大正文" * 12000 if section == "record" else "共用方案"}],
                "reading": {"section": section, "complete": True, "section_counts": {"content": 80}}}
    run = {"id": "r", "briefing_snapshot": {"frozen": True, "sections": {"EXPERIMENT": [{"ref": ref}]}}}
    result = await business_pack().handlers["business_read"](ExecutionContext(principal, run, {}, host), {"ref": ref})
    assert [call["section"] for call in calls] == ["record", "overview"]
    assert result["record_requires_paging"] and result["candidates"][0]["text"] == "共用方案"
    Briefings.record_read(run, "business_read", {"ref": ref}, result)
    assert not coverage(run["briefing_snapshot"])["complete"]
    assert Briefings.pending_reads(run) == [{"ref": ref, "section": "content", "offset": 0, "limit": 40}]


async def test_single_flight_isolated_by_actor_run_scope_and_version_and_returns_copies(principal):
    cache, entered, finish = ReadCache(), asyncio.Event(), asyncio.Event()
    async def load():
        entered.set()
        await finish.wait()
        return {"rows": [{"value": 0, "missing": None}]}
    operation = AsyncMock(side_effect=load)
    run = {"id": "r", "epoch": 1, "scope_hash": "scope"}
    first = asyncio.create_task(cache.read(principal, run, "business_read", {"ref": {"id": "a"}}, operation))
    await entered.wait()
    second = asyncio.create_task(cache.read(principal, run, "business_read", {"ref": {"id": "a"}}, operation))
    await asyncio.sleep(0)
    finish.set()
    a, b = await asyncio.gather(first, second)
    a["rows"][0]["value"] = 9
    assert b["rows"] == [{"value": 0, "missing": None}]
    assert operation.await_count == 1
    assert (await cache.read(principal, run, "business_read", {"ref": {"id": "a"}}, operation)) == b
    for changed in ({**run, "id": "other"}, {**run, "scope_hash": "other"}, {**run, "epoch": 2}):
        await cache.read(principal, changed, "business_read", {"ref": {"id": "a"}}, operation)
    other = principal.model_copy(update={"actor_id": __import__('uuid').uuid4()})
    await cache.read(other, run, "business_read", {"ref": {"id": "a"}}, operation)
    await cache.read(principal, run, "business_read", {"ref": {"id": "a", "version_id": "v2"}}, operation)
    assert operation.await_count == 6


async def test_refresh_mutation_invalidation_and_inflight_reads_cannot_restore_old_snapshot(principal):
    cache, finish = ReadCache(), asyncio.Event()
    async def old():
        await finish.wait()
        return {"value": "old"}
    run, args = {"id": "r"}, {"query": "paper"}
    pending = asyncio.create_task(cache.read(principal, run, "business_search", args, old))
    await asyncio.sleep(0)
    cache.invalidate()
    fresh = AsyncMock(return_value={"value": "new"})
    await cache.read(principal, run, "business_search", args, fresh)
    finish.set()
    await pending
    assert (await cache.read(principal, run, "business_search", args, fresh))["value"] == "new"
    fresh.return_value = {"value": "latest"}
    await cache.read(principal, run, "business_search", {**args, "refresh": True}, fresh)
    assert (await cache.read(principal, run, "business_search", args, fresh))["value"] == "latest"
    assert fresh.await_count == 2


async def test_failed_reads_and_file_transfers_are_not_reused(principal):
    cache, run = ReadCache(), {"id": "r"}
    operation = AsyncMock(return_value={"error": "denied"})
    for _ in range(2):
        await cache.read(principal, run, "business_read", {}, operation)
    assert operation.await_count == 2
    operation.return_value = {"business_file": {"id": "f"}}
    for _ in range(2):
        await cache.read(principal, run, "business_read", {"include_file": True}, operation)
    assert operation.await_count == 4


async def test_cached_and_shared_sources_are_reauthorized_even_if_registration_previously_failed(principal):
    cache, run = ReadCache(), {"id": "r"}
    load = AsyncMock(return_value={"candidates": [{"source": {"resource_id": "a"}}]})
    validate = AsyncMock(side_effect=HTTPException(403, "ACCESS_REVOKED"))
    await cache.read(principal, run, "business_read", {}, load, validate)
    with pytest.raises(HTTPException, match="ACCESS_REVOKED"):
        await cache.read(principal, run, "business_read", {}, load, validate)
    assert load.await_count == 1 and validate.await_count == 1


async def test_small_search_hydrates_pinned_bodies_batch_reuses_and_failed_refs_stay_visible(principal):
    refs = [{"type": "EXPERIMENT", "id": "a", "version_id": "v1"},
            {"type": "EXPERIMENT", "id": "b", "version_id": "v2"}]
    async def host(user, action, args, rid):
        if action == "business_search":
            return {"candidates": [{"ref": ref, "text": "directory"} for ref in refs]}
        assert args["section"] == "record"
        assert "sample_key" not in args and "field_codes" not in args
        if args["ref"]["id"] == "b":
            raise HTTPException(403, "denied")
        return {"candidates": [{"ref": refs[0], "text": "body"}],
            "reading": {"section": "record", "complete": True, "section_counts": {"testResults": 1}}}
    host_mock = AsyncMock(side_effect=host)
    run = {"id": "r", "briefing_snapshot": {"sections": {}}}
    context = ExecutionContext(principal, run, {}, host_mock,
        runtime=SimpleNamespace(read_cache=ReadCache(), briefings=Briefings))
    pack = business_pack()
    search = await pack.handlers["business_search"](context, {"type": "EXPERIMENT", "query": "F8"})
    assert search["records_complete"] is False
    assert [item["text"] for item in search["candidates"]] == ["body", "directory"]
    batch = await pack.handlers["business_read_many"](context, {"requests": [
        {"ref": refs[0], "sample_key": " ", "field_codes": []}]})
    assert host_mock.await_count == 3  # Search + two authoritative reads; no second read of A.
    Briefings.record_read(run, "business_search", {}, search)
    assert next(iter(run["briefing_snapshot"]["reads"].values()))["overview"] is True
    assert "business_read_many" in READ_TOOLS
    assert batch["complete"] is True


async def test_multi_type_lookup_keeps_denial_distinct_and_hydrates_exact_successful_refs(principal):
    ref = {"type": "EXPERIMENT", "id": "resolved", "version_id": "fixed"}
    calls = []
    async def host(user, action, args, rid):
        calls.append((action, args))
        if action == "business_read":
            return {"candidates": [{"ref": ref, "text": "真实正文"}], "reading": {"section": "record", "complete": True}}
        assert args["query"] == "UA-1117"
        if args["type"] == "QUALITY_STANDARD":
            raise HTTPException(403, "ACCESS_DENIED")
        return {"candidates": [{"ref": ref, "text": "目录"}] if args["type"] == "EXPERIMENT" else [], "total": 1 if args["type"] == "EXPERIMENT" else 0}
    context = ExecutionContext(principal, {"id": "r"}, {}, host)
    result = await business_pack().handlers["business_search"](context,
        {"query": "UA-1117", "types": ["EXPERIMENT", "RAW_MATERIAL", "QUALITY_STANDARD"]})
    assert result["query_executed"] and not result["lookup_complete"] and not result["records_complete"]
    assert result["candidates"][0]["text"] == "真实正文"
    assert result["searches"][1] == {"type": "RAW_MATERIAL", "returned": 0, "total": 0}
    assert result["searches"][2]["status"] == 403 and result["searches"][2]["error"] == "ACCESS_DENIED"
    assert len(calls) == 4
    assert calls[-1][1]["ref"] == ref


def test_only_safe_read_pagination_is_repaired_once_without_dropping_meaningful_filters():
    spec = next(spec for spec in business_pack().tools if spec["name"] == "business_read_many")
    args = {"requests": [{"ref": {"type": "EXPERIMENT", "id": "a"}, "limit": 100, "field_codes": [], "sample_key": None}]}
    value, repairs = repair_read_arguments(args, spec["parameters"], read_only=True)
    assert value["requests"][0]["limit"] == 40 and len(repairs) == 3
    assert "field_codes" not in value["requests"][0]
    assert repair_read_arguments(value, spec["parameters"], read_only=True) == (value, [])
    assert repair_read_arguments(args, spec["parameters"], read_only=False) == (args, [])
    assert args["requests"][0]["limit"] == 100
