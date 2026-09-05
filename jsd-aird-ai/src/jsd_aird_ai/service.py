from __future__ import annotations

from jsd_aird_ai.artifacts import ArtifactClient, LruCache
from jsd_aird_ai.contracts import (
    ClassificationPrediction,
    DomainStatus,
    Prediction,
    OrdinalPrediction,
    RecommendRequest,
    RecommendResponse,
    ScoreRequest,
    ScoreResponse,
    ScoredRow,
    SnapshotValidationResponse,
    TrainRequest,
    TrainResponse,
    ValidateSnapshotRequest,
    ValueType,
)
from jsd_aird_ai.digests import canonical_sha256, sha256_bytes
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.features import FeatureBuilder
from jsd_aird_ai.modeling import ModelBundle, ModelTrainer, load_bundle
from jsd_aird_ai.optimizer import RecommendationEngine
from jsd_aird_ai.settings import Settings
from jsd_aird_ai.snapshot import SnapshotValidator


class FormulaModelService:
    def __init__(self, settings: Settings) -> None:
        self._artifacts = ArtifactClient(settings)
        self._snapshots = SnapshotValidator(self._artifacts)
        self._trainer = ModelTrainer(thread_count=settings.model_threads)
        self._recommendations = RecommendationEngine()
        self._cache: LruCache[ModelBundle] = LruCache(settings.model_cache_entries)

    def validate_snapshot(self, request: ValidateSnapshotRequest) -> SnapshotValidationResponse:
        return self._snapshots.load_and_validate(request).response

    def train(self, request: TrainRequest) -> TrainResponse:
        snapshot = self._snapshots.load_and_validate(request)
        bundle_bytes, target_results, warnings = self._trainer.train(
            snapshot,
            request.task_profile,
            request.task_profile_hash,
            request.snapshot_hash,
            request.seed,
        )
        digest = sha256_bytes(bundle_bytes)
        self._artifacts.write(request.output, bundle_bytes)
        uploaded = True
        ready = sum(item.status == "READY" for item in target_results)
        unsupported_or_failed = sum(item.status != "READY" for item in target_results)
        status = "REJECTED" if ready == 0 else "PARTIAL" if unsupported_or_failed else "READY"
        return TrainResponse(
            request_id=request.request_id,
            snapshot_id=snapshot.response.snapshot_id,
            snapshot_hash=request.snapshot_hash,
            status=status,
            model_bundle_sha256=digest,
            model_bundle_size=len(bundle_bytes),
            uploaded=uploaded,
            targets=target_results,
            warnings=warnings,
        )

    def score(self, request: ScoreRequest) -> ScoreResponse:
        bundle = self._bundle(request)
        builder = FeatureBuilder(request.task_profile)
        features = builder.from_api_rows(
            [row.formula for row in request.rows],
            [row.context for row in request.rows],
        )
        predictions_by_target = {}
        ordinal_by_target = {}
        classification_by_target = {}
        domain_by_target = {}
        unsupported = []
        spec_by_code = {item.code: item for item in request.task_profile.targets}
        for target_code in request.target_codes:
            target = bundle.targets.get(target_code)
            if target is None:
                unsupported.append(target_code)
                continue
            if str(target.get("target_type")) == "ORDINAL":
                ordinal_by_target[target_code] = target["scorer"].predict(features)
            elif str(target.get("target_type")) in {"BINARY", "CATEGORICAL"}:
                classification_by_target[target_code] = target["scorer"].predict(features)
            else:
                predictions_by_target[target_code] = target["scorer"].predict(features)
            domain = target.get("applicability_domain")
            if domain is not None:
                domain_by_target[target_code] = domain.assess(features)
        if (
            not predictions_by_target
            and not ordinal_by_target
            and not classification_by_target
        ):
            raise FormulaModelError(
                ErrorCode.MODEL_NOT_READY,
                "none of the requested targets has a trained scorer",
                details={"unsupportedTargets": unsupported},
            )
        rows = []
        for index, row in enumerate(request.rows):
            row_predictions = []
            row_domains = []
            for target_code, (expected, lower, upper) in predictions_by_target.items():
                target = bundle.targets[target_code]
                domain = (
                    domain_by_target[target_code][index]
                    if target_code in domain_by_target
                    else None
                )
                if domain is not None:
                    row_domains.append((target_code, domain))
                row_predictions.append(
                    Prediction(
                        target_code=target_code,
                        expected=float(expected[index]),
                        lower=float(lower[index]),
                        upper=float(upper[index]),
                        unit=spec_by_code[target_code].unit,
                        scorer_type=target["scorer_type"],
                        applicability_domain=domain,
                    )
                )
            ordinal_predictions = []
            for target_code, (predicted, probabilities, lower, upper) in ordinal_by_target.items():
                scorer = bundle.targets[target_code]["scorer"]
                labels = scorer.ordered_labels
                domain = (
                    domain_by_target[target_code][index]
                    if target_code in domain_by_target
                    else None
                )
                if domain is not None:
                    row_domains.append((target_code, domain))
                ordinal_predictions.append(
                    OrdinalPrediction(
                        target_code=target_code,
                        predicted_class=labels[int(predicted[index])],
                        class_probabilities={
                            label: float(probabilities[index, class_index])
                            for class_index, label in enumerate(labels)
                        },
                        lower_class90=labels[int(lower[index])],
                        upper_class90=labels[int(upper[index])],
                        applicability_domain=domain,
                    )
                )
            classification_predictions = []
            for target_code, (predicted, raw, calibrated) in classification_by_target.items():
                target = bundle.targets[target_code]
                scorer = target["scorer"]
                domain = (
                    domain_by_target[target_code][index]
                    if target_code in domain_by_target
                    else None
                )
                if domain is not None:
                    row_domains.append((target_code, domain))
                probabilities = {
                    label: float(calibrated[index, class_index])
                    for class_index, label in enumerate(scorer.class_labels)
                }
                raw_probabilities = {
                    label: float(raw[index, class_index])
                    for class_index, label in enumerate(scorer.class_labels)
                }
                positive_class = (
                    scorer.positive_class
                    if scorer.target_type == ValueType.BINARY
                    else None
                )
                positive_index = (
                    scorer.class_labels.index(positive_class)
                    if positive_class is not None
                    else None
                )
                classification_predictions.append(
                    ClassificationPrediction(
                        target_code=target_code,
                        target_type=scorer.target_type,
                        predicted_class=str(predicted[index]),
                        class_probabilities=probabilities,
                        raw_class_probabilities=raw_probabilities,
                        confidence=max(probabilities.values()),
                        positive_class=positive_class,
                        raw_positive_probability=(
                            float(raw[index, positive_index])
                            if positive_index is not None
                            else None
                        ),
                        calibrated_positive_probability=(
                            float(calibrated[index, positive_index])
                            if positive_index is not None
                            else None
                        ),
                        decision_threshold=(
                            scorer.decision_threshold
                            if positive_index is not None
                            else None
                        ),
                        calibration_method=scorer.calibration_method,
                        scorer_type=target["scorer_type"],
                        applicability_domain=domain,
                    )
                )
            severity = {
                DomainStatus.IN_DOMAIN: 0,
                DomainStatus.NEAR_BOUNDARY: 1,
                DomainStatus.OUT_OF_DOMAIN: 2,
            }
            domain_status = max(
                (item.status for _, item in row_domains),
                key=lambda item: severity[DomainStatus(item)],
                default=DomainStatus.IN_DOMAIN,
            )
            fallback_reasons = [
                f"{target_code}:{reason}"
                for target_code, evidence in row_domains
                if not evidence.model_usable
                for reason in (evidence.reasons or ["OUT_OF_DOMAIN"])
            ]
            rows.append(
                ScoredRow(
                    row_id=row.row_id,
                    predictions=row_predictions,
                    ordinal_predictions=ordinal_predictions,
                    classification_predictions=classification_predictions,
                    domain_status=domain_status,
                    model_usable=not fallback_reasons,
                    fallback_reasons=fallback_reasons,
                )
            )
        return ScoreResponse(
            request_id=request.request_id,
            model_bundle_sha256=request.model_bundle.sha256,
            rows=rows,
            unsupported_targets=unsupported,
        )

    def recommend(self, request: RecommendRequest) -> RecommendResponse:
        bundle = self._bundle(request)
        result = self._recommendations.recommend(request, bundle)
        warnings = []
        if result.unsupported_targets:
            warnings.append("unsupported targets require CASE_STAT_RULE validation")
        if result.unavailable_targets:
            warnings.append("unavailable target models were excluded from optimization")
        if result.missing_strategies:
            warnings.append("feasible space was insufficient for every requested strategy")
        return RecommendResponse(
            request_id=request.request_id,
            status=(
                "PARTIAL"
                if result.unsupported_targets
                or result.unavailable_targets
                or result.missing_strategies
                or result.missing_strategy_reasons
                else "READY"
            ),
            model_bundle_sha256=request.model_bundle.sha256,
            candidates=result.candidates,
            missing_strategies=result.missing_strategies,
            missing_strategy_reasons=result.missing_strategy_reasons,
            unsupported_targets=result.unsupported_targets,
            unavailable_targets=result.unavailable_targets,
            recommendation_mode=request.recommendation_mode,
            search_space=result.search_space,
            target_conflicts=result.target_conflicts,
            warnings=warnings,
        )

    def _bundle(self, request: ScoreRequest | RecommendRequest) -> ModelBundle:
        if canonical_sha256(request.task_profile) != request.task_profile_hash:
            raise FormulaModelError(ErrorCode.HASH_MISMATCH, "task profile hash mismatch")
        cached = self._cache.get(request.model_bundle.sha256)
        if cached is None:
            data = self._artifacts.read(request.model_bundle)
            cached = load_bundle(data, request.model_bundle.sha256)
            self._cache.put(request.model_bundle.sha256, cached)
        payload = cached.payload
        if payload.get("task_profile_hash") != request.task_profile_hash:
            raise FormulaModelError(ErrorCode.HASH_MISMATCH, "model bundle task profile mismatch")
        if payload.get("snapshot_hash") != request.snapshot_hash:
            raise FormulaModelError(ErrorCode.HASH_MISMATCH, "model bundle snapshot mismatch")
        if payload.get("seed") != request.seed:
            raise FormulaModelError(ErrorCode.HASH_MISMATCH, "model bundle seed mismatch")
        return cached
