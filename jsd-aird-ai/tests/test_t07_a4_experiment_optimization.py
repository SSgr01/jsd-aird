from __future__ import annotations

from types import SimpleNamespace
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from jsd_aird_ai.contracts import (
    ApplicabilityDomainEvidence,
    DomainStatus,
    MaterialConstraint,
    ModelType,
    RecommendRequest,
    RecommendationMode,
)
from jsd_aird_ai.digests import canonical_sha256, sha256_bytes
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.features import FeatureBuilder, MAIN_RESIN_PCT
from jsd_aird_ai.optimizer import CandidatePool, CandidatePoolGenerator, RecommendationEngine
from jsd_aird_ai.optimization_replay import run_synthetic_optimization_replay
from jsd_aird_ai.modeling import load_bundle, rebind_bundle_schema


class RecordingDomain:
    def assess(self, features: pd.DataFrame) -> list[ApplicabilityDomainEvidence]:
        output = []
        for value in features[MAIN_RESIN_PCT].astype(float):
            if value >= 74.0:
                status = DomainStatus.OUT_OF_DOMAIN
            elif value >= 69.0:
                status = DomainStatus.NEAR_BOUNDARY
            else:
                status = DomainStatus.IN_DOMAIN
            output.append(
                ApplicabilityDomainEvidence(
                    status=status,
                    model_usable=status != DomainStatus.OUT_OF_DOMAIN,
                    nearest_training_row_id="HISTORY-001",
                    nearest_distance=abs(value - 56.0) / 20.0,
                    near_boundary_threshold=0.65,
                    out_of_domain_threshold=0.90,
                    reasons=[] if status == DomainStatus.IN_DOMAIN else [status],
                )
            )
        return output


class UnexpectedDomain:
    def __init__(self) -> None:
        self.called = False

    def assess(self, features: pd.DataFrame):
        self.called = True
        raise AssertionError("an unrequested target must not participate in OOD gating")


class ExactBaselineOutOfDomain:
    def assess(self, features: pd.DataFrame) -> list[ApplicabilityDomainEvidence]:
        output = []
        for value in features[MAIN_RESIN_PCT].astype(float):
            status = (
                DomainStatus.OUT_OF_DOMAIN
                if abs(value - 56.0) <= 1e-9
                else DomainStatus.IN_DOMAIN
            )
            output.append(
                ApplicabilityDomainEvidence(
                    status=status,
                    model_usable=status != DomainStatus.OUT_OF_DOMAIN,
                    nearest_training_row_id="HISTORY-002",
                    nearest_distance=1.0 if status == DomainStatus.OUT_OF_DOMAIN else 0.1,
                    near_boundary_threshold=0.65,
                    out_of_domain_threshold=0.90,
                    reasons=[] if status == DomainStatus.IN_DOMAIN else [status],
                )
            )
        return output


class RecordingScorer:
    def __init__(self, target: str, calls: list[tuple[str, list[float]]]) -> None:
        self.target = target
        self.calls = calls

    def predict(self, features: pd.DataFrame):
        resin = features[MAIN_RESIN_PCT].to_numpy(dtype=float)
        self.calls.append((self.target, resin.tolist()))
        if self.target == "WARP":
            expected = 3.0 - 0.08 * (resin - 56.0)
            radius = 0.10 + 0.02 * np.abs(resin - 56.0)
        else:
            expected = 200.0 - 8.0 * (resin - 56.0)
            radius = 10.0 + np.abs(resin - 56.0)
        return expected, expected - radius, expected + radius


class FailingScorer:
    def predict(self, features: pd.DataFrame):
        raise RuntimeError("simulated scorer failure")


class FixedBaybe:
    def shortlist(self, candidates, bundle, targets, batch_size, seed):
        return candidates.iloc[1: min(len(candidates), 4)].copy()


class ExplodingBaybe:
    def shortlist(self, candidates, bundle, targets, batch_size, seed):
        raise RuntimeError("simulated BayBE failure")


class FixedPool:
    def __init__(self, formulas):
        self.formulas = formulas

    def generate(self, request):
        return CandidatePool(
            formulas=self.formulas,
            raw_candidate_count=len(self.formulas) + 3,
            material_constraint_rejected_count=2,
            duplicate_candidate_count=1,
        )


def shifted_formula(baseline: dict[str, float], resin_code: str, value: float):
    result = dict(baseline)
    delta = value - result[resin_code]
    result[resin_code] = value
    result["S-48"] -= delta
    return result


