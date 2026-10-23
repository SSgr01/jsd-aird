"""Loopback HTTP acceptance for shared packs and the independent scheduler process."""

import argparse
import asyncio
import json
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
from dotenv import dotenv_values

PREFIX = "/internal/v1/agents"
DATASET = "70000000-0000-0000-0000-000000000001"


async def demo(base_url):
    if urlsplit(base_url).hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("Demo connects only to loopback")
    config = dotenv_values(Path(__file__).resolve().parents[1] / ".env")
    auth = {"Authorization": "Bearer " + config["JSD_AIRD_AGENTS_ACTOR_TOKEN"]}
    async with httpx.AsyncClient(base_url=base_url, headers=auth, timeout=30, trust_env=False) as client:
        packs = await client.get(PREFIX + "/packs")
        packs.raise_for_status()
        assert {p["id"] for p in packs.json()["packs"]} >= {"project", "quality"}
        created = await client.post(
            PREFIX + "/schedules",
            headers={"Idempotency-Key": str(uuid4())},
            json={
                "pack_id": "quality",
                "job_id": "daily-quality-anomaly-check",
                "scope_id": DATASET,
                "max_runs": 1,
            },
        )
        created.raise_for_status()
        sid = created.json()["id"]
        for _ in range(600):
            response = await client.get(PREFIX + f"/schedules/{sid}")
            response.raise_for_status()
            schedule = response.json()
            completed = [o for o in schedule["occurrences"] if o["status"] == "SUCCEEDED"]
            if completed:
                rid = completed[0]["run_id"]
                run = (await client.get(PREFIX + f"/runs/{rid}")).json()
                assert run["status"] == "SUCCEEDED"
                assert run["result"]["citations"] and all(
                    e["pack_id"] == "quality" for e in run["result"]["citations"]
                )
                assert run["result"]["receipts"] == []
                artifact = await client.get(PREFIX + f"/artifacts/{run['result']['artifact']['id']}")
                artifact.raise_for_status()
                events = await client.get(PREFIX + f"/runs/{rid}/events")
                events.raise_for_status()
                ids = [int(line[4:]) for line in events.text.splitlines() if line.startswith("id: ")]
                replay = await client.get(
                    PREFIX + f"/runs/{rid}/events", headers={"Last-Event-ID": str(ids[0])}
                )
                replay.raise_for_status()
                assert "event: run.succeeded" in replay.text
                print(
                    json.dumps(
                        {
                            "schedule_id": sid,
                            "run_id": rid,
                            "status": run["status"],
                            "pack_ids": run["pack_ids"],
                            "artifact_id": run["result"]["artifact"]["id"],
                            "sse_events": len(ids),
                            "simulated": True,
                        },
                        ensure_ascii=False,
                        indent=2,
                    )
                )
                return
            if schedule["status"] == "FAILED" or any(
                o["status"] == "FAILED" for o in schedule["occurrences"]
            ):
                raise RuntimeError("Scheduled mock acceptance failed; inspect schedule audit and run error")
            await asyncio.sleep(0.5)
        raise TimeoutError("Start API, Worker and Scheduler before running this demo")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8091")
    asyncio.run(demo(parser.parse_args().base_url))
