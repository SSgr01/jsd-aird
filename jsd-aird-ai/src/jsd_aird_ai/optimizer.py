from __future__ import annotations

import math
from dataclasses import dataclass
from threading import RLock
from typing import Any

import numpy as np
import pandas as pd
from baybe.campaign import Campaign
from baybe.objectives import DesirabilityObjective
from baybe.parameters import CategoricalParameter, NumericalDiscreteParameter
from baybe.recommenders import BotorchRecommender
from baybe.searchspace import SearchSpace
from baybe.settings import Settings as BaybeSettings
from baybe.surrogates import GaussianProcessSurrogate
from baybe.targets import NumericalTarget
from scipy.stats import qmc

from jsd_aird_ai.contracts import (
    ApplicabilityDomainEvidence,
    DomainStatus,
    FormulaDifference,
    MaterialConstraint,
    Prediction,
    RecommendationMode,
    RecommendRequest,
    RecommendedCandidate,
    RequestedTarget,
    SearchSpaceStatistics,
    TargetConflict,
    TargetTradeoff,
    TargetMode,
    TaskProfile,
)
from jsd_aird_ai.digests import canonical_sha256
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.feature_views import base_feature_view
from jsd_aird_ai.features import FeatureBuilder, MAIN_RESIN_CODE, MAIN_RESIN_PCT
from jsd_aird_ai.modeling import ModelBundle


@dataclass
class CandidatePool:
    formulas: list[dict[str, float]]
    raw_candidate_count: int = 0
    material_constraint_rejected_count: int = 0
    duplicate_candidate_count: int = 0


@dataclass(frozen=True)
class RecommendationResult:
    candidates: list[RecommendedCandidate]
    missing_strategies: list[str]
    missing_strategy_reasons: dict[str, str]
    unsupported_targets: list[str]
    unavailable_targets: list[str]
    search_space: SearchSpaceStatistics
    target_conflicts: list[TargetConflict]


class CandidatePoolGenerator:
    def generate(self, request: RecommendRequest) -> CandidatePool:
        profile = request.task_profile
        builder = FeatureBuilder(profile)
        constraint_by_code = {item.material_code: item for item in request.material_constraints}
        unknown = set(constraint_by_code) - set(builder.material_by_code)
        if unknown:
            raise FormulaModelError(
                ErrorCode.NO_FEASIBLE_CANDIDATE,
                "material constraint contains unknown material",
                details={"unknown": sorted(unknown)},
            )

        ranges: dict[str, tuple[float, float, float | None]] = {}
        for code, spec in builder.material_by_code.items():
            constraint = constraint_by_code.get(code)
            lower = max(spec.minimum, constraint.minimum if constraint and constraint.minimum is not None else spec.minimum)
            upper = min(spec.maximum, constraint.maximum if constraint and constraint.maximum is not None else spec.maximum)
            fixed = constraint.fixed if constraint else None
            if fixed is not None:
                if fixed < lower - 1e-9 or fixed > upper + 1e-9:
                    raise FormulaModelError(
                        ErrorCode.NO_FEASIBLE_CANDIDATE,
                        f"fixed material value is outside the task-profile range for {code}",
                    )
                lower = upper = fixed
            if lower > upper:
                raise FormulaModelError(
                    ErrorCode.NO_FEASIBLE_CANDIDATE,
                    f"material constraint is infeasible for {code}",
                )
            ranges[code] = (lower, upper, fixed)

        forced_resins = [
            code
            for code in profile.formula.main_resin_codes
            if ranges[code][2] is not None and float(ranges[code][2]) > 1e-9
        ]
        if len(forced_resins) > 1:
            raise FormulaModelError(
                ErrorCode.NO_FEASIBLE_CANDIDATE,
                "more than one main resin is fixed to a positive value",
            )
        resin_codes = forced_resins or profile.formula.main_resin_codes
        balance_code = profile.formula.balance_material_code
        sampled_codes = [
            code
            for code, material in builder.material_by_code.items()
            if code != balance_code and material.role != "MAIN_RESIN"
        ]
        maximum = profile.candidate.maximum_pool_size
        desired = min(maximum, max(2048, request.count * 512))
        per_resin = max(1, math.ceil(desired / len(resin_codes)))
        dimension = 1 + len(sampled_codes)
        formulas: list[dict[str, float]] = []
        seen: set[tuple[tuple[str, float], ...]] = set()
        raw_count = 0
        constraint_rejected = 0
        duplicate_count = 0

        for resin_index, resin_code in enumerate(resin_codes):
            sampler = qmc.Sobol(d=dimension, scramble=True, seed=request.seed + resin_index)
            draw_count = 1 << math.ceil(math.log2(per_resin))
            samples = sampler.random_base2(int(math.log2(draw_count)))[:per_resin]
            variable_codes = [resin_code, *sampled_codes]
            for sample in samples:
                raw_count += 1
                values: dict[str, float] = {}
                for index, code in enumerate(variable_codes):
                    spec = builder.material_by_code[code]
                    lower, upper, fixed = ranges[code]
                    raw = fixed if fixed is not None else lower + sample[index] * (upper - lower)
                    value = lower + round((float(raw) - lower) / spec.step) * spec.step
                    values[code] = min(upper, max(lower, value))
                formula = builder.full_formula(
                    resin_code,
                    values[resin_code],
                    {code: values[code] for code in sampled_codes},
                )
                balance = formula[balance_code]
                balance_lower, balance_upper, balance_fixed = ranges[balance_code]
                if balance < balance_lower - 1e-9 or balance > balance_upper + 1e-9:
                    constraint_rejected += 1
                    continue
                if balance_fixed is not None and abs(balance - balance_fixed) > profile.formula.sum_tolerance:
                    constraint_rejected += 1
                    continue
                if not self._constraint_compatible(formula, constraint_by_code):
                    constraint_rejected += 1
                    continue
                key = tuple(sorted((code, round(value, 6)) for code, value in formula.items()))
                if key not in seen:
                    seen.add(key)
                    formulas.append(formula)
                else:
                    duplicate_count += 1
                if len(formulas) >= desired:
                    break
            if len(formulas) >= desired:
                break

        try:
            raw_count += 1
            builder.from_api_rows([request.baseline_formula], [request.context])
            baseline_key = tuple(
                sorted((code, round(float(value), 6)) for code, value in request.baseline_formula.items())
            )
            if baseline_key not in seen and self._constraint_compatible(request.baseline_formula, constraint_by_code):
                formulas.insert(0, {code: float(value) for code, value in request.baseline_formula.items()})
            elif baseline_key in seen:
                duplicate_count += 1
            else:
                constraint_rejected += 1
        except FormulaModelError:
            constraint_rejected += 1
        if not formulas:
            raise FormulaModelError(
                ErrorCode.NO_FEASIBLE_CANDIDATE,
                "no formula satisfies the configured material constraints",
                details={
                    "rawCandidateCount": raw_count,
                    "materialConstraintRejectedCount": constraint_rejected,
                    "duplicateCandidateCount": duplicate_count,
                },
            )
        return CandidatePool(
            formulas=formulas,
            raw_candidate_count=raw_count,
            material_constraint_rejected_count=constraint_rejected,
            duplicate_candidate_count=duplicate_count,
        )

    @staticmethod
    def _constraint_compatible(
        formula: dict[str, float], constraints: dict[str, MaterialConstraint]
    ) -> bool:
        for code, constraint in constraints.items():
            value = float(formula.get(code, 0.0))
            if constraint.fixed is not None and abs(value - constraint.fixed) > 1e-6:
                return False
            if constraint.minimum is not None and value < constraint.minimum - 1e-9:
                return False
            if constraint.maximum is not None and value > constraint.maximum + 1e-9:
                return False
        return True