@pytest.fixture
def optimization_case(profile):
    builder = FeatureBuilder(profile)
    frame = pd.read_parquet(
        "docs/AI实验优化、配方预测/UVPU_APPLICATION_FORMULATION_Synthetic_Shared_Process_V3_Package/"
        "UVPU_APPLICATION_FORMULATION_Synthetic_TrainingSnapshot_V2_Shared_Process_PyArrow/measurements.parquet"
    )
    row = frame.iloc[0]
    baseline = {item.code: float(row[item.column]) for item in profile.formula.materials}
    context = {item.code: row[item.column] for item in profile.context_features}
    resin = next(code for code in profile.formula.main_resin_codes if baseline[code] > 0)
    # Put the synthetic test baseline at a stable, easy-to-reason-about resin level.
    baseline = shifted_formula(baseline, resin, 56.0)
    formulas = [
        baseline,
        shifted_formula(baseline, resin, 59.0),
        shifted_formula(baseline, resin, 65.0),
        shifted_formula(baseline, resin, 70.0),
        shifted_formula(baseline, resin, 75.0),
        shifted_formula(baseline, resin, 50.0),
    ]
    features = builder.from_api_rows([baseline], [context])
    calls: list[tuple[str, list[float]]] = []
    domain = RecordingDomain()
    targets = {
        "WARP": {
            "target_type": "CONTINUOUS",
            "scorer": RecordingScorer("WARP", calls),
            "scorer_type": ModelType.GAUSSIAN_PROCESS,
            "applicability_domain": domain,
            "observed_lower": 0.0,
            "observed_upper": 5.0,
        },
        "WEAR": {
            "target_type": "CONTINUOUS",
            "scorer": RecordingScorer("WEAR", calls),
            "scorer_type": ModelType.GAUSSIAN_PROCESS,
            "applicability_domain": domain,
            "observed_lower": 50.0,
            "observed_upper": 350.0,
        },
    }
    history = features.copy()
    history["WARP"] = 3.0
    history["WEAR"] = 200.0
    bundle = SimpleNamespace(targets=targets, payload={"history": history})
    request = RecommendRequest(
        request_id="a4-unit",
        task_profile_hash=canonical_sha256(profile),
        snapshot_hash="a" * 64,
        model_bundle_hash="b" * 64,
        seed=17,
        task_profile=profile,
        model_bundle={"url": "https://example.invalid/model.zip", "sha256": "b" * 64},
        baseline_formula=baseline,
        context=context,
        targets=[
            {"code": "WARP", "mode": "MINIMIZE", "weight": 3.0},
            {"code": "WEAR", "mode": "MAXIMIZE", "weight": 1.0},
        ],
        count=4,
        minimum_potential_desirability=0.0,
    )
    engine = RecommendationEngine(FixedBaybe())
    engine._pool = FixedPool(formulas)
    return engine, request, bundle, calls, resin


def test_ood_candidates_are_removed_before_model_scoring(optimization_case):
    engine, request, bundle, calls, _ = optimization_case
    result = engine.recommend(request, bundle)
    assert result.search_space.out_of_domain_rejected_count == 1
    assert result.search_space.final_scoreable_candidate_count == 5
    assert calls
    assert all(75.0 not in values for _, values in calls)
    assert all(item.domain_status != DomainStatus.OUT_OF_DOMAIN for item in result.candidates)


def test_only_requested_targets_participate_in_ood_gating(optimization_case):
    engine, request, bundle, _, _ = optimization_case
    unexpected_domain = UnexpectedDomain()
    bundle.targets["UNREQUESTED_TARGET"] = {
        "target_type": "CONTINUOUS",
        "scorer": FailingScorer(),
        "scorer_type": ModelType.GAUSSIAN_PROCESS,
        "applicability_domain": unexpected_domain,
        "observed_lower": 0.0,
        "observed_upper": 1.0,
    }
    result = engine.recommend(request, bundle)
    assert result.candidates
    assert unexpected_domain.called is False


def test_control_identifies_nearest_feasible_replacement_when_baseline_is_ood(
    optimization_case,
):
    engine, request, bundle, _, _ = optimization_case
    domain = ExactBaselineOutOfDomain()
    for payload in bundle.targets.values():
        payload["applicability_domain"] = domain
    result = engine.recommend(request, bundle)
    control = next(item for item in result.candidates if item.strategy == "CONTROL")
    assert control.control_source == "NEAREST_FEASIBLE_BASELINE"
    assert control.l1_distance_from_baseline > 0.0
    assert "exact baseline was unavailable" in control.selection_reason


def test_near_boundary_is_only_a_controlled_exploration_candidate(optimization_case):
    engine, request, bundle, _, _ = optimization_case
    result = engine.recommend(request, bundle)
    near = [item for item in result.candidates if item.domain_status == DomainStatus.NEAR_BOUNDARY]
    assert near
    assert all(item.strategy == "EXPLORATORY" for item in near)
    assert all("NEAR_BOUNDARY_EXPLORATION_REQUIRES_REVIEW" in item.risks for item in near)


