from __future__ import annotations

import hashlib
import time
import warnings
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import numpy as np
import pandas as pd
from sklearn.ensemble import ExtraTreesRegressor

from jsd_aird_ai.applicability import fit_applicability_domain
from jsd_aird_ai.contracts import (
    CandidatePolicy,
    DomainStatus,
    RecommendRequest,
    TaskProfile,
    ValueType,
)
from jsd_aird_ai.digests import canonical_sha256
from jsd_aird_ai.features import FeatureBuilder, MAIN_RESIN_CODE, MAIN_RESIN_PCT
from jsd_aird_ai.model_adapters import (
    GaussianProcessAdapter,
    common_numeric_preprocessor,
)
from jsd_aird_ai.optimizer import CandidatePoolGenerator, RecommendationEngine
from jsd_aird_ai.validation import conformal_radius, group_folds


@dataclass
class ReplayState:
    features: pd.DataFrame
    outcomes: pd.DataFrame
    lineage_groups: np.ndarray
    source_groups: np.ndarray
    row_ids: list[str]

    def append(
        self,
        features: pd.DataFrame,
        outcomes: pd.DataFrame,
        prefix: str,
    ) -> None:
        start = len(self.features)
        count = len(features)
        self.features = pd.concat([self.features, features], ignore_index=True)
        self.outcomes = pd.concat([self.outcomes, outcomes], ignore_index=True)
        self.lineage_groups = np.concatenate(
            [self.lineage_groups, np.asarray([f"{prefix}-L-{index}" for index in range(count)])]
        )
        self.source_groups = np.concatenate(
            [self.source_groups, np.asarray([f"{prefix}-S-{index}" for index in range(count)])]
        )
        self.row_ids.extend(f"{prefix}-{start + index}" for index in range(count))

    def clone(self) -> "ReplayState":
        return ReplayState(
            features=self.features.copy(),
            outcomes=self.outcomes.copy(),
            lineage_groups=self.lineage_groups.copy(),
            source_groups=self.source_groups.copy(),
            row_ids=list(self.row_ids),
        )


class IndependentSyntheticOracle:
    """Hidden deterministic surface fitted from all 400 synthetic rows.

    ExtraTrees is deliberately separate from the replay GP optimizer. Its output is
    simulation truth only; it is not persisted as a production model.
    """

    def __init__(
        self,
        features: pd.DataFrame,
        measurements: pd.DataFrame,
        profile: TaskProfile,
        seed: int,
        threads: int,
    ) -> None:
        self.target_specs = [
            item for item in profile.targets if item.value_type == ValueType.CONTINUOUS
        ]
        self.layout = FeatureBuilder(profile).layout
        self.models: dict[str, tuple[Any, ExtraTreesRegressor]] = {}
        self.ranges: dict[str, tuple[float, float]] = {}
        for target_index, spec in enumerate(self.target_specs):
            values = pd.to_numeric(measurements[spec.code], errors="coerce")
            mask = values.notna() & np.isfinite(values)
            processor = common_numeric_preprocessor(self.layout)
            matrix = processor.fit_transform(features.loc[mask])
            model = ExtraTreesRegressor(
                n_estimators=240,
                min_samples_leaf=2,
                max_features=0.8,
                random_state=seed + target_index,
                n_jobs=max(1, threads),
            )
            model.fit(matrix, values.loc[mask].to_numpy(dtype=float))
            self.models[spec.code] = (processor, model)
            self.ranges[spec.code] = (
                float(values.loc[mask].min()),
                float(values.loc[mask].max()),
            )

    def evaluate(self, features: pd.DataFrame) -> pd.DataFrame:
        result = {}
        for code, (processor, model) in self.models.items():
            result[code] = model.predict(processor.transform(features))
        return pd.DataFrame(result)


