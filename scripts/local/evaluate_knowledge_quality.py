#!/usr/bin/env python3
"""Run a locally supplied knowledge-quality suite through the normal APIs.

Gold answers stay in the local suite and are never sent to the model. Outputs,
including source text and events, belong in an ignored runtime directory.
Password: JSD_AIRD_EVAL_PASSWORD. No writes are retried automatically.

Usage: --cases <local JSON> --output <ignored directory> [--cross-entrypoints].
The manifest contains a cases array with id, question, entrypoint (qa/research),
split (development/acceptance), knowledge_category_ids and authorized documents
(document_id, publication_id). expected and evidence_groups are local judgments.
--retrieval-only accepts id, query, kind, context_refs and local evidence_groups;
it also requires JSD_AIRD_EVAL_TOOL_TICKET from the existing delegation flow.
Retrieval metrics rank CHILD hits only. Parent context and per-page traces are
retained separately for review and are never counted as extra ranked documents.
"""

from __future__ import annotations

import argparse
import concurrent.futures
from datetime import datetime
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import threading
import time
import urllib.error
import urllib.request
import uuid


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def sse_events(response, deadline=None):
    event, data = "message", []
    for raw in response:
        if deadline is not None and time.monotonic() > deadline:
            raise TimeoutError("Evaluation deadline reached; execution is not replayed")
        line = raw.decode("utf-8").rstrip("\r\n")
        if not line:
            if data:
                yield event, json.loads("\n".join(data))
            event, data = "message", []
        elif line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].lstrip())
    if data:
        yield event, json.loads("\n".join(data))


class Client:
    def __init__(self, base_url, username, password, timeout):
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )
        self.csrf = self.json("GET", "/api/v1/auth/csrf")["token"]
        self.json("POST", "/api/v1/auth/login", {
            "username": username, "password": password, "rememberMe": False,
        })
        self.csrf = self.json("GET", "/api/v1/auth/csrf")["token"]

    def request(self, method, path, value=None, *, last_event_id=None):
        headers = {"Accept": "text/event-stream" if path.endswith(("/events", "/qa/stream"))
                   else "application/json"}
        if last_event_id is not None:
            headers["Last-Event-ID"] = str(last_event_id)
        body = None
        if value is not None:
            body = json.dumps(value, ensure_ascii=False).encode()
            headers.update({"Content-Type": "application/json", "X-XSRF-TOKEN": self.csrf,
                            "Idempotency-Key": str(uuid.uuid4())})
        if path == "/internal/v1/agent-tools":
            ticket = os.environ.get("JSD_AIRD_EVAL_TOOL_TICKET")
            if not ticket:
                raise RuntimeError("Retrieval evaluation requires a current authorized JSD_AIRD_EVAL_TOOL_TICKET")
            headers["X-Jsd-Agent-Ticket"] = ticket
        request = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        try:
            return self.opener.open(request, timeout=self.timeout)
        except urllib.error.HTTPError as error:
            # Do not echo headers, tokens or raw provider errors.
            try:
                code = json.load(error).get("code", "HTTP_ERROR")
            except (ValueError, AttributeError):
                code = "HTTP_ERROR"
            raise RuntimeError(f"HTTP {error.code}: {code}") from None

    def json(self, method, path, value=None):
        with self.request(method, path, value) as response:
            result = json.load(response)
        return result.get("data", result) if isinstance(result, dict) else result


def model_request(case, entrypoint):
    """Explicit allowlist: expected values, gold passages and grading never cross the API."""
    if entrypoint == "qa":
        return {
            "question": case["question"],
            "knowledgeCategoryIds": case.get("knowledge_category_ids", []),
            "dataCategoryIds": case.get("data_category_ids", []),
            "webSearchEnabled": False,
        }
    return {
        "request": case["question"], "profile": "agent",
        "pack_ids": case.get("pack_ids", ["core", "literature", "files"]),
        "context_refs": [
            {"kind": "KB_PUBLICATION", "resource_id": d["document_id"],
             "publication_id": d["publication_id"]}
            for d in case.get("documents", [])
        ],
        "retrieval_scope": {"knowledge_category_ids": case.get("knowledge_category_ids", []),
                            "web_search_enabled": False},
    }