def test_four_experiment_roles_have_distinct_selection_evidence(optimization_case):
    engine, request, bundle, _, _ = optimization_case
    result = engine.recommend(request, bundle)
    by_strategy = {item.strategy: item for item in result.candidates}
    assert set(by_strategy) == {"CONTROL", "CONSERVATIVE", "BALANCED", "EXPLORATORY"}
    assert by_strategy["CONTROL"].l1_distance_from_baseline == pytest.approx(0.0)
    assert by_strategy["CONTROL"].control_source == "EXACT_BASELINE"
    assert "conservative desirability" in by_strategy["CONSERVATIVE"].selection_reason
    assert "multi-target expected desirability" in by_strategy["BALANCED"].selection_reason
    assert "combined information value" in by_strategy["EXPLORATORY"].selection_reason
    assert len({item.candidate_id for item in result.candidates}) == 4


def test_target_conflict_and_candidate_tradeoffs_are_structured(optimization_case):
    engine, request, bundle, _, _ = optimization_case
    result = engine.recommend(request, bundle)
    assert any(
        item.conflict_type == "NO_JOINT_IMPROVEMENT"
        and set(item.target_codes) == {"WARP", "WEAR"}
        for item in result.target_conflicts
    )
    balanced = next(item for item in result.candidates if item.strategy == "BALANCED")
    assert {item.outcome for item in balanced.target_tradeoffs} >= {"IMPROVES", "TRADES_OFF"}
    assert balanced.conflicts
    assert "not a causal" in balanced.conflicts[0].explanation


def test_search_space_statistics_and_batch_history_diversity(optimization_case):
    engine, request, bundle, _, _ = optimization_case
    result = engine.recommend(request, bundle)
    stats = result.search_space
    assert stats.raw_candidate_count == 9
    assert stats.material_constraint_rejected_count == 2
    assert stats.duplicate_candidate_count == 1
    assert stats.near_boundary_count == 1
    assert stats.in_domain_count == 4
    assert stats.baybe_shortlist_count > 0
    assert 0.0 < stats.coverage_ratio < 1.0
    for item in result.candidates:
        if item.strategy != "CONTROL":
            assert item.l1_distance_from_history >= request.task_profile.candidate.minimum_l1_distance
        if item.l1_distance_from_batch is not None:
            assert item.l1_distance_from_batch >= request.task_profile.candidate.minimum_l1_distance


def test_fixed_seed_recommendation_is_reproducible(optimization_case):
    engine, request, bundle, _, _ = optimization_case
    assert engine.recommend(request, bundle) == engine.recommend(request, bundle)


def test_formula_prediction_and_experiment_optimization_are_not_aliases(optimization_case):
    engine, request, bundle, _, _ = optimization_case
    experiment = engine.recommend(request, bundle)
    formula_request = request.model_copy(
        update={"recommendation_mode": RecommendationMode.FORMULA_PREDICTION}
    )
    prediction = engine.recommend(formula_request, bundle)
    assert {item.strategy for item in experiment.candidates} != {
        item.strategy for item in prediction.candidates
    }
    assert all(
        "information gain is intentionally not part" in item.selection_reason
        for item in prediction.candidates
    )


def test_insufficient_space_returns_missing_strategies_and_reasons(optimization_case):
    engine, request, bundle, _, _ = optimization_case
    engine._pool = FixedPool(engine._pool.formulas[:1])
    result = engine.recommend(request, bundle)
    assert result.missing_strategies
    assert set(result.missing_strategies) == set(result.missing_strategy_reasons)
    assert result.search_space.insufficient_space_reasons


def test_model_failure_is_rejected_when_no_target_remains(optimization_case):
    engine, request, bundle, _, _ = optimization_case
    for payload in bundle.targets.values():
        payload["scorer"] = FailingScorer()
    with pytest.raises(FormulaModelError) as raised:
        engine.recommend(request, bundle)
    assert raised.value.code == ErrorCode.MODEL_NOT_READY


def test_one_failed_target_is_reported_without_blocking_remaining_model(
    optimization_case,
):
    engine, request, bundle, _, _ = optimization_case
    bundle.targets["WEAR"]["scorer"] = FailingScorer()
    result = engine.recommend(request, bundle)
    assert result.candidates
    assert result.unavailable_targets == ["WEAR"]
    assert all(
        [prediction.target_code for prediction in candidate.predictions] == ["WARP"]
        for candidate in result.candidates
    )


