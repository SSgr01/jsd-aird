from __future__ import annotations

import math

import pytest

from conftest import first_api_row, validate_contract_definition
from jsd_aird_ai.contracts import RecommendRequest, ScoreRequest
from jsd_aird_ai.digests import canonical_sha256
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.modeling import load_bundle
from jsd_aird_ai.service import FormulaModelService


@pytest.mark.golden
def test_training_builds_five_model_and_ordinal_development_bundle(
    trained_bundle: dict,
) -> None:
    response = trained_bundle["response"]
    assert response.status == "READY"
    assert response.uploaded is True
    assert trained_bundle["path"].stat().st_size == response.model_bundle_size
    by_code = {item.target_code: item for item in response.targets}
    continuous = [
        item for item in response.targets if item.target_type == "CONTINUOUS"
    ]
    assert len(continuous) == 4
    hardness = by_code["Y__HARDNESS_PET_1KG_ORD"]
    assert hardness.status == "READY"
    assert hardness.scorer_type == "ORDINAL_CUMULATIVE_LOGIT"
    assert hardness.ordinal.observed_classes == ["H", "2H", "3H"]
    assert all(item.development_eligible for item in continuous)
    assert all(not item.production_eligible for item in response.targets)
    assert all(
        "SYNTHETIC_DATA_NOT_PRODUCTION_ELIGIBLE" in item.reasons
        for item in response.targets
    )
    assert all(math.isfinite(item.metrics.nmae) for item in continuous)
    assert all(math.isfinite(item.sheet_cv.nmae) for item in continuous)
    assert all(0.0 <= item.lineage_cv.interval_coverage <= 1.0 for item in continuous)
    assert all(0.0 <= item.sheet_cv.interval_coverage <= 1.0 for item in continuous)
    assert all(
        item.lineage_cv.calibration_sample_source == "INNER_GROUP_OOF"
        for item in continuous
    )
    assert all(item.validation_manifest is not None for item in response.targets)
    assert all(item.applicability_domain is not None for item in response.targets)
    assert all(item.stability is not None for item in continuous)
    assert all(
        len(item.model_selection.candidate_metrics) == 5 for item in continuous
    )
    assert all(not item.model_selection.skipped_models for item in continuous)
    assert all(not item.model_selection.failed_models for item in continuous)
    validate_contract_definition(
        "TrainResponse",
        response.model_dump(mode="json", by_alias=True),
    )


@pytest.mark.golden
def test_score_returns_continuous_and_ordinal_predictions(
    service: FormulaModelService,
    trained_bundle: dict,
) -> None:
    profile = trained_bundle["profile"]
    formula, context = first_api_row(profile)
    request = ScoreRequest.model_validate(
        {
            "requestId": "score-golden",
            "taskProfileHash": trained_bundle["profile_hash"],
            "snapshotHash": trained_bundle["snapshot_hash"],
            "modelBundleHash": trained_bundle["sha256"],
            "seed": 20260903,
            "taskProfile": profile.model_dump(mode="json", by_alias=True),
            "modelBundle": {
                "url": trained_bundle["path"].resolve().as_uri(),
                "sha256": trained_bundle["sha256"],
            },
            "rows": [{"rowId": "baseline", "formula": formula, "context": context}],
            "targetCodes": [item.code for item in profile.targets],
        }
    )
    response = service.score(request)
    assert response.unsupported_targets == []
    assert len(response.rows[0].predictions) == 4
    assert len(response.rows[0].ordinal_predictions) == 1
    assert response.rows[0].ordinal_predictions[0].predicted_class in {
        "H",
        "2H",
        "3H",
        "4H",
    }
    assert sum(
        response.rows[0].ordinal_predictions[0].class_probabilities.values()
    ) == pytest.approx(1.0)
    assert all(item.lower <= item.expected <= item.upper for item in response.rows[0].predictions)
    assert response.rows[0].model_usable is True
    assert all(
        item.applicability_domain is not None
        for item in response.rows[0].predictions
    )
    validate_contract_definition(
        "ScoreResponse",
        response.model_dump(mode="json", by_alias=True),
    )


@pytest.mark.golden
def test_score_marks_unseen_context_as_out_of_domain(
    service: FormulaModelService,
    trained_bundle: dict,
) -> None:
    profile = trained_bundle["profile"]
    formula, context = first_api_row(profile)
    context["curingSource"] = "UNSEEN_UV_SOURCE"
    request = ScoreRequest.model_validate(
        {
            "requestId": "score-ood-golden",
            "taskProfileHash": trained_bundle["profile_hash"],
            "snapshotHash": trained_bundle["snapshot_hash"],
            "modelBundleHash": trained_bundle["sha256"],
            "seed": 20260903,
            "taskProfile": profile.model_dump(mode="json", by_alias=True),
            "modelBundle": {
                "url": trained_bundle["path"].resolve().as_uri(),
                "sha256": trained_bundle["sha256"],
            },
            "rows": [{"rowId": "ood", "formula": formula, "context": context}],
            "targetCodes": ["Y__WARPING_PET_INITIAL_CM"],
        }
    )
    response = service.score(request)
    row = response.rows[0]
    evidence = row.predictions[0].applicability_domain
    assert row.domain_status == "OUT_OF_DOMAIN"
    assert row.model_usable is False
    assert evidence is not None
    assert evidence.unknown_categories == ["X_CONTEXT__CURING_SOURCE"]
    assert row.fallback_reasons


