#!/usr/bin/env python3
"""Paired live DSH evaluation; local cases and gold answers stay outside Git.

Run baseline before changing research guidance, then enhanced with the same
suite and settings. Each repetition creates a fresh conversation. Memory is
temporarily disabled through the normal settings API and restored in finally.
This calls the existing knowledge evaluator; it never installs a model loop.
"""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import sys
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("knowledge_evaluation", Path(__file__).with_name("evaluate_knowledge_quality.py"))
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)
GUIDANCE = (
    "jsd-aird-agent/enterprise-plugin/skills/literature-review/SKILL.md",
    "jsd-aird-agent/control/src/aird_agent/capabilities/registry.py",
)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def environment(path):
    values = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    return {**values, **os.environ}


def memory(client, origin, body):
    request = urllib.request.Request(origin.rstrip("/") + "/agent/business/memory",
        data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json", "Origin": origin.rstrip("/")})
    with client.opener.open(request, timeout=120) as response:
        return json.load(response)


def manifest(args, values):
    suite = args.cases.read_bytes()
    tracked = {name: (ROOT / name).read_text(encoding="utf-8") for name in GUIDANCE}
    # Only explicit non-secret settings are recorded. Provider credentials never enter artifacts.
    names = ("JSD_AIRD_DSH_PROVIDER", "JSD_AIRD_DSH_MODEL", "JSD_AIRD_DSH_REASONING",
        "JSD_AIRD_DSH_MAX_OUTPUT_TOKENS", "JSD_AIRD_HARNESS_MAX_STEPS",
        "JSD_AIRD_HARNESS_MAX_MODEL_CALLS", "JSD_AIRD_HARNESS_MAX_TOOL_CALLS",
        "JSD_AIRD_HARNESS_MAX_ACTIVE_SECONDS")
    return {"cases_sha256": digest(suite), "repetitions": args.repetitions,
        "workers": args.workers, "timeout": args.timeout, "base_url": args.base_url,
        "model_and_budget_config": {name: values.get(name) for name in names},
        "memory_during_evaluation": {"read_enabled": False, "auto_enabled": False},
        "guidance": {name: {"sha256": digest(text.encode()), "content": text} for name, text in tracked.items()},
        "pricing": "UNKNOWN unless an actual billing receipt is available",
        "gold_policy": "Only question and authorized scope enter model_request; local expected values never do."}


def assert_comparable(left, right):
    for key in ("cases_sha256", "repetitions", "workers", "timeout", "base_url",
                "model_and_budget_config", "memory_during_evaluation"):
        if left[key] != right[key]:
            raise ValueError("Comparison settings differ: " + key)


def native_measurements(record):
    """Current DSH receipts, rather than legacy pi generation events."""
    response = record.get("response", {})
    usage = response.get("dsh_usage", [])
    if not usage:
        return {"input": None, "output": None, "total": None, "cache_read": None,
                "coverage": "No native usage receipt"}
    return {"input": sum(row.get("inputTokens", 0) for row in usage),
        "output": sum(row.get("outputTokens", 0) for row in usage),
        "total": sum(row.get("totalTokens", 0) for row in usage),
        "cache_read": sum(row.get("cacheReadTokens", 0) for row in usage),
        "cache_write": sum(row.get("cacheWriteTokens", 0) for row in usage),
        "generation_calls": len(usage),
        "coverage": "Native main model receipts; separate review, title and memory usage not included"}


def actual_models(record):
    return sorted({(row.get("provider"), row.get("model"))
        for row in record.get("response", {}).get("dsh_model_attempts", [])
        if row.get("purpose") == "conversation"})


def run(args):
    values = environment(args.env_file)
    password = values.get("JSD_AIRD_EVAL_PASSWORD") or values.get("JSD_AIRD_IAM_ADMIN_PASSWORD")
    if not password:
        raise ValueError("Evaluation credentials are unavailable; no requests were submitted")
    username = values.get("JSD_AIRD_EVAL_USERNAME", "admin")
    arm = args.output / args.variant
    arm.mkdir(parents=True, exist_ok=True)
    current = manifest(args, values)
    target = arm / "manifest.json"
    if target.exists() and json.loads(target.read_text(encoding="utf-8")) != current:
        raise ValueError("Existing arm has different guidance/settings; use a new output directory")
    if args.variant == "enhanced":
        baseline = json.loads((args.output / "baseline/manifest.json").read_text(encoding="utf-8"))
        assert_comparable(baseline, current)
    quality.write_json(target, current)
    suite = json.loads(args.cases.read_text(encoding="utf-8"))
    cases = suite["cases"] if isinstance(suite, dict) else suite
    if len({case["id"] for case in cases}) != len(cases):
        raise ValueError("Case IDs must be unique")
    control = quality.Client(args.base_url, username, password, args.timeout)
    prior_path = arm / "memory-before.json"
    if not prior_path.exists():
        quality.write_json(prior_path, memory(control, args.origin, {"action": "settings"}))
    prior = json.loads(prior_path.read_text(encoding="utf-8"))
    def execute(case, repetition):
        item = {**case, "id": case["id"] + "-r" + str(repetition)}
        # One login per arm: enterprise login replaces previous sessions for
        # the same account. CookieJar serializes cookie updates internally.
        result = quality.run_case(control, item, "research", arm)
        path = arm / (item["id"] + "-research.json")
        record = json.loads(path.read_text(encoding="utf-8"))
        if record.get("run_id"):
            # GET only: unknown runs are collected, never blindly resubmitted.
            snapshot = control.json("GET", "/api/v1/agents/runs/" + record["run_id"])
            quality.write_json(arm / "snapshots" / (item["id"] + ".json"), snapshot)
        return result

    isolated = False
    try:
        settings = memory(control, args.origin, {"action": "settings"})
        actual = memory(control, args.origin, {"action": "set-settings", "revision": settings["revision"],
            "patch": {"read_enabled": False, "auto_enabled": False}})
        isolated = True
        if actual.get("read_enabled") is not False or actual.get("auto_enabled") is not False:
            raise ValueError("Memory isolation could not be established")
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(execute, case, repetition)
                for repetition in range(1, args.repetitions + 1) for case in cases]
            for future in as_completed(futures):
                print(json.dumps(future.result(), ensure_ascii=False), flush=True)
    finally:
        if isolated:
            settings = memory(control, args.origin, {"action": "settings"})
            restored = memory(control, args.origin, {"action": "set-settings", "revision": settings["revision"], "patch": {
                "read_enabled": prior["read_enabled"], "auto_enabled": prior["auto_enabled"]}})
            quality.write_json(arm / "memory-restored.json", restored)