class BaybeOptimizerAdapter:
    """The only module allowed to depend on BayBE public APIs."""

    def __init__(self) -> None:
        # BayBE 0.15 controls its RNG through process-global active settings.
        # Serialize the small optimization section so concurrent HTTP requests
        # cannot interfere with deterministic request seeds.
        self._settings_lock = RLock()

    def shortlist(
        self,
        candidates: pd.DataFrame,
        bundle: ModelBundle,
        targets: list[RequestedTarget],
        batch_size: int,
        seed: int,
    ) -> pd.DataFrame:
        layout = bundle.payload["layout"]
        history = bundle.payload["history"]
        usable_targets = [
            item
            for item in targets
            if item.code in bundle.targets
            and str(bundle.targets[item.code].get("target_type")) == "CONTINUOUS"
        ]
        target_columns = [item.code for item in usable_targets]
        complete_history = history[[*layout.all, *target_columns]].dropna()
        combined_parameters = pd.concat(
            [candidates[layout.all], complete_history[layout.all]],
            ignore_index=True,
        )
        # BayBE 0.15 requires at least two values for every categorical parameter.
        # Fixed process/context columns remain inputs to the champion scorers but are
        # intentionally not optimization dimensions.
        parameter_columns = [
            name for name in layout.all if combined_parameters[name].nunique(dropna=False) >= 2
        ]
        measurements = complete_history[[*parameter_columns, *target_columns]].drop_duplicates(
            subset=parameter_columns, keep="last"
        )
        search_frame = combined_parameters[parameter_columns].drop_duplicates(ignore_index=True)
        parameters = []
        for name in parameter_columns:
            values = search_frame[name].drop_duplicates().tolist()
            if name in layout.categorical:
                parameters.append(CategoricalParameter(name=name, values=values))
            else:
                parameters.append(
                    NumericalDiscreteParameter(name=name, values=sorted(float(value) for value in values))
                )
        searchspace = SearchSpace.from_dataframe(search_frame, parameters)

        target_objects = []
        weights = []
        for requested in usable_targets:
            payload = bundle.targets[requested.code]
            lower = float(payload["observed_lower"])
            upper = float(payload["observed_upper"])
            if upper <= lower:
                upper = lower + 1.0
            descending = requested.mode in {TargetMode.MINIMIZE, TargetMode.AT_MOST}
            target_objects.append(
                NumericalTarget.normalized_ramp(
                    requested.code,
                    (lower, upper),
                    descending=descending,
                )
            )
            weights.append(float(requested.weight))
        if not target_objects:
            raise FormulaModelError(ErrorCode.MODEL_NOT_READY, "none of the requested targets has a model")
        objective = (
            target_objects[0].to_objective()
            if len(target_objects) == 1
            else DesirabilityObjective(target_objects, weights=weights)
        )
        minimum_rows = max(3, len(target_objects) + 1)
        if len(measurements) < minimum_rows:
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "not enough complete rows for BayBE")
        with self._settings_lock, BaybeSettings(random_seed=seed, cache_directory=None):
            recommender = BotorchRecommender(surrogate_model=GaussianProcessSurrogate())
            campaign = Campaign(searchspace=searchspace, objective=objective, recommender=recommender)
            campaign.add_measurements(measurements)
            return campaign.recommend(batch_size=min(batch_size, len(candidates)))