def test_baybe_failure_is_rejected_as_compute_error(optimization_case):
    _, request, bundle, _, _ = optimization_case
    engine = RecommendationEngine(ExplodingBaybe())
    engine._pool = FixedPool(optimization_case[0]._pool.formulas)
    with pytest.raises(FormulaModelError) as raised:
        engine.recommend(request, bundle)
    assert raised.value.code == ErrorCode.INTERNAL_COMPUTE_ERROR


def test_task_profile_material_bounds_cannot_be_overridden(profile, optimization_case):
    _, request, _, _, _ = optimization_case
    invalid = request.model_copy(
        update={
            "material_constraints": [
                MaterialConstraint(material_code="DSP-3315", fixed=50.0)
            ]
        }
    )
    with pytest.raises(FormulaModelError) as raised:
        CandidatePoolGenerator().generate(invalid)
    assert raised.value.code == ErrorCode.NO_FEASIBLE_CANDIDATE


def test_candidate_pool_respects_request_and_task_profile_constraints(optimization_case):
    _, request, _, _, _ = optimization_case
    constrained = request.model_copy(
        update={
            "material_constraints": [
                MaterialConstraint(material_code="DSP-3315", maximum=1.0)
            ],
            "count": 1,
        }
    )
    pool = CandidatePoolGenerator().generate(constrained)
    assert pool.formulas
    assert all(formula["DSP-3315"] <= 1.0 for formula in pool.formulas)


def test_five_round_replay_compares_baselines_without_mutating_snapshot(profile):
    measurements = Path(
        "docs/AI实验优化、配方预测/UVPU_APPLICATION_FORMULATION_Synthetic_Shared_Process_V3_Package/"
        "UVPU_APPLICATION_FORMULATION_Synthetic_TrainingSnapshot_V2_Shared_Process_PyArrow/measurements.parquet"
    )
    result = run_synthetic_optimization_replay(
        measurements_path=measurements,
        profile=profile,
        rounds=5,
        initial_rows=40,
        experiments_per_round=4,
        pool_size=100,
        seed=20260904,
        threads=1,
    )
    assert len(result["roundMetrics"]) == 5
    assert result["sourceSnapshotUnchanged"] is True
    assert set(result["policySummary"]) == {
        "AI_OPTIMIZATION",
        "RANDOM_FEASIBLE",
        "LOCAL_PERTURBATION",
    }
    for summary in result["policySummary"].values():
        assert summary["experiments"] > 0
        assert 0.0 <= summary["mandatoryTargetHitRate"] <= 1.0
        assert "bestNewExperimentDesirability" in summary
        assert "newExperimentParetoContributions" in summary
    equal_budget = result["equalBudgetComparison"]
    assert equal_budget["experimentBudget"] == min(
        equal_budget["originalExperimentCounts"].values()
    )
    assert {
        summary["experiments"] for summary in equal_budget["policies"].values()
    } == {equal_budget["experimentBudget"]}
    assert [item["experimentBudget"] for item in result["budgetCheckpointComparison"]]
    assert result["exploratoryInformationSummary"]["roundsEvaluated"] > 0
    first_information = result["roundMetrics"][0]["exploratoryInformationGain"]
    assert first_information["available"] is True
    assert first_information["before"]["heldoutRows"] == 360
    assert first_information["afterExploratoryOnly"]["heldoutRows"] == 360
    assert set(first_information["delta"]) >= {
        "heldoutNmaeImprovement",
        "picp90Change",
        "normalizedIntervalWidthReduction",
        "inDomainCoverageChange",
        "nearestTrainingDistanceReduction",
    }


def test_schema_only_repackage_preserves_scorers_and_rebinds_hash(trained_bundle):
    original = trained_bundle["path"].read_bytes()
    updated_profile = trained_bundle["profile"].model_copy(
        update={"schema_hash": "f" * 64}
    )
    repackaged = rebind_bundle_schema(
        original,
        trained_bundle["sha256"],
        updated_profile,
    )
    repackaged_hash = sha256_bytes(repackaged)
    loaded = load_bundle(repackaged, repackaged_hash)
    assert loaded.profile.schema_hash == "f" * 64
    assert loaded.payload["task_profile_hash"] == canonical_sha256(updated_profile)
    assert set(loaded.targets) == set(
        load_bundle(original, trained_bundle["sha256"]).targets
    )


def test_schema_repackage_rejects_any_semantic_profile_change(trained_bundle):
    original = trained_bundle["path"].read_bytes()
    changed = trained_bundle["profile"].model_copy(
        update={"schema_hash": "e" * 64, "rule_version": "changed-rule"}
    )
    with pytest.raises(FormulaModelError) as raised:
        rebind_bundle_schema(original, trained_bundle["sha256"], changed)
    assert raised.value.code == ErrorCode.HASH_MISMATCH
