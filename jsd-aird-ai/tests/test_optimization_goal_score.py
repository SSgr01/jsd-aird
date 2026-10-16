from jsd_aird_ai.contracts_v2 import (
    ArtifactRefV2,
    ApplicabilityV2,
    BinaryResultV2,
    ContinuousResultV2,
    FormulaComponentV2,
    FormulaV2,
    InputFieldV2,
    InputSchemeV2,
    MaterialDictionaryV2,
    MaterialV2,
    ModelBindingV2,
    PreprocessingV2,
    RecommendRequestV2,
    ScoreResponseV2,
    TargetSuccessV2,
    TargetDefinitionV2,
    TypedGoalV2,
)
from jsd_aird_ai.service_v2 import FormulaModelV2Service
from jsd_aird_ai.settings import Settings


def success(target_id: str, result: ContinuousResultV2 | BinaryResultV2) -> TargetSuccessV2:
    return TargetSuccessV2(
        target_id=target_id,
        model_version_id="model-1",
        result=result,
        applicability=ApplicabilityV2(status="IN_DOMAIN", distance=0),
    )


def test_minimize_and_maximize_goals_rank_in_the_requested_direction(tmp_path) -> None:
    service = FormulaModelV2Service(Settings(allow_file_urls=True, temporary_root=tmp_path))
    minimize = [TypedGoalV2(target_id="y1", operator="MINIMIZE", value=50, weight=1)]
    maximize = [TypedGoalV2(target_id="y1", operator="MAXIMIZE", value=50, weight=1)]
    lower_value = [success("y1", ContinuousResultV2(value=40, lower=38, upper=42, coverage_level=0.9))]
    higher_value = [success("y1", ContinuousResultV2(value=60, lower=58, upper=62, coverage_level=0.9))]

    assert service._goal_score(minimize, lower_value) > service._goal_score(minimize, higher_value)
    assert service._goal_score(maximize, higher_value) > service._goal_score(maximize, lower_value)


def test_directional_preferences_do_not_reject_candidates_for_missing_a_baseline_threshold(tmp_path) -> None:
    service = FormulaModelV2Service(Settings(allow_file_urls=True, temporary_root=tmp_path))
    less_than_baseline = [success("y1", ContinuousResultV2(value=40, lower=38, upper=42, coverage_level=0.9))]
    uncertain_pass = [success("y1", ContinuousResultV2(value=52, lower=47, upper=57, coverage_level=0.9))]
    uncertain_range_pass = [success("y1", ContinuousResultV2(value=51, lower=45, upper=57, coverage_level=0.9))]

    maximize = [TypedGoalV2(target_id="y1", operator="MAXIMIZE", value=50, mandatory=False, weight=1)]
    minimize = [TypedGoalV2(target_id="y1", operator="MINIMIZE", value=50, mandatory=False, weight=1)]
    hard_minimum = [TypedGoalV2(target_id="y1", operator="AT_LEAST", value=50, mandatory=True, weight=1)]
    hard_maximum = [TypedGoalV2(target_id="y1", operator="AT_MOST", value=50, mandatory=True, weight=1)]
    hard_range = [TypedGoalV2(target_id="y1", operator="RANGE", value=[49, 52], mandatory=True, weight=1)]

    assert service._goals_satisfied(maximize, less_than_baseline)
    assert service._goals_satisfied(minimize, less_than_baseline)
    assert not service._goals_satisfied(hard_minimum, less_than_baseline)
    assert service._goals_satisfied(hard_minimum, uncertain_pass)
    assert service._goals_satisfied(hard_maximum, [success(
        "y1", ContinuousResultV2(value=48, lower=43, upper=53, coverage_level=0.9),
    )])
    assert service._goals_satisfied(hard_range, uncertain_range_pass)


