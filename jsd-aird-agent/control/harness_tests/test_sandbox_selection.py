import pytest
from fastapi import HTTPException

from aird_agent.core.workspace import Workspace
from aird_agent.sandbox.client import Sandbox


def test_explicit_selection_bypasses_manifest_truncation_and_rehashes(tmp_path, principal, settings):
    workspace = Workspace(tmp_path, str(principal.organization_id), str(principal.actor_id))
    workspace.prepare()
    for i in range(510):
        workspace.materialize(f"input/{i}.txt", b"unused")
    workspace.write("scratch/selected.json", "original")
    assert workspace.manifest()["limited"] is True
    assert "scratch/selected.json" not in {f["path"] for f in workspace.manifest()["files"]}
    sandbox = Sandbox(settings)
    report = sandbox.prepare(workspace, ["scratch/selected.json"])
    assert report["file_count"] == 1 and report["bytes"] == 8
    workspace.write("scratch/selected.json", "changed")
    assert (
        sandbox.prepare(workspace, ["scratch/selected.json"])["files"][0]["sha256"]
        != report["files"][0]["sha256"]
    )
    assert sandbox.prepare(workspace, [])["bytes"] == 0
    for paths in (["input/"], ["scratch/no-file"], ["output/a"], ["input/*.txt"], ["input/../secret"]):
        with pytest.raises(HTTPException):
            sandbox.prepare(workspace, paths)
    assert sandbox.prepare(workspace, [f"input/{i}.txt" for i in range(50)])["file_count"] == 50
    with pytest.raises(HTTPException) as exc:
        sandbox.prepare(workspace, [f"input/{i}.txt" for i in range(51)])
    assert exc.value.detail["error"] == "SANDBOX_FILE_COUNT_EXCEEDED"
    workspace.write_bytes("scratch/large", b"0" * (10 * 1024 * 1024))
    assert sandbox.prepare(workspace, ["scratch/large"])["bytes"] == 10 * 1024 * 1024
    with pytest.raises(HTTPException) as exc:
        sandbox.prepare(workspace, ["scratch/large", "scratch/selected.json"])
    assert exc.value.detail["error"] == "SANDBOX_INPUT_BYTES_EXCEEDED"