class ReplayModelFactory:
    def __init__(self, profile: TaskProfile, seed: int) -> None:
        self.profile = profile
        self.seed = seed
        self.builder = FeatureBuilder(profile)

    def build(self, state: ReplayState, round_index: int):
        adapter = GaussianProcessAdapter(thread_count=1)
        targets: dict[str, dict[str, Any]] = {}
        for target_index, spec in enumerate(self.profile.targets):
            if spec.value_type != ValueType.CONTINUOUS:
                continue
            values = pd.to_numeric(state.outcomes[spec.code], errors="coerce")
            mask = values.notna() & np.isfinite(values)
            features = state.features.loc[mask].reset_index(drop=True)
            target = values.loc[mask].to_numpy(dtype=float)
            lineage = state.lineage_groups[mask.to_numpy()]
            sheets = state.source_groups[mask.to_numpy()]
            row_ids = np.asarray(state.row_ids)[mask.to_numpy()]
            folds = group_folds(lineage, min(3, len(np.unique(lineage))))
            oof = np.full(len(target), np.nan)
            for fold_index, fold in enumerate(folds):
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore")
                    fitted = adapter.fit(
                        features.iloc[fold.train],
                        target[fold.train],
                        self.builder.layout,
                        self.seed + round_index * 1_000 + target_index * 100 + fold_index,
                        {"n_restarts_optimizer": 0},
                        self.profile.validation.interval_level,
                    )
                oof[fold.validation] = fitted.point_predict(features.iloc[fold.validation])
            radius = conformal_radius(
                target,
                oof,
                self.profile.validation.interval_level,
            )
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                scorer = adapter.fit(
                    features,
                    target,
                    self.builder.layout,
                    self.seed + round_index * 1_000 + target_index * 100 + 99,
                    {"n_restarts_optimizer": 0},
                    self.profile.validation.interval_level,
                ).calibrated(radius)
            domain = fit_applicability_domain(
                features,
                self.builder.layout,
                row_ids,
                lineage,
                sheets,
                self.profile.applicability_domain,
            )
            targets[spec.code] = {
                "target_type": "CONTINUOUS",
                "scorer": scorer,
                "scorer_type": "GAUSSIAN_PROCESS",
                "applicability_domain": domain,
                "observed_lower": float(np.min(target)),
                "observed_upper": float(np.max(target)),
            }
        history = pd.concat(
            [state.features.reset_index(drop=True), state.outcomes.reset_index(drop=True)],
            axis=1,
        )
        return SimpleNamespace(
            targets=targets,
            payload={"history": history, "layout": self.builder.layout},
        )


def _heldout_generalization_metrics(
    model: Any,
    features: pd.DataFrame,
    truth: pd.DataFrame,
    profile: TaskProfile,
    ranges: dict[str, tuple[float, float]],
) -> dict[str, Any]:
    """Evaluate one replay model on a fixed oracle-labelled holdout.

    The holdout is never used by the replay optimizer. These metrics therefore
    measure what an exploratory result teaches the model, rather than whether
    that result immediately beats the best known formulation.
    """

    target_metrics: dict[str, dict[str, float]] = {}
    nmae_values: list[float] = []
    coverage_values: list[np.ndarray] = []
    raw_width_values: list[np.ndarray] = []
    normalized_width_values: list[np.ndarray] = []
    evidence_by_target: list[list[Any]] = []

    for spec in profile.targets:
        payload = model.targets.get(spec.code)
        if (
            spec.value_type != ValueType.CONTINUOUS
            or payload is None
            or spec.code not in truth
        ):
            continue
        expected, lower, upper = payload["scorer"].predict(features)
        actual = truth[spec.code].to_numpy(dtype=float)
        target_lower, target_upper = ranges[spec.code]
        span = max(target_upper - target_lower, 1e-12)
        absolute_error = np.abs(actual - expected)
        width = upper - lower
        covered = (actual >= lower) & (actual <= upper)
        nmae = float(np.mean(absolute_error) / span)
        target_metrics[spec.code] = {
            "nmae": nmae,
            "picp90": float(np.mean(covered)),
            "meanPredictionIntervalWidth": float(np.mean(width)),
            "meanNormalizedPredictionIntervalWidth": float(np.mean(width / span)),
        }
        nmae_values.append(nmae)
        coverage_values.append(covered)
        raw_width_values.append(width)
        normalized_width_values.append(width / span)
        evidence_by_target.append(payload["applicability_domain"].assess(features))

    if not target_metrics:
        raise ValueError("replay model has no continuous target for held-out evaluation")

    aggregated_statuses = []
    nearest_distances = []
    for index in range(len(features)):
        row_evidence = [target[index] for target in evidence_by_target]
        aggregated_statuses.append(
            RecommendationEngine._aggregate_domain_status(row_evidence)
        )
        nearest_distances.append(max(item.nearest_distance for item in row_evidence))

    return {
        "heldoutRows": len(features),
        "heldoutNmae": float(np.mean(nmae_values)),
        "picp90": float(np.mean(np.concatenate(coverage_values))),
        "meanPredictionIntervalWidth": float(
            np.mean(np.concatenate(raw_width_values))
        ),
        "meanNormalizedPredictionIntervalWidth": float(
            np.mean(np.concatenate(normalized_width_values))
        ),
        "inDomainCoverage": float(
            np.mean([status == DomainStatus.IN_DOMAIN for status in aggregated_statuses])
        ),
        "nonOodCoverage": float(
            np.mean(
                [status != DomainStatus.OUT_OF_DOMAIN for status in aggregated_statuses]
            )
        ),
        "meanNearestTrainingDistance": float(np.mean(nearest_distances)),
        "targetMetrics": target_metrics,
    }


