from __future__ import annotations

import copy
import math
from pathlib import Path

import numpy as np
import pytest

from jsd_aird_ai.classification import (
    CatBoostClassificationAdapter,
    ClassificationModelAdapter,
    LogisticRegressionClassificationAdapter,
    classification_selection_score,
    default_classification_adapters,
    encode_class_labels,
    evaluate_classification_adapter,
    select_classification_champion,
)
from jsd_aird_ai.contracts import (
    BinaryMetricSet,
    CandidateModelMetric,
    CategoricalMetricSet,
    ClassificationModelSelectionPolicy,
    ModelType,
    PerClassMetric,
    ProbabilityQuality,
    ScoreRequest,
    ValueType,
)
from jsd_aird_ai.digests import canonical_sha256, sha256_bytes
from jsd_aird_ai.features import FeatureBuilder
from jsd_aird_ai.modeling import ModelTrainer, load_bundle
from jsd_aird_ai.service import FormulaModelService
from jsd_aird_ai.settings import Settings
from jsd_aird_ai.synthetic_classification import (
    BINARY_TARGET,
    CATEGORICAL_TARGET,
    build_synthetic_classification_fixture,
)
from jsd_aird_ai.validation import group_folds

from conftest import REPAIRED_SNAPSHOT_ROOT


class FastClassifier(LogisticRegressionClassificationAdapter):
    def __init__(self, model_type: ModelType):
        super().__init__(1)
        self.model_type = model_type

    def parameter_candidates(self):
        return [{"C": 1.0}]


class FailingClassifier(FastClassifier):
    def fit(self, *args, **kwargs):
        raise RuntimeError("intentional challenger failure")


@pytest.fixture(scope="module")
def classification_data(profile):
    snapshot, classification_profile = build_synthetic_classification_fixture(
        REPAIRED_SNAPSHOT_ROOT,
        profile,
        rows=120,
    )
    return snapshot, classification_profile


@pytest.fixture(scope="module")
def fast_training(classification_data):
    snapshot, profile = classification_data
    model_types = [
        ModelType.LOGISTIC_REGRESSION,
        ModelType.RANDOM_FOREST,
        ModelType.LIGHTGBM,
        ModelType.XGBOOST,
        ModelType.CATBOOST,
    ]
    trainer = ModelTrainer(
        thread_count=1,
        classification_adapters=[FastClassifier(item) for item in model_types],
    )
    bundle, results, warnings = trainer.train(
        snapshot,
        profile,
        canonical_sha256(profile),
        snapshot.response.snapshot_hash,
        20260903,
    )
    return snapshot, profile, bundle, results, warnings


def test_binary_and_categorical_run_five_model_competitions(fast_training):
    _, _, _, results, _ = fast_training
    by_target = {item.target_code: item for item in results}
    assert by_target[BINARY_TARGET].status == "READY"
    assert by_target[CATEGORICAL_TARGET].status == "READY"
    expected = {
        ModelType.LOGISTIC_REGRESSION,
        ModelType.RANDOM_FOREST,
        ModelType.LIGHTGBM,
        ModelType.XGBOOST,
        ModelType.CATBOOST,
    }
    for result in by_target.values():
        assert set(result.model_selection.eligible_models) == expected
        assert len(result.model_selection.candidate_metrics) == 5
        assert result.lineage_cv is not None and result.sheet_cv is not None
        assert "SYNTHETIC_DATA_NOT_PRODUCTION_ELIGIBLE" in result.reasons
        assert result.production_eligible is False


def test_class_names_and_raw_calibrated_probability_quality_are_preserved(fast_training):
    _, _, _, results, _ = fast_training
    binary = next(item for item in results if item.target_code == BINARY_TARGET)
    categorical = next(item for item in results if item.target_code == CATEGORICAL_TARGET)
    assert binary.classification.observed_classes == ["FAIL", "PASS"]
    assert binary.classification.positive_class == "PASS"
    assert binary.lineage_cv.raw_probability_quality.log_loss >= 0
    assert binary.lineage_cv.calibrated_probability_quality.log_loss >= 0
    assert categorical.classification.observed_classes == [
        "NO_DEFECT",
        "CRACK",
        "BLISTER",
        "PEEL",
    ]
    assert set(categorical.lineage_cv.per_class) == set(
        categorical.classification.observed_classes
    )


