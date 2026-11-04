"""Run-scoped navigation receipts; bodies live only in the canonical evidence ledger."""

import copy

from aird_agent.core.contracts import digest
from aird_agent.evidence.models import SourceRef


def retrieval_progress(result, known):
    def version(item):
        source = item["source"]
        return (
            source.get("kind"),
            source.get("document_id"),
            source.get("publication_id"),
            source.get("version_id"),
        )

    candidates = result.get("candidates", []) + result.get("context_candidates", [])
    return {
        "new_evidence_count": len({item["evidence_id"] for item in candidates} - known.keys()),
        "new_source_versions": len(
            {version(item) for item in candidates} - {version(item) for item in known.values()}
        ),
    }


async def invalidate_search(runtime, principal, run, key):
    async with runtime.store.transaction(principal, run["id"]) as conn:
        active = await runtime.active(principal, run["id"], run["epoch"], conn)
        active.get("ranking_snapshots", {}).pop(key, None)
        active.pop("navigation_cache", None)
        active.pop("navigation_stalls", None)
        await runtime.store.update(principal, "run", active, conn)


async def cached_host(runtime, principal, run, action, args):
    current = await runtime.store.get(principal, "run", run["id"])
    policy = current.get("retrieval_policy", {})
    key_args = dict(args)
    if "query" in key_args:
        key_args["query"] = " ".join(key_args["query"].lower().split())
        key_args.setdefault("match_mode", "auto")
    key_args.setdefault("offset", 0)
    identity = [
        action,
        run["scope_hash"],
        run.get("private_documents"),
        list(principal.key),
        policy.get("fingerprint"),
    ]
    signature = digest([identity, key_args])
    search = action in {"knowledge_search", "private_source_search"}
    snapshot_key = digest([identity, {k: v for k, v in key_args.items() if k != "offset"}])
    progress_key = digest(
        [
            action,
            args.get("document_id") or args.get("source", {}).get("document_id"),
            args.get("source_node_key") or args.get("source", {}).get("source_node_key"),
            args.get("offset", 0),
            args.get("cursor", 0),
            args.get("relation", "neighbors"),
        ]
    )
    entry = current.get("navigation_cache", {}).get(signature)
    # A stalled page cannot be replayed indefinitely. An unseen query/window
    # still reaches the host: it may produce new evidence and resolve the stall.
    if entry and current.get("navigation_stalls", {}).get(progress_key, 0) >= 3:
        from fastapi import HTTPException

        raise HTTPException(
            409, "READ_NO_PROGRESS: switch to outline/context, read new quote spans or advance offset/window"
        )
    known_ids = set(current.get("evidence", {}))
    # Library reuse always goes through the host to verify the authorized window revision, without re-ranking.
    if (
        entry
        and entry["metadata"].get("trace", {}).get("scope") != "AUTHORIZED_LIBRARY"
        and all(eid in current["evidence"] for group in entry["groups"].values() for eid in group["ids"])
    ):
        result = copy.deepcopy(entry["metadata"])
        for name, group in entry["groups"].items():
            result[name] = [
                {**copy.deepcopy(current["evidence"][eid]), **location}
                for eid, location in zip(group["ids"], group["locations"])
            ]
        candidates = result.get("candidates", []) + result.get("context_candidates", [])
        if candidates:
            try:
                await runtime.host_call(
                    principal,
                    "validate_cached_evidence",
                    {"candidates": [{"source": c["source"], "text": c["text"]} for c in candidates]},
                    run["id"],
                )
            except Exception as error:
                from fastapi import HTTPException

                if search and isinstance(error, HTTPException) and error.status_code in (404, 409):
                    await invalidate_search(runtime, principal, run, snapshot_key)
                    raise HTTPException(
                        409, "RETRIEVAL_SNAPSHOT_CHANGED: restart search at offset 0"
                    ) from error
                raise
        result.setdefault("trace", {}).update(
            cache_hit=True, rewrite_model_calls=0, rerank_calls=0, embedding_calls=0
        )
        if search:
            result["trace"].update(retrieval_progress(result, current["evidence"]))
        if not candidates and "outline" not in action:
            from fastapi import HTTPException

            raise HTTPException(409, "READ_NO_PROGRESS: cached empty result; change query or source/window")
        async with runtime.store.transaction(principal, run["id"]) as conn:
            active = await runtime.active(principal, run["id"], run["epoch"], conn)
            stalls = active.setdefault("navigation_stalls", {})
            stalls[progress_key] = stalls.get(progress_key, 0) + 1
            await runtime.store.update(principal, "run", active, conn)
        return result
    request = dict(args)
    snapshot = current.get("ranking_snapshots", {}).get(snapshot_key) if search else None
    if snapshot:
        request["ranking_snapshot"] = snapshot
    elif search and policy.get("research", {}).get("rewrite") and request.get("match_mode", "auto") == "auto":
        await runtime.callback(
            principal, run["id"], "auxiliary", {"epoch": run["epoch"], "purpose": "research_query_rewrite"}
        )
        request["rewrite_budget_reserved"] = True
    try:
        result = await runtime.host_call(principal, action, request, run["id"])
    except Exception as error:
        from fastapi import HTTPException

        if isinstance(error, HTTPException) and "RETRIEVAL_SNAPSHOT_CHANGED" in str(error.detail):
            await invalidate_search(runtime, principal, run, snapshot_key)
        raise
    # Snapshots contain handles/versions/positions only. They are never exposed as editable tool arguments.
    ranking = result.pop("ranking_snapshot", None)
    groups = {}
    for name in ("candidates", "context_candidates"):
        ids, locations = [], []
        for candidate in result.get(name, []):
            candidate["evidence_id"] = (
                "e" + digest(SourceRef.model_validate(candidate["source"]).model_dump(mode="json"))[:24]
            )
            ids.append(candidate["evidence_id"])
            locations.append({k: candidate[k] for k in ("node_offset", "role", "score") if k in candidate})
        groups[name] = {"ids": ids, "locations": locations}
    if search:
        result.setdefault("trace", {}).update(retrieval_progress(result, current.get("evidence", {})))
    await runtime.remember_sources(principal, run, result)
    ids = set(eid for group in groups.values() for eid in group["ids"])
    async with runtime.store.transaction(principal, run["id"]) as conn:
        current = await runtime.active(principal, run["id"], run["epoch"], conn)
        stalls = current.setdefault("navigation_stalls", {})
        blocked = stalls.get(progress_key, 0) >= 3 and not (ids - known_ids) and "outline" not in action
        stalls[progress_key] = (
            stalls.get(progress_key, 0) + 1 if not (ids - known_ids) and "outline" not in action else 0
        )
        cache = current.setdefault("navigation_cache", {})
        cache[signature] = {
            "groups": groups,
            "metadata": {k: v for k, v in result.items() if k not in groups},
        }
        while len(cache) > 32:
            del cache[next(iter(cache))]
        if ranking is not None:
            snapshots = current.setdefault("ranking_snapshots", {})
            snapshots[snapshot_key] = ranking
            while len(snapshots) > 16:
                del snapshots[next(iter(snapshots))]
        await runtime.store.update(principal, "run", current, conn)
    if blocked:
        from fastapi import HTTPException

        raise HTTPException(
            409, "READ_NO_PROGRESS: no new evidence; read existing source quotes or advance the source window"
        )
    return result