def _information_gain_delta(
    before: dict[str, Any], after: dict[str, Any]
) -> dict[str, float]:
    return {
        "heldoutNmaeImprovement": before["heldoutNmae"] - after["heldoutNmae"],
        "picp90Change": after["picp90"] - before["picp90"],
        "predictionIntervalWidthReduction": (
            before["meanPredictionIntervalWidth"]
            - after["meanPredictionIntervalWidth"]
        ),
        "normalizedIntervalWidthReduction": (
            before["meanNormalizedPredictionIntervalWidth"]
            - after["meanNormalizedPredictionIntervalWidth"]
        ),
        "inDomainCoverageChange": (
            after["inDomainCoverage"] - before["inDomainCoverage"]
        ),
        "nonOodCoverageChange": (
            after["nonOodCoverage"] - before["nonOodCoverage"]
        ),
        "nearestTrainingDistanceReduction": (
            before["meanNearestTrainingDistance"]
            - after["meanNearestTrainingDistance"]
        ),
    }


def _formula_from_feature(row: pd.Series, builder: FeatureBuilder) -> dict[str, float]:
    additives = {
        item.code: float(row[item.column])
        for item in builder.non_main_materials
        if item.role != "BALANCE"
    }
    return builder.full_formula(
        str(row[MAIN_RESIN_CODE]),
        float(row[MAIN_RESIN_PCT]),
        additives,
    )


def _outcome_desirability(
    outcomes: pd.DataFrame,
    profile: TaskProfile,
    ranges: dict[str, tuple[float, float]],
) -> np.ndarray:
    weighted = np.zeros(len(outcomes), dtype=float)
    total = 0.0
    for spec in profile.targets:
        if spec.code not in outcomes or spec.code not in ranges:
            continue
        lower, upper = ranges[spec.code]
        span = max(upper - lower, 1e-12)
        values = outcomes[spec.code].to_numpy(dtype=float)
        component = (values - lower) / span
        if spec.direction == "MINIMIZE":
            component = 1.0 - component
        weighted += np.clip(component, 0.0, 1.0) * spec.weight
        total += spec.weight
    return weighted / max(total, 1e-12)


def _pareto_front_mask(outcomes: pd.DataFrame, profile: TaskProfile) -> np.ndarray:
    specs = [
        item
        for item in profile.targets
        if item.value_type == ValueType.CONTINUOUS and item.code in outcomes
    ]
    values = outcomes[[item.code for item in specs]].to_numpy(dtype=float)
    signs = np.asarray([-1.0 if item.direction == "MINIMIZE" else 1.0 for item in specs])
    utility = values * signs
    dominated = np.zeros(len(utility), dtype=bool)
    for index in range(len(utility)):
        better_or_equal = np.all(utility >= utility[index], axis=1)
        strictly_better = np.any(utility > utility[index], axis=1)
        dominated[index] = bool(np.any(better_or_equal & strictly_better))
    return ~dominated


def _pareto_front_size(outcomes: pd.DataFrame, profile: TaskProfile) -> int:
    return int(np.sum(_pareto_front_mask(outcomes, profile)))


def _best_formula(state: ReplayState, profile: TaskProfile, oracle: IndependentSyntheticOracle):
    desirability = _outcome_desirability(state.outcomes, profile, oracle.ranges)
    return int(np.argmax(desirability)), desirability


def _mandatory_hit(
    outcomes: pd.DataFrame,
    thresholds: dict[str, float],
) -> np.ndarray:
    return (
        (outcomes["Y__WARPING_PET_INITIAL_CM"].to_numpy(dtype=float)
         <= thresholds["Y__WARPING_PET_INITIAL_CM"])
        & (outcomes["Y__STEEL_WOOL_500G_CYCLES"].to_numpy(dtype=float)
           >= thresholds["Y__STEEL_WOOL_500G_CYCLES"])
    )