def test_model_bundle_save_load_keeps_only_champion_scorers(fast_training):
    _, _, bundle_bytes, results, _ = fast_training
    bundle = load_bundle(bundle_bytes, sha256_bytes(bundle_bytes))
    assert set(bundle.targets) == {BINARY_TARGET, CATEGORICAL_TARGET}
    for result in results:
        target = bundle.targets[result.target_code]
        assert target["scorer"].model_type == result.scorer_type
        assert "candidateMetrics" in target["model_selection"]


def test_classification_applicability_domain_blocks_unknown_context(fast_training):
    snapshot, profile, bundle_bytes, _, _ = fast_training
    bundle = load_bundle(bundle_bytes, sha256_bytes(bundle_bytes))
    builder = FeatureBuilder(profile)
    row = snapshot.measurements.iloc[0]
    formula = {item.code: float(row[item.column]) for item in profile.formula.materials}
    context = {item.code: row[item.column] for item in profile.context_features}
    context["substrate"] = "UNKNOWN_SUBSTRATE"
    features = builder.from_api_rows([formula], [context])
    for target in bundle.targets.values():
        evidence = target["applicability_domain"].assess(features)[0]
        assert evidence.status == "OUT_OF_DOMAIN"
        assert evidence.model_usable is False
        assert "X_CONTEXT__SUBSTRATE" in evidence.unknown_categories


def test_score_response_returns_classification_probabilities_and_ood(fast_training, tmp_path):
    snapshot, profile, bundle_bytes, _, _ = fast_training
    digest = sha256_bytes(bundle_bytes)
    bundle = load_bundle(bundle_bytes, digest)
    row = snapshot.measurements.iloc[0]
    formula = {item.code: float(row[item.column]) for item in profile.formula.materials}
    context = {item.code: row[item.column] for item in profile.context_features}
    context["substrate"] = "UNKNOWN_SUBSTRATE"
    request = ScoreRequest.model_validate(
        {
            "requestId": "a5-classification-score",
            "taskProfileHash": canonical_sha256(profile),
            "snapshotHash": snapshot.response.snapshot_hash,
            "seed": 20260903,
            "taskProfile": profile.model_dump(mode="json", by_alias=True),
            "modelBundleHash": digest,
            "modelBundle": {"url": "https://example.invalid/a5.zip", "sha256": digest},
            "rows": [{"rowId": "OOD-1", "formula": formula, "context": context}],
            "targetCodes": [BINARY_TARGET, CATEGORICAL_TARGET],
        }
    )
    service = FormulaModelService(Settings(temporary_root=tmp_path))
    service._bundle = lambda _: bundle
    result = service.score(request)
    scored = result.rows[0]
    assert scored.domain_status == "OUT_OF_DOMAIN"
    assert scored.model_usable is False
    assert len(scored.classification_predictions) == 2
    for prediction in scored.classification_predictions:
        assert prediction.applicability_domain.model_usable is False
        assert sum(prediction.class_probabilities.values()) == pytest.approx(1.0)
        assert prediction.predicted_class in prediction.class_probabilities


def test_single_class_and_minority_class_shortage_are_model_not_ready(classification_data):
    source_snapshot, source_profile = classification_data
    for binary_values, expected_reason in [
        (["PASS"] * len(source_snapshot.measurements), "CLASSIFICATION_OBSERVED_CLASSES_LESS_THAN_TWO"),
        (["FAIL"] * 5 + ["PASS"] * (len(source_snapshot.measurements) - 5), "BINARY_MINORITY_CLASS_THRESHOLD_NOT_MET"),
    ]:
        snapshot = copy.copy(source_snapshot)
        measurements = source_snapshot.measurements.copy()
        measurements[BINARY_TARGET] = binary_values
        object.__setattr__(snapshot, "measurements", measurements)
        _, results, _ = ModelTrainer(
            thread_count=1,
            classification_adapters=[FastClassifier(ModelType.LOGISTIC_REGRESSION)],
        ).train(
            snapshot,
            source_profile,
            canonical_sha256(source_profile),
            snapshot.response.snapshot_hash,
            20260903,
        )
        binary = next(item for item in results if item.target_code == BINARY_TARGET)
        assert binary.status == "MODEL_NOT_READY"
        assert expected_reason in binary.reasons