class RecommendationEngine:
    def __init__(self, baybe_adapter: BaybeOptimizerAdapter | None = None) -> None:
        self._pool = CandidatePoolGenerator()
        self._baybe = baybe_adapter or BaybeOptimizerAdapter()

    def recommend(self, request: RecommendRequest, bundle: ModelBundle) -> RecommendationResult:
        feature_view = getattr(bundle, "feature_view", None) or base_feature_view(request.task_profile)
        if feature_view.scope != "BASE":
            raise FormulaModelError(
                ErrorCode.MODEL_NOT_READY,
                "enhanced resin-batch models are for development scoring only; recommendations use BASE_V1",
            )
        pool = self._pool.generate(request)
        formulas = pool.formulas
        builder = FeatureBuilder(request.task_profile, feature_view)
        all_features = builder.from_api_rows(formulas, [request.context] * len(formulas))
        domain_by_target: dict[str, list[ApplicabilityDomainEvidence]] = {}
        supported_payloads: dict[str, dict[str, Any]] = {}
        unsupported: list[str] = []
        unavailable: list[str] = []
        for requested in request.targets:
            payload = bundle.targets.get(requested.code)
            if payload is None or str(payload.get("target_type")) != "CONTINUOUS":
                unsupported.append(requested.code)
                continue
            domain = payload.get("applicability_domain")
            if domain is None:
                unavailable.append(requested.code)
                continue
            try:
                domain_by_target[requested.code] = domain.assess(all_features)
                supported_payloads[requested.code] = payload
            except Exception:
                unavailable.append(requested.code)

        if not supported_payloads:
            raise FormulaModelError(
                ErrorCode.MODEL_NOT_READY,
                "none of the requested targets has a usable model and applicability domain",
                details={
                    "unsupportedTargets": unsupported,
                    "unavailableTargets": unavailable,
                },
            )

        all_statuses = [
            self._aggregate_domain_status(
                [domain_by_target[code][index] for code in supported_payloads]
            )
            for index in range(len(formulas))
        ]
        scoreable_indices = np.array(
            [index for index, status in enumerate(all_statuses) if status != DomainStatus.OUT_OF_DOMAIN],
            dtype=int,
        )
        out_of_domain_count = len(formulas) - len(scoreable_indices)
        near_boundary_count = sum(status == DomainStatus.NEAR_BOUNDARY for status in all_statuses)
        in_domain_count = sum(status == DomainStatus.IN_DOMAIN for status in all_statuses)
        if not len(scoreable_indices):
            raise FormulaModelError(
                ErrorCode.NO_FEASIBLE_CANDIDATE,
                "all constraint-compatible candidates are outside the model applicability domain",
                details={
                    "outOfDomainRejectedCount": out_of_domain_count,
                    "acceptedAfterConstraintsCount": len(formulas),
                },
            )

        scoreable_formulas = [formulas[index] for index in scoreable_indices]
        features = all_features.iloc[scoreable_indices].reset_index(drop=True)
        scoreable_statuses = [all_statuses[index] for index in scoreable_indices]
        scoreable_domain = {
            code: [evidence[index] for index in scoreable_indices]
            for code, evidence in domain_by_target.items()
        }
        predictions: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray]] = {}
        for requested in request.targets:
            payload = supported_payloads.get(requested.code)
            if payload is None:
                continue
            try:
                predictions[requested.code] = payload["scorer"].predict(features)
            except Exception:
                unavailable.append(requested.code)
                scoreable_domain.pop(requested.code, None)
        if not predictions:
            raise FormulaModelError(
                ErrorCode.MODEL_NOT_READY,
                "all requested target models failed during candidate scoring",
                details={"unavailableTargets": sorted(set(unavailable))},
            )

        unavailable = sorted(set(unavailable))
        active_targets = [item for item in request.targets if item.code in predictions]
        scoreable_statuses = [
            self._aggregate_domain_status(
                [scoreable_domain[code][index] for code in predictions]
            )
            for index in range(len(scoreable_formulas))
        ]

        expected_score = self._desirability(active_targets, bundle, predictions, pessimistic=False)
        conservative_score = self._desirability(active_targets, bundle, predictions, pessimistic=True)
        target_components = self._target_components(active_targets, bundle, predictions)
        feasible = self._mandatory_mask(active_targets, predictions)
        feasible_indices = np.flatnonzero(feasible)
        if not len(feasible_indices):
            raise FormulaModelError(
                ErrorCode.NO_FEASIBLE_CANDIDATE,
                "no candidate satisfies modeled mandatory target bounds",
                details={
                    "outOfDomainRejectedCount": out_of_domain_count,
                    "finalScoreableCandidateCount": len(scoreable_formulas),
                },
            )

        try:
            baybe_rows = self._baybe.shortlist(
                features.iloc[feasible_indices].reset_index(drop=True),
                bundle,
                request.targets,
                batch_size=min(16, len(feasible_indices)),
                seed=request.seed,
            )
            baybe_indices = self._match_rows(features, baybe_rows)
        except Exception as exc:
            if isinstance(exc, FormulaModelError):
                raise
            raise FormulaModelError(
                ErrorCode.INTERNAL_COMPUTE_ERROR,
                "BayBE candidate search failed",
                retryable=False,
            ) from exc

        history_formulas = self._history_formulas(bundle, builder)
        history_distances = self._history_distances(scoreable_formulas, history_formulas)
        baseline_distances = np.asarray(
            [self._l1(formula, request.baseline_formula) for formula in scoreable_formulas],
            dtype=float,
        )
        nearest_training_distances = np.asarray(
            [
                max(
                    scoreable_domain[code][index].nearest_distance
                    for code in predictions
                )
                for index in range(len(scoreable_formulas))
            ],
            dtype=float,
        )
        uncertainty = self._uncertainty_score(predictions)
        information_value = self._information_value(
            uncertainty,
            nearest_training_distances,
            history_distances,
            expected_score,
            request.task_profile.candidate.minimum_l1_distance,
        )
        baseline_index = self._find_formula(scoreable_formulas, request.baseline_formula)
        baseline_expected = {
            code: float(values[0][baseline_index])
            for code, values in predictions.items()
            if baseline_index is not None
        }
        baseline_components = {
            code: float(values[baseline_index])
            for code, values in target_components.items()
            if baseline_index is not None
        }
        global_conflicts = self._global_conflicts(
            active_targets,
            target_components,
            baseline_components,
            feasible_indices,
        )

        search_space = SearchSpaceStatistics(
            raw_candidate_count=pool.raw_candidate_count,
            material_constraint_rejected_count=pool.material_constraint_rejected_count,
            duplicate_candidate_count=pool.duplicate_candidate_count,
            accepted_after_constraints_count=len(formulas),
            out_of_domain_rejected_count=out_of_domain_count,
            near_boundary_count=near_boundary_count,
            in_domain_count=in_domain_count,
            final_scoreable_candidate_count=len(scoreable_formulas),
            baybe_shortlist_count=len(set(baybe_indices)),
            coverage_ratio=len(scoreable_formulas) / max(len(formulas), 1),
        )

        if request.recommendation_mode == RecommendationMode.FORMULA_PREDICTION:
            return self._formula_prediction_result(
                request=request,
                bundle=bundle,
                formulas=scoreable_formulas,
                predictions=predictions,
                scoreable_domain=scoreable_domain,
                scoreable_statuses=scoreable_statuses,
                expected_score=expected_score,
                conservative_score=conservative_score,
                information_value=information_value,
                target_components=target_components,
                baseline_expected=baseline_expected,
                baseline_components=baseline_components,
                baseline_distances=baseline_distances,
                history_distances=history_distances,
                nearest_training_distances=nearest_training_distances,
                feasible_indices=feasible_indices,
                unsupported=unsupported,
                unavailable=unavailable,
                search_space=search_space,
                global_conflicts=global_conflicts,
            )

        strategies = ["CONTROL", "CONSERVATIVE", "BALANCED", "EXPLORATORY"][
            : request.count
        ]
        selected: list[int] = []
        output: list[RecommendedCandidate] = []
        missing: list[str] = []
        missing_reasons: dict[str, str] = {}
        safe = np.asarray(
            [status == DomainStatus.IN_DOMAIN for status in scoreable_statuses], dtype=bool
        )
        novel = history_distances >= request.task_profile.candidate.minimum_l1_distance
        potential = expected_score >= request.minimum_potential_desirability

        for strategy in strategies:
            control_source = None
            if strategy == "CONTROL":
                order = (
                    [baseline_index]
                    if baseline_index is not None
                    and feasible[baseline_index]
                    and safe[baseline_index]
                    else []
                )
                order += sorted(
                    [index for index in feasible_indices if safe[index]],
                    key=lambda index: baseline_distances[index],
                )
                missing_reason = "NO_IN_DOMAIN_FEASIBLE_CONTROL"
            elif strategy == "CONSERVATIVE":
                nearby = np.array(
                    [
                        index
                        for index in feasible_indices
                        if safe[index]
                        and novel[index]
                        and baseline_distances[index]
                        <= request.task_profile.candidate.conservative_maximum_l1_distance
                    ],
                    dtype=int,
                )
                conservative_rank = (
                    0.80 * conservative_score[nearby]
                    + 0.20 * expected_score[nearby]
                    - 0.01
                    * baseline_distances[nearby]
                    / request.task_profile.candidate.conservative_maximum_l1_distance
                )
                order = list(nearby[np.argsort(-conservative_rank)])
                missing_reason = "NO_SAFE_NOVEL_CANDIDATE_WITHIN_CONSERVATIVE_DISTANCE"
            elif strategy == "BALANCED":
                baybe_feasible = [
                    index
                    for index in baybe_indices
                    if feasible[index] and safe[index] and novel[index]
                ]
                order = sorted(baybe_feasible, key=lambda index: expected_score[index], reverse=True)
                safe_feasible = np.array(
                    [index for index in feasible_indices if safe[index] and novel[index]],
                    dtype=int,
                )
                order += list(safe_feasible[np.argsort(-expected_score[safe_feasible])])
                missing_reason = "NO_SAFE_NOVEL_BALANCED_CANDIDATE"
            else:
                exploratory = np.array(
                    [
                        index
                        for index in feasible_indices
                        if novel[index] and potential[index]
                    ],
                    dtype=int,
                )
                baybe_feasible = [index for index in baybe_indices if index in set(exploratory)]
                order = sorted(
                    baybe_feasible,
                    key=lambda index: information_value[index],
                    reverse=True,
                )
                order += list(exploratory[np.argsort(-information_value[exploratory])])
                missing_reason = "NO_NON_OOD_POTENTIAL_NOVEL_EXPLORATION_CANDIDATE"
            chosen = self._first_diverse(
                order,
                selected,
                scoreable_formulas,
                request.task_profile.candidate.minimum_l1_distance,
            )
            if chosen is None:
                missing.append(strategy)
                missing_reasons[strategy] = missing_reason
                continue
            if strategy == "CONTROL":
                control_source = (
                    "EXACT_BASELINE"
                    if baseline_index is not None and chosen == baseline_index
                    else "NEAREST_FEASIBLE_BASELINE"
                )
            selected.append(chosen)
            output.append(
                self._candidate(
                    chosen=chosen,
                    strategy=strategy,
                    request=request,
                    bundle=bundle,
                    formulas=scoreable_formulas,
                    predictions=predictions,
                    scoreable_domain=scoreable_domain,
                    status=scoreable_statuses[chosen],
                    expected_score=expected_score,
                    conservative_score=conservative_score,
                    information_value=information_value,
                    target_components=target_components,
                    baseline_expected=baseline_expected,
                    baseline_components=baseline_components,
                    baseline_distance=baseline_distances[chosen],
                    history_distance=history_distances[chosen],
                    nearest_training_distance=nearest_training_distances[chosen],
                    selected=selected[:-1],
                    unsupported=unsupported,
                    control_source=control_source,
                )
            )
        reasons = list(missing_reasons.values())
        search_space.insufficient_space_reasons = reasons
        return RecommendationResult(
            candidates=output,
            missing_strategies=missing,
            missing_strategy_reasons=missing_reasons,
            unsupported_targets=unsupported,
            unavailable_targets=unavailable,
            search_space=search_space,
            target_conflicts=global_conflicts,
        )

    @staticmethod
    def _aggregate_domain_status(
        evidence: list[ApplicabilityDomainEvidence],
    ) -> DomainStatus:
        if any(item.status == DomainStatus.OUT_OF_DOMAIN for item in evidence):
            return DomainStatus.OUT_OF_DOMAIN
        if any(item.status == DomainStatus.NEAR_BOUNDARY for item in evidence):
            return DomainStatus.NEAR_BOUNDARY
        return DomainStatus.IN_DOMAIN

    def _formula_prediction_result(
        self,
        *,
        request: RecommendRequest,
        bundle: ModelBundle,
        formulas: list[dict[str, float]],
        predictions: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray]],
        scoreable_domain: dict[str, list[ApplicabilityDomainEvidence]],
        scoreable_statuses: list[DomainStatus],
        expected_score: np.ndarray,
        conservative_score: np.ndarray,
        information_value: np.ndarray,
        target_components: dict[str, np.ndarray],
        baseline_expected: dict[str, float],
        baseline_components: dict[str, float],
        baseline_distances: np.ndarray,
        history_distances: np.ndarray,
        nearest_training_distances: np.ndarray,
        feasible_indices: np.ndarray,
        unsupported: list[str],
        unavailable: list[str],
        search_space: SearchSpaceStatistics,
        global_conflicts: list[TargetConflict],
    ) -> RecommendationResult:
        # Formula prediction deliberately ignores information gain and history novelty:
        # it asks which X is most likely to satisfy the requested Y goals now.
        order = sorted(
            [
                index
                for index in feasible_indices
                if scoreable_statuses[index] == DomainStatus.IN_DOMAIN
            ],
            key=lambda index: expected_score[index],
            reverse=True,
        )
        selected: list[int] = []
        output: list[RecommendedCandidate] = []
        for _ in range(request.count):
            chosen = self._first_diverse(
                order,
                selected,
                formulas,
                request.task_profile.candidate.minimum_l1_distance,
            )
            if chosen is None:
                break
            output.append(
                self._candidate(
                    chosen=chosen,
                    strategy="BALANCED",
                    request=request,
                    bundle=bundle,
                    formulas=formulas,
                    predictions=predictions,
                    scoreable_domain=scoreable_domain,
                    status=scoreable_statuses[chosen],
                    expected_score=expected_score,
                    conservative_score=conservative_score,
                    information_value=information_value,
                    target_components=target_components,
                    baseline_expected=baseline_expected,
                    baseline_components=baseline_components,
                    baseline_distance=baseline_distances[chosen],
                    history_distance=history_distances[chosen],
                    nearest_training_distance=nearest_training_distances[chosen],
                    selected=selected,
                    unsupported=unsupported,
                )
            )
            selected.append(chosen)
        missing_reasons: dict[str, str] = {}
        if len(output) < request.count:
            missing_reasons["FORMULA_PREDICTION"] = (
                "INSUFFICIENT_IN_DOMAIN_DIVERSE_FORMULAS"
            )
            search_space.insufficient_space_reasons = list(missing_reasons.values())
        return RecommendationResult(
            candidates=output,
            missing_strategies=[],
            missing_strategy_reasons=missing_reasons,
            unsupported_targets=unsupported,
            unavailable_targets=unavailable,
            search_space=search_space,
            target_conflicts=global_conflicts,
        )

    def _candidate(
        self,
        *,
        chosen: int,
        strategy: str,
        request: RecommendRequest,
        bundle: ModelBundle,
        formulas: list[dict[str, float]],
        predictions: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray]],
        scoreable_domain: dict[str, list[ApplicabilityDomainEvidence]],
        status: DomainStatus,
        expected_score: np.ndarray,
        conservative_score: np.ndarray,
        information_value: np.ndarray,
        target_components: dict[str, np.ndarray],
        baseline_expected: dict[str, float],
        baseline_components: dict[str, float],
        baseline_distance: float,
        history_distance: float,
        nearest_training_distance: float,
        selected: list[int],
        unsupported: list[str],
        control_source: str | None = None,
    ) -> RecommendedCandidate:
        tradeoffs = self._tradeoffs(
            request.targets,
            predictions,
            target_components,
            baseline_expected,
            baseline_components,
            chosen,
        )
        improves = [item.target_code for item in tradeoffs if item.outcome == "IMPROVES"]
        retreats = [item.target_code for item in tradeoffs if item.outcome == "TRADES_OFF"]
        conflicts = []
        if improves and retreats:
            conflicts.append(
                TargetConflict(
                    target_codes=[*improves, *retreats],
                    conflict_type="PARETO_TRADE_OFF",
                    explanation=(
                        "candidate improves "
                        + ", ".join(improves)
                        + " while conceding "
                        + ", ".join(retreats)
                        + "; this is predictive trade-off evidence, not a causal claim"
                    ),
                )
            )
        selection_reason = self._selection_reason(
            strategy,
            request.recommendation_mode,
            expected_score[chosen],
            conservative_score[chosen],
            information_value[chosen],
            status,
            improves,
            retreats,
            control_source,
        )
        batch_distance = (
            min(self._l1(formulas[chosen], formulas[index]) for index in selected)
            if selected
            else None
        )
        risks = []
        if status == DomainStatus.NEAR_BOUNDARY:
            risks.append("NEAR_BOUNDARY_EXPLORATION_REQUIRES_REVIEW")
        if retreats:
            risks.append("PREDICTED_TARGET_TRADE_OFF")
        return RecommendedCandidate(
            candidate_id=f"CAND-{canonical_sha256(formulas[chosen])[:16].upper()}",
            strategy=strategy,
            formula=formulas[chosen],
            predictions=self._prediction_models(
                chosen,
                request.task_profile,
                bundle,
                predictions,
                scoreable_domain,
            ),
            desirability=float(np.clip(expected_score[chosen], 0.0, 1.0)),
            l1_distance_from_baseline=float(baseline_distance),
            reasons=self._reasons(
                strategy,
                request.recommendation_mode,
                unsupported,
                control_source,
            ),
            recommendation_mode=request.recommendation_mode,
            domain_status=status,
            nearest_training_distance=float(nearest_training_distance),
            l1_distance_from_history=float(history_distance),
            l1_distance_from_batch=batch_distance,
            conservative_desirability=float(
                np.clip(conservative_score[chosen], 0.0, 1.0)
            ),
            exploration_value=float(np.clip(information_value[chosen], 0.0, 1.0)),
            formula_differences=self._formula_differences(
                formulas[chosen], request.baseline_formula
            ),
            target_tradeoffs=tradeoffs,
            conflicts=conflicts,
            control_source=control_source,
            selection_reason=selection_reason,
            risks=risks,
        )

    @staticmethod
    def _target_components(
        requested_targets: list[RequestedTarget],
        bundle: ModelBundle,
        predictions: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray]],
    ) -> dict[str, np.ndarray]:
        output: dict[str, np.ndarray] = {}
        for requested in requested_targets:
            if requested.code not in predictions:
                continue
            expected = predictions[requested.code][0]
            payload = bundle.targets[requested.code]
            observed_lower = float(payload["observed_lower"])
            observed_upper = float(payload["observed_upper"])
            span = max(observed_upper - observed_lower, 1e-12)
            if requested.mode in {TargetMode.MINIMIZE, TargetMode.AT_MOST}:
                component = 1.0 - (expected - observed_lower) / span
            elif requested.mode in {TargetMode.MAXIMIZE, TargetMode.AT_LEAST}:
                component = (expected - observed_lower) / span
            elif requested.mode == TargetMode.MATCH:
                component = 1.0 - np.abs(expected - float(requested.value)) / span
            else:
                midpoint = (float(requested.minimum) + float(requested.maximum)) / 2.0
                half_width = max(
                    (float(requested.maximum) - float(requested.minimum)) / 2.0,
                    1e-12,
                )
                component = 1.0 - np.abs(expected - midpoint) / max(span, half_width)
            output[requested.code] = np.clip(component, 0.0, 1.0)
        return output

    @staticmethod
    def _information_value(
        uncertainty: np.ndarray,
        nearest_training_distance: np.ndarray,
        history_distance: np.ndarray,
        expected_desirability: np.ndarray,
        minimum_history_distance: float,
    ) -> np.ndarray:
        def normalized(values: np.ndarray) -> np.ndarray:
            lower = float(np.min(values))
            upper = float(np.max(values))
            if upper <= lower:
                return np.zeros(len(values), dtype=float)
            return (values - lower) / (upper - lower)

        domain_coverage_gap = normalized(nearest_training_distance)
        history_coverage_gap = np.clip(
            history_distance / max(minimum_history_distance * 4.0, 1e-12),
            0.0,
            1.0,
        )
        value = (
            0.40 * uncertainty
            + 0.25 * domain_coverage_gap
            + 0.20 * history_coverage_gap
            + 0.15 * expected_desirability
        )
        return np.clip(value, 0.0, 1.0)

    @staticmethod
    def _history_formulas(
        bundle: ModelBundle, builder: FeatureBuilder
    ) -> list[dict[str, float]]:
        history = bundle.payload.get("history")
        if not isinstance(history, pd.DataFrame) or history.empty:
            return []
        formulas = []
        additives = [
            item for item in builder.non_main_materials if item.role != "BALANCE"
        ]
        for _, row in history.iterrows():
            formulas.append(
                builder.full_formula(
                    str(row[MAIN_RESIN_CODE]),
                    float(row[MAIN_RESIN_PCT]),
                    {item.code: float(row[item.column]) for item in additives},
                )
            )
        return formulas

    @staticmethod
    def _history_distances(
        formulas: list[dict[str, float]], history: list[dict[str, float]]
    ) -> np.ndarray:
        if not history:
            return np.zeros(len(formulas), dtype=float)
        codes = sorted(history[0])
        reference = np.asarray(
            [[float(formula[code]) for code in codes] for formula in history],
            dtype=float,
        )
        result = np.empty(len(formulas), dtype=float)
        for start in range(0, len(formulas), 2_048):
            stop = min(start + 2_048, len(formulas))
            candidate = np.asarray(
                [
                    [float(formulas[index][code]) for code in codes]
                    for index in range(start, stop)
                ],
                dtype=float,
            )
            result[start:stop] = np.min(
                np.abs(candidate[:, None, :] - reference[None, :, :]).sum(axis=2),
                axis=1,
            )
        return result

    @staticmethod
    def _formula_differences(
        formula: dict[str, float], baseline: dict[str, float]
    ) -> list[FormulaDifference]:
        output = []
        for code in sorted(set(formula) | set(baseline)):
            before = float(baseline.get(code, 0.0))
            after = float(formula.get(code, 0.0))
            if abs(after - before) <= 1e-9:
                continue
            output.append(
                FormulaDifference(
                    material_code=code,
                    baseline_value=before,
                    candidate_value=after,
                    delta=after - before,
                )
            )
        return output

    @staticmethod
    def _tradeoffs(
        requested_targets: list[RequestedTarget],
        predictions: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray]],
        components: dict[str, np.ndarray],
        baseline_expected: dict[str, float],
        baseline_components: dict[str, float],
        index: int,
    ) -> list[TargetTradeoff]:
        output = []
        tolerance = 0.01
        for requested in requested_targets:
            if requested.code not in predictions:
                continue
            expected = float(predictions[requested.code][0][index])
            if requested.code not in baseline_expected:
                output.append(
                    TargetTradeoff(
                        target_code=requested.code,
                        outcome="NO_BASELINE",
                        expected=expected,
                        explanation="baseline was not eligible for in-domain model scoring",
                    )
                )
                continue
            delta = float(
                components[requested.code][index]
                - baseline_components[requested.code]
            )
            if delta > tolerance:
                outcome = "IMPROVES"
            elif delta < -tolerance:
                outcome = "TRADES_OFF"
            else:
                outcome = "UNCHANGED"
            output.append(
                TargetTradeoff(
                    target_code=requested.code,
                    outcome=outcome,
                    expected=expected,
                    baseline_expected=baseline_expected[requested.code],
                    desirability_delta=delta,
                    explanation=(
                        f"predicted normalized desirability changes by {delta:+.4f} "
                        "relative to the in-domain baseline; this is not causal evidence"
                    ),
                )
            )
        return output

    @staticmethod
    def _global_conflicts(
        requested_targets: list[RequestedTarget],
        components: dict[str, np.ndarray],
        baseline_components: dict[str, float],
        feasible_indices: np.ndarray,
    ) -> list[TargetConflict]:
        if not baseline_components:
            return []
        available = [item.code for item in requested_targets if item.code in components]
        conflicts = []
        for left_index, left in enumerate(available):
            for right in available[left_index + 1 :]:
                left_gain = components[left][feasible_indices] - baseline_components[left]
                right_gain = components[right][feasible_indices] - baseline_components[right]
                left_possible = bool(np.any(left_gain > 0.01))
                right_possible = bool(np.any(right_gain > 0.01))
                joint = bool(np.any((left_gain > 0.01) & (right_gain > 0.01)))
                if left_possible and right_possible and not joint:
                    conflicts.append(
                        TargetConflict(
                            target_codes=[left, right],
                            conflict_type="NO_JOINT_IMPROVEMENT",
                            explanation=(
                                "within the non-OOD feasible pool each target can improve, "
                                "but no candidate improves both relative to the baseline"
                            ),
                        )
                    )
                    continue
                if len(feasible_indices) >= 3:
                    correlation = float(np.corrcoef(left_gain, right_gain)[0, 1])
                    if np.isfinite(correlation) and correlation < -0.25:
                        conflicts.append(
                            TargetConflict(
                                target_codes=[left, right],
                                conflict_type="PARETO_TRADE_OFF",
                                explanation=(
                                    f"predicted desirability gains are negatively associated "
                                    f"in the feasible pool (correlation={correlation:.3f}); "
                                    "this describes model predictions, not causality"
                                ),
                            )
                        )
        return conflicts

    @staticmethod
    def _selection_reason(
        strategy: str,
        mode: RecommendationMode,
        expected: float,
        conservative: float,
        information: float,
        status: DomainStatus,
        improves: list[str],
        retreats: list[str],
        control_source: str | None,
    ) -> str:
        if mode == RecommendationMode.FORMULA_PREDICTION:
            return (
                f"selected by expected multi-target desirability={expected:.4f}; "
                "information gain is intentionally not part of formula-prediction ranking"
            )
        control_reason = (
            "selected as the exact in-domain baseline retest"
            if control_source == "EXACT_BASELINE"
            else "selected as the nearest feasible in-domain replacement because the exact baseline was unavailable"
        )
        details = {
            "CONTROL": control_reason,
            "CONSERVATIVE": (
                f"selected for conservative desirability={conservative:.4f} near the baseline"
            ),
            "BALANCED": f"selected for multi-target expected desirability={expected:.4f}",
            "EXPLORATORY": (
                f"selected for combined information value={information:.4f}, "
                f"potential desirability={expected:.4f}, domain={status}"
            ),
        }[strategy]
        if improves or retreats:
            details += (
                f"; predicted improvements={improves or ['none']}, "
                f"trade-offs={retreats or ['none']}"
            )
        return details

    @staticmethod
    def _desirability(
        requested_targets: list[RequestedTarget],
        bundle: ModelBundle,
        predictions: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray]],
        *,
        pessimistic: bool,
    ) -> np.ndarray:
        first = next(iter(predictions.values()))[0]
        weighted = np.zeros(len(first), dtype=float)
        total_weight = 0.0
        for requested in requested_targets:
            if requested.code not in predictions:
                continue
            expected, lower_bound, upper_bound = predictions[requested.code]
            payload = bundle.targets[requested.code]
            observed_lower = float(payload["observed_lower"])
            observed_upper = float(payload["observed_upper"])
            span = max(observed_upper - observed_lower, 1e-12)
            if requested.mode in {TargetMode.MINIMIZE, TargetMode.AT_MOST}:
                values = upper_bound if pessimistic else expected
                component = 1.0 - (values - observed_lower) / span
            elif requested.mode in {TargetMode.MAXIMIZE, TargetMode.AT_LEAST}:
                values = lower_bound if pessimistic else expected
                component = (values - observed_lower) / span
            elif requested.mode == TargetMode.MATCH:
                values = expected
                component = 1.0 - np.abs(values - float(requested.value)) / span
            else:
                values = expected
                midpoint = (float(requested.minimum) + float(requested.maximum)) / 2.0
                half_width = max((float(requested.maximum) - float(requested.minimum)) / 2.0, 1e-12)
                component = 1.0 - np.abs(values - midpoint) / max(span, half_width)
            weighted += np.clip(component, 0.0, 1.0) * requested.weight
            total_weight += requested.weight
        return weighted / max(total_weight, 1e-12)

    @staticmethod
    def _mandatory_mask(
        requested_targets: list[RequestedTarget],
        predictions: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray]],
    ) -> np.ndarray:
        first = next(iter(predictions.values()))[0]
        mask = np.ones(len(first), dtype=bool)
        for requested in requested_targets:
            if not requested.mandatory or requested.code not in predictions:
                continue
            _, lower, upper = predictions[requested.code]
            if requested.mode == TargetMode.AT_LEAST:
                mask &= lower >= float(requested.value)
            elif requested.mode == TargetMode.AT_MOST:
                mask &= upper <= float(requested.value)
            elif requested.mode == TargetMode.RANGE:
                mask &= (lower >= float(requested.minimum)) & (upper <= float(requested.maximum))
            elif requested.mode == TargetMode.MATCH:
                tolerance = float(requested.tolerance or 0.0)
                mask &= (lower >= float(requested.value) - tolerance) & (
                    upper <= float(requested.value) + tolerance
                )
        return mask

    @staticmethod
    def _uncertainty_score(
        predictions: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray]]
    ) -> np.ndarray:
        widths = np.column_stack([upper - lower for _, lower, upper in predictions.values()])
        normalized = np.zeros_like(widths)
        for column in range(widths.shape[1]):
            low = float(np.min(widths[:, column]))
            high = float(np.max(widths[:, column]))
            if high > low:
                normalized[:, column] = (widths[:, column] - low) / (high - low)
        return normalized.mean(axis=1)

    @staticmethod
    def _prediction_models(
        index: int,
        profile: TaskProfile,
        bundle: ModelBundle,
        predictions: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray]],
        domain_by_target: dict[str, list[ApplicabilityDomainEvidence]],
    ) -> list[Prediction]:
        spec_by_code = {item.code: item for item in profile.targets}
        output = []
        for code, (expected, lower, upper) in predictions.items():
            payload = bundle.targets[code]
            output.append(
                Prediction(
                    target_code=code,
                    expected=float(expected[index]),
                    lower=float(lower[index]),
                    upper=float(upper[index]),
                    unit=spec_by_code[code].unit if code in spec_by_code else None,
                    scorer_type=payload["scorer_type"],
                    applicability_domain=domain_by_target[code][index],
                )
            )
        return output

    @staticmethod
    def _match_rows(features: pd.DataFrame, rows: pd.DataFrame) -> list[int]:
        indices: list[int] = []
        for _, row in rows.iterrows():
            mask = pd.Series(True, index=features.index)
            for column in rows.columns:
                if pd.api.types.is_numeric_dtype(features[column]):
                    mask &= np.isclose(features[column].astype(float), float(row[column]), atol=1e-8)
                else:
                    mask &= features[column].astype(str) == str(row[column])
            matched = features.index[mask]
            if len(matched):
                index = int(matched[0])
                if index not in indices:
                    indices.append(index)
                continue
            # BayBE may recommend an unmeasured combination from the discrete
            # parameter representation that is not an exact row in the explicit
            # safe pool. Project it to the nearest candidate with identical
            # categorical context; never return a point outside ``features``.
            categorical_mask = pd.Series(True, index=features.index)
            numeric_columns = []
            for column in rows.columns:
                if pd.api.types.is_numeric_dtype(features[column]):
                    numeric_columns.append(column)
                else:
                    categorical_mask &= features[column].astype(str) == str(row[column])
            eligible = features.index[categorical_mask]
            if not len(eligible):
                continue
            distance = np.zeros(len(eligible), dtype=float)
            for column in numeric_columns:
                values = features.loc[eligible, column].astype(float).to_numpy()
                span = max(float(features[column].max() - features[column].min()), 1e-12)
                distance += np.abs(values - float(row[column])) / span
            nearest = int(eligible[int(np.argmin(distance))])
            if nearest not in indices:
                indices.append(nearest)
        return indices

    @staticmethod
    def _find_formula(
        formulas: list[dict[str, float]], baseline: dict[str, float]
    ) -> int | None:
        for index, formula in enumerate(formulas):
            if set(formula) == set(baseline) and all(
                abs(float(formula[code]) - float(baseline[code])) <= 1e-6 for code in formula
            ):
                return index
        return None

    @staticmethod
    def _l1(left: dict[str, float], right: dict[str, float]) -> float:
        return float(sum(abs(float(left.get(code, 0.0)) - float(right.get(code, 0.0))) for code in set(left) | set(right)))

    def _first_diverse(
        self,
        order: list[int],
        selected: list[int],
        formulas: list[dict[str, float]],
        minimum_distance: float,
    ) -> int | None:
        seen = set()
        for index in order:
            if index is None or index in seen or index in selected:
                continue
            seen.add(index)
            if all(self._l1(formulas[index], formulas[prior]) >= minimum_distance for prior in selected):
                return index
        return None

    @staticmethod
    def _reasons(
        strategy: str,
        mode: RecommendationMode,
        unsupported: list[str],
        control_source: str | None = None,
    ) -> list[str]:
        if mode == RecommendationMode.FORMULA_PREDICTION:
            result = [
                "ranked by expected target satisfaction",
                "requires authoritative Java rule validation",
            ]
            if unsupported:
                result.append("unsupported targets require CASE_STAT_RULE validation")
            return result
        control_reason = (
            "exact baseline retest"
            if control_source == "EXACT_BASELINE"
            else "nearest feasible in-domain baseline replacement"
        )
        reason = {
            "CONTROL": control_reason,
            "CONSERVATIVE": "best pessimistic interval desirability",
            "BALANCED": "BayBE shortlist with champion-model desirability reranking",
            "EXPLORATORY": "BayBE shortlist favoring uncertainty under mandatory bounds",
        }[strategy]
        result = [reason, "requires authoritative Java rule validation"]
        if unsupported:
            result.append("unsupported targets require CASE_STAT_RULE validation")
        return result