async def context_for(runtime, principal, run, candidate, before=1, after=1, cursor=0, relation="neighbors"):
    source = candidate["source"]
    if source["kind"] not in {"KB_PUBLICATION", "RUN_UPLOAD", "EXTERNAL_FULLTEXT"}:
        from fastapi import HTTPException

        raise HTTPException(422, "SOURCE_KIND_HAS_NO_DOCUMENT_CONTEXT")
    private = source["kind"] in {"RUN_UPLOAD", "EXTERNAL_FULLTEXT"}
    if private and relation == "parent":
        return {"candidates": [], "trace": {"parent_status": "NOT_APPLICABLE", "fulltext_coverage": False}}
    args = {"before": before, "after": after, "cursor": cursor, "relation": relation}
    if private:
        args.update(
            document_id=source["document_id"],
            source_node_key=source["source_node_key"],
            conversation_id=run["conversation_id"],
        )
    else:
        args["source"] = source
    return await cached_host(
        runtime, principal, run, "private_source_context" if private else "source_context", args
    )


def preview_context(result):
    return {
        "nodes": [
            {
                "evidence_id": c["evidence_id"],
                "node_offset": c.get("node_offset"),
                "role": c.get("role"),
                "preview": c["text"][:300],
                "preview_only": True,
            }
            for c in result.get("candidates", [])
        ],
        "next_cursor": result.get("next_offset"),
        "trace": result.get("trace", {}),
        "instruction": "Use evidence_read for exact source quotations; previews are not complete reading.",
    }


def search_preview(result, budget):
    """Bound search transport, retaining canonical bodies/quote handles in the ledger."""
    import json
    import re

    from aird_agent.evidence.quotes import quotes

    terms = re.findall(r"\w+", result.get("query", "").casefold())
    page = {
        "hits": [],
        "trace": result.get("trace", {}),
        "context": result.get("context"),
        "parent_context": [],
        "next_offset": result.get("next_offset"),
        "next_cursor": result.get("next_cursor"),
        "instruction": "Hits contain exact source quotes, not complete nodes. Use evidence_read with evidence_id and quote_offset for more; evidence_context for neighbors.",
    }
    parent_used = 0
    for candidate in result.get("context_candidates", []):
        context = {
            "evidence_id": candidate["evidence_id"],
            "source": candidate["source"],
            "role": "PARENT_CONTEXT",
            "text": candidate["text"],
            "quote_count": len(quotes(candidate)),
        }
        size = len(json.dumps(context, ensure_ascii=False)) + 2
        if parent_used + size > min(6000, budget // 3):
            page.setdefault("parent_evidence_ids", []).append(candidate["evidence_id"])
            continue
        page["parent_context"].append(context)
        parent_used += size
    for candidate in result.get("candidates", []):
        spans = quotes(candidate)
        index = max(
            range(len(spans)),
            key=lambda i: sum(term in spans[i]["text"].casefold() for term in terms),
            default=0,
        )
        hit = {
            "evidence_id": candidate["evidence_id"],
            "source": candidate["source"],
            "node_offset": candidate.get("node_offset"),
            "quote_offset": index,
            "quote_count": len(spans),
            "quote": spans[index] if spans else None,
        }
        proposed = {**page, "hits": [*page["hits"], hit]}
        if len(json.dumps(proposed, ensure_ascii=False)) > budget - 128:
            if not page["hits"]:
                page.pop("context", None)
                proposed = {**page, "hits": [hit]}
            if len(json.dumps(proposed, ensure_ascii=False)) > budget - 128:
                break
        page = proposed
    total = result.get("trace", {}).get("candidate_count", 0)
    offset = result.get("trace", {}).get("offset", 0)
    if len(page["hits"]) < len(result.get("candidates", [])):
        page["next_offset"] = offset + len(page["hits"])
    if not page["hits"] and result.get("candidates"):
        page["evidence_ids"] = [result["candidates"][0]["evidence_id"]]
        page["instruction"] = (
            "Source metadata exceeds preview budget; use evidence_read with this evidence ID."
        )
    page["trace"] = {**page["trace"], "candidate_count": total, "preview_only": True}
    return page