def evidence_metrics(hits, groups, key_documents, relevant_documents):
    """Alternative supporting passages form one group; duplicate hits cannot inflate recall."""
    matched = set()
    documents = []
    unique_hits, seen = [], set()
    for hit in hits:
        source = hit.get("source", hit)
        key = (source.get("document_id", source.get("documentId")),
               source.get("version_id", source.get("versionId")),
               source.get("source_node_key", hit.get("chunkId")), source.get("content_sha256"))
        if key not in seen:
            seen.add(key)
            unique_hits.append(hit)
    for hit in unique_hits:
        source = hit.get("source", hit)
        identity = (source.get("document_id", source.get("documentId")),
                    source.get("version_id", source.get("versionId")))
        if identity not in documents:
            documents.append(identity)
    for hit in unique_hits[:20]:
        source = hit.get("source", hit)
        identity = (source.get("document_id", source.get("documentId")),
                    source.get("version_id", source.get("versionId")))
        compact = " ".join(hit.get("text", hit.get("content", "")).casefold().split())
        for i, group in enumerate(groups):
            for alternative in group["alternatives"]:
                if (identity[0] == alternative["document_id"] and
                    identity[1] == alternative["version_id"] and
                    " ".join(alternative["text"].casefold().split()) in compact):
                    matched.add(i)
    relevant = set(relevant_documents)
    first_ten = unique_hits[:10]
    precision = sum(
        h.get("source", h).get("document_id", h.get("documentId")) in relevant for h in first_ten
    ) / len(first_ten) if first_ten else 0.0
    return {
        "evidence_recall_at_20": len(matched) / len(groups) if groups else None,
        "key_documents_at_5": set(key_documents).issubset({d[0] for d in documents[:5]})
        if key_documents else None,
        "precision_at_10": precision,
        "returned_at_10": len(first_ten),
        "relevance_basis": "frozen document judgments; paragraph relevance requires human review",
        "matched_evidence_groups": sorted(matched),
        "required_evidence_groups": len(groups),
    }


def measured_usage(record):
    if record["entrypoint"] == "qa":
        usage = record.get("response", {}).get("usage", {})
        return {"input": usage.get("inputTokens"), "output": usage.get("outputTokens"),
                "cacheRead": None, "cacheWrite": None}
    totals = {key: 0 for key in ("input", "output", "cacheRead", "cacheWrite")}
    count = 0
    for event in record.get("events", []):
        data = event["data"]
        if isinstance(data, dict) and data.get("type") == "message.completed":
            usage = data.get("payload", {}).get("usage")
            if usage:
                count += 1
                for key in totals:
                    totals[key] += usage.get(key, 0) or 0
    return {**totals, "measured_generation_calls": count,
            "coverage": "pi generation events; auxiliary review/retrieval usage may be unavailable"}


def durable_timing(record):
    """Replay receipt times are not token latency. Server event times survive collection recovery."""
    events = [e["data"] for e in record.get("events", []) if isinstance(e.get("data"), dict)]
    def timestamp(event):
        return datetime.fromisoformat(event["timestamp"]).timestamp()
    created = next((e for e in events if e.get("type") == "run.queued"), None)
    first = next((e for e in events if e.get("type") == "message.delta" and e.get("payload", {}).get("delta")), None)
    last = next((e for e in reversed(events) if e.get("type") in {"run.succeeded", "run.failed", "run.cancelled", "approval.required"}), None)
    result = {"active_seconds": record.get("response", {}).get("active_seconds")}
    if created and first:
        result["first_text_ms"] = round((timestamp(first) - timestamp(created)) * 1000)
    if created and last:
        result["total_ms"] = round((timestamp(last) - timestamp(created)) * 1000)
    if created and record.get("response", {}).get("started_at"):
        started_at = record["response"]["started_at"]
        # A resumed run may expose its latest lease start. It cannot be the
        # initial start when text was already produced before that time.
        if not first or started_at <= timestamp(first):
            result["initial_queue_ms"] = max(0, round((started_at - timestamp(created)) * 1000))
        else:
            result["initial_queue_ms"] = None
            result["queue_timing_note"] = "Initial start unavailable after resume"
    return result