def bundle(args):
    manifests = [json.loads((args.output / arm / "manifest.json").read_text(encoding="utf-8"))
        for arm in ("baseline", "enhanced")]
    assert_comparable(*manifests)
    suite = json.loads(args.cases.read_text(encoding="utf-8"))
    cases = suite["cases"] if isinstance(suite, dict) else suite
    if digest(args.cases.read_bytes()) != manifests[0]["cases_sha256"]:
        raise ValueError("The frozen suite changed")
    pairs, keys, measures = [], {}, []
    rng = random.Random(12026)
    for case in cases:
        for repetition in range(1, manifests[0]["repetitions"] + 1):
            records = []
            for arm in ("baseline", "enhanced"):
                record = json.loads((args.output / arm / (case["id"] + f"-r{repetition}-research.json")).read_text(encoding="utf-8"))
                records.append((arm, record))
            if records[0][1].get("conversation_id") == records[1][1].get("conversation_id"):
                raise ValueError("A pair reused conversation history")
            if actual_models(records[0][1]) != actual_models(records[1][1]):
                raise ValueError("A pair used different actual models: " + case["id"])
            rng.shuffle(records)
            pair = {"id": case["id"] + f"-r{repetition}", "question": case["question"],
                "expected": case.get("expected", []), "answers": {}}
            for label, (arm, record) in zip(("A", "B"), records):
                pair["answers"][label] = {"status": record["status"], "answer": record.get("answer", "")}
                keys[pair["id"] + "/" + label] = arm
                calls = [event["data"].get("payload", {}) for event in record.get("events", [])
                    if event.get("data", {}).get("type") == "research.activity"
                    and event["data"].get("payload", {}).get("status") == "running"]
                measures.append({"case": pair["id"], "variant": arm, "status": record["status"],
                    "total_ms": record.get("total_ms"), "first_text_ms": record.get("first_text_ms"),
                    "usage": native_measurements(record),
                    "actual_models": actual_models(record), "answer_characters": len(record.get("answer", "")),
                    "model_calls": record.get("response", {}).get("model_calls"),
                    "tool_calls": record.get("response", {}).get("tool_calls"),
                    "activity_counts": dict(Counter(call.get("title", "unknown") for call in calls)),
                    "cost": record.get("cost"), "pricing": "UNKNOWN"})
            pairs.append(pair)
    quality.write_json(args.output / "blind-review.json", pairs)
    quality.write_json(args.output / "blind-key.json", keys)
    quality.write_json(args.output / "measurements.json", measures)
    print(json.dumps({"pairs": len(pairs), "statuses": dict(Counter(row["status"] for row in measures)),
        "review": str(args.output / "blind-review.json")}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("run", "bundle"))
    parser.add_argument("--cases", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--variant", choices=("baseline", "enhanced"), default="baseline")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--base-url", default="http://127.0.0.1:8180")
    parser.add_argument("--origin", default="http://127.0.0.1:5184")
    parser.add_argument("--repetitions", type=int, choices=range(1, 5), default=2)
    parser.add_argument("--workers", type=int, choices=range(1, 3), default=2)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    run(args) if args.action == "run" else bundle(args)


if __name__ == "__main__":
    main()
