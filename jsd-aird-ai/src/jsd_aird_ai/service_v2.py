from __future__ import annotations

import hashlib
import io
import json
import pickle
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

import httpx
import joblib
import numpy as np
import pandas as pd
import pyarrow.parquet as pq
from scipy.stats import spearmanr
from sklearn.base import BaseEstimator, ClassifierMixin, clone
from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor
from sklearn.feature_extraction import DictVectorizer
from sklearn.gaussian_process import GaussianProcessRegressor
from sklearn.gaussian_process.kernels import Matern, WhiteKernel
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score, mean_absolute_error, mean_squared_error, r2_score
from baybe.campaign import Campaign
from baybe.parameters import NumericalDiscreteParameter
from baybe.recommenders import BotorchRecommender
from baybe.searchspace import SearchSpace
from baybe.settings import Settings as BaybeSettings
from baybe.surrogates import GaussianProcessSurrogate
from baybe.targets import NumericalTarget

from jsd_aird_ai.contracts_v2 import (
    ApplicabilityV2,
    ArtifactRefV2,
    BinaryResultV2,
    CategoricalResultV2,
    ContinuousResultV2,
    DomainStatusV2,
    FoldAssignmentV2,
    OrdinalResultV2,
    ScoreRequestV2,
    ScoreResponseV2,
    TargetBlockedV2,
    TargetFailedV2,
    TargetSuccessV2,
    TrainRequestV2,
    TrainResponseV2,
    ValidateRequestV2,
    ValidateResponseV2,
    ValidationFoldsRequestV2,
    ValidationFoldsResponseV2,
    ValidationIssueV2,
    ValueTypeV2,
    FormulaV2,
    FormulaComponentV2,
    RecommendRequestV2,
    RecommendResponseV2,
    RecommendedCandidateV2,
    TargetOutcomeV2,
)
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.settings import Settings


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@dataclass
class _Dataset:
    rows: pd.DataFrame
    source: pd.DataFrame


class OrdinalCumulativeLogit(ClassifierMixin, BaseEstimator):
    """Proportional-threshold classifier that preserves the configured class order."""

    def __init__(self, ordered_classes: tuple[str, ...], random_state: int, max_iter: int = 2000) -> None:
        self.ordered_classes = ordered_classes
        self.random_state = random_state
        self.max_iter = max_iter

    def fit(self, x: np.ndarray, y: np.ndarray) -> "OrdinalCumulativeLogit":
        self.classes_ = np.asarray(self.ordered_classes)
        order = {label: index for index, label in enumerate(self.ordered_classes)}
        encoded = np.asarray([order[str(value)] for value in y], dtype=int)
        self.threshold_models_: list[LogisticRegression | float] = []
        for threshold in range(len(self.ordered_classes) - 1):
            binary = (encoded > threshold).astype(int)
            if np.unique(binary).size == 1:
                self.threshold_models_.append(float(binary[0]))
            else:
                self.threshold_models_.append(LogisticRegression(
                    max_iter=self.max_iter, random_state=self.random_state,
                ).fit(x, binary))
        return self

    def predict_proba(self, x: np.ndarray) -> np.ndarray:
        greater = []
        for model in self.threshold_models_:
            if isinstance(model, float):
                greater.append(np.full(len(x), model, dtype=float))
            else:
                positive = list(model.classes_).index(1)
                greater.append(model.predict_proba(x)[:, positive])
        p_greater = np.column_stack(greater)
        p_greater = np.minimum.accumulate(p_greater, axis=1)
        probabilities = np.empty((len(x), len(self.ordered_classes)), dtype=float)
        probabilities[:, 0] = 1.0 - p_greater[:, 0]
        for index in range(1, len(self.ordered_classes) - 1):
            probabilities[:, index] = p_greater[:, index - 1] - p_greater[:, index]
        probabilities[:, -1] = p_greater[:, -1]
        probabilities = np.clip(probabilities, 1e-12, 1.0)
        return probabilities / probabilities.sum(axis=1, keepdims=True)

    def predict(self, x: np.ndarray) -> np.ndarray:
        return self.classes_[np.argmax(self.predict_proba(x), axis=1)]