def test_optimization_returns_improving_candidates_when_prediction_interval_crosses_baseline(tmp_path, monkeypatch) -> None:
    service = FormulaModelV2Service(Settings(allow_file_urls=True, temporary_root=tmp_path))
    digest = "a" * 64
    binding = ModelBindingV2(
        target=TargetDefinitionV2(
            id="target-v1", version=1, sha256=digest, code="GLOSS_60",
            value_type="CONTINUOUS", unit="GU",
        ),
        model_version_id="model-1",
        model_bundle=ArtifactRefV2(url="https://example.test/model", sha256=digest),
        input_scheme=InputSchemeV2(
            id="input-v1", version=1, sha256=digest, target_version_id="target-v1",
            fields=[InputFieldV2(
                field_version_id="field-v1", code="temperature", value_type="NUMBER",
                required=False, acquisition_timing="PRE_EXPERIMENT",
            )],
        ),
        material_dictionary=MaterialDictionaryV2(
            id="materials-v1", version=1, sha256=digest,
            materials=[
                MaterialV2(material_id="m1", code="M1", encoder_index=0),
                MaterialV2(material_id="m2", code="M2", encoder_index=1),
            ],
        ),
        preprocessing=PreprocessingV2(id="preprocessing-v1", version=1, sha256=digest, config={}),
        applicability_domain={},
    )

    def formula(first_ratio: float) -> FormulaV2:
        return FormulaV2(
            basis="MASS_PERCENT", composition_complete=True,
            components=[
                FormulaComponentV2(material_id="m1", ratio=first_ratio, unit="%", amount_known=True),
                FormulaComponentV2(material_id="m2", ratio=100 - first_ratio, unit="%", amount_known=True),
            ],
            recorded_total=100,
        )

    baseline = formula(50)
    request = RecommendRequestV2(
        request_id="request-1", seed=23, run_id="run-1", mode="EXPERIMENT_OPTIMIZATION",
        baseline_formula=baseline, fixed_inputs={},
        goals=[TypedGoalV2(target_id="target-v1", operator="MAXIMIZE", value=50, mandatory=False, weight=1)],
        constraints={
            "targetTotal": 100,
            "variableMaterials": [
                {"materialId": "m1", "minimum": 45, "maximum": 55},
                {"materialId": "m2", "minimum": 45, "maximum": 55},
            ],
            "variableInputs": {},
            "strategies": ["CONTROL", "CONSERVATIVE", "BALANCED", "EXPLORATORY"],
        },
        search_space={}, model_bindings=[binding], candidate_count=4,
    )

    monkeypatch.setattr(service, "_search_engine", lambda _: ("DETERMINISTIC_CANDIDATE_POOL", "DISCRETE_DETERMINISTIC_POOL"))
    monkeypatch.setattr(service, "_formula_pool", lambda *_: [formula(51), formula(54), formula(46), formula(49)])

    def fake_score(score_request):
        first_ratio = float(score_request.formula.components[0].ratio)
        predicted = 50 + (first_ratio - 50) * 0.3
        result = success("target-v1", ContinuousResultV2(
            value=predicted, lower=predicted - 4, upper=predicted + 4, coverage_level=0.9,
        ))
        return ScoreResponseV2(
            request_id=score_request.request_id, run_id=score_request.run_id,
            execution_status="SUCCEEDED", outcome_status="SUCCEEDED", results=[result],
        )

    monkeypatch.setattr(service, "score", fake_score)
    response = service._recommend_optimization(request)

    assert response.outcome_status == "SUCCEEDED"
    assert {candidate.strategy for candidate in response.candidates} == {
        "CONTROL", "CONSERVATIVE", "BALANCED", "EXPLORATORY",
    }
    balanced = next(candidate for candidate in response.candidates if candidate.strategy == "BALANCED")
    assert balanced.formula.components[0].ratio > 50
    assert balanced.results[0].result.lower < 50
    conservative = next(candidate for candidate in response.candidates if candidate.strategy == "CONSERVATIVE")
    assert conservative.formula.components[0].ratio > 50

    hard_goal_request = request.model_copy(update={
        "goals": [TypedGoalV2(
            target_id="target-v1", operator="AT_LEAST", value=50,
            mandatory=True, weight=1,
        )],
    })
    hard_goal_response = service._recommend_optimization(hard_goal_request)

    assert hard_goal_response.status == "PARTIAL"
    assert hard_goal_response.outcome_status == "PARTIAL"
    assert hard_goal_response.missing_strategies == []
    assert {candidate.strategy for candidate in hard_goal_response.candidates} == {
        "CONTROL", "CONSERVATIVE", "BALANCED", "EXPLORATORY",
    }
    for candidate in hard_goal_response.candidates:
        if candidate.strategy == "CONTROL":
            continue
        assert ("TARGET_NOT_MET" in candidate.risk_flags) == (candidate.results[0].result.value < 50)

    def fake_near_boundary_score(score_request):
        first_ratio = float(score_request.formula.components[0].ratio)
        predicted = 50 + (first_ratio - 50) * 0.3
        result = TargetSuccessV2(
            target_id="target-v1",
            model_version_id="model-1",
            result=ContinuousResultV2(
                value=predicted, lower=predicted - 4, upper=predicted + 4, coverage_level=0.9,
            ),
            applicability=ApplicabilityV2(status="NEAR_BOUNDARY", distance=0.95),
        )
        return ScoreResponseV2(
            request_id=score_request.request_id, run_id=score_request.run_id,
            execution_status="SUCCEEDED", outcome_status="SUCCEEDED", results=[result],
        )

    monkeypatch.setattr(service, "score", fake_near_boundary_score)
    near_boundary_request = request.model_copy(update={
        "goals": [TypedGoalV2(
            target_id="target-v1", operator="AT_LEAST", value=100,
            mandatory=True, weight=1,
        )],
    })
    near_boundary_response = service._recommend_optimization(near_boundary_request)

    assert near_boundary_response.missing_strategies == []
    assert {candidate.strategy for candidate in near_boundary_response.candidates} == {
        "CONTROL", "CONSERVATIVE", "BALANCED", "EXPLORATORY",
    }
    assert all(
        "TARGET_NOT_MET" in candidate.risk_flags and "NEAR_BOUNDARY" in candidate.risk_flags
        for candidate in near_boundary_response.candidates
        if candidate.strategy != "CONTROL"
    )
    assert all(candidate.applicability["status"] == "NEAR_BOUNDARY" for candidate in near_boundary_response.candidates)
    assert near_boundary_response.status == "PARTIAL"