def _policy_summary(
    state: ReplayState,
    initial_count: int,
    profile: TaskProfile,
    oracle: IndependentSyntheticOracle,
    thresholds: dict[str, float],
    experiment_budget: int | None = None,
) -> dict[str, Any]:
    available_experiments = len(state.outcomes) - initial_count
    evaluated_experiments = (
        available_experiments
        if experiment_budget is None
        else min(experiment_budget, available_experiments)
    )
    evaluated_outcomes = state.outcomes.iloc[
        : initial_count + evaluated_experiments
    ].reset_index(drop=True)
    desirability = _outcome_desirability(
        evaluated_outcomes, profile, oracle.ranges
    )
    new_desirability = desirability[initial_count:]
    hits = np.flatnonzero(_mandatory_hit(evaluated_outcomes, thresholds))
    new_hits = _mandatory_hit(evaluated_outcomes.iloc[initial_count:], thresholds)
    post_initial_hits = hits[hits >= initial_count]
    best_targets = {}
    best_new_targets = {}
    for spec in profile.targets:
        if (
            spec.code not in evaluated_outcomes
            or spec.value_type != ValueType.CONTINUOUS
        ):
            continue
        values = evaluated_outcomes[spec.code]
        best_targets[spec.code] = float(
            values.min() if spec.direction == "MINIMIZE" else values.max()
        )
        new_values = values.iloc[initial_count:]
        best_new_targets[spec.code] = float(
            new_values.min() if spec.direction == "MINIMIZE" else new_values.max()
        )
    pareto_mask = _pareto_front_mask(evaluated_outcomes, profile)
    return {
        "experiments": evaluated_experiments,
        "availableExperiments": available_experiments,
        "requestedExperimentBudget": experiment_budget,
        "bestDesirability": float(np.max(desirability)),
        "initialBestDesirability": float(np.max(desirability[:initial_count])),
        "desirabilityImprovement": float(
            np.max(desirability) - np.max(desirability[:initial_count])
        ),
        "bestNewExperimentDesirability": float(np.max(new_desirability)),
        "meanNewExperimentDesirability": float(np.mean(new_desirability)),
        "mandatoryTargetHitRate": float(np.mean(new_hits)),
        "experimentsToMandatoryTargets": (
            int(post_initial_hits[0] - initial_count + 1)
            if len(post_initial_hits)
            else None
        ),
        "paretoFrontSize": _pareto_front_size(evaluated_outcomes, profile),
        "initialParetoFrontSize": _pareto_front_size(
            state.outcomes.iloc[:initial_count], profile
        ),
        "newExperimentParetoContributions": int(
            np.sum(pareto_mask[initial_count:])
        ),
        "bestTargets": best_targets,
        "bestNewTargets": best_new_targets,
    }


