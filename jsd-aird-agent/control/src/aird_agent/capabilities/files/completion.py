"""Check only the downloadable files promised by this task."""

from pathlib import PurePosixPath

from fastapi import HTTPException


def check_file_completion(run, plan):
    expected = set(run.get("required_artifacts", [])) | set(plan.get("file_names", []))
    saved = {
        PurePosixPath(output.get("path") or output.get("title", "")).name
        for output in run.get("registered_outputs", [])
        if output["kind"] == "artifact"
    }
    if missing_files := expected - saved:
        raise HTTPException(
            422, "DELIVERABLE_FILES_MISSING: register each requested file: " + ", ".join(sorted(missing_files))
        )
    if "analysis_file" in plan.get("deliverables", []) and not saved:
        raise HTTPException(422, "DELIVERABLES_MISSING: analysis_file")