def test_goal_score_uses_requested_class_probability_not_the_largest_class(tmp_path) -> None:
    service = FormulaModelV2Service(Settings(allow_file_urls=True, temporary_root=tmp_path))
    pass_result = success("y1", BinaryResultV2(
        label="FAIL", positive_class="PASS", probability=0.2, decision_threshold=0.5
    ))
    pass_goal = [TypedGoalV2(target_id="y1", operator="MATCH", value="PASS", weight=1)]
    fail_goal = [TypedGoalV2(target_id="y1", operator="MATCH", value="FAIL", weight=1)]

    assert service._goal_score(fail_goal, [pass_result]) > service._goal_score(pass_goal, [pass_result])


def test_multiple_objectives_are_weighted_after_normalizing_each_result(tmp_path) -> None:
    service = FormulaModelV2Service(Settings(allow_file_urls=True, temporary_root=tmp_path))
    goals = [
        TypedGoalV2(target_id="y1", operator="MINIMIZE", value=20, weight=1),
        TypedGoalV2(target_id="y2", operator="MAXIMIZE", value=0, weight=1),
    ]
    results = [
        success("y1", ContinuousResultV2(value=10, lower=9, upper=11, coverage_level=0.9)),
        success("y2", ContinuousResultV2(value=2, lower=1, upper=3, coverage_level=0.9)),
    ]

    # Each target contributes a bounded utility in [0, 1], so a large unit
    # such as GU cannot overwhelm a smaller-scale output such as a ratio.
    assert service._goal_score(goals, results) == 0.875
