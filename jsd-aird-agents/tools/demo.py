"""Real local HTTP demo against API + Worker. Only simulated data is modified."""

import argparse
import asyncio
import json
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
from dotenv import dotenv_values

PREFIX = "/internal/v1/agents"
PROJECT = "30000000-0000-0000-0000-000000000001"


async def demo(base_url, project_id=PROJECT, deadline="2026-11-10"):
    if urlsplit(base_url).hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("The fixture demo connects only to a loopback API")
    values = dotenv_values(Path(__file__).resolve().parents[1] / ".env")
    actor = {"Authorization": "Bearer " + values["JSD_AIRD_AGENTS_ACTOR_TOKEN"]}
    reviewer = {"Authorization": "Bearer " + values["JSD_AIRD_AGENTS_APPROVER_TOKEN"]}
    async with httpx.AsyncClient(base_url=base_url, timeout=30, trust_env=False) as client:
        response = await client.post(
            PREFIX + "/runs",
            headers={**actor, "Idempotency-Key": str(uuid4())},
            json={"project_id": project_id, "request": f"分析延期并将耐候测试验证截止日期调整到{deadline}"},
        )
        response.raise_for_status()
        run_id = response.json()["run_id"]
        cursor = 0
        # Disconnect an actual SSE subscriber. The independent Worker must continue.
        async with client.stream("GET", PREFIX + f"/runs/{run_id}/events", headers=actor) as stream:
            stream.raise_for_status()
            async for line in stream.aiter_lines():
                if line.startswith("id:"):
                    cursor = int(line.split(":", 1)[1])
                    break
        decided = False
        for _ in range(660):
            run = (await client.get(PREFIX + f"/runs/{run_id}", headers=actor)).json()
            if run["status"] == "WAITING_APPROVAL" and not decided:
                action_id = run["interrupt"]["value"]["action_id"]
                action = (await client.get(PREFIX + f"/actions/{action_id}", headers=reviewer)).json()
                decision = await client.post(
                    PREFIX + f"/actions/{action_id}/decisions",
                    headers=reviewer,
                    json={"decision": "APPROVED", "request_sha256": action["request_hash"]},
                )
                decision.raise_for_status()
                decided = True
            elif run["status"] == "SUCCEEDED":
                replay = await client.get(
                    PREFIX + f"/runs/{run_id}/events",
                    headers={**actor, "Last-Event-ID": str(cursor)},
                )
                replay.raise_for_status()
                replay_ids = [
                    int(line.split(":", 1)[1]) for line in replay.text.splitlines() if line.startswith("id:")
                ]
                assert (
                    replay_ids
                    and replay_ids == sorted(set(replay_ids))
                    and all(i > cursor for i in replay_ids)
                )
                assert "event: run.succeeded" in replay.text and "event: answer.delta" in replay.text
                assert run["result"]["receipts"] and all(r["verified"] for r in run["result"]["receipts"])
                print(
                    json.dumps(
                        {
                            "run_id": run_id,
                            "status": run["status"],
                            "simulated": True,
                            "sse_replayed_events": len(replay_ids),
                            "answer": run["result"]["answer"],
                            "receipts": run["result"]["receipts"],
                        },
                        ensure_ascii=False,
                        indent=2,
                    )
                )
                return
            elif run["status"] in {"FAILED", "CANCELLED", "WAITING_CLARIFICATION"}:
                raise RuntimeError(json.dumps(run.get("error") or run.get("interrupt"), ensure_ascii=False))
            await asyncio.sleep(0.5)
        raise TimeoutError("Demo did not reach a terminal state")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8091")
    parser.add_argument("--project-id", default=PROJECT)
    parser.add_argument("--deadline", default="2026-11-10")
    args = parser.parse_args()
    asyncio.run(demo(args.base_url, args.project_id, args.deadline))