class FormulaModelV2Service:
    """Runtime for the independent, versioned formula-model.v2 training contract."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    def validate_snapshot(self, request: ValidateRequestV2) -> ValidateResponseV2:
        issues: list[ValidationIssueV2] = []
        try:
            dataset = self._dataset(request)
            self._validate_rows(request, dataset, issues)
        except FormulaModelError as exc:
            issues.append(ValidationIssueV2(code=str(exc.code), message=exc.message))
        return ValidateResponseV2(
            request_id=request.request_id,
            status="REJECTED" if issues else "VALID",
            snapshot_hash=request.snapshot.sha256,
            issues=issues,
        )

    def validation_folds(self, request: ValidationFoldsRequestV2) -> ValidationFoldsResponseV2:
        dataset = self._dataset(request)
        issues: list[ValidationIssueV2] = []
        self._validate_rows(request, dataset, issues)
        if issues:
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "snapshot rows are invalid",
                                    details={"issues": [item.model_dump(by_alias=True) for item in issues]})
        source = dataset.source.set_index("row_id", drop=False)
        assignments: list[FoldAssignmentV2] = []
        unique_groups: dict[str, int] = {}
        for row_id in dataset.rows["row_id"].astype(str):
            if row_id not in source.index:
                raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "source-map row is missing",
                                        details={"rowId": row_id})
            source_row = source.loc[row_id]
            parts = [str(source_row.get(field, "")) for field in request.group_fields]
            group_key = "|".join(parts)
            if group_key not in unique_groups:
                digest = hashlib.sha256(f"{request.seed}:{group_key}".encode()).digest()
                unique_groups[group_key] = int.from_bytes(digest[:8], "big")
            fold = unique_groups[group_key] % request.fold_count
            assignments.append(FoldAssignmentV2(row_id=row_id, group_key=group_key, fold=fold))
        if len({item.fold for item in assignments}) < 2:
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA,
                                    "independent groups cannot produce at least two validation folds")
        canonical = json.dumps([item.model_dump(by_alias=True) for item in assignments],
                               ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()
        return ValidationFoldsResponseV2(
            request_id=request.request_id,
            snapshot_hash=request.snapshot.sha256,
            assignments_hash=_sha(canonical),
            assignments=assignments,
        )

    def train(self, request: TrainRequestV2) -> TrainResponseV2:
        dataset = self._dataset(request)
        issues: list[ValidationIssueV2] = []
        self._validate_rows(request, dataset, issues)
        if issues:
            return TrainResponseV2(request_id=request.request_id, job_id=request.job_id,
                                   status="REJECTED", reasons=[item.code for item in issues])
        fold_bytes = self._read(request.validation_folds.url, request.validation_folds.sha256)
        try:
            fold_payload = json.loads(fold_bytes)
            fold_items = fold_payload.get("assignments", fold_payload)
            fold_by_row = {str(item["rowId"]): int(item["fold"]) for item in fold_items}
        except Exception as exc:
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "validation-folds artifact is invalid") from exc

        # Keep the business code and display name together.  Snapshot facts retain
        # the immutable source field label (for example ``涂料固含``), while a
        # frozen input scheme uses its stable field code (``COATING_SOLIDS``).
        # The feature builder must bridge those two identities instead of
        # silently producing an empty feature matrix.
        allowed_input_codes = {
            field.code: (field.name or field.code)
            for field in request.context.input_scheme.fields
        }
        include_formula = self._scheme_uses_formula(request.context.input_scheme)
        feature_records = [self._features(row, allowed_input_codes, include_formula) for _, row in dataset.rows.iterrows()]
        vectorizer = DictVectorizer(sparse=False)
        features = vectorizer.fit_transform(feature_records)
        row_ids = dataset.rows["row_id"].astype(str).tolist()
        folds = np.array([fold_by_row.get(row_id, -1) for row_id in row_ids], dtype=int)
        if np.any(folds < 0) or len(np.unique(folds)) < 2:
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "validation-folds do not cover the snapshot")

        requires_formula = self._scheme_uses_formula(request.context.input_scheme)
        target_type = ValueTypeV2(request.context.target_definition.value_type)
        target = self._target(dataset.rows, target_type)
        algorithms = request.context.training_policy.config.get("candidateAlgorithms") or []
        if not algorithms:
            return TrainResponseV2(request_id=request.request_id, job_id=request.job_id,
                                   status="REJECTED", reasons=["CANDIDATE_ALGORITHMS_REQUIRED"])
        candidates = self._candidates(target_type, algorithms, request.seed,
                                      request.context.target_definition.classes)
        if not candidates:
            return TrainResponseV2(request_id=request.request_id, job_id=request.job_id,
                                   status="REJECTED", reasons=["UNSUPPORTED_TARGET_TYPE_OR_ALGORITHM"])

        results: list[tuple[float, str, Any, dict[str, Any]]] = []
        for name, estimator in candidates:
            try:
                metrics, loss = self._cross_validate(estimator, features, target, folds, target_type,
                                                     request.context.target_definition.classes)
                fitted = clone(estimator).fit(features, target)
                results.append((loss, name, fitted, metrics))
            except Exception:
                # A single optional learner may not support the dataset. Other configured
                # candidates still receive the identical folds and comparison set.
                continue
        if not results:
            return TrainResponseV2(request_id=request.request_id, job_id=request.job_id,
                                   status="REJECTED", reasons=["NO_CANDIDATE_COMPLETED"])
        results.sort(key=lambda item: (item[0], item[1]))
        loss, name, model, metrics = results[0]
        metrics["selectedAlgorithm"] = name
        metrics["candidateCount"] = len(results)
        metrics["comparison"] = [dict(algorithm=item[1], loss=item[0], **item[3]) for item in results]
        metrics["baselines"] = {
            "simple": self._baseline_metrics(target, folds, target_type,
                                             request.context.target_definition.classes),
            "historicalCase": {"status": "UNAVAILABLE", "reason": "HISTORICAL_CASE_BASELINE_NOT_CONFIGURED"},
        }
        comparison_rows = sorted((row_id, int(fold), str(value))
                                 for row_id, fold, value in zip(row_ids, folds.tolist(), target.tolist()))
        metrics["comparisonSetHash"] = _sha(json.dumps(comparison_rows, ensure_ascii=False,
                                                       separators=(",", ":")).encode())
        domain = self._domain(features, vectorizer.get_feature_names_out().tolist())
        bundle = self._bundle(request, vectorizer, model, metrics, domain)
        self._write(request.output, bundle)
        return TrainResponseV2(
            request_id=request.request_id,
            job_id=request.job_id,
            status="CANDIDATE",
            model_bundle=ArtifactRefV2(url=request.output, sha256=_sha(bundle)),
            metrics=metrics,
            applicability_domain=domain,
            reasons=[],
        )

    def score(self, request: ScoreRequestV2) -> ScoreResponseV2:
        """Score each pinned model independently without hiding target-local failures."""
        outcomes: list[TargetSuccessV2 | TargetBlockedV2 | TargetFailedV2] = []
        for binding in request.model_bindings:
            target_id = binding.target.id
            model_id = binding.model_version_id
            try:
                missing = [field.code for field in binding.input_scheme.fields
                           if field.required and str(field.value_type) != "COMPOSITION"
                           and request.inputs.get(field.code) is None]
                if missing:
                    outcomes.append(TargetBlockedV2(target_id=target_id, model_version_id=model_id,
                        code="MISSING_REQUIRED_X", message="缺少模型要求的输入字段",
                        detail={"missingFields": missing}))
                    continue

                bundle_bytes = self._read(binding.model_bundle.url, binding.model_bundle.sha256)
                bundle = self._load_bundle(bundle_bytes)
                self._verify_binding(binding, bundle)
                vectorizer = bundle["vectorizer"]
                feature_names = vectorizer.get_feature_names_out().tolist()
                needs_formula = self._scheme_uses_formula(binding.input_scheme) or any(
                    name.startswith("material:") or name.startswith("formula:") for name in feature_names
                )
                if needs_formula and request.formula is None:
                    outcomes.append(TargetBlockedV2(target_id=target_id, model_version_id=model_id,
                        code="FORMULA_INCOMPLETE", message="该模型需要完整配方", detail={}))
                    continue
                if needs_formula:
                    material_ids = {item.material_id for item in binding.material_dictionary.materials}
                    unknown = sorted({item.material_id for item in request.formula.components
                                      if item.material_id not in material_ids})
                    if unknown:
                        outcomes.append(TargetBlockedV2(target_id=target_id, model_version_id=model_id,
                            code="MATERIAL_NOT_IN_MODEL", message="材料已识别，但当前模型未覆盖",
                            detail={"materialIds": unknown}))
                        continue
                filtered_inputs = self._filtered_inputs(request.inputs, binding.input_scheme)
                feature_record = self._feature_record(
                    request.formula.model_dump(by_alias=True) if needs_formula and request.formula else {},
                    filtered_inputs, include_formula=needs_formula,
                )
                unknown_categories = sorted(key for key in feature_record if "=" in key and key not in feature_names)
                if unknown_categories:
                    outcomes.append(TargetBlockedV2(target_id=target_id, model_version_id=model_id,
                        code="UNKNOWN_CATEGORY", message="输入包含模型未见过的分类值",
                        detail={"encodedValues": unknown_categories}))
                    continue
                vector = vectorizer.transform([feature_record])
                relevant_features = self._domain_features(binding, feature_names)
                applicability = self._applicability(vector[0], feature_names,
                                                     binding.applicability_domain,
                                                     bundle["applicabilityDomain"], relevant_features)
                if applicability.status == DomainStatusV2.OUT_OF_DOMAIN:
                    outcomes.append(TargetBlockedV2(target_id=target_id, model_version_id=model_id,
                        code="OUT_OF_DOMAIN", message="输入超出模型适用域",
                        detail={"distance": applicability.distance, "reasons": applicability.reasons}))
                    continue
                result = self._typed_result(binding, bundle, vector)
                outcomes.append(TargetSuccessV2(target_id=target_id, model_version_id=model_id,
                    result=result, applicability=applicability, warnings=[]))
            except FormulaModelError as exc:
                outcomes.append(TargetFailedV2(target_id=target_id, model_version_id=model_id,
                    code=str(exc.code), message=exc.message, detail=exc.details))
            except Exception as exc:
                outcomes.append(TargetFailedV2(target_id=target_id, model_version_id=model_id,
                    code="MODEL_LOAD_FAILED", message="模型制品加载或评分失败",
                    detail={"exception": type(exc).__name__, "reason": str(exc)}))

        succeeded = sum(item.status == "SUCCEEDED" for item in outcomes)
        failed = sum(item.status == "FAILED" for item in outcomes)
        if failed and not succeeded:
            return ScoreResponseV2(request_id=request.request_id, run_id=request.run_id,
                                   execution_status="FAILED", outcome_status=None, results=outcomes)
        outcome = "SUCCEEDED" if succeeded == len(outcomes) else "PARTIAL" if succeeded else "BLOCKED"
        return ScoreResponseV2(request_id=request.request_id, run_id=request.run_id,
                               execution_status="SUCCEEDED", outcome_status=outcome, results=outcomes)

    def recommend(self, request: RecommendRequestV2) -> RecommendResponseV2:
        """Deterministic, model-only formula search for the V2 boundary.

        BayBE is intentionally kept behind this service boundary.  The
        controlled pool is deterministic and honours the same frozen model
        bindings and feature builder as scoring, which makes it safe for the
        local acceptance environment when an optimizer is unavailable.
        """
        if request.mode == "EXPERIMENT_OPTIMIZATION":
            return self._recommend_optimization(request)
        total = request.baseline_formula.recorded_total if request.baseline_formula and request.baseline_formula.recorded_total is not None else 100.0
        baseline = request.baseline_formula
        if baseline is None:
            materials: list[str] = []
            for binding in request.model_bindings:
                for material in binding.material_dictionary.materials:
                    if material.material_id not in materials:
                        materials.append(material.material_id)
            materials = materials[:3] or ["UNKNOWN"]
            ratio = total / len(materials)
            baseline = FormulaV2(basis="MASS_PERCENT", composition_complete=True,
                                 components=[FormulaComponentV2(material_id=m, ratio=ratio, unit="PERCENT", amount_known=True) for m in materials],
                                 recorded_total=total)
        engine, strategy = self._search_engine(request)
        # Build feasible points without evaluating them.  For continuous
        # objectives BayBE must own the ask -> score -> tell loop: only points
        # it proposes are sent to the frozen model bundles.  This is a bounded
        # discrete representation of the feasible formula space, rather than
        # scoring the entire pool and applying BayBE as a post-hoc sorter.
        pool_size = max(request.candidate_count * 8, 32) if engine == "BAYBE" else request.candidate_count
        formulas = self._formula_pool(baseline, total, pool_size, request.seed, request.search_space)
        accepted: list[RecommendedCandidateV2] = []
        rejected = {"TARGET_NOT_MET": 0, "OUT_OF_DOMAIN": 0, "INPUT_MISSING": 0,
                    "MATERIAL_MISMATCH": 0, "RULE_FAILED": 0, "DUPLICATE": 0}
        had_execution_failure = False
        first_failure_code: str | None = None
        first_failure_message: str | None = None
        proposed_count = 0
        scored_count = 0
        if engine == "BAYBE" and formulas:
            max_evaluations = min(len(formulas), max(request.candidate_count * 8, 16))
            candidate_values = [float(index) for index in range(max_evaluations)]
            searchspace = SearchSpace.from_dataframe(
                pd.DataFrame({"candidateIndex": candidate_values}),
                [NumericalDiscreteParameter(name="candidateIndex", values=candidate_values)],
            )
            # The goal score is not constrained to a scientific unit range.
            # A monotone bounded utility gives the GP a stable target while
            # retaining the original score on the candidate record.
            objective = NumericalTarget.normalized_ramp("utility", (-1.0, 1.0)).to_objective()
            try:
                campaign = Campaign(searchspace=searchspace, objective=objective,
                                    recommender=BotorchRecommender(
                                        surrogate_model=GaussianProcessSurrogate()))
                measured: list[dict[str, float]] = []
                scored_by_index: set[int] = set()

                def evaluate(index: int) -> float:
                    nonlocal had_execution_failure, first_failure_code, first_failure_message, scored_count
                    if index in scored_by_index:
                        return 0.0
                    scored_by_index.add(index)
                    scored_count += 1
                    formula = formulas[index]
                    candidate_inputs = self._candidate_inputs(request, index, len(formulas))
                    scored = self.score(ScoreRequestV2(
                        request_id=f"{request.request_id}:score:{index}", seed=request.seed,
                        run_id=request.run_id, formula=formula, inputs=candidate_inputs,
                        model_bindings=request.model_bindings))
                    if scored.execution_status == "FAILED":
                        had_execution_failure = True
                        failure = next((item for item in scored.results if getattr(item, "status", None) == "FAILED"), None)
                        if failure is not None and first_failure_code is None:
                            first_failure_code = getattr(failure, "code", None)
                            first_failure_message = getattr(failure, "message", None)
                        return -1.0
                    successes = [item for item in scored.results if item.status == "SUCCEEDED"]
                    if len(successes) != len(request.model_bindings):
                        self._count_rejections(rejected, scored.results)
                        return -1.0
                    score = self._goal_score(request.goals, successes)
                    if self._goals_satisfied(request.goals, successes):
                        accepted.append(RecommendedCandidateV2(
                            candidate_id=f"{request.run_id}-candidate-{index+1}", formula=formula,
                            results=successes, score=score, inputs=candidate_inputs,
                            rule_check={"javaFinalCheckRequired": True, "formulaTotal": sum((c.ratio or 0) for c in formula.components)},
                            strategy_evidence={"searchEngine": engine, "searchStrategy": strategy,
                                               "seed": request.seed, "poolSize": len(formulas),
                                               "proposedBeforeScore": proposed_count + 1},
                        ))
                    else:
                        rejected["TARGET_NOT_MET"] += 1
                    return float(score / (1.0 + abs(score)))

                # A Bayesian recommender requires measured data.  Seed it with
                # two deterministic feasible points, then continue with
                # recommendations generated from the posterior.
                seed_indexes = [0]
                if max_evaluations > 1:
                    seed_indexes.append(max_evaluations // 2)
                with BaybeSettings(random_seed=int(request.seed) & 0xFFFFFFFF, cache_directory=None):
                    for index in seed_indexes:
                        proposed_count += 1
                        utility = evaluate(index)
                        measured.append({"candidateIndex": float(index), "utility": utility})
                    campaign.add_measurements(pd.DataFrame(measured))
                    while len(accepted) < request.candidate_count and scored_count < max_evaluations:
                        batch_size = min(2, max_evaluations - scored_count,
                                         request.candidate_count - len(accepted) or 1)
                        recommendation = campaign.recommend(batch_size=max(1, batch_size))
                        indexes = [int(round(float(value))) for value in recommendation["candidateIndex"].tolist()]
                        indexes = [index for index in indexes if 0 <= index < max_evaluations and index not in scored_by_index]
                        if not indexes:
                            break
                        rows: list[dict[str, float]] = []
                        for index in indexes:
                            proposed_count += 1
                            rows.append({"candidateIndex": float(index), "utility": evaluate(index)})
                        campaign.add_measurements(pd.DataFrame(rows))
            except Exception as exc:
                raise FormulaModelError(ErrorCode.INTERNAL_COMPUTE_ERROR,
                                        f"BayBE自适应候选提议失败: {type(exc).__name__}: {exc}")
        else:
            for index, formula in enumerate(formulas):
                proposed_count += 1
                scored_count += 1
                candidate_inputs = self._candidate_inputs(request, index, len(formulas))
                scored = self.score(ScoreRequestV2(request_id=f"{request.request_id}:score:{index}", seed=request.seed,
                                                    run_id=request.run_id, formula=formula, inputs=candidate_inputs,
                                                    model_bindings=request.model_bindings))
                if scored.execution_status == "FAILED":
                    had_execution_failure = True
                    failure = next((item for item in scored.results if getattr(item, "status", None) == "FAILED"), None)
                    if failure is not None and first_failure_code is None:
                        first_failure_code = getattr(failure, "code", None)
                        first_failure_message = getattr(failure, "message", None)
                    continue
                successes = [item for item in scored.results if item.status == "SUCCEEDED"]
                if len(successes) != len(request.model_bindings):
                    self._count_rejections(rejected, scored.results)
                    continue
                if not self._goals_satisfied(request.goals, successes):
                    rejected["TARGET_NOT_MET"] += 1
                    continue
                accepted.append(RecommendedCandidateV2(
                    candidate_id=f"{request.run_id}-candidate-{index+1}", formula=formula,
                    results=successes, score=self._goal_score(request.goals, successes), inputs=candidate_inputs,
                    rule_check={"javaFinalCheckRequired": True, "formulaTotal": sum((c.ratio or 0) for c in formula.components)},
                    strategy_evidence={"searchEngine": engine, "searchStrategy": strategy,
                                       "seed": request.seed, "poolSize": len(formulas)},
                ))
        accepted = accepted[:request.candidate_count]
        if had_execution_failure and not accepted:
            return RecommendResponseV2(request_id=request.request_id, run_id=request.run_id,
                                       status="FAILED", execution_status="FAILED", outcome_status=None,
                                       candidates=[], blocked_targets=[], warnings=[],
                                       code=("MODEL_ARTIFACT_INVALID" if first_failure_code in {"INVALID_SNAPSHOT", "HASH_MISMATCH", "UNSUPPORTED_CONTRACT", "MODEL_LOAD_FAILED"} else "COMPUTE_UNAVAILABLE"),
                                       message=first_failure_message or "模型制品或计算服务不可用",
                                       search_engine=engine, search_strategy=strategy,
                                       rejection_summary=rejected,
                                       search_evidence={"poolSize": len(formulas), "proposedCount": proposed_count,
                                                        "scoredCount": scored_count, "acceptedCount": len(accepted),
                                                        "algorithm": "BayBE ask-score-tell adaptive campaign" if engine == "BAYBE" else "stable deterministic pool"})
        status = "READY" if len(accepted) == request.candidate_count else "PARTIAL" if accepted else "NO_FEASIBLE_CANDIDATE"
        outcome = "SUCCEEDED" if len(accepted) == request.candidate_count else "PARTIAL" if accepted else "BLOCKED"
        hints=self._rejection_hints(rejected)
        return RecommendResponseV2(request_id=request.request_id, run_id=request.run_id, status=status,
                                   execution_status="SUCCEEDED", outcome_status=outcome,
                                   candidates=accepted, blocked_targets=[], warnings=[],
                                   shortfall_reason=None if status == "READY" else self._shortfall_reason(rejected),
                                   shortfall_reason_code=None if status == "READY" else self._shortfall_code(rejected),
                                   rejection_summary=rejected, action_hints=hints,
                                   search_engine=engine, search_strategy=strategy,
                                   search_evidence={"poolSize": len(formulas),
                                                    "proposedCount": proposed_count,
                                                    "scoredCount": scored_count,
                                                    "acceptedCount": len(accepted),
                                                    "algorithm": "BayBE ask-score-tell adaptive campaign" if engine == "BAYBE" else "stable deterministic pool"})

    def _search_engine(self, request: RecommendRequestV2) -> tuple[str, str]:
        """Select the search implementation from the frozen Y semantics."""
        has_continuous = any(binding.target.value_type == ValueTypeV2.CONTINUOUS
                             for binding in request.model_bindings)
        return ("BAYBE", "CONTINUOUS_BAYBE") if has_continuous else (
            "DETERMINISTIC_CANDIDATE_POOL", "DISCRETE_DETERMINISTIC_POOL")

    def _recommend_optimization(self, request: RecommendRequestV2) -> RecommendResponseV2:
        """Choose four distinct experiment strategies from one frozen baseline.

        CONTROL is always the unmodified baseline.  The other strategies use
        the explicitly confirmed target total and remain within the model
        domain; EXPLORATORY may use NEAR_BOUNDARY but never OUT_OF_DOMAIN.
        """
        assert request.baseline_formula is not None
        baseline = request.baseline_formula
        baseline_total = float(baseline.recorded_total or sum(float(c.ratio or 0) for c in baseline.components))
        target_total = float(request.constraints.get("targetTotal", baseline_total))
        strategies = list(request.constraints.get("strategies") or
                          ["CONTROL", "CONSERVATIVE", "BALANCED", "EXPLORATORY"])
        engine, search_strategy = self._search_engine(request)
        pool = [baseline]
        # Keep the feasible representation larger than the evaluation budget.
        # Continuous optimization evaluates only BayBE proposals; ordinal and
        # categorical optimization keeps the deterministic pool semantics.
        pool.extend(self._formula_pool(
            baseline, target_total, max(64, request.candidate_count * 16) if engine == "BAYBE"
            else max(32, request.candidate_count * 8), request.seed, request.constraints,
        ))
        scored_pool: list[tuple[FormulaV2, dict[str, Any], list[TargetSuccessV2], float, float, str]] = []
        had_failure = False
        failure_code: str | None = None
        failure_message: str | None = None
        baseline_vector = {c.material_id: float(c.ratio or 0) for c in baseline.components}
        proposed_count = 0
        scored_count = 0
        evaluated_indexes: set[int] = set()

        def score_index(index: int) -> float:
            nonlocal had_failure, failure_code, failure_message, proposed_count, scored_count
            if index in evaluated_indexes:
                return 0.0
            evaluated_indexes.add(index)
            proposed_count += 1
            scored_count += 1
            formula = pool[index]
            candidate_inputs = request.fixed_inputs if index == 0 else self._input_candidate(
                request.fixed_inputs, request.constraints, index - 1, max(len(pool) - 1, 1))
            scored = self.score(ScoreRequestV2(
                request_id=f"{request.request_id}:optimization:{index}", seed=request.seed,
                run_id=request.run_id, formula=formula, inputs=candidate_inputs,
                model_bindings=request.model_bindings,
            ))
            if scored.execution_status == "FAILED":
                had_failure = True
                failed = next((item for item in scored.results if getattr(item, "status", None) == "FAILED"), None)
                failure_code = failure_code or getattr(failed, "code", None)
                failure_message = failure_message or getattr(failed, "message", None)
                return -1.0
            successes = [item for item in scored.results if item.status == "SUCCEEDED"]
            if len(successes) != len(request.model_bindings):
                return -1.0
            mandatory_ok = self._goals_satisfied(request.goals, successes)
            score = self._goal_score(request.goals, successes)
            current = {c.material_id: float(c.ratio or 0) for c in formula.components}
            keys = set(baseline_vector) | set(current)
            distance = sum(abs(current.get(k, 0.0) - baseline_vector.get(k, 0.0)) for k in keys) / max(target_total, 1e-9)
            distance += self._input_distance(request.fixed_inputs, candidate_inputs, request.constraints)
            domain = "NEAR_BOUNDARY" if any(s.applicability.status == "NEAR_BOUNDARY" for s in successes) else "IN_DOMAIN"
            scored_pool.append((formula, candidate_inputs, successes, score, distance,
                                domain if mandatory_ok else "GOAL_MISS"))
            # A bounded monotone utility lets BayBE compare any result type
            # without encoding scientific thresholds in the optimizer.
            return float(score / (1.0 + abs(score)))

        if engine == "BAYBE" and len(pool) > 1:
            # The baseline is always evaluated for the CONTROL strategy.  All
            # other points enter the model only after BayBE proposes them.
            score_index(0)
            max_evaluations = min(len(pool) - 1, max(request.candidate_count * 8, 16))
            values = [float(index) for index in range(1, max_evaluations + 1)]
            try:
                searchspace = SearchSpace.from_dataframe(
                    pd.DataFrame({"candidateIndex": values}),
                    [NumericalDiscreteParameter(name="candidateIndex", values=values)],
                )
                objective = NumericalTarget.normalized_ramp("utility", (-1.0, 1.0)).to_objective()
                campaign = Campaign(searchspace=searchspace, objective=objective,
                                    recommender=BotorchRecommender(
                                        surrogate_model=GaussianProcessSurrogate()))
                seed_indexes = [1]
                if max_evaluations > 1:
                    seed_indexes.append(1 + max_evaluations // 2)
                measured: list[dict[str, float]] = []
                with BaybeSettings(random_seed=int(request.seed) & 0xFFFFFFFF, cache_directory=None):
                    for index in seed_indexes:
                        measured.append({"candidateIndex": float(index), "utility": score_index(index)})
                    campaign.add_measurements(pd.DataFrame(measured))
                    while scored_count < max_evaluations + 1:
                        batch_size = min(2, max_evaluations + 1 - scored_count)
                        recommendation = campaign.recommend(batch_size=max(1, batch_size))
                        indexes = [int(round(float(value))) for value in recommendation["candidateIndex"].tolist()]
                        indexes = [index for index in indexes if 1 <= index <= max_evaluations]
                        indexes = [index for index in indexes if index not in evaluated_indexes]
                        if not indexes:
                            break
                        rows = [{"candidateIndex": float(index), "utility": score_index(index)} for index in indexes]
                        campaign.add_measurements(pd.DataFrame(rows))
            except Exception as exc:
                raise FormulaModelError(ErrorCode.INTERNAL_COMPUTE_ERROR,
                                        f"BayBE自适应优化候选提议失败: {type(exc).__name__}: {exc}")
        else:
            for index in range(len(pool)):
                score_index(index)
        if had_failure and not scored_pool:
            return RecommendResponseV2(request_id=request.request_id, run_id=request.run_id,
                status="FAILED", execution_status="FAILED", outcome_status=None, candidates=[],
                code="MODEL_ARTIFACT_INVALID" if failure_code in {"INVALID_SNAPSHOT", "HASH_MISMATCH", "UNSUPPORTED_CONTRACT", "MODEL_LOAD_FAILED"} else "COMPUTE_UNAVAILABLE",
                message=failure_message or "模型制品或计算服务不可用",
                search_engine=engine,
                search_strategy=search_strategy,
                search_evidence={"poolSize": len(pool), "proposedCount": proposed_count,
                                 "scoredCount": scored_count,
                                 "algorithm": "BayBE ask-score-tell adaptive campaign" if engine == "BAYBE" else "stable deterministic strategy pool"})

        selected: list[RecommendedCandidateV2] = []
        used: set[str] = set()
        missing_reasons: dict[str, str] = {}
        non_control = [item for item in scored_pool if item[0] != baseline]
        goal_satisfied = [item for item in non_control if item[5] != "GOAL_MISS"]
        in_domain = [item for item in goal_satisfied if item[5] == "IN_DOMAIN"]
        for strategy in strategies:
            choice: tuple[FormulaV2, dict[str, Any], list[TargetSuccessV2], float, float, str] | None = None
            if strategy == "CONTROL":
                choice = next((item for item in scored_pool if item[0] == baseline), None)
            else:
                feasible = [item for item in scored_pool
                            if item[5] != "GOAL_MISS" and item[0] != baseline
                            and self._candidate_fingerprint(item[0], item[1]) not in used]
                if strategy == "CONSERVATIVE":
                    feasible = [item for item in feasible if item[5] == "IN_DOMAIN"]
                    choice = min(feasible, key=lambda item: (item[4], -item[3]), default=None)
                elif strategy == "BALANCED":
                    feasible = [item for item in feasible if item[5] == "IN_DOMAIN"]
                    choice = max(feasible, key=lambda item: (item[3], -item[4]), default=None)
                elif strategy == "EXPLORATORY":
                    choice = max(feasible, key=lambda item: (item[4], item[3]), default=None)
            if choice is None:
                # Conservative and balanced require an in-domain candidate;
                # exploratory may use NEAR_BOUNDARY but still must meet every
                # mandatory goal. Keep these gates visible to the caller.
                if strategy == "CONTROL":
                    missing_reasons[strategy] = "CONTROL_BASELINE_UNAVAILABLE"
                elif not goal_satisfied:
                    missing_reasons[strategy] = "MANDATORY_GOAL_NOT_MET"
                elif strategy in {"CONSERVATIVE", "BALANCED"} and not in_domain:
                    missing_reasons[strategy] = "NO_IN_DOMAIN_CANDIDATE"
                else:
                    missing_reasons[strategy] = "NO_DISTINCT_FEASIBLE_CANDIDATE"
                continue
            formula, candidate_inputs, successes, score, distance, domain = choice
            fingerprint = self._candidate_fingerprint(formula, candidate_inputs)
            if fingerprint in used:
                continue
            used.add(fingerprint)
            changes = self._formula_changes(baseline, formula)
            risks = ["接近适用域边界"] if domain == "NEAR_BOUNDARY" else []
            selected.append(RecommendedCandidateV2(
                candidate_id=f"{request.run_id}-{strategy.lower()}", formula=formula,
                results=successes, score=score, strategy=strategy, inputs=candidate_inputs,
                quality={"source": "PUBLISHED_QUALITY_POLICIES"},
                applicability={"status": domain}, baseline_distance=distance,
                change_summary=changes,
                strategy_evidence={"selection": strategy, "seed": request.seed,
                                   "baselineTotal": baseline_total, "targetTotal": float(formula.recorded_total or 0)},
                risk_flags=risks,
                rule_check={"javaFinalCheckRequired": True,
                            "formulaTotal": sum(float(c.ratio or 0) for c in formula.components)},
            ))
        missing = [item for item in strategies if all(candidate.strategy != item for candidate in selected)]
        for strategy in missing:
            missing_reasons.setdefault(strategy, "NO_DISTINCT_FEASIBLE_CANDIDATE")
        status = "READY" if not missing else "PARTIAL" if selected else "NO_FEASIBLE_CANDIDATE"
        outcome = "SUCCEEDED" if not missing else "PARTIAL" if selected else "BLOCKED"
        reason = None if not missing else "不可行策略：" + ",".join(missing)
        return RecommendResponseV2(request_id=request.request_id, run_id=request.run_id, status=status,
            execution_status="SUCCEEDED", outcome_status=outcome, candidates=selected,
            missing_strategies=missing,
            missing_strategy_reasons=missing_reasons,
            shortfall_reason=reason,
            search_engine=engine,
            search_strategy=search_strategy,
            search_evidence={"algorithm": "BayBE ask-score-tell adaptive campaign" if engine == "BAYBE" else "stable deterministic strategy pool",
                             "strategies": strategies, "poolSize": len(pool),
                             "proposedCount": proposed_count, "scoredCount": scored_count})

    def _candidate_fingerprint(self, formula: FormulaV2, inputs: dict[str, Any]) -> str:
        return formula.model_dump_json(by_alias=True) + "|" + json.dumps(inputs, ensure_ascii=False,
                                                                           sort_keys=True)

    def _input_candidate(self, baseline: dict[str, Any], constraints: dict[str, Any], index: int,
                         count: int) -> dict[str, Any]:
        result = dict(baseline)
        variables = constraints.get("variableInputs") or []
        fraction = ((index * 29 + 13) % max(count, 2)) / max(count - 1, 1)
        for item in variables:
            code = str(item.get("code") or "")
            if not code:
                continue
            allowed = item.get("allowedValues") or []
            if allowed:
                result[code] = allowed[index % len(allowed)]
                continue
            if item.get("minimum") is not None and item.get("maximum") is not None:
                minimum, maximum = float(item["minimum"]), float(item["maximum"])
                value = minimum + (maximum - minimum) * fraction
                step = item.get("step")
                if step is not None and float(step) > 0:
                    value = minimum + round((value - minimum) / float(step)) * float(step)
                result[code] = max(minimum, min(maximum, value))
        return result

    def _input_distance(self, baseline: dict[str, Any], candidate: dict[str, Any],
                        constraints: dict[str, Any]) -> float:
        distance = 0.0
        for item in constraints.get("variableInputs") or []:
            code = str(item.get("code") or "")
            if not code or code not in candidate or code not in baseline:
                continue
            if item.get("minimum") is not None and item.get("maximum") is not None:
                span = max(float(item["maximum"]) - float(item["minimum"]), 1e-9)
                distance += abs(float(candidate[code]) - float(baseline[code])) / span
            elif candidate[code] != baseline[code]:
                distance += 1.0
        return distance

    def _formula_changes(self, baseline: FormulaV2, candidate: FormulaV2) -> dict[str, Any]:
        before = {c.material_id: float(c.ratio or 0) for c in baseline.components}
        after = {c.material_id: float(c.ratio or 0) for c in candidate.components}
        return {"materials": [{"materialId": key, "before": before.get(key, 0.0),
                                "after": after.get(key, 0.0),
                                "delta": after.get(key, 0.0) - before.get(key, 0.0)}
                               for key in sorted(set(before) | set(after))
                               if abs(after.get(key, 0.0) - before.get(key, 0.0)) > 1e-9],
                "totalBefore": float(baseline.recorded_total or 0),
                "totalAfter": float(candidate.recorded_total or 0)}

    def _formula_pool(self, baseline: FormulaV2, total: float, count: int, seed: int,
                      constraints: dict[str, Any] | None = None) -> list[FormulaV2]:
        components = list(baseline.components)
        if len(components) == 1:
            return [baseline]
        requested_variables = (constraints or {}).get("variableMaterials") or []
        bounds = {
            str(item.get("materialId")): (float(item.get("minimum", 0)), float(item.get("maximum", total)))
            for item in requested_variables if item.get("materialId")
        }
        baseline_values = {component.material_id: float(component.ratio or 0) for component in components}
        if bounds:
            fixed_total = sum(value for material, value in baseline_values.items() if material not in bounds)
            minimum_total = fixed_total + sum(pair[0] for pair in bounds.values())
            maximum_total = fixed_total + sum(pair[1] for pair in bounds.values())
            if total < minimum_total - 1e-8 or total > maximum_total + 1e-8:
                return []
        # deterministic perturbations preserve the user supplied total and do
        # not add a hidden complement material or normalize existing values.
        out: list[FormulaV2] = []
        seen: set[str] = set()
        attempts = max(count * 8, 32)
        variable_indexes = [index for index, component in enumerate(components)
                            if not bounds or component.material_id in bounds]
        if len(variable_indexes) < 2:
            valid_total=abs(sum(baseline_values.values())-total)<=1e-6
            valid_bounds=all(low-1e-8<=baseline_values.get(material,0)<=high+1e-8
                             for material,(low,high) in bounds.items())
            return [baseline] if valid_total and valid_bounds else []
        first, second, *remaining = variable_indexes
        for i in range(attempts):
            ratios = [float(c.ratio or 0) for c in components]
            fixed_total = sum(ratios[index] for index in range(len(ratios)) if index not in variable_indexes)
            variable_total = total - fixed_total
            low_first, high_first = bounds.get(components[first].material_id, (0.0, variable_total))
            other_minimum = sum(bounds.get(components[index].material_id, (0.0, variable_total))[0]
                                for index in variable_indexes if index != first)
            other_maximum = sum(bounds.get(components[index].material_id, (0.0, variable_total))[1]
                                for index in variable_indexes if index != first)
            low_first = max(low_first, variable_total - other_maximum)
            high_first = min(high_first, variable_total - other_minimum)
            if high_first < low_first - 1e-8:
                continue
            fraction = ((i * 37 + seed * 11) % 997) / 996.0
            ratios[first] = low_first + (high_first - low_first) * fraction
            remaining_total = variable_total - ratios[first]
            remaining_indexes = [index for index in variable_indexes if index != first]
            # Allocate the remaining total deterministically while respecting
            # every explicitly confirmed material bound.
            for position, index in enumerate(remaining_indexes):
                low, high = bounds.get(components[index].material_id, (0.0, variable_total))
                later_min = sum(bounds.get(components[j].material_id, (0.0, variable_total))[0]
                                for j in remaining_indexes[position + 1:])
                later_max = sum(bounds.get(components[j].material_id, (0.0, variable_total))[1]
                                for j in remaining_indexes[position + 1:])
                assigned = remaining_total if position == len(remaining_indexes) - 1 else max(
                    low, min(high, remaining_total - (later_min + later_max) / 2.0))
                ratios[index] = assigned
                remaining_total -= assigned
            if abs(sum(ratios) - total) > 1e-6:
                continue
            if any(component.material_id in bounds and
                   not bounds[component.material_id][0] - 1e-8 <= ratios[index] <= bounds[component.material_id][1] + 1e-8
                   for index, component in enumerate(components)):
                continue
            formula = FormulaV2(
                basis=baseline.basis, composition_complete=True,
                components=[FormulaComponentV2(material_id=c.material_id, ratio=round(r, 8),
                                               unit=c.unit, amount_known=True)
                            for c, r in zip(components, ratios)],
                recorded_total=total,
            )
            fingerprint = formula.model_dump_json(by_alias=True)
            if fingerprint in seen:
                continue
            seen.add(fingerprint)
            out.append(formula)
            if len(out) >= count:
                break
        return out

    @staticmethod
    def _candidate_inputs(request: RecommendRequestV2, index: int, count: int) -> dict[str, Any]:
        values=dict(request.fixed_inputs)
        variables=request.search_space.get("variableInputs") or {}
        for position,(code,spec) in enumerate(sorted(variables.items())):
            if not isinstance(spec,dict): continue
            minimum=spec.get("minimum");maximum=spec.get("maximum")
            if minimum is None or maximum is None: continue
            fraction=((index*37+position*17+int(request.seed)*11)%997)/996.0 if count>1 else 0.5
            value=float(minimum)+(float(maximum)-float(minimum))*fraction
            step=spec.get("step")
            if step is not None and float(step)>0:
                value=float(minimum)+round((value-float(minimum))/float(step))*float(step)
                value=min(float(maximum),max(float(minimum),value))
            values[code]=value
        return values

    def _goals_satisfied(self, goals: list[Any], successes: list[TargetSuccessV2]) -> bool:
        by_target = {item.target_id: item for item in successes}
        for index, goal in enumerate(goals):
            if not goal.mandatory:
                continue
            # The business request addresses a prediction target, while the
            # frozen V2 binding/result uses the immutable target-version id.
            # Prefer an exact match, then fall back to the frozen binding
            # order; never treat a missing result as a successful goal.
            success = by_target.get(goal.target_id)
            if success is None and index < len(successes):
                success = successes[index]
            if success is None:
                return False
            result = success.result
            value = goal.value
            if isinstance(result, ContinuousResultV2):
                if goal.operator == "AT_LEAST" and result.lower < float(value): return False
                if goal.operator == "AT_MOST" and result.upper > float(value): return False
                if goal.operator == "RANGE":
                    lo, hi = float(value[0]), float(value[1])
                    if result.lower < lo or result.upper > hi: return False
                if goal.operator == "MATCH":
                    if not isinstance(value,dict): return False
                    if abs(result.value-float(value.get("target")))>float(value.get("tolerance",0.0)): return False
            elif isinstance(result, (OrdinalResultV2, BinaryResultV2, CategoricalResultV2)):
                if goal.minimum_probability is None: return False
                label = value.get("label") if isinstance(value, dict) else str(value)
                if isinstance(result, BinaryResultV2):
                    # The binary wire result stores the positive-class
                    # probability only.  Expose the complementary probability
                    # for the other declared class so a MATCH goal can be
                    # evaluated without confusing the predicted label with
                    # the positive class.
                    probabilities = {label: (result.probability if label == result.positive_class
                                              else 1.0 - result.probability)}
                else:
                    probabilities = result.probabilities if hasattr(result, "probabilities") else {result.label: result.probability}
                if isinstance(result, OrdinalResultV2):
                    classes=list(probabilities)
                    if label not in classes: return False
                    idx=classes.index(label)
                    probability=(sum(probabilities[item] for item in classes[idx:]) if goal.operator == "AT_LEAST"
                                 else sum(probabilities[item] for item in classes[:idx+1]) if goal.operator == "AT_MOST"
                                 else probabilities[label])
                else:
                    probability=probabilities.get(label, -1.0)
                if probability < goal.minimum_probability: return False
        return True

    @staticmethod
    def _count_rejections(summary: dict[str, int], outcomes: list[Any]) -> None:
        for item in outcomes:
            if getattr(item, "status", None) != "BLOCKED": continue
            code=getattr(item, "code", "")
            if code == "OUT_OF_DOMAIN": summary["OUT_OF_DOMAIN"] += 1
            elif code in {"MISSING_REQUIRED_X", "FORMULA_INCOMPLETE"}: summary["INPUT_MISSING"] += 1
            elif code in {"MATERIAL_NOT_IN_MODEL", "UNKNOWN_MATERIAL"}: summary["MATERIAL_MISMATCH"] += 1
            else: summary["RULE_FAILED"] += 1

    @staticmethod
    def _shortfall_code(summary: dict[str, int]) -> str:
        return max(summary, key=summary.get) if any(summary.values()) else "SEARCH_SPACE_EMPTY"

    @staticmethod
    def _shortfall_reason(summary: dict[str, int]) -> str:
        labels={"TARGET_NOT_MET":"候选未达到目标要求","OUT_OF_DOMAIN":"候选超出模型适用范围",
                "INPUT_MISSING":"候选缺少模型输入","MATERIAL_MISMATCH":"候选材料不在模型覆盖范围",
                "RULE_FAILED":"候选未通过业务规则","DUPLICATE":"候选重复"}
        code=FormulaModelV2Service._shortfall_code(summary)
        return labels.get(code,"当前材料和约束无法形成可行候选")

    @staticmethod
    def _rejection_hints(summary: dict[str, int]) -> list[str]:
        hints=[]
        if summary.get("TARGET_NOT_MET"): hints.append("放宽目标值或最低概率")
        if summary.get("OUT_OF_DOMAIN"): hints.append("缩小材料和工艺变量范围")
        if summary.get("INPUT_MISSING"): hints.append("补充模型要求的实验前条件")
        if summary.get("MATERIAL_MISMATCH"): hints.append("选择所有目标模型共同覆盖的材料")
        if not hints: hints.append("调整材料比例范围或可变条件")
        return hints

    def _goal_score(self, goals: list[Any], successes: list[TargetSuccessV2]) -> float:
        score = 0.0
        for index, goal in enumerate(goals):
            result = next((item.result for item in successes if item.target_id == goal.target_id), None)
            if result is None and index < len(successes):
                result = successes[index].result
            if result is None: continue
            if isinstance(result, ContinuousResultV2):
                score += float(result.value) * float(goal.weight)
            elif hasattr(result, "probabilities"):
                score += max(result.probabilities.values()) * float(goal.weight)
            else:
                score += float(getattr(result, "probability", 0.0)) * float(goal.weight)
        return score

    def _load_bundle(self, data: bytes) -> dict[str, Any]:
        try:
            with zipfile.ZipFile(io.BytesIO(data), "r") as archive:
                if "model.joblib" not in archive.namelist() or "model-card.json" not in archive.namelist():
                    raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "模型制品缺少必需文件")
                payload = joblib.load(io.BytesIO(archive.read("model.joblib")))
        except FormulaModelError:
            raise
        except Exception as exc:
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "模型制品无法加载") from exc
        if not isinstance(payload, dict):
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "模型制品内容无效")
        return payload

    def _verify_binding(self, binding: Any, bundle: dict[str, Any]) -> None:
        if bundle.get("contractVersion") != "formula-model.v2":
            raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "模型制品契约版本不匹配")
        checks = [
            ("target", binding.target),
            ("inputScheme", binding.input_scheme),
            ("materialDictionary", binding.material_dictionary),
            ("preprocessing", binding.preprocessing),
        ]
        for name, expected in checks:
            actual = bundle.get(name)
            if not isinstance(actual, dict):
                raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, f"模型制品缺少{name}")
            expected_payload = expected.model_dump(by_alias=True)
            for key in ("id", "sha256"):
                if actual.get(key) != expected_payload.get(key):
                    raise FormulaModelError(ErrorCode.HASH_MISMATCH, f"模型制品{name}版本或哈希不匹配",
                                            details={"field": key})

    def _domain_features(self, binding: Any, names: list[str]) -> set[str]:
        relevant = {"formula:recordedTotal"}
        relevant.update(f"material:{item.material_id}" for item in binding.material_dictionary.materials)
        for field in binding.input_scheme.fields:
            direct = f"input:{field.code}"
            relevant.update(name for name in names if name == direct or name.startswith(direct + "="))
        return relevant

    def _applicability(self, values: np.ndarray, names: list[str], pinned: dict[str, Any],
                       trained: dict[str, Any], relevant_features: set[str] | None = None) -> ApplicabilityV2:
        required = {"method", "features", "minimum", "maximum", "nearBoundaryRatio"}
        if not required.issubset(pinned):
            raise FormulaModelError(ErrorCode.MODEL_NOT_READY, "适用域策略未完整冻结")
        if pinned.get("features") != trained.get("features") or pinned.get("minimum") != trained.get("minimum") \
                or pinned.get("maximum") != trained.get("maximum"):
            raise FormulaModelError(ErrorCode.HASH_MISMATCH, "适用域边界与模型制品不一致")
        if names != list(pinned["features"]):
            raise FormulaModelError(ErrorCode.HASH_MISMATCH, "适用域特征顺序与编码器不一致")
        minimum = np.asarray(pinned["minimum"], dtype=float)
        maximum = np.asarray(pinned["maximum"], dtype=float)
        if len(values) != len(minimum) or len(minimum) != len(maximum):
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "适用域边界维度无效")
        ratio = float(pinned["nearBoundaryRatio"])
        if not 0 <= ratio < 0.5:
            raise FormulaModelError(ErrorCode.MODEL_NOT_READY, "适用域边界比例未配置")
        checked = np.asarray([relevant_features is None or name in relevant_features for name in names], dtype=bool)
        below, above = (values < minimum) & checked, (values > maximum) & checked
        if bool(np.any(below | above)):
            overflow = np.maximum(minimum - values, values - maximum)
            return ApplicabilityV2(status="OUT_OF_DOMAIN", distance=float(np.max(np.maximum(overflow, 0))),
                                   reasons=[names[i] for i in np.where(below | above)[0]])
        spans = maximum - minimum
        # One-hot category indicators necessarily sit on 0/1.  Treating
        # those endpoints as a geometric boundary would classify every
        # valid categorical observation as NEAR_BOUNDARY.  They still
        # participate in the out-of-range check above, but only continuous
        # dimensions contribute to boundary distance.
        categorical_indicator = np.asarray([
            "=" in name and abs(minimum[index]) <= 1e-12 and abs(maximum[index] - 1.0) <= 1e-12
            for index, name in enumerate(names)
        ], dtype=bool)
        usable = (spans > 1e-12) & ~categorical_indicator & checked
        edge_distance = np.ones_like(values, dtype=float)
        edge_distance[usable] = np.minimum(values[usable] - minimum[usable],
                                           maximum[usable] - values[usable]) / spans[usable]
        distance = float(np.min(edge_distance)) if len(edge_distance) else 1.0
        status = "NEAR_BOUNDARY" if distance <= ratio else "IN_DOMAIN"
        return ApplicabilityV2(status=status, distance=max(distance, 0.0), reasons=[])

    def _typed_result(self, binding: Any, bundle: dict[str, Any], vector: np.ndarray) -> Any:
        model = bundle.get("model")
        metrics = bundle.get("metrics") or {}
        if model is None:
            raise FormulaModelError(ErrorCode.MODEL_NOT_READY, "模型制品未包含可执行模型")
        kind = ValueTypeV2(binding.target.value_type)
        if kind == ValueTypeV2.CONTINUOUS:
            value = float(model.predict(vector)[0])
            spread = metrics.get("residualStd")
            if spread is None or not np.isfinite(float(spread)):
                raise FormulaModelError(ErrorCode.MODEL_NOT_READY, "连续值模型缺少区间校准参数")
            half = 1.645 * float(spread)
            return ContinuousResultV2(value=value, unit=binding.target.unit,
                                      lower=value-half, upper=value+half, coverage_level=0.9)
        probabilities_raw = np.asarray(model.predict_proba(vector)[0], dtype=float)
        if not np.all(np.isfinite(probabilities_raw)) or np.any(probabilities_raw < 0):
            raise FormulaModelError(ErrorCode.INTERNAL_COMPUTE_ERROR, "分类模型返回了无效概率")
        total = float(np.sum(probabilities_raw))
        if total <= 0:
            raise FormulaModelError(ErrorCode.INTERNAL_COMPUTE_ERROR, "分类模型概率和无效")
        probabilities_raw /= total
        model_classes = [str(item) for item in model.classes_]
        probabilities = {label: float(probabilities_raw[model_classes.index(label)])
                         if label in model_classes else 0.0 for label in binding.target.classes}
        label = max(probabilities, key=probabilities.get)
        if kind == ValueTypeV2.ORDINAL:
            index = binding.target.classes.index(label)
            threshold_probability = sum(probabilities[item] for item in binding.target.classes[index:])
            return OrdinalResultV2(label=label, probabilities=probabilities,
                                   threshold_probability=threshold_probability)
        if kind == ValueTypeV2.BINARY:
            threshold = binding.target.observation_semantics.get("decisionThreshold")
            if threshold is None or not 0 < float(threshold) < 1:
                raise FormulaModelError(ErrorCode.MODEL_NOT_READY, "二分类目标缺少版本化决策阈值")
            positive = binding.target.positive_class
            probability = probabilities[str(positive)]
            selected = str(positive) if probability >= float(threshold) else next(
                item for item in binding.target.classes if item != positive)
            return BinaryResultV2(label=selected, positive_class=str(positive), probability=probability,
                                  decision_threshold=float(threshold))
        return CategoricalResultV2(label=label, probabilities=probabilities)

    def _dataset(self, request: ValidateRequestV2 | ValidationFoldsRequestV2 | TrainRequestV2) -> _Dataset:
        manifest = self._read(request.snapshot.manifest.url, request.snapshot.manifest.sha256)
        try:
            manifest_json = json.loads(manifest)
        except Exception as exc:
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "snapshot manifest is not JSON") from exc
        if manifest_json.get("snapshotHash") != request.snapshot.sha256:
            raise FormulaModelError(ErrorCode.HASH_MISMATCH, "snapshot hash differs from manifest")
        rows = pq.read_table(io.BytesIO(self._read(request.snapshot.rows.url, request.snapshot.rows.sha256))).to_pandas()
        source = pq.read_table(io.BytesIO(self._read(request.snapshot.source_map.url, request.snapshot.source_map.sha256))).to_pandas()
        return _Dataset(rows=rows, source=source)

    def _validate_rows(self, request: ValidateRequestV2 | ValidationFoldsRequestV2 | TrainRequestV2,
                       dataset: _Dataset, issues: list[ValidationIssueV2]) -> None:
        required = {"row_id", "formula_json", "inputs_json", "target_numeric", "target_label"}
        missing = sorted(required - set(dataset.rows.columns))
        if missing:
            issues.append(ValidationIssueV2(code="MISSING_COLUMNS", message=",".join(missing)))
            return
        if dataset.rows.empty:
            issues.append(ValidationIssueV2(code="INSUFFICIENT_DATA", message="snapshot contains no rows"))
            return
        if dataset.rows["row_id"].astype(str).duplicated().any():
            issues.append(ValidationIssueV2(code="DUPLICATE_ROW_ID", message="row ids must be unique"))
        source_ids = set(dataset.source.get("row_id", pd.Series(dtype=str)).astype(str))
        requires_formula = self._scheme_uses_formula(request.context.input_scheme)
        for _, row in dataset.rows.iterrows():
            row_id = str(row["row_id"])
            if row_id not in source_ids:
                issues.append(ValidationIssueV2(code="SOURCE_MAP_MISSING", message="source row missing", row_id=row_id))
            try:
                formula = json.loads(row["formula_json"])
                if requires_formula and not formula.get("components"):
                    issues.append(ValidationIssueV2(code="FORMULA_EMPTY", message="formula has no components", row_id=row_id))
                # recordedTotal is evidence. It is intentionally never normalized to 100.
                if formula.get("recordedTotal") is not None and float(formula["recordedTotal"]) < 0:
                    issues.append(ValidationIssueV2(code="FORMULA_TOTAL_INVALID", message="recorded total is negative", row_id=row_id))
                json.loads(row["inputs_json"])
            except Exception:
                issues.append(ValidationIssueV2(code="ROW_JSON_INVALID", message="formula or inputs are invalid", row_id=row_id))
        target_type = ValueTypeV2(request.context.target_definition.value_type)
        if target_type == ValueTypeV2.CONTINUOUS and dataset.rows["target_numeric"].isna().any():
            issues.append(ValidationIssueV2(code="TARGET_VALUE_MISSING", message="continuous target is missing"))
        if target_type != ValueTypeV2.CONTINUOUS and dataset.rows["target_label"].isna().any():
            issues.append(ValidationIssueV2(code="TARGET_LABEL_MISSING", message="class target is missing"))

    def _features(self, row: pd.Series, allowed_input_codes: Any | None = None,
                  include_formula: bool = True) -> dict[str, Any]:
        formula = json.loads(row["formula_json"])
        inputs = json.loads(row["inputs_json"])
        return self._feature_record(formula, self._filtered_inputs(inputs, allowed_input_codes), include_formula)

    def _filtered_inputs(self, inputs: dict[str, Any], scheme: Any) -> dict[str, Any]:
        if not isinstance(inputs, dict):
            return {}
        if scheme is None:
            return dict(inputs)
        if isinstance(scheme, (set, frozenset, list, tuple)) and all(isinstance(item, str) for item in scheme):
            scheme = {item: item for item in scheme}
        if isinstance(scheme, dict) and all(isinstance(key, str) for key in scheme):
            # Directly keyed facts are already normalized.
            out = {key: inputs[key] for key in scheme if key in inputs}
            if out:
                return out

            # Imported and completed-experiment facts intentionally retain their
            # source identity.  Resolve a requested X by the stable code, its
            # configured business name, the source field code, or label path.
            wanted = {
                code: {self._normalize_feature_text(code), self._normalize_feature_text(name)}
                for code, name in scheme.items()
            }
            found: dict[str, Any] = {}

            def walk(node: Any) -> None:
                if isinstance(node, dict):
                    source_keys = {
                        self._normalize_feature_text(str(node.get("fieldCode", ""))),
                        self._normalize_feature_text(str(node.get("labelPath", ""))),
                        self._normalize_feature_text(str(node.get("name", ""))),
                    }
                    # Imported facts usually retain their source shape, for
                    # example ``conditions.temperature`` or
                    # ``process.uvEnergy``.  Those leaves do not carry a
                    # fieldCode/name wrapper, so include the object keys in
                    # the identity candidates.  A frozen X code may contain
                    # a stable domain prefix (``TEST_TEMPERATURE``); matching
                    # the unprefixed source leaf keeps that identity bridge
                    # deterministic without turning arbitrary labels into
                    # model features.
                    source_keys.update(
                        self._normalize_feature_text(str(key))
                        for key in node.keys()
                        if self._normalize_feature_text(str(key))
                    )
                    source_keys.discard("")
                    for code, aliases in wanted.items():
                        matched = any(
                            alias == source
                            or (len(source) >= 4 and (alias.endswith(source) or source.endswith(alias)))
                            for alias in aliases
                            for source in source_keys
                        )
                        if code in found or not matched:
                            continue
                        value = node.get("rawNumericValue")
                        if value is None:
                            value = node.get("normalizedValue")
                        if value is None:
                            value = node.get("rawValue")
                        if value is not None:
                            found[code] = self._coerce_feature_value(value)
                        else:
                            # Plain imported objects use a scalar leaf such
                            # as ``{"temperature": 21}`` instead of a
                            # wrapped field-value object.  Resolve the
                            # matching leaf itself; otherwise the identity
                            # match would succeed but still yield no value.
                            for key, leaf in node.items():
                                normalized_key = self._normalize_feature_text(str(key))
                                if not isinstance(leaf, (dict, list)) and any(
                                    alias == normalized_key
                                    or (len(normalized_key) >= 4 and (
                                        alias.endswith(normalized_key) or normalized_key.endswith(alias)))
                                    for alias in aliases
                                ):
                                    found[code] = self._coerce_feature_value(leaf)
                                    break
                    for value in node.values():
                        walk(value)
                elif isinstance(node, list):
                    for value in node:
                        walk(value)

            walk(inputs)
            return found
        fields = getattr(scheme, "fields", None)
        if fields is None and isinstance(scheme, dict):
            fields = scheme.get("fields", [])
        codes = {getattr(field, "code", field.get("code") if isinstance(field, dict) else "")
                 for field in (fields or [])}
        # A frozen scheme is authoritative.  Keep only its explicitly named
        # values so incidental source columns cannot change model features.
        return {key: value for key, value in inputs.items() if key in codes}

    @staticmethod
    def _normalize_feature_text(value: str) -> str:
        return "".join(str(value).strip().upper().split()).replace("_", "")

    @staticmethod
    def _coerce_feature_value(value: Any) -> Any:
        if isinstance(value, (int, float, bool)):
            return value
        text = str(value).strip()
        try:
            return float(text)
        except ValueError:
            return text

    def _feature_record(self, formula: dict[str, Any], inputs: dict[str, Any], include_formula: bool = True) -> dict[str, Any]:
        out: dict[str, Any] = {}
        if include_formula:
            for component in formula.get("components", []):
                material = str(component.get("materialId") or component.get("materialCode") or "UNKNOWN")
                ratio = component.get("ratio")
                if ratio is not None:
                    out[f"material:{material}"] = float(ratio)
            recorded_total = formula.get("recordedTotal")
            if recorded_total is not None:
                out["formula:recordedTotal"] = float(recorded_total)
        self._flatten_inputs(inputs, "input", out)
        return out

    @staticmethod
    def _scheme_uses_formula(scheme: Any) -> bool:
        fields=getattr(scheme,"fields",None) or []
        for field in fields:
            value_type=str(getattr(field,"value_type","") or "")
            code=str(getattr(field,"code","") or "").upper()
            if value_type == "COMPOSITION" or "FORMULA" in code or "COMPOSITION" in code: return True
        return False

    def _flatten_inputs(self, node: Any, prefix: str, out: dict[str, Any]) -> None:
        if isinstance(node, dict):
            for key in sorted(node):
                self._flatten_inputs(node[key], f"{prefix}:{key}", out)
        elif isinstance(node, list):
            for index, value in enumerate(node):
                self._flatten_inputs(value, f"{prefix}:{index}", out)
        else:
            key = prefix
            value = node
            if isinstance(value, bool):
                out[key] = int(value)
            elif isinstance(value, (int, float)):
                out[key] = float(value)
            elif value is not None:
                out[f"{key}={value}"] = 1.0

    def _target(self, rows: pd.DataFrame, target_type: ValueTypeV2) -> np.ndarray:
        if target_type == ValueTypeV2.CONTINUOUS:
            return rows["target_numeric"].astype(float).to_numpy()
        return rows["target_label"].astype(str).to_numpy()

    def _candidates(self, target_type: ValueTypeV2, names: list[str], seed: int,
                    classes: list[str]) -> list[tuple[str, Any]]:
        requested = {str(name).upper() for name in names}
        classification = target_type != ValueTypeV2.CONTINUOUS
        out: list[tuple[str, Any]] = []
        if "GAUSSIAN_PROCESS" in requested and not classification:
            out.append(("GAUSSIAN_PROCESS", GaussianProcessRegressor(kernel=Matern() + WhiteKernel(), normalize_y=True, random_state=seed)))
        if "LOGISTIC_REGRESSION" in requested and classification and target_type != ValueTypeV2.ORDINAL:
            out.append(("LOGISTIC_REGRESSION", LogisticRegression(max_iter=2000, random_state=seed)))
        if "ORDINAL_CUMULATIVE_LOGIT" in requested and target_type == ValueTypeV2.ORDINAL:
            out.append(("ORDINAL_CUMULATIVE_LOGIT", OrdinalCumulativeLogit(tuple(classes), seed)))
        if "RANDOM_FOREST" in requested:
            cls = RandomForestClassifier if classification else RandomForestRegressor
            out.append(("RANDOM_FOREST", cls(n_estimators=160, min_samples_leaf=2, random_state=seed, n_jobs=self.settings.model_threads)))
        if "LIGHTGBM" in requested:
            from lightgbm import LGBMClassifier, LGBMRegressor
            cls = LGBMClassifier if classification else LGBMRegressor
            out.append(("LIGHTGBM", cls(n_estimators=160, random_state=seed, n_jobs=self.settings.model_threads, verbosity=-1)))
        if "XGBOOST" in requested:
            from xgboost import XGBClassifier, XGBRegressor
            cls = XGBClassifier if classification else XGBRegressor
            out.append(("XGBOOST", cls(n_estimators=160, random_state=seed, n_jobs=self.settings.model_threads)))
        if "CATBOOST" in requested:
            from catboost import CatBoostClassifier, CatBoostRegressor
            cls = CatBoostClassifier if classification else CatBoostRegressor
            out.append(("CATBOOST", cls(iterations=160, random_seed=seed, thread_count=self.settings.model_threads, verbose=False)))
        # Remove duplicate algorithm labels while preserving configured order.
        return list({name: estimator for name, estimator in out}.items())

    def _baseline_metrics(self, y: np.ndarray, folds: np.ndarray, target_type: ValueTypeV2,
                          classes: list[str]) -> dict[str, Any]:
        if target_type == ValueTypeV2.CONTINUOUS:
            predictions = np.zeros(len(y), dtype=float)
            for fold in sorted(set(folds.tolist())):
                train, valid = np.where(folds != fold)[0], np.where(folds == fold)[0]
                predictions[valid] = float(np.mean(y[train].astype(float)))
            actual = y.astype(float)
            return {"status": "AVAILABLE", "method": "TRAIN_FOLD_MEAN",
                    "mae": float(mean_absolute_error(actual, predictions)),
                    "rmse": float(mean_squared_error(actual, predictions) ** 0.5),
                    "r2": float(r2_score(actual, predictions))}
        ordered = np.asarray(classes if classes else sorted(set(y.astype(str))))
        predictions = np.empty(len(y), dtype=object)
        probabilities = np.zeros((len(y), len(ordered)), dtype=float)
        for fold in sorted(set(folds.tolist())):
            train, valid = np.where(folds != fold)[0], np.where(folds == fold)[0]
            labels, counts = np.unique(y[train].astype(str), return_counts=True)
            majority = sorted(zip(counts.tolist(), labels.tolist()), key=lambda item: (-item[0], item[1]))[0][1]
            predictions[valid] = majority
            total = float(np.sum(counts))
            for label, count in zip(labels, counts):
                probabilities[valid, list(ordered).index(label)] = count / total
        actual = y.astype(str)
        clipped = np.clip(probabilities, 1e-12, 1.0)
        clipped /= clipped.sum(axis=1, keepdims=True)
        result: dict[str, Any] = {"status": "AVAILABLE", "method": "TRAIN_FOLD_MAJORITY",
                                  "accuracy": float(accuracy_score(actual, predictions.astype(str))),
                                  "macroF1": float(f1_score(actual, predictions.astype(str), average="macro")),
                                  "logLoss": float(-np.mean([np.log(clipped[index, list(ordered).index(label)])
                                                              for index, label in enumerate(actual)]))}
        if target_type == ValueTypeV2.ORDINAL:
            order = {label: index for index, label in enumerate(classes)}
            actual_index = np.asarray([order[item] for item in actual])
            predicted_index = np.asarray([order[item] for item in predictions.astype(str)])
            result["gradeMae"] = float(np.mean(np.abs(actual_index - predicted_index)))
            result["plusMinusOneAccuracy"] = float(np.mean(np.abs(actual_index - predicted_index) <= 1))
        return result

    def _cross_validate(self, estimator: Any, x: np.ndarray, y: np.ndarray, folds: np.ndarray,
                        target_type: ValueTypeV2, classes: list[str]) -> tuple[dict[str, Any], float]:
        predictions: np.ndarray = np.empty(len(y), dtype=object if target_type != ValueTypeV2.CONTINUOUS else float)
        probabilities: np.ndarray | None = None
        ordered_classes = np.array(classes if classes else sorted(set(y.astype(str))))
        if target_type != ValueTypeV2.CONTINUOUS:
            probabilities = np.zeros((len(y), len(ordered_classes)), dtype=float)
        for fold in sorted(set(folds.tolist())):
            train, valid = np.where(folds != fold)[0], np.where(folds == fold)[0]
            if not len(train) or not len(valid):
                raise ValueError("empty validation split")
            fitted = clone(estimator).fit(x[train], y[train])
            predictions[valid] = fitted.predict(x[valid])
            if probabilities is not None:
                local = fitted.predict_proba(x[valid])
                local_classes = [str(item) for item in fitted.classes_]
                for index, label in enumerate(ordered_classes):
                    if str(label) in local_classes:
                        probabilities[valid, index] = local[:, local_classes.index(str(label))]
        if target_type == ValueTypeV2.CONTINUOUS:
            actual, pred = y.astype(float), predictions.astype(float)
            mae = float(mean_absolute_error(actual, pred))
            rmse = float(mean_squared_error(actual, pred) ** 0.5)
            residual = actual - pred
            spread = float(np.std(residual))
            metrics = {"mae": mae, "rmse": rmse, "r2": float(r2_score(actual, pred)),
                       "intervalCoverage90": float(np.mean(np.abs(residual) <= 1.645 * max(spread, 1e-12))),
                       "residualStd": spread}
            return metrics, mae
        actual, pred = y.astype(str), predictions.astype(str)
        metrics = {"accuracy": float(accuracy_score(actual, pred)),
                   "macroF1": float(f1_score(actual, pred, average="macro"))}
        if probabilities is not None:
            clipped = np.clip(probabilities, 1e-12, 1.0)
            clipped /= clipped.sum(axis=1, keepdims=True)
            order = {str(label): index for index, label in enumerate(ordered_classes)}
            metrics["logLoss"] = float(-np.mean([np.log(clipped[index, order[label]])
                                                  for index, label in enumerate(actual)]))
        if target_type == ValueTypeV2.ORDINAL:
            order = {label: index for index, label in enumerate(classes)}
            actual_idx = np.array([order[item] for item in actual])
            pred_idx = np.array([order[item] for item in pred])
            corr = spearmanr(actual_idx, pred_idx).statistic
            metrics.update({"gradeMae": float(np.mean(np.abs(actual_idx - pred_idx))),
                            "plusMinusOneAccuracy": float(np.mean(np.abs(actual_idx - pred_idx) <= 1)),
                            "spearman": float(corr) if np.isfinite(corr) else 0.0})
            return metrics, metrics["gradeMae"]
        return metrics, metrics.get("logLoss", 1.0 - metrics["macroF1"])

    def _domain(self, features: np.ndarray, names: list[str]) -> dict[str, Any]:
        return {"method": "ROBUST_FEATURE_RANGE", "featureCount": len(names),
                "minimum": np.nanmin(features, axis=0).tolist(),
                "maximum": np.nanmax(features, axis=0).tolist(), "features": names}

    def _bundle(self, request: TrainRequestV2, vectorizer: DictVectorizer, model: Any,
                metrics: dict[str, Any], domain: dict[str, Any]) -> bytes:
        model_bytes = io.BytesIO()
        joblib.dump({"contractVersion": "formula-model.v2", "target": request.context.target_definition.model_dump(by_alias=True),
                     "inputScheme": request.context.input_scheme.model_dump(by_alias=True),
                     "materialDictionary": request.context.material_dictionary.model_dump(by_alias=True),
                     "preprocessing": request.context.preprocessing.model_dump(by_alias=True),
                     "vectorizer": vectorizer, "model": model, "metrics": metrics, "applicabilityDomain": domain}, model_bytes)
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("model.joblib", model_bytes.getvalue())
            archive.writestr("model-card.json", json.dumps({"jobId": request.job_id, "snapshotId": request.snapshot.id,
                                                            "target": request.context.target_definition.model_dump(by_alias=True),
                                                            "metrics": metrics, "applicabilityDomain": domain},
                                                           ensure_ascii=False, sort_keys=True, separators=(",", ":")))
        return output.getvalue()

    def _read(self, url: Any, expected_sha: str) -> bytes:
        parsed = urlparse(str(url))
        if parsed.scheme == "file":
            if not self.settings.allow_file_urls:
                raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "file URLs are disabled")
            raw = unquote(parsed.path)
            if parsed.netloc:
                raw = f"//{parsed.netloc}{raw}"
            if len(raw) >= 3 and raw[0] == "/" and raw[2] == ":":
                raw = raw[1:]
            try:
                data = Path(raw).read_bytes()
            except OSError as exc:
                raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "unable to read artifact", retryable=True) from exc
        else:
            try:
                response = httpx.get(str(url), timeout=self.settings.artifact_timeout_seconds, follow_redirects=False)
                response.raise_for_status()
                data = response.content
            except httpx.HTTPError as exc:
                raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "unable to download artifact", retryable=True) from exc
        if len(data) > self.settings.maximum_artifact_bytes:
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "artifact exceeds configured size limit")
        if _sha(data) != expected_sha:
            raise FormulaModelError(ErrorCode.HASH_MISMATCH, "artifact SHA-256 does not match the request")
        return data

    def _write(self, url: Any, data: bytes) -> None:
        if len(data) > self.settings.maximum_artifact_bytes:
            raise FormulaModelError(ErrorCode.INTERNAL_COMPUTE_ERROR, "model bundle is too large")
        parsed = urlparse(str(url))
        if parsed.scheme == "file":
            if not self.settings.allow_file_urls:
                raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "file URLs are disabled")
            raw = unquote(parsed.path)
            if parsed.netloc:
                raw = f"//{parsed.netloc}{raw}"
            if len(raw) >= 3 and raw[0] == "/" and raw[2] == ":":
                raw = raw[1:]
            path = Path(raw)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            return
        try:
            response = httpx.put(str(url), content=data, headers={"Content-Type": "application/zip"},
                                 timeout=self.settings.artifact_timeout_seconds, follow_redirects=False)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise FormulaModelError(ErrorCode.INTERNAL_COMPUTE_ERROR, "unable to upload model bundle", retryable=True) from exc
