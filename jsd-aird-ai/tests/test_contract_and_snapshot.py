from __future__ import annotations

import hashlib
import json

import numpy as np
import pytest
import baybe
import catboost
import lightgbm
import sklearn
import xgboost
from jsonschema import Draft202012Validator
from sklearn.model_selection import GroupKFold

from conftest import (
    GOLDEN_SCORE_REQUEST,
    GOLDEN_SCORE_RESPONSE,
    ORIGINAL_SNAPSHOT_ROOT,
    PROFILE_PATH,
    REPAIRED_SNAPSHOT_ROOT,
    SCHEMA_PATH,
    request_payload,
    validate_contract_definition,
)
from jsd_aird_ai.artifacts import ArtifactClient
from jsd_aird_ai.contracts import (
    ScoreRequest,
    ScoreResponse,
    TaskProfile,
    ValidateSnapshotRequest,
)
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.features import FeatureBuilder
from jsd_aird_ai.modeling import create_bundle, load_bundle
from jsd_aird_ai.settings import Settings
from jsd_aird_ai.snapshot import SnapshotValidator


def test_task_profile_matches_versioned_json_schema(profile: TaskProfile) -> None:
    schema_bytes = SCHEMA_PATH.read_bytes()
    assert hashlib.sha256(schema_bytes).hexdigest() == profile.schema_hash
    schema = json.loads(schema_bytes)
    validator = Draft202012Validator(schema)
    payload = request_payload(profile, REPAIRED_SNAPSHOT_ROOT, "schema-check")
    validator.validate(payload)
    assert TaskProfile.model_validate_json(PROFILE_PATH.read_bytes()) == profile


def test_score_golden_request_and_response_match_shared_contract() -> None:
    request = ScoreRequest.model_validate_json(GOLDEN_SCORE_REQUEST.read_bytes())
    response = ScoreResponse.model_validate_json(GOLDEN_SCORE_RESPONSE.read_bytes())
    assert request.request_id == response.request_id
    assert request.model_bundle_hash == response.model_bundle_sha256
    validate_contract_definition(
        "ScoreRequest",
        request.model_dump(mode="json", by_alias=True),
    )
    validate_contract_definition(
        "ScoreResponse",
        response.model_dump(mode="json", by_alias=True),
    )


def test_repaired_snapshot_is_valid_and_preserves_target_semantics(
    validation_request: ValidateSnapshotRequest,
) -> None:
    validator = SnapshotValidator(ArtifactClient(Settings(allow_file_urls=True)))
    result = validator.load_and_validate(validation_request).response
    assert result.valid is True
    assert (result.rows, result.groups, result.source_sheets) == (400, 40, 67)
    by_code = {item.target_code: item for item in result.targets}
    assert by_code["Y__WARPING_PET_12H_CM"].valid_rows == 371
    assert by_code["Y__STEEL_WOOL_1KG_CYCLES"].valid_rows == 306
    assert by_code["Y__HARDNESS_PET_1KG_ORD"].status == "SUPPORTED"
    assert by_code["Y__HARDNESS_PET_1KG_ORD"].source_groups == 67
    assert result.source_sheet_stats.total_sheets == 67
    assert result.source_sheet_stats.sheets_with_multiple_lineages > 0
    assert result.source_sheet_stats.max_lineages_per_sheet > 1
    assert result.production_eligible is False
    validate_contract_definition(
        "SnapshotValidationResponse",
        result.model_dump(mode="json", by_alias=True),
    )


def test_original_custom_parquet_is_rejected(profile: TaskProfile) -> None:
    if not (ORIGINAL_SNAPSHOT_ROOT / "manifest.json").is_file():
        pytest.skip("original legacy snapshot is an optional local traceability artifact")
    request = ValidateSnapshotRequest.model_validate(
        request_payload(profile, ORIGINAL_SNAPSHOT_ROOT, "invalid-original")
    )
    validator = SnapshotValidator(ArtifactClient(Settings(allow_file_urls=True)))
    result = validator.load_and_validate(request).response
    assert result.valid is False
    assert result.rows == 0
    assert any("row_count" in error for error in result.errors)


def test_real_zero_is_not_counted_as_missing(validation_request: ValidateSnapshotRequest) -> None:
    validator = SnapshotValidator(ArtifactClient(Settings(allow_file_urls=True)))
    snapshot = validator.load_and_validate(validation_request)
    values = snapshot.measurements["Y__WARPING_PET_12H_CM"]
    assert (values == 0.0).any()
    assert int(values.notna().sum()) == 371


def test_group_kfold_never_splits_formula_lineage(
    validation_request: ValidateSnapshotRequest,
) -> None:
    validator = SnapshotValidator(ArtifactClient(Settings(allow_file_urls=True)))
    snapshot = validator.load_and_validate(validation_request)
    identity = "experiment_version_id"
    joined = snapshot.measurements[[identity]].merge(
        snapshot.source_map[[identity, "formula_lineage_group"]],
        on=identity,
        how="inner",
        validate="one_to_one",
    )
    groups = joined["formula_lineage_group"].astype(str).to_numpy()

    for train_index, validation_index in GroupKFold(n_splits=5).split(
        np.zeros(len(groups)), groups=groups
    ):
        assert set(groups[train_index]).isdisjoint(set(groups[validation_index]))


def test_missing_material_is_not_interpreted_as_zero(
    profile: TaskProfile,
) -> None:
    formula = {item.code: 0.0 for item in profile.formula.materials}
    formula[profile.formula.main_resin_codes[0]] = 70.0
    formula["DSP-3315"] = 1.0
    formula["SS059/PC=1/1"] = 1.0
    formula[profile.formula.balance_material_code] = 28.0
    formula.pop(profile.formula.balance_material_code)
    context = {item.code: "UNKNOWN" for item in profile.context_features}

    with pytest.raises(FormulaModelError) as raised:
        FeatureBuilder(profile).from_api_rows([formula], [context])

    assert raised.value.code == ErrorCode.INVALID_SNAPSHOT


def test_model_bundle_packaging_is_deterministic(profile: TaskProfile) -> None:
    payload = {
        "contract_version": "formula-model.v1",
        "targets": {},
        "seed": 20260903,
    }
    card = {
        "contractVersion": "formula-model.v1",
        "seed": 20260903,
        "runtime": {
            "baybe": baybe.__version__,
            "scikitLearn": sklearn.__version__,
            "lightgbm": lightgbm.__version__,
            "xgboost": xgboost.__version__,
            "catboost": catboost.__version__,
        },
    }

    first = create_bundle(profile, payload, card)
    assert first == create_bundle(profile, payload, card)
    assert load_bundle(first, hashlib.sha256(first).hexdigest()).profile == profile