def test_outer_fold_missing_a_class_is_safe_and_calibration_rows_are_isolated(classification_data):
    snapshot, profile = classification_data
    builder = FeatureBuilder(profile)
    features = builder.from_snapshot(snapshot.measurements).iloc[:60].reset_index(drop=True)
    groups = np.repeat(np.arange(6), 10)
    target = np.zeros(60, dtype=int)
    target[:10] = 1
    folds = group_folds(groups, 3)
    evaluation = evaluate_classification_adapter(
        FastClassifier(ModelType.LOGISTIC_REGRESSION),
        features,
        target,
        groups,
        folds,
        builder.layout,
        17,
        ValueType.BINARY,
        ["FAIL", "PASS"],
        "PASS",
        0.5,
        profile.probability_calibration,
    )
    assert np.isfinite(evaluation.calibrated_probabilities).all()
    for calibration_rows, validation_rows in zip(
        evaluation.calibration_training_rows,
        evaluation.validation_rows,
        strict=True,
    ):
        assert set(calibration_rows).isdisjoint(validation_rows)


def test_all_classification_models_share_supplied_outer_fold_rows(classification_data):
    snapshot, profile = classification_data
    builder = FeatureBuilder(profile)
    features = builder.from_snapshot(snapshot.measurements).iloc[:60].reset_index(drop=True)
    target = encode_class_labels(
        snapshot.measurements[BINARY_TARGET].iloc[:60].astype(str).to_numpy(),
        ["FAIL", "PASS"],
    )
    groups = snapshot.joined[profile.validation.group_column].iloc[:60].to_numpy()
    folds = group_folds(groups, 3)
    expected_rows = tuple(
        tuple(int(index) for index in fold.validation) for fold in folds
    )
    for model_type in [
        ModelType.LOGISTIC_REGRESSION,
        ModelType.RANDOM_FOREST,
        ModelType.LIGHTGBM,
        ModelType.XGBOOST,
        ModelType.CATBOOST,
    ]:
        evaluation = evaluate_classification_adapter(
            FastClassifier(model_type),
            features,
            target,
            groups,
            folds,
            builder.layout,
            29,
            ValueType.BINARY,
            ["FAIL", "PASS"],
            "PASS",
            0.5,
            profile.probability_calibration,
        )
        assert evaluation.validation_rows == expected_rows


@pytest.mark.parametrize("adapter", default_classification_adapters(1))
def test_real_classification_adapters_are_reproducible(adapter, classification_data):
    snapshot, profile = classification_data
    builder = FeatureBuilder(profile)
    features = builder.from_snapshot(snapshot.measurements).iloc[:80].reset_index(drop=True)
    values = snapshot.measurements[BINARY_TARGET].iloc[:80].astype(str).to_numpy()
    target = encode_class_labels(values, ["FAIL", "PASS"])
    params = adapter.parameter_candidates()[0]
    first = adapter.fit(
        features, target, builder.layout, 101, params, ["FAIL", "PASS"],
        ValueType.BINARY, "PASS", 0.5, "PLATT"
    ).raw_class_probabilities(features.iloc[:8])
    second = adapter.fit(
        features, target, builder.layout, 101, params, ["FAIL", "PASS"],
        ValueType.BINARY, "PASS", 0.5, "PLATT"
    ).raw_class_probabilities(features.iloc[:8])
    np.testing.assert_allclose(first, second, rtol=0, atol=1e-12)


def test_catboost_uses_native_categories_and_others_use_one_hot():
    adapters = default_classification_adapters(1)
    assert isinstance(adapters[-1], CatBoostClassificationAdapter)
    assert adapters[-1].native_categorical is True
    assert all(not item.native_categorical for item in adapters[:-1])


def _binary_metric(score: float) -> BinaryMetricSet:
    quality = ProbabilityQuality(
        log_loss=score,
        brier_score=min(score / 2, 1.0),
        expected_calibration_error=min(score / 4, 1.0),
    )
    return BinaryMetricSet(
        roc_auc=max(0.0, 1.0 - score / 2),
        pr_auc=max(0.0, 1.0 - score / 2),
        precision=max(0.0, 1.0 - score / 2),
        recall=max(0.0, 1.0 - score / 2),
        f1=max(0.0, 1.0 - score / 2),
        minority_class="FAIL",
        minority_class_recall=max(0.0, 1.0 - score / 2),
        confusion_matrix=[[10, 0], [0, 10]],
        raw_probability_quality=quality,
        calibrated_probability_quality=quality,
        decision_threshold=0.5,
    )