def channel_metrics(hits, trace, relevant_documents):
    """Trace rankings contain chunk IDs, which need not equal evidence node keys."""
    by_chunk = {}
    for hit in hits:
        source = hit.get("source", {})
        chunk_id = source.get("anchor", {}).get("chunkId") or hit.get("chunkId")
        if chunk_id:
            by_chunk[chunk_id] = hit
    return [
        {"channel": query["channel"], "status": query["status"], "result_count": query["resultCount"],
         "ranked_hits_resolved": sum(key in by_chunk for key in query.get("topChunkIds", [])),
         "metrics": evidence_metrics([by_chunk[key] for key in query.get("topChunkIds", []) if key in by_chunk],
                                     [], [], relevant_documents)}
        for query in trace.get("queries", [])
    ]


def run_retrieval(client, case, output):
    path = output / (case["id"] + "-retrieval.json")
    if path.exists():
        return {"case": case["id"], "skipped": "already recorded"}
    # Only the query and authorized scope are sent; gold evidence and judgments stay local.
    args = {"query": case["query"], "profile": "agent", "context_refs": case.get("context_refs", []),
            "retrieval_scope": {"web_search_enabled": False}, "offset": 0}
    record = {"case": case["id"], "query": case["query"], "kind": case.get("kind"), "status": "RESULT_UNKNOWN",
              "hits": [], "context_pages": []}
    write_json(path, record)
    started = time.monotonic()
    try:
        first = client.json("POST", "/internal/v1/agent-tools", {"action": "knowledge_search", "arguments": args})
        page = first
        while True:
            record["hits"].extend(page.get("candidates", []))
            record["context_pages"].append({
                "offset": args["offset"], "context_candidates": page.get("context_candidates", []),
                "document_metadata": page.get("document_metadata", []), "trace": page.get("trace", {}),
            })
            if page.get("next_offset") is None:
                break
            args.update(offset=page["next_offset"], ranking_snapshot=first["ranking_snapshot"])
            page = client.json("POST", "/internal/v1/agent-tools", {"action": "knowledge_search", "arguments": args})
        record.update(status="SUCCEEDED", trace=first.get("trace", {}))
        record["metrics"] = evidence_metrics(record["hits"], case.get("evidence_groups", []), case.get("key_documents", []), case.get("relevant_documents", []))
        record["channels"] = channel_metrics(record["hits"], record["trace"], case.get("relevant_documents", []))
    except Exception as error:
        record.update(status="FAILED", error=type(error).__name__ + ": " + str(error))
    record["total_ms"] = round((time.monotonic() - started) * 1000)
    write_json(path, record)
    return {key: record.get(key) for key in ("case", "status", "total_ms", "metrics")}


def run_case(client, case, entrypoint, output):
    path = output / f"{case['id']}-{entrypoint}.json"
    start = time.monotonic()
    record = {"case": case["id"], "entrypoint": entrypoint, "question": case["question"], "status": "RESULT_UNKNOWN",
              "started_at": time.time(), "events": [], "cost": None, "pricing": "UNKNOWN", "client_timing_valid": True}
    existing_run = None
    if path.exists():
        previous = json.loads(path.read_text(encoding="utf-8"))
        if previous.get("status") not in {"RESULT_UNKNOWN", "RUNNING", "QUEUED"} or not previous.get("run_id"):
            return {"case": case["id"], "entrypoint": entrypoint, "skipped": "already recorded"}
        existing_run = previous["run_id"]
        record = previous
        record.setdefault("initial_collection_ms", previous.get("total_ms"))
        record.pop("error", None)
        record["client_timing_valid"] = False
    write_json(path, record)  # A partial/unknown request is never silently replayed.
    try:
        body = model_request(case, entrypoint)
        if entrypoint == "qa":
            response = client.request("POST", "/api/v1/assistant/qa/stream", body)
        else:
            if not existing_run:
                created = client.json("POST", "/api/v1/agents/runs", body)
                record["run_id"] = created.get("run_id", created.get("id"))
                record["conversation_id"] = created.get("conversation_id")
            write_json(path, record)
            response = None
        deadline = start + client.timeout
        seen = {event["data"]["seq"] for event in record["events"]
                if isinstance(event.get("data"), dict) and "seq" in event["data"]}
        while True:
            if entrypoint == "research":
                response = client.request("GET", f"/api/v1/agents/runs/{record['run_id']}/events",
                                          last_event_id=max(seen, default=0))
            with response:
                for name, data in sse_events(response, deadline=deadline):
                    seq = data.get("seq") if isinstance(data, dict) else None
                    if seq is not None and seq in seen:
                        continue
                    if seq is not None:
                        seen.add(seq)
                    collect_event(record, name, data, start)
                    kind = data.get("type", name) if isinstance(data, dict) else name
                    if entrypoint == "qa" and name == "done":
                        record.update(response=data, answer=data.get("answer", ""), status="SUCCEEDED")
                        break
                    if kind == "error":
                        record["status"] = "FAILED"
                    if kind in {"run.succeeded", "run.completed", "run.failed", "run.cancelled", "approval.required", "approval.ready"}:
                        break
                    if len(record["events"]) % 20 == 0:
                        write_json(path, record)
            if entrypoint == "qa":
                break
            run = client.json("GET", f"/api/v1/agents/runs/{record['run_id']}")
            record.update(response=run, status=run["status"], answer=run.get("result") or run.get("last_text", ""))
            write_json(path, record)
            if run["status"] not in {"RUNNING", "QUEUED"}:
                break
            if time.monotonic() >= deadline:
                raise TimeoutError("Collection deadline reached; use the same output to recover this run without re-executing it")
            # A stream EOF does not imply a completed task. Only reconnect to its
            # durable events; never create or resume model execution here.
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        if entrypoint == "research":
            record["server_timing"] = durable_timing(record)
        record["model_usage"] = measured_usage(record)
    except Exception as error:
        record["error"] = type(error).__name__ + ": " + str(error)
        record["status"] = "RESULT_UNKNOWN" if record.get("run_id") else "FAILED"
    record["collection_ms"] = round((time.monotonic() - start) * 1000)
    record["total_ms"] = record.get("server_timing", {}).get("total_ms", record["collection_ms"])
    write_json(path, record)
    return {key: record.get(key) for key in ("case", "entrypoint", "status", "total_ms", "first_text_ms")}


