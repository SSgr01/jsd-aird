from jsd_aird_ai.contracts_v2 import (
    ApplicabilityV2,
    BinaryResultV2,
    ContinuousResultV2,
    TargetSuccessV2,
    TypedGoalV2,
)
from jsd_aird_ai.service_v2 import FormulaModelV2Service
from jsd_aird_ai.settings import Settings


def success(target_id, result):
    return TargetSuccessV2(
        target_id=target_id, model_version_id="model-1", result=result,
        applicability=ApplicabilityV2(status="IN_DOMAIN", distance=0),
    )


def test_minimize_and_maximize_rank_in_the_requested_direction(tmp_path):
    service = FormulaModelV2Service(Settings(allow_file_urls=True, temporary_root=tmp_path))
    lower = [success("y1", ContinuousResultV2(value=40, lower=38, upper=42, coverage_level=0.9))]
    higher = [success("y1", ContinuousResultV2(value=60, lower=58, upper=62, coverage_level=0.9))]
    minimize = [TypedGoalV2(target_id="y1", operator="MINIMIZE", value=50)]
    maximize = [TypedGoalV2(target_id="y1", operator="MAXIMIZE", value=50)]
    assert service._goal_score(minimize, lower) > service._goal_score(minimize, higher)
    assert service._goal_score(maximize, higher) > service._goal_score(maximize, lower)


def test_preferences_and_mandatory_thresholds_have_distinct_effects(tmp_path):
    service = FormulaModelV2Service(Settings(allow_file_urls=True, temporary_root=tmp_path))
    below = [success("y1", ContinuousResultV2(value=40, lower=38, upper=42, coverage_level=0.9))]
    uncertain_pass = [success("y1", ContinuousResultV2(value=52, lower=47, upper=57, coverage_level=0.9))]
    preference = [TypedGoalV2(target_id="y1", operator="MAXIMIZE", value=50)]
    threshold = [TypedGoalV2(target_id="y1", operator="AT_LEAST", value=50, mandatory=True)]
    assert service._goals_satisfied(preference, below)
    assert not service._goals_satisfied(threshold, below)
    assert service._goals_satisfied(threshold, uncertain_pass)


def test_class_goal_uses_requested_class_probability(tmp_path):
    service = FormulaModelV2Service(Settings(allow_file_urls=True, temporary_root=tmp_path))
    result = [success("y1", BinaryResultV2(
        label="FAIL", positive_class="PASS", probability=0.2, decision_threshold=0.5,
    ))]
    pass_goal = [TypedGoalV2(target_id="y1", operator="MATCH", value="PASS")]
    fail_goal = [TypedGoalV2(target_id="y1", operator="MATCH", value="FAIL")]
    assert service._goal_score(fail_goal, result) > service._goal_score(pass_goal, result)


def test_multiple_objectives_normalize_different_units(tmp_path):
    service = FormulaModelV2Service(Settings(allow_file_urls=True, temporary_root=tmp_path))
    goals = [TypedGoalV2(target_id="y1", operator="MINIMIZE", value=20),
             TypedGoalV2(target_id="y2", operator="MAXIMIZE", value=0)]
    results = [success("y1", ContinuousResultV2(value=10, lower=9, upper=11, coverage_level=0.9)),
               success("y2", ContinuousResultV2(value=2, lower=1, upper=3, coverage_level=0.9))]
    assert service._goal_score(goals, results) == 0.875
