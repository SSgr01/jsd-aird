from __future__ import annotations

import shutil
import tempfile
from pathlib import Path

import pytest

from conftest import REPOSITORY_ROOT
from jsd_aird_ai.devtools import (
    DEFAULT_SCORE_INPUT,
    DevModelIndex,
    build_indexed_score_request,
    read_score_input,
    score_indexed_model,
    write_json,
)


def test_example_score_input_is_a_complete_formula() -> None:
    score_input = read_score_input(DEFAULT_SCORE_INPUT)
    assert len(score_input.rows) == 1
    assert sum(score_input.rows[0].formula.values()) == pytest.approx(100.0)
    assert score_input.rows[0].context["substrate"] == "PET_100UM_OPTICAL"


@pytest.mark.golden
def test_content_addressed_dev_index_can_score(trained_bundle: dict) -> None:
    temporary_parent = REPOSITORY_ROOT / ".tmp"
    temporary_parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="t07-a1-test-", dir=temporary_parent) as raw:
        directory = Path(raw)
        model_path = directory / f"formula-model-{trained_bundle['sha256'][:16]}.zip"
        shutil.copyfile(trained_bundle["path"], model_path)
        profile = trained_bundle["profile"]
        response = trained_bundle["response"]
        index = DevModelIndex(
            task_profile_code=profile.code,
            task_profile_version=profile.version,
            task_profile_hash=trained_bundle["profile_hash"],
            schema_hash=profile.schema_hash,
            snapshot_id=response.snapshot_id,
            snapshot_hash=trained_bundle["snapshot_hash"],
            model_bundle_path=model_path.relative_to(REPOSITORY_ROOT).as_posix(),
            model_bundle_hash=trained_bundle["sha256"],
            model_bundle_size=model_path.stat().st_size,
            seed=20260903,
            status=response.status,
            targets=response.targets,
            warnings=response.warnings,
        )
        index_path = directory / "active-dev-model.json"
        write_json(index_path, index.model_dump(mode="json", by_alias=True))
        score_input = read_score_input(DEFAULT_SCORE_INPUT)

        request = build_indexed_score_request(score_input, index_path)
        scored = score_indexed_model(score_input, index_path)

        assert request.model_bundle_hash == index.model_bundle_hash
        assert len(scored.rows[0].predictions) == 4
        assert len(scored.rows[0].ordinal_predictions) == 1
        assert scored.rows[0].ordinal_predictions[0].predicted_class in {"H", "2H", "3H", "4H"}
        assert scored.unsupported_targets == []