def run_synthetic_optimization_replay(
    *,
    measurements_path: Path,
    profile: TaskProfile,
    rounds: int = 5,
    initial_rows: int = 100,
    experiments_per_round: int = 4,
    pool_size: int = 512,
    seed: int = 20260904,
    threads: int = 2,
) -> dict[str, Any]:
    if not 5 <= rounds <= 10:
        raise ValueError("rounds must be between 5 and 10")
    if initial_rows < 30:
        raise ValueError("initial_rows must satisfy the development threshold")
    started = time.perf_counter()
    source_map_path = measurements_path.with_name("source-map.parquet")

    def source_hash() -> str:
        digest = hashlib.sha256()
        for path in [measurements_path, source_map_path]:
            digest.update(path.name.encode("utf-8"))
            digest.update(path.read_bytes())
        return digest.hexdigest()

    source_hash_before = source_hash()
    measurements = pd.read_parquet(measurements_path)
    source_map = pd.read_parquet(source_map_path)
    provenance = measurements[["experiment_version_id"]].merge(
        source_map[
            ["experiment_version_id", "formula_lineage_group", "source_sheet"]
        ],
        on="experiment_version_id",
        how="left",
        validate="one_to_one",
    )
    if provenance[["formula_lineage_group", "source_sheet"]].isna().any().any():
        raise ValueError("source-map does not cover every replay measurement row")
    replay_profile = profile.model_copy(
        update={
            "candidate": CandidatePolicy(
                maximum_pool_size=max(100, pool_size),
                minimum_l1_distance=profile.candidate.minimum_l1_distance,
                conservative_maximum_l1_distance=(
                    profile.candidate.conservative_maximum_l1_distance
                ),
                default_count=experiments_per_round,
            )
        }
    )
    builder = FeatureBuilder(replay_profile)
    all_features = builder.from_snapshot(measurements)
    target_codes = [
        item.code for item in replay_profile.targets if item.value_type == ValueType.CONTINUOUS
    ]
    rng = np.random.default_rng(seed)
    initial_indices = np.sort(rng.choice(len(measurements), size=initial_rows, replace=False))
    initial_features = all_features.iloc[initial_indices].reset_index(drop=True)
    oracle = IndependentSyntheticOracle(
        all_features,
        measurements,
        replay_profile,
        seed + 50_000,
        threads,
    )
    initial_outcomes = oracle.evaluate(initial_features)
    heldout_indices = np.setdiff1d(np.arange(len(measurements)), initial_indices)
    heldout_features = all_features.iloc[heldout_indices].reset_index(drop=True)
    heldout_outcomes = oracle.evaluate(heldout_features)
    base_state = ReplayState(
        features=initial_features,
        outcomes=initial_outcomes,
        lineage_groups=provenance.iloc[initial_indices]["formula_lineage_group"].astype(str).to_numpy(),
        source_groups=provenance.iloc[initial_indices]["source_sheet"].astype(str).to_numpy(),
        row_ids=measurements.iloc[initial_indices]["experiment_version_id"].astype(str).tolist(),
    )
    states = {
        name: ReplayState(
            features=base_state.features.copy(),
            outcomes=base_state.outcomes.copy(),
            lineage_groups=base_state.lineage_groups.copy(),
            source_groups=base_state.source_groups.copy(),
            row_ids=list(base_state.row_ids),
        )
        for name in ["AI_OPTIMIZATION", "RANDOM_FEASIBLE", "LOCAL_PERTURBATION"]
    }
    thresholds = {
        "Y__WARPING_PET_INITIAL_CM": float(
            initial_outcomes["Y__WARPING_PET_INITIAL_CM"].quantile(0.25)
        ),
        "Y__STEEL_WOOL_500G_CYCLES": float(
            initial_outcomes["Y__STEEL_WOOL_500G_CYCLES"].quantile(0.75)
        ),
    }
    target_requests = [
        {
            "code": item.code,
            "mode": "MINIMIZE" if item.direction == "MINIMIZE" else "MAXIMIZE",
            "mandatory": False,
            "weight": item.weight,
        }
        for item in replay_profile.targets
        if item.value_type == ValueType.CONTINUOUS
    ]
    model_factory = ReplayModelFactory(replay_profile, seed)
    engine = RecommendationEngine()
    pool_generator = CandidatePoolGenerator()
    round_records = []
    seen_by_policy: dict[str, list[dict[str, float]]] = {
        name: [_formula_from_feature(row, builder) for _, row in state.features.iterrows()]
        for name, state in states.items()
    }

    for round_index in range(rounds):
        ai_state = states["AI_OPTIMIZATION"]
        model = model_factory.build(ai_state, round_index)
        heldout_before_exploration = _heldout_generalization_metrics(
            model,
            heldout_features,
            heldout_outcomes,
            replay_profile,
            oracle.ranges,
        )
        best_index, before_desirability = _best_formula(ai_state, replay_profile, oracle)
        baseline_formula = _formula_from_feature(ai_state.features.iloc[best_index], builder)
        context_row = measurements.iloc[initial_indices[0]]
        context = {
            item.code: context_row[item.column] for item in replay_profile.context_features
        }
        request = RecommendRequest(
            request_id=f"synthetic-replay-round-{round_index}",
            task_profile_hash=canonical_sha256(replay_profile),
            snapshot_hash=source_hash_before,
            model_bundle_hash="0" * 64,
            seed=seed + round_index,
            task_profile=replay_profile,
            model_bundle={"url": "https://example.invalid/replay.zip", "sha256": "0" * 64},
            baseline_formula=baseline_formula,
            context=context,
            targets=target_requests,
            count=experiments_per_round,
            recommendation_mode="EXPERIMENT_OPTIMIZATION",
            minimum_potential_desirability=0.10,
        )
        ai_result = engine.recommend(request, model)
        ai_formulas = [item.formula for item in ai_result.candidates]
        ai_features = builder.from_api_rows(ai_formulas, [context] * len(ai_formulas))
        ai_outcomes = oracle.evaluate(ai_features)
        previous_best = float(np.max(before_desirability))
        exploratory_gain = None
        exploratory_index = None
        for candidate_index, (candidate, outcome) in enumerate(
            zip(ai_result.candidates, ai_outcomes.to_dict("records"), strict=True)
        ):
            if candidate.strategy == "EXPLORATORY":
                exploratory_index = candidate_index
                exploratory_gain = float(
                    _outcome_desirability(pd.DataFrame([outcome]), replay_profile, oracle.ranges)[0]
                    - previous_best
                )
        exploratory_information_gain: dict[str, Any] = {
            "available": exploratory_index is not None,
            "before": heldout_before_exploration,
            "afterExploratoryOnly": None,
            "delta": None,
        }
        if exploratory_index is not None:
            exploration_state = ai_state.clone()
            exploration_state.append(
                ai_features.iloc[[exploratory_index]].reset_index(drop=True),
                ai_outcomes.iloc[[exploratory_index]].reset_index(drop=True),
                f"AI-INFO-R{round_index}",
            )
            model_after_exploration = model_factory.build(
                exploration_state, round_index
            )
            heldout_after_exploration = _heldout_generalization_metrics(
                model_after_exploration,
                heldout_features,
                heldout_outcomes,
                replay_profile,
                oracle.ranges,
            )
            exploratory_information_gain["afterExploratoryOnly"] = (
                heldout_after_exploration
            )
            exploratory_information_gain["delta"] = _information_gain_delta(
                heldout_before_exploration,
                heldout_after_exploration,
            )
        ai_state.append(ai_features, ai_outcomes, f"AI-R{round_index}")
        seen_by_policy["AI_OPTIMIZATION"].extend(ai_formulas)

        pool = pool_generator.generate(request)
        available = [
            formula
            for formula in pool.formulas
            if min(
                RecommendationEngine._l1(formula, prior)
                for prior in seen_by_policy["RANDOM_FEASIBLE"]
            )
            >= replay_profile.candidate.minimum_l1_distance
        ]
        random_indices = rng.choice(
            len(available),
            size=min(experiments_per_round, len(available)),
            replace=False,
        )
        random_formulas = [available[int(index)] for index in random_indices]
        random_features = builder.from_api_rows(
            random_formulas, [context] * len(random_formulas)
        )
        random_outcomes = oracle.evaluate(random_features)
        states["RANDOM_FEASIBLE"].append(
            random_features, random_outcomes, f"RANDOM-R{round_index}"
        )
        seen_by_policy["RANDOM_FEASIBLE"].extend(random_formulas)

        local_state = states["LOCAL_PERTURBATION"]
        local_best, _ = _best_formula(local_state, replay_profile, oracle)
        local_formula = _formula_from_feature(local_state.features.iloc[local_best], builder)
        local_available = [
            formula
            for formula in pool.formulas
            if min(
                RecommendationEngine._l1(formula, prior)
                for prior in seen_by_policy["LOCAL_PERTURBATION"]
            )
            >= replay_profile.candidate.minimum_l1_distance
        ]
        local_formulas = sorted(
            local_available,
            key=lambda formula: RecommendationEngine._l1(formula, local_formula),
        )[:experiments_per_round]
        local_features = builder.from_api_rows(
            local_formulas, [context] * len(local_formulas)
        )
        local_outcomes = oracle.evaluate(local_features)
        local_state.append(local_features, local_outcomes, f"LOCAL-R{round_index}")
        seen_by_policy["LOCAL_PERTURBATION"].extend(local_formulas)

        round_records.append(
            {
                "round": round_index + 1,
                "aiRecommended": len(ai_formulas),
                "aiStrategies": [item.strategy for item in ai_result.candidates],
                "aiBestDesirability": float(
                    np.max(_outcome_desirability(ai_state.outcomes, replay_profile, oracle.ranges))
                ),
                "randomBestDesirability": float(
                    np.max(
                        _outcome_desirability(
                            states["RANDOM_FEASIBLE"].outcomes,
                            replay_profile,
                            oracle.ranges,
                        )
                    )
                ),
                "localBestDesirability": float(
                    np.max(
                        _outcome_desirability(
                            local_state.outcomes,
                            replay_profile,
                            oracle.ranges,
                        )
                    )
                ),
                "aiParetoFrontSize": _pareto_front_size(ai_state.outcomes, replay_profile),
                "oodRejectedRate": (
                    ai_result.search_space.out_of_domain_rejected_count
                    / max(ai_result.search_space.accepted_after_constraints_count, 1)
                ),
                "nearBoundaryCount": ai_result.search_space.near_boundary_count,
                "meanPredictionIntervalWidth": float(
                    np.mean(
                        [
                            prediction.upper - prediction.lower
                            for item in ai_result.candidates
                            for prediction in item.predictions
                        ]
                    )
                ),
                "exploratoryActualDesirabilityGain": exploratory_gain,
                "exploratoryInformationGain": exploratory_information_gain,
                "nonControlHistoryDuplicateRate": float(
                    np.mean(
                        [
                            item.l1_distance_from_history
                            < replay_profile.candidate.minimum_l1_distance
                            for item in ai_result.candidates
                            if item.strategy != "CONTROL"
                        ]
                        or [0.0]
                    )
                ),
                "missingStrategies": ai_result.missing_strategies,
                "searchSpace": ai_result.search_space.model_dump(
                    mode="json", by_alias=True
                ),
            }
        )

    source_hash_after = source_hash()
    policy_names = [
        "AI_OPTIMIZATION",
        "RANDOM_FEASIBLE",
        "LOCAL_PERTURBATION",
    ]
    experiment_counts = {
        name: len(states[name].outcomes) - initial_rows for name in policy_names
    }
    equal_budget = min(experiment_counts.values())

    def summaries_at_budget(budget: int) -> dict[str, dict[str, Any]]:
        return {
            name: _policy_summary(
                states[name],
                initial_rows,
                replay_profile,
                oracle,
                thresholds,
                experiment_budget=budget,
            )
            for name in policy_names
        }

    checkpoints = list(range(experiments_per_round, equal_budget + 1, experiments_per_round))
    if equal_budget not in checkpoints:
        checkpoints.append(equal_budget)
    information_deltas = [
        record["exploratoryInformationGain"]["delta"]
        for record in round_records
        if record["exploratoryInformationGain"]["delta"] is not None
    ]
    information_summary = {
        "roundsEvaluated": len(information_deltas),
        "meanDelta": (
            {
                key: float(np.mean([item[key] for item in information_deltas]))
                for key in information_deltas[0]
            }
            if information_deltas
            else {}
        ),
        "interpretation": (
            "Positive NMAE improvement, interval-width reduction, domain-coverage change, "
            "or nearest-distance reduction indicates information value. PICP change must be "
            "interpreted relative to the configured coverage target rather than maximized."
        ),
    }
    return {
        "dataNature": "SYNTHETIC",
        "snapshotPurpose": "DEVELOPMENT",
        "productionEligible": False,
        "productionModel": "NOT_ACTIVE",
        "method": "INDEPENDENT_EXTRATREES_ORACLE_WITH_ROUND_GP_RETRAINING",
        "oracleWarning": (
            "The hidden ExtraTrees oracle is a deterministic simulator fitted from the fixed "
            "Synthetic Snapshot. Results validate workflow only and are not customer efficacy evidence."
        ),
        "seed": seed,
        "rounds": rounds,
        "initialRows": initial_rows,
        "experimentsPerRound": experiments_per_round,
        "sourceSnapshotSha256Before": source_hash_before,
        "sourceSnapshotSha256After": source_hash_after,
        "sourceSnapshotUnchanged": source_hash_before == source_hash_after,
        "mandatoryThresholds": thresholds,
        "roundMetrics": round_records,
        "policySummary": {
            name: _policy_summary(
                state,
                initial_rows,
                replay_profile,
                oracle,
                thresholds,
            )
            for name, state in states.items()
        },
        "equalBudgetComparison": {
            "experimentBudget": equal_budget,
            "originalExperimentCounts": experiment_counts,
            "policies": summaries_at_budget(equal_budget),
        },
        "budgetCheckpointComparison": [
            {"experimentBudget": budget, "policies": summaries_at_budget(budget)}
            for budget in checkpoints
        ],
        "exploratoryInformationSummary": information_summary,
        "elapsedSeconds": time.perf_counter() - started,
    }