def collect_event(record, name, data, start):
    """Keep server sequence IDs so reconnects cannot double count model usage."""
    elapsed = round((time.monotonic() - start) * 1000)
    record["events"].append({"event": name, "data": data, "elapsed_ms": elapsed})
    kind = data.get("type", name) if isinstance(data, dict) else name
    if kind in {"message.delta", "delta", "token"} and data and record["client_timing_valid"]:
        record.setdefault("first_text_ms", elapsed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--base-url", default="http://127.0.0.1:8180")
    parser.add_argument("--username", default=os.environ.get("JSD_AIRD_EVAL_USERNAME", "admin"))
    parser.add_argument("--workers", type=int, choices=range(1, 5), default=2)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--split", choices=("all", "development", "acceptance"), default="all")
    parser.add_argument("--only", help="Comma-separated case IDs")
    parser.add_argument("--cross-entrypoints", action="store_true")
    parser.add_argument("--retrieval-only", action="store_true", help="Manifest of frozen retrieval queries; requires an authorized tool ticket")
    args = parser.parse_args()
    password = os.environ.get("JSD_AIRD_EVAL_PASSWORD")
    if not password:
        parser.error("Set JSD_AIRD_EVAL_PASSWORD without putting credentials in the case file")
    raw = args.cases.read_bytes()
    suite = json.loads(raw)
    cases = suite if isinstance(suite, list) else suite["cases"]
    if len({c["id"] for c in cases}) != len(cases):
        parser.error("Case IDs must be unique")
    args.output.mkdir(parents=True, exist_ok=True)
    lock = args.output / "suite.sha256"
    digest = hashlib.sha256(raw).hexdigest()
    if lock.exists() and lock.read_text().strip() != digest:
        parser.error("Suite changed; use a new output directory")
    lock.write_text(digest, encoding="ascii")
    local = threading.local()

    def execute(case, entrypoint):
        if not hasattr(local, "client"):
            local.client = Client(args.base_url, args.username, password, args.timeout)
        return run_retrieval(local.client, case, args.output) if args.retrieval_only else run_case(local.client, case, entrypoint, args.output)

    tasks = []
    selected = set(args.only.split(",")) if args.only else None
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for case in cases:
            if args.split != "all" and case.get("split") != args.split or selected and case["id"] not in selected:
                continue
            if args.retrieval_only:
                tasks.append(pool.submit(execute, case, None))
                continue
            entries = [case["entrypoint"]]
            if args.cross_entrypoints and case.get("cross_entrypoint"):
                entries.append("qa" if entries[0] == "research" else "research")
            for entry in entries:
                tasks.append(pool.submit(execute, case, entry))
        for task in concurrent.futures.as_completed(tasks):
            print(json.dumps(task.result(), ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
