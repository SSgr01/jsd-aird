"""Golden checks for numeric boundaries; independent of a model's prose."""

from datetime import datetime

import pytest

from jsd_aird_agents.app.platform.monitoring.contracts import MonitorOptions
from jsd_aird_agents.app.verticals.project.monitoring import evaluate


@pytest.mark.parametrize(
    "due,progress_at,status,overdue,soon,stalled",
    [
        ("2026-10-21", "2026-10-19T01:00:00+00:00", "IN_PROGRESS", "FAIL", "PASS", "FAIL"),
        ("2026-10-22", "2026-10-20T01:00:00+00:00", "IN_PROGRESS", "PASS", "FAIL", "PASS"),
        ("2026-10-25", None, "IN_PROGRESS", "PASS", "FAIL", "UNKNOWN"),
        ("2026-10-26", None, "IN_PROGRESS", "PASS", "PASS", "UNKNOWN"),
        ("2026-10-01", None, "DONE", "PASS", "PASS", "PASS"),
        ("2026-10-01", None, "CANCELLED", "PASS", "PASS", "PASS"),
    ],
)
def test_task_rules_golden_boundaries(due, progress_at, status, overdue, soon, stalled):
    snapshot = {
        "tasks": [
            {
                "id": "task-1",
                "name": "样例",
                "owner_name": "研发负责人",
                "planned_date": due,
                "last_progress_at": progress_at,
                "status": status,
            }
        ]
    }
    report = evaluate(
        snapshot, MonitorOptions(), datetime.fromisoformat("2026-10-22T02:00:00+00:00"), ["source"]
    )
    statuses = {o["rule_id"]: o["status"] for o in report["observations"]}
    assert statuses["task.overdue"] == overdue
    assert statuses["task.due-soon"] == soon
    assert statuses["task.stalled"] == stalled
    assert all(o["evidence_ids"] == ["source"] for o in report["observations"])


def test_undefined_milestone_requirements_are_unknown():
    snapshot = {"milestones": [{"id": "m1", "name": "验收", "due_date": "2026-10-27", "status": "PENDING"}]}
    report = evaluate(
        snapshot, MonitorOptions(), datetime.fromisoformat("2026-10-22T02:00:00+00:00"), ["source"]
    )
    assert report["observations"][0]["status"] == "UNKNOWN"
