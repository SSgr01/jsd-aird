"""Check only the downloadable files promised by this task."""

from pathlib import PurePosixPath

from fastapi import HTTPException


def check_file_completion(run):
    expected = set(run.get("required_artifacts", []))
    saved = {
        PurePosixPath(output.get("path") or output.get("title", "")).name
        for output in run.get("registered_outputs", [])
        if output["kind"] == "artifact"
    }
    if missing_files := expected - saved:
        raise HTTPException(
            422, "DELIVERABLE_FILES_MISSING: register each requested file: " + ", ".join(sorted(missing_files))
        )