def _categorical_metric(
    macro_f1: float,
    balanced_accuracy: float,
    calibrated_log_loss: float,
) -> CategoricalMetricSet:
    classes = ["NO_DEFECT", "CRACK", "BLISTER", "PEEL"]
    quality = ProbabilityQuality(
        log_loss=calibrated_log_loss,
        expected_calibration_error=0.0,
    )
    return CategoricalMetricSet(
        macro_f1=macro_f1,
        weighted_f1=macro_f1,
        balanced_accuracy=balanced_accuracy,
        per_class={
            label: PerClassMetric(
                precision=macro_f1,
                recall=balanced_accuracy,
                f1=macro_f1,
                support=10,
            )
            for label in classes
        },
        confusion_matrix=[
            [10 if row == column else 0 for column in range(4)]
            for row in range(4)
        ],
        raw_probability_quality=quality,
        calibrated_probability_quality=quality,
    )


def test_categorical_selection_score_direction_normalization_and_weights():
    metrics = _categorical_metric(
        macro_f1=0.60,
        balanced_accuracy=0.50,
        calibrated_log_loss=math.log(4.0) * 0.20,
    )
    expected = 0.40 * 0.40 + 0.25 * 0.50 + 0.35 * 0.20
    assert classification_selection_score(metrics) == pytest.approx(expected)

    improved = _categorical_metric(
        macro_f1=0.70,
        balanced_accuracy=0.60,
        calibrated_log_loss=math.log(4.0) * 0.10,
    )
    assert classification_selection_score(improved) < classification_selection_score(metrics)


def test_categorical_golden_scores_and_tie_candidate_set_are_exact():
    golden = {
        ModelType.LOGISTIC_REGRESSION: (
            (0.48910881812466755, 0.5262193946404472, 0.7295797636488293),
            (0.46671982987772465, 0.4831158424908425, 0.8693473781292503),
        ),
        ModelType.RANDOM_FOREST: (
            (0.4705939639021314, 0.5001204935415462, 0.8081898519668850),
            (0.4312815207552050, 0.4593695777906304, 0.8488791541017839),
        ),
        ModelType.LIGHTGBM: (
            (0.4859077500670421, 0.5097358781569308, 0.7703831939101354),
            (0.44615940317583813, 0.44986866203971465, 0.7791712390610603),
        ),
        ModelType.XGBOOST: (
            (0.4513642939859255, 0.48592635434740694, 0.7741027698035390),
            (0.4564785373608903, 0.46177342394447657, 0.7745445259474589),
        ),
        ModelType.CATBOOST: (
            (0.4449239155121508, 0.49599961442066703, 0.7804232967707868),
            (0.4319130827812331, 0.44156966936572195, 0.7438566342260865),
        ),
    }
    candidates = [
        CandidateModelMetric(
            model_type=model_type,
            status="ELIGIBLE",
            lineage_cv=_categorical_metric(*metrics[0]),
            sheet_cv=_categorical_metric(*metrics[1]),
        )
        for model_type, metrics in golden.items()
    ]
    scores = {
        candidate.model_type: max(
            classification_selection_score(candidate.lineage_cv),
            classification_selection_score(candidate.sheet_cv),
        )
        for candidate in candidates
    }
    assert scores == pytest.approx(
        {
            ModelType.LOGISTIC_REGRESSION: 0.5620186588926557,
            ModelType.RANDOM_FOREST: 0.5769629027891763,
            ModelType.LIGHTGBM: 0.5557877076741587,
            ModelType.XGBOOST: 0.5475157497126408,
            ModelType.CATBOOST: 0.5546450480788842,
        }
    )
    best_score = min(scores.values())
    near_tie = {
        model_type
        for model_type, score in scores.items()
        if score - best_score < 0.01
    }
    assert near_tie == {
        ModelType.LIGHTGBM,
        ModelType.XGBOOST,
        ModelType.CATBOOST,
    }
    assert scores[ModelType.LOGISTIC_REGRESSION] - best_score == pytest.approx(
        0.014502909180014845
    )

    selection = select_classification_champion(
        candidates,
        ValueType.CATEGORICAL,
        ClassificationModelSelectionPolicy(tie_score_tolerance=0.01),
    )
    assert selection.champion_model == ModelType.XGBOOST
    assert "diagnostic-only" in selection.selection_reason


