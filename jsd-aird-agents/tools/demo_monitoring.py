"""Five first-batch scenarios through loopback HTTP and separate Worker/Scheduler.

Requires opt-in seed-monitoring. Default: ordinary rule tasks, zero model calls.
Optional --with-agent-action also creates and later cancels a pending Agent proposal.
"""

import argparse
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
from dotenv import dotenv_values

PREFIX = "/internal/v1/agents"
PROJECT = "30000000-0000-0000-0000-000000000001"
SECOND = "30000000-0000-0000-0000-000000000002"
JOBS = [
    "daily-project-progress-inspection",
    "daily-task-followup",
    "daily-milestone-precheck",
    "weekly-project-report",
    "daily-approval-reminder",
]


async def wait_run(client, run_id, expected):
    for _ in range(180):
        response = await client.get(PREFIX + f"/runs/{run_id}")
        response.raise_for_status()
        run = response.json()
        if run["status"] == expected:
            return run
        if run["status"] in {"FAILED", "CANCELLED"}:
            raise RuntimeError(f"Mock run failed: {run.get('error')}")
        await asyncio.sleep(0.25)
    raise TimeoutError("Start the independent API, Worker and Scheduler")


async def demo(base_url, with_agent_action=False):
    if urlsplit(base_url).hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("Demo only connects to loopback")
    config = dotenv_values(Path(__file__).resolve().parents[1] / ".env")
    auth = {"Authorization": "Bearer " + config["JSD_AIRD_AGENTS_ACTOR_TOKEN"]}
    reviewer = {"Authorization": "Bearer " + config["JSD_AIRD_AGENTS_APPROVER_TOKEN"]}
    results = []
    async with httpx.AsyncClient(base_url=base_url, headers=auth, trust_env=False, timeout=30) as client:
        pending_id = action_id = None
        try:
            if with_agent_action:
                created = await client.post(
                    PREFIX + "/runs",
                    headers={"Idempotency-Key": str(uuid4())},
                    json={"request": "将耐候测试验证截止日期调整到2026-12-01", "project_id": PROJECT},
                )
                created.raise_for_status()
                pending_id = created.json()["run_id"]
                pending = await wait_run(client, pending_id, "WAITING_APPROVAL")
                action_id = pending["interrupt"]["value"]["action_id"]
            for job in JOBS:
                created = await client.post(
                    PREFIX + "/schedules",
                    headers={"Idempotency-Key": str(uuid4())},
                    json={
                        "pack_id": "project",
                        "job_id": job,
                        "scope_id": PROJECT if "approval" in job else SECOND,
                        "first_fire_at": datetime.now(timezone.utc).isoformat(),
                        "max_runs": 1,
                        "monitoring": {"approval_wait_minutes": 0},
                    },
                )
                created.raise_for_status()
                sid = created.json()["id"]
                for _ in range(180):
                    response = await client.get(PREFIX + f"/schedules/{sid}")
                    response.raise_for_status()
                    occurrences = response.json()["occurrences"]
                    if occurrences and occurrences[0]["run_id"]:
                        break
                    await asyncio.sleep(0.25)
                else:
                    raise TimeoutError("Scheduler did not dispatch")
                run = await wait_run(client, occurrences[0]["run_id"], "SUCCEEDED")
                assert run["result"]["publication_status"] == "DRAFT" and not run["result"]["receipts"]
                assert run["result"]["citations"] and run["simulated"]
                assert run["execution_kind"] == "task" and run["model_calls"] == 0
                report = run["result"]["monitoring"]
                response = await client.get(PREFIX + f"/runs/{run['id']}/events")
                response.raise_for_status()
                assert "event: run.succeeded" in response.text and "event: answer.delta" in response.text
                cursor = next(line[4:] for line in response.text.splitlines() if line.startswith("id: "))
                replay = await client.get(
                    PREFIX + f"/runs/{run['id']}/events", headers={"Last-Event-ID": cursor}
                )
                assert replay.status_code == 200 and "event: run.succeeded" in replay.text
                artifact = await client.get(PREFIX + f"/artifacts/{run['result']['artifact']['id']}")
                artifact.raise_for_status()
                results.append(
                    {
                        "job": job,
                        "schedule_id": sid,
                        "run_id": run["id"],
                        "status": run["status"],
                        "failures": sum(o["status"] == "FAIL" for o in report["observations"]),
                        "unknown": report["coverage"]["unknown"],
                        "model_calls": run["model_calls"],
                        "tool_calls": run["tool_calls"],
                        "artifact_id": run["result"]["artifact"]["id"],
                    }
                )
            if with_agent_action:
                for _ in range(40):
                    notices = await client.get(PREFIX + "/notifications", headers=reviewer)
                    notices.raise_for_status()
                    if any(
                        n["payload"].get("action_id") == action_id for n in notices.json()["notifications"]
                    ):
                        break
                    await asyncio.sleep(0.25)
                else:
                    raise AssertionError("Approver did not receive simulated in-app reminder")
                ledger = await client.get(PREFIX + f"/actions/{action_id}")
                ledger.raise_for_status()
                assert ledger.json()["approval_status"] == "PENDING" and ledger.json()["receipt"] is None
            print(
                json.dumps(
                    {
                        "scenarios": results,
                        "pending_action_unchanged": True if with_agent_action else None,
                        "mode": "rules_with_agent_proposal" if with_agent_action else "rules_only",
                        "simulated": True,
                        "external_notifications": False,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
        finally:
            if pending_id:
                cancelled = await client.post(PREFIX + f"/runs/{pending_id}/cancel")
                cancelled.raise_for_status()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8091")
    parser.add_argument("--with-agent-action", action="store_true")
    args = parser.parse_args()
    asyncio.run(demo(args.base_url, args.with_agent_action))