@pytest.mark.golden
def test_baybe_recommendation_returns_diverse_strategies(
    service: FormulaModelService,
    trained_bundle: dict,
) -> None:
    profile = trained_bundle["profile"]
    formula, context = first_api_row(profile)
    request = RecommendRequest.model_validate(
        {
            "requestId": "recommend-golden",
            "taskProfileHash": trained_bundle["profile_hash"],
            "snapshotHash": trained_bundle["snapshot_hash"],
            "modelBundleHash": trained_bundle["sha256"],
            "seed": 20260903,
            "taskProfile": profile.model_dump(mode="json", by_alias=True),
            "modelBundle": {
                "url": trained_bundle["path"].resolve().as_uri(),
                "sha256": trained_bundle["sha256"],
            },
            "baselineFormula": formula,
            "context": context,
            "targets": [
                {"code": "Y__WARPING_PET_INITIAL_CM", "mode": "MINIMIZE", "mandatory": False, "weight": 3.0},
                {"code": "Y__STEEL_WOOL_500G_CYCLES", "mode": "MAXIMIZE", "mandatory": False, "weight": 1.0},
                {"code": "Y__HARDNESS_PET_1KG_ORD", "mode": "MAXIMIZE", "mandatory": False, "weight": 1.0}
            ],
            "materialConstraints": [],
            "count": 4,
        }
    )
    response = service.recommend(request)
    repeated = service.recommend(request)
    assert response.status == "PARTIAL"
    assert response == repeated
    assert response.unsupported_targets == ["Y__HARDNESS_PET_1KG_ORD"]
    assert response.recommendation_mode == "EXPERIMENT_OPTIMIZATION"
    assert response.search_space.out_of_domain_rejected_count > 0
    assert response.search_space.final_scoreable_candidate_count > 0
    assert response.search_space.baybe_shortlist_count > 0
    assert 1 <= len(response.candidates) <= 4
    assert len({item.candidate_id for item in response.candidates}) == len(response.candidates)
    assert all(
        abs(sum(item.formula.values()) - 100.0) <= profile.formula.sum_tolerance
        for item in response.candidates
    )
    assert all(
        "requires authoritative Java rule validation" in item.reasons
        for item in response.candidates
    )
    assert all(item.domain_status != "OUT_OF_DOMAIN" for item in response.candidates)
    assert all(
        prediction.applicability_domain is not None
        for item in response.candidates
        for prediction in item.predictions
    )
    assert all(
        item.strategy == "EXPLORATORY"
        for item in response.candidates
        if item.domain_status == "NEAR_BOUNDARY"
    )
    assert all(item.selection_reason for item in response.candidates)
    conservative = next(
        (item for item in response.candidates if item.strategy == "CONSERVATIVE"),
        None,
    )
    if conservative is not None:
        assert (
            conservative.l1_distance_from_baseline
            <= profile.candidate.conservative_maximum_l1_distance
        )
    validate_contract_definition(
        "RecommendResponse",
        response.model_dump(mode="json", by_alias=True),
    )


@pytest.mark.golden
def test_tampered_model_bundle_is_rejected(trained_bundle: dict) -> None:
    original = trained_bundle["path"].read_bytes()
    tampered = original[:-1] + bytes([original[-1] ^ 0xFF])

    with pytest.raises(FormulaModelError) as raised:
        load_bundle(tampered, trained_bundle["sha256"])

    assert raised.value.code == ErrorCode.HASH_MISMATCH


@pytest.mark.golden
@pytest.mark.parametrize("binding", ["snapshot", "task_profile", "seed"])
def test_score_rejects_wrong_model_bundle_binding(
    service: FormulaModelService,
    trained_bundle: dict,
    binding: str,
) -> None:
    profile = trained_bundle["profile"]
    formula, context = first_api_row(profile)
    request_profile = profile
    task_profile_hash = trained_bundle["profile_hash"]
    snapshot_hash = trained_bundle["snapshot_hash"]
    seed = 20260903
    if binding == "snapshot":
        snapshot_hash = "0" * 64
    elif binding == "task_profile":
        request_profile = profile.model_copy(update={"version": "binding-negative-test"})
        task_profile_hash = canonical_sha256(request_profile)
    else:
        seed += 1
    request = ScoreRequest.model_validate(
        {
            "requestId": f"wrong-{binding}-binding",
            "taskProfileHash": task_profile_hash,
            "snapshotHash": snapshot_hash,
            "modelBundleHash": trained_bundle["sha256"],
            "seed": seed,
            "taskProfile": request_profile.model_dump(mode="json", by_alias=True),
            "modelBundle": {
                "url": trained_bundle["path"].resolve().as_uri(),
                "sha256": trained_bundle["sha256"],
            },
            "rows": [{"rowId": "binding", "formula": formula, "context": context}],
            "targetCodes": ["Y__WARPING_PET_INITIAL_CM"],
        }
    )

    with pytest.raises(FormulaModelError) as raised:
        service.score(request)

    assert raised.value.code == ErrorCode.HASH_MISMATCH