def test_categorical_approximate_tolerance_cannot_override_lower_score():
    best = CandidateModelMetric(
        model_type=ModelType.XGBOOST,
        status="ELIGIBLE",
        lineage_cv=_categorical_metric(1.0, 1.0, 0.0),
        sheet_cv=_categorical_metric(1.0, 1.0, 0.0),
    )
    at_boundary = CandidateModelMetric(
        model_type=ModelType.CATBOOST,
        status="ELIGIBLE",
        lineage_cv=_categorical_metric(0.975, 1.0, 0.0),
        sheet_cv=_categorical_metric(0.975, 1.0, 0.0),
    )
    inside = CandidateModelMetric(
        model_type=ModelType.CATBOOST,
        status="ELIGIBLE",
        lineage_cv=_categorical_metric(0.97525, 1.0, 0.0),
        sheet_cv=_categorical_metric(0.97525, 1.0, 0.0),
    )
    policy = ClassificationModelSelectionPolicy(tie_score_tolerance=0.01)

    boundary_score = classification_selection_score(at_boundary.lineage_cv)
    assert boundary_score >= policy.tie_score_tolerance
    assert (
        select_classification_champion(
            [best, at_boundary], ValueType.CATEGORICAL, policy
        ).champion_model
        == ModelType.XGBOOST
    )
    assert classification_selection_score(inside.lineage_cv) < policy.tie_score_tolerance
    assert (
        select_classification_champion(
            [best, inside], ValueType.CATEGORICAL, policy
        ).champion_model
        == ModelType.XGBOOST
    )


def _exact_tie_candidate(
    model_type: ModelType,
    metric: CategoricalMetricSet,
    *,
    lineage_fold_scores: list[float],
    sheet_fold_scores: list[float],
    training_time: float,
    prediction_time: float,
) -> CandidateModelMetric:
    return CandidateModelMetric(
        model_type=model_type,
        status="ELIGIBLE",
        lineage_cv=metric,
        sheet_cv=metric,
        lineage_fold_scores=lineage_fold_scores,
        sheet_fold_scores=sheet_fold_scores,
        training_time_seconds=training_time,
        prediction_time_seconds=prediction_time,
    )


def test_categorical_exact_tie_prefers_dual_cv_stability_before_model_order():
    metric = _categorical_metric(0.75, 1.0, 0.0)
    stable_logistic = _exact_tie_candidate(
        ModelType.LOGISTIC_REGRESSION,
        metric,
        lineage_fold_scores=[0.25, 0.25, 0.25],
        sheet_fold_scores=[0.25, 0.25, 0.25],
        training_time=2.0,
        prediction_time=0.1,
    )
    unstable_catboost = _exact_tie_candidate(
        ModelType.CATBOOST,
        metric,
        lineage_fold_scores=[0.10, 0.25, 0.40],
        sheet_fold_scores=[0.10, 0.25, 0.40],
        training_time=1.0,
        prediction_time=0.1,
    )
    selected = select_classification_champion(
        [unstable_catboost, stable_logistic],
        ValueType.CATEGORICAL,
        ClassificationModelSelectionPolicy(),
    )
    assert selected.champion_model == ModelType.LOGISTIC_REGRESSION
    assert "dual-CV fold stability" in selected.selection_reason


def test_categorical_exact_tie_prefers_probability_quality_then_compute_cost():
    score = 0.10
    probability_worse = _categorical_metric(
        1.0,
        1.0,
        score * math.log(4.0) / 0.35,
    )
    probability_better = _categorical_metric(0.75, 1.0, 0.0)
    folds = [score, score, score]
    logistic = _exact_tie_candidate(
        ModelType.LOGISTIC_REGRESSION,
        probability_better,
        lineage_fold_scores=folds,
        sheet_fold_scores=folds,
        training_time=20.0,
        prediction_time=1.0,
    )
    catboost = _exact_tie_candidate(
        ModelType.CATBOOST,
        probability_worse,
        lineage_fold_scores=folds,
        sheet_fold_scores=folds,
        training_time=1.0,
        prediction_time=0.1,
    )
    assert classification_selection_score(logistic.lineage_cv) == pytest.approx(score)
    assert classification_selection_score(catboost.lineage_cv) == pytest.approx(score)
    selected = select_classification_champion(
        [catboost, logistic],
        ValueType.CATEGORICAL,
        ClassificationModelSelectionPolicy(),
    )
    assert selected.champion_model == ModelType.LOGISTIC_REGRESSION

    same_quality_catboost = _exact_tie_candidate(
        ModelType.CATBOOST,
        probability_better,
        lineage_fold_scores=folds,
        sheet_fold_scores=folds,
        training_time=20.0,
        prediction_time=1.0,
    )
    cheaper_logistic = _exact_tie_candidate(
        ModelType.LOGISTIC_REGRESSION,
        probability_better,
        lineage_fold_scores=folds,
        sheet_fold_scores=folds,
        training_time=1.0,
        prediction_time=0.1,
    )
    selected = select_classification_champion(
        [same_quality_catboost, cheaper_logistic],
        ValueType.CATEGORICAL,
        ClassificationModelSelectionPolicy(),
    )
    assert selected.champion_model == ModelType.LOGISTIC_REGRESSION


def test_categorical_model_order_is_only_the_final_exact_tie_break():
    metric = _categorical_metric(0.75, 1.0, 0.0)
    folds = [0.10, 0.10, 0.10]
    logistic = _exact_tie_candidate(
        ModelType.LOGISTIC_REGRESSION,
        metric,
        lineage_fold_scores=folds,
        sheet_fold_scores=folds,
        training_time=1.0,
        prediction_time=0.1,
    )
    catboost = _exact_tie_candidate(
        ModelType.CATBOOST,
        metric,
        lineage_fold_scores=folds,
        sheet_fold_scores=folds,
        training_time=1.0,
        prediction_time=0.1,
    )
    selected = select_classification_champion(
        [logistic, catboost],
        ValueType.CATEGORICAL,
        ClassificationModelSelectionPolicy(),
    )
    assert selected.champion_model == ModelType.CATBOOST
    assert "then deterministic model tie-break order" in selected.selection_reason


@pytest.mark.parametrize(
    "winner",
    [
        ModelType.LOGISTIC_REGRESSION,
        ModelType.RANDOM_FOREST,
        ModelType.LIGHTGBM,
        ModelType.XGBOOST,
        ModelType.CATBOOST,
    ],
)
def test_champion_selection_can_choose_every_classifier(winner):
    model_types = [
        ModelType.LOGISTIC_REGRESSION,
        ModelType.RANDOM_FOREST,
        ModelType.LIGHTGBM,
        ModelType.XGBOOST,
        ModelType.CATBOOST,
    ]
    candidates = [
        CandidateModelMetric(
            model_type=item,
            status="ELIGIBLE",
            lineage_cv=_binary_metric(0.10 if item == winner else 0.40),
            sheet_cv=_binary_metric(0.10 if item == winner else 0.40),
        )
        for item in model_types
    ]
    selection = select_classification_champion(
        candidates,
        ValueType.BINARY,
        ClassificationModelSelectionPolicy(tie_score_tolerance=0.001),
    )
    assert selection.champion_model == winner


def test_failed_challenger_does_not_block_other_classifiers(classification_data):
    snapshot, profile = classification_data
    trainer = ModelTrainer(
        thread_count=1,
        classification_adapters=[
            FailingClassifier(ModelType.XGBOOST),
            FastClassifier(ModelType.LOGISTIC_REGRESSION),
        ],
    )
    _, results, _ = trainer.train(
        snapshot,
        profile,
        canonical_sha256(profile),
        snapshot.response.snapshot_hash,
        20260903,
    )
    assert all(item.status == "READY" for item in results)
    assert all(ModelType.XGBOOST in item.model_selection.failed_models for item in results)
    assert all(item.model_selection.champion_model == ModelType.LOGISTIC_REGRESSION for item in results)
