
import hashlib
import io
import json
import logging
import zipfile
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

import httpx
import joblib
import numpy as np
import pandas as pd
from scipy.stats import norm, spearmanr
from sklearn.base import clone
from sklearn.gaussian_process import GaussianProcessRegressor
from sklearn.gaussian_process.kernels import Matern, WhiteKernel
from sklearn.metrics import accuracy_score, f1_score, mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import GroupKFold, KFold, StratifiedGroupKFold, StratifiedKFold
from sklearn.pipeline import Pipeline
from baybe.campaign import Campaign
from baybe.parameters import NumericalDiscreteParameter
from baybe.recommenders import BotorchRecommender
from baybe.searchspace import SearchSpace
from baybe.settings import Settings as BaybeSettings
from baybe.surrogates import GaussianProcessSurrogate
from baybe.targets import NumericalTarget

from jsd_aird_ai.contracts_v2 import ApplicabilityV2, BinaryResultV2, CategoricalResultV2, ContinuousResultV2, DomainStatusV2, OrdinalResultV2, ScoreRequestV2, ScoreResponseV2, TargetBlockedV2, TargetFailedV2, TargetSuccessV2, ValueTypeV2, FormulaV2, FormulaComponentV2, RecommendRequestV2, RecommendResponseV2, RecommendedCandidateV2
from jsd_aird_ai.errors import ErrorCode, FormulaModelError
from jsd_aird_ai.settings import Settings
from jsd_aird_ai.workbench_training import candidates as workbench_candidates, fitted_metrics, prepare_data


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()






class FormulaModelV2Service:
    """Runtime for the independent, versioned formula-model.v2 training contract."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings




    def train_workbench(self, request: dict[str, Any]) -> dict[str, Any]:
        """Train an exploratory model directly from selected table columns."""
        request_id = str(request.get("requestId") or "")
        x_columns = request.get("xColumns")
        y_column = request.get("yColumn")
        rows = request.get("rows")
        output = request.get("output")
        if not request_id or not isinstance(x_columns, list) or not x_columns or not isinstance(y_column, dict):
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "请至少选择一列 X 和一列 Y")
        if not isinstance(rows, list) or len(rows) > 20000:
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "本次数据量超出单次建模范围，请缩小数据集")
        x_codes = [str(column.get("code") or "") for column in x_columns]
        y_code = str(y_column.get("code") or "")
        if any(not code for code in x_codes) or len(set(x_codes)) != len(x_codes) or y_code in x_codes:
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "X/Y 列选择无效，请重新选择")
        if not isinstance(output, str) or not output:
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "模型文件保存地址无效")

        numeric_y = str(y_column.get("kind") or "").upper() == "NUMERIC"
        target_type = ValueTypeV2.CONTINUOUS if numeric_y else ValueTypeV2.CATEGORICAL
        # Older frozen requests contain only complete rows. New requests also retain
        # all selected rows so exclusions can be explained without rereading live data.
        selected_rows = request.get("selectedRows", rows)
        if not isinstance(selected_rows, list) or len(selected_rows) > 20000:
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "本次数据量超出单次建模范围，请缩小数据集")
        feature_rows, target, checks = prepare_data(x_columns, y_column, selected_rows)
        sample_count = len(target)
        if sample_count < 6:
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, f"X/Y 组合只有 {sample_count} 条完整记录，至少需要 6 条")
        if all(field["distinctCount"] < 2 for field in checks["fields"][:-1]):
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "所选 X 在完整记录中都没有变化，请选择有不同取值的输入列")
        feature_count = sum(1 if column["kind"] == "NUMERIC" else field["distinctCount"]
                            for column, field in zip(x_columns, checks["fields"][:-1]))
        if feature_count * sample_count > 20_000_000:
            categorical = sorted([(field["distinctCount"], field["name"]) for column, field in zip(x_columns, checks["fields"][:-1])
                                  if column["kind"] != "NUMERIC"], reverse=True)
            detail = "、".join(f"{name}（{count} 种类别）" for count, name in categorical[:3])
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA,
                f"类别展开后有 {feature_count} 个输入项、{sample_count} 条记录，超出本次训练可处理的规模。"
                f"请减少勾选记录，或取消类别过多的 X：{detail}")
        classes: list[str] = []
        if numeric_y:
            if np.unique(target).size < 2:
                raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "Y 列只有一种结果，请选择有不同实测值的结果列")
        else:
            labels, counts = np.unique(target.astype(str), return_counts=True)
            if len(labels) < 2:
                raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "Y 列只有一种结果，无法训练分类模型")
            if int(counts.min()) < 2:
                raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "Y 列中每种结果至少需要 2 条记录")
            classes = labels.tolist()

        seed = int(request.get("seed") or 17)
        fold_count = min(5, sample_count // 2) if numeric_y else min(5, int(counts.min()))
        # Repeated inputs belong to one fold, including replicates with different Y,
        # so seeing the same input during training cannot inflate validation scores.
        group_keys = [tuple(sorted(row.items())) for row in feature_rows]
        group_ids = {key: index for index, key in enumerate(dict.fromkeys(group_keys))}
        groups = np.asarray([group_ids[key] for key in group_keys])
        grouped = len(group_ids) < sample_count
        if grouped:
            fold_count = min(fold_count, len(group_ids))
            splitter = (GroupKFold(n_splits=fold_count, shuffle=True, random_state=seed) if numeric_y else
                        StratifiedGroupKFold(n_splits=fold_count, shuffle=True, random_state=seed))
            splits = splitter.split(feature_rows, target, groups)
        else:
            splitter = (KFold(n_splits=fold_count, shuffle=True, random_state=seed) if numeric_y else
                        StratifiedKFold(n_splits=fold_count, shuffle=True, random_state=seed))
            splits = splitter.split(feature_rows, target)
        folds = np.empty(sample_count, dtype=int)
        for fold, (_, valid) in enumerate(splits):
            folds[valid] = fold
        minimum_train_count = min(int(np.sum(folds != fold)) for fold in np.unique(folds))
        candidates = workbench_candidates(numeric_y, sample_count, minimum_train_count, seed, self.settings.model_threads, feature_count)
        results: list[tuple[float, str, Any, dict[str, Any], dict[str, Any], str]] = []
        failures = []
        for index, (name, params, estimator) in enumerate(candidates):
            candidate_id = f"{name}:{index}"
            try:
                metrics, loss = self._cross_validate(estimator, feature_rows, target, folds, target_type, classes)
                if not np.isfinite(loss) or any(not np.isfinite(value) for value in metrics.values() if isinstance(value, (float, int))):
                    raise ValueError("non-finite validation metrics")
                fitted = clone(estimator).fit(feature_rows, target)
                metrics["trainingMetrics"] = fitted_metrics(fitted, feature_rows, target, numeric_y, classes)
                results.append((loss, name, fitted, metrics, params, candidate_id))
            except Exception as exc:
                logging.getLogger(__name__).warning("Workbench candidate %s failed: %s", candidate_id, type(exc).__name__)
                failures.append(dict(candidateId=candidate_id, algorithm=name, errorType=type(exc).__name__))
        if not results:
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "所选数据无法完成模型训练；请检查类别数量或更换 X/Y 列")
        results.sort(key=lambda item: (item[0], item[1], item[5]))
        _, selected_algorithm, fitted, selected_metrics, _, selected_id = results[0]
        # Preserve the original, unscaled feature ranges used by registration and
        # prediction forms. The remaining fitted pipeline travels with the model.
        vectorizer = fitted.named_steps["vectorizer"]
        features = vectorizer.transform(feature_rows)
        model = Pipeline(fitted.steps[1:])
        metrics = dict(selected_metrics)
        metrics.update(selectedAlgorithm=selected_algorithm, selectedCandidateId=selected_id,
                       selectionMetric="mae" if numeric_y else "logLoss", selectionDirection="MINIMIZE",
                       candidateCount=len(results), validationFolds=fold_count,
                       validationGrouping="IDENTICAL_X" if grouped else "ROWS",
                       baselineMetrics=self._baseline_metrics(target, folds, target_type, classes),
                       dataChecks=checks, failedCandidates=failures,
                       preprocessing=dict(numericScaling="STANDARD", categoricalEncoding="ONE_HOT", fitScope="TRAIN_FOLD"))
        metrics["comparison"] = [dict(algorithm=name, candidateId=candidate_id, parameters=params, loss=loss, **item_metrics)
                                 for loss, name, _, item_metrics, params, candidate_id in results]
        domain = self._domain(features, vectorizer.get_feature_names_out().tolist())

        model_payload = io.BytesIO()
        joblib.dump({
            "contractVersion": "modeling-workbench.v1",
            "xColumns": x_columns,
            "yColumn": y_column,
            "valueType": str(target_type),
            "classes": classes,
            "vectorizer": vectorizer,
            "model": model,
            "metrics": metrics,
            "applicabilityDomain": domain,
        }, model_payload)
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
            bundle.writestr("model.joblib", model_payload.getvalue())
            bundle.writestr("model-card.json", json.dumps({
                "requestId": request_id,
                "xColumns": x_columns,
                "yColumn": y_column,
                "rowCount": sample_count,
                "metrics": metrics,
                "applicabilityDomain": domain,
            }, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
        bundle_bytes = archive.getvalue()
        self._write(output, bundle_bytes)
        return {
            "requestId": request_id,
            "status": "SUCCEEDED",
            "rowCount": sample_count,
            "excludedRowCount": checks["excludedRowCount"],
            "metrics": metrics,
            "applicabilityDomain": domain,
            "modelBundle": {"url": output, "sha256": _sha(bundle_bytes)},
        }

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
                if bundle.get("contractVersion") != "modeling-workbench.v1":
                    raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "请选择建模工具注册的模型")
                outcomes.append(self._score_workbench(binding, bundle, request.inputs))
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

    def _score_workbench(self, binding: Any, bundle: dict[str, Any], inputs: dict[str, Any]) -> Any:
        columns = bundle.get("xColumns", [])
        fields = binding.input_scheme.fields
        y = bundle.get("yColumn", {})
        if ([(c["code"], c.get("unit"), "NUMBER" if c["kind"] == "NUMERIC" else "CATEGORY") for c in columns]
                != [(f.code, f.unit, f.value_type) for f in fields]
                or bundle.get("valueType") != str(binding.target.value_type)
                or y.get("unit") != binding.target.unit
                or bundle.get("classes", []) != binding.target.classes):
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "模型字段与注册信息不一致，请在模型中心检查此版本")
        vectorizer = bundle["vectorizer"]
        names = vectorizer.get_feature_names_out().tolist()
        record: dict[str, Any] = {}
        for column in columns:
            code = column["code"]
            name = column.get("name", code)
            value = inputs.get(code)
            problem = None
            if value is None or isinstance(value, str) and not value.strip():
                problem = ("MISSING_REQUIRED_X", f"请填写{name}")
            elif column["kind"] == "NUMERIC":
                if isinstance(value, bool) or not isinstance(value, (int, float)) or not np.isfinite(value):
                    problem = ("INVALID_X_VALUE", f"{name}需要填写有效数字")
                else:
                    record[code] = float(value)
            else:
                text = str(value).strip()
                if f"{code}={text}" not in names:
                    problem = ("UNKNOWN_CATEGORY", f"{name}没有“{text}”的训练记录，请选择已有类别")
                else:
                    record[code] = text
            if problem:
                return TargetBlockedV2(target_id=binding.target.id, model_version_id=binding.model_version_id,
                                       code=problem[0], message=problem[1], detail={"fieldCode": code, "fieldName": name})
        vector = vectorizer.transform([record])
        applicability = self._applicability(vector[0], names, binding.applicability_domain,
                                             bundle["applicabilityDomain"])
        return TargetSuccessV2(target_id=binding.target.id, model_version_id=binding.model_version_id,
            result=self._typed_result(binding, bundle, vector), applicability=applicability,
            warnings=[{"code": "EXTRAPOLATION", "message": "超出历史实验范围，结果需要实验验证"}]
            if applicability.status == DomainStatusV2.OUT_OF_DOMAIN else [])

    def recommend(self, request: RecommendRequestV2) -> RecommendResponseV2:
        """Deterministic, model-only formula search for the V2 boundary.

        BayBE is intentionally kept behind this service boundary.  The
        controlled pool is deterministic and honours the same frozen model
        bindings and feature builder as scoring, which makes it safe for the
        local acceptance environment when an optimizer is unavailable.
        """
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
        # Leave room to reject candidates for class/probability goals as well as
        # numeric goals; the requested result count is not a search budget.
        pool_size = max(request.candidate_count * 8, 32)
        formulas = self._formula_pool(baseline, total, pool_size, request.seed, request.search_space)
        # BayBE's discrete parameter requires at least two values.  A narrow
        # rule-constrained space may legitimately contain only one formula;
        # evaluate it deterministically instead of turning a valid request
        # into an optimizer error.
        if engine == "BAYBE" and len(formulas) < 2:
            engine, strategy = "DETERMINISTIC_CANDIDATE_POOL", "DISCRETE_DETERMINISTIC_POOL"
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
                    score_inputs = self._formula_inputs(request, formula, candidate_inputs)
                    scored = self.score(ScoreRequestV2(
                        request_id=f"{request.request_id}:score:{index}", seed=request.seed,
                        run_id=request.run_id, formula=formula, inputs=score_inputs,
                        model_bindings=request.model_bindings, allow_extrapolation=True))
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
                score_inputs = self._formula_inputs(request, formula, candidate_inputs)
                scored = self.score(ScoreRequestV2(request_id=f"{request.request_id}:score:{index}", seed=request.seed,
                                                    run_id=request.run_id, formula=formula, inputs=score_inputs,
                                                    model_bindings=request.model_bindings, allow_extrapolation=True))
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
                                       execution_status="FAILED", outcome_status=None,
                                       candidates=[], blocked_targets=[], warnings=[],
                                       code=("MODEL_ARTIFACT_INVALID" if first_failure_code in {"INVALID_SNAPSHOT", "HASH_MISMATCH", "UNSUPPORTED_CONTRACT", "MODEL_LOAD_FAILED"} else "COMPUTE_UNAVAILABLE"),
                                       message=first_failure_message or "模型制品或计算服务不可用",
                                       search_engine=engine, search_strategy=strategy,
                                       rejection_summary=rejected,
                                       search_evidence={"poolSize": len(formulas), "proposedCount": proposed_count,
                                                        "scoredCount": scored_count, "acceptedCount": len(accepted),
                                                        "algorithm": "BayBE ask-score-tell adaptive campaign" if engine == "BAYBE" else "stable deterministic pool"})
        outcome = "SUCCEEDED" if len(accepted) == request.candidate_count else "PARTIAL" if accepted else "BLOCKED"
        hints=self._rejection_hints(rejected)
        return RecommendResponseV2(request_id=request.request_id, run_id=request.run_id,
                                   execution_status="SUCCEEDED", outcome_status=outcome,
                                   candidates=accepted, blocked_targets=[], warnings=[],
                                   shortfall_reason=None if outcome == "SUCCEEDED" else self._shortfall_reason(rejected),
                                   shortfall_reason_code=None if outcome == "SUCCEEDED" else self._shortfall_code(rejected),
                                   rejection_summary=rejected, action_hints=hints,
                                   search_engine=engine, search_strategy=strategy,
                                   search_evidence={"poolSize": len(formulas),
                                                    "proposedCount": proposed_count,
                                                    "scoredCount": scored_count,
                                                    "acceptedCount": len(accepted),
                                                    "algorithm": "BayBE ask-score-tell adaptive campaign" if engine == "BAYBE" else "stable deterministic pool"})

    def recommend_from_history(self, request: dict[str, Any]) -> dict[str, Any]:
        """Fit a Gaussian process to measured X/Y rows and maximize EI."""
        features = request.get("features")
        rows = request.get("rows")
        target = request.get("target")
        direction = str(request.get("direction", "MAXIMIZE")).upper()
        if not isinstance(features, list) or not features or not isinstance(rows, list) or not isinstance(target, str):
            raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "请选择 X 因素、Y 指标和历史实验数据")
        if direction not in {"MAXIMIZE", "MINIMIZE"}:
            raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "优化方向只能是提高或降低")

        selected_features: list[dict[str, Any]] = []
        for feature in features:
            if not isinstance(feature, dict) or not feature.get("code"):
                raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "X 因素格式不正确")
            kind = str(feature.get("kind", "NUMERIC")).upper()
            if kind not in {"NUMERIC", "CATEGORICAL"}:
                raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "X 因素只支持数值或分类字段")
            selected_features.append({**feature, "kind": kind, "code": str(feature["code"])})

        data: list[dict[str, Any]] = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            try:
                y = float(row[target])
                record: dict[str, Any] = {"__y": y}
                valid = np.isfinite(y)
                for feature in selected_features:
                    code = feature["code"]
                    value = row.get(code)
                    if feature["kind"] == "NUMERIC":
                        value = float(value)
                        valid = valid and np.isfinite(value)
                    else:
                        value = str(value).strip()
                        valid = valid and bool(value)
                    record[code] = value
                if valid:
                    data.append(record)
            except (TypeError, ValueError, OverflowError):
                continue
        if len(data) < 4:
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "至少需要 4 条 X 和 Y 都填写完整的历史实验")

        categories: dict[str, list[str]] = {}
        for feature in selected_features:
            if feature["kind"] == "CATEGORICAL":
                categories[feature["code"]] = sorted({str(row[feature["code"]]) for row in data})
                if len(categories[feature["code"]]) < 2:
                    raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA,
                                            f"X 因素“{feature.get('name', feature['code'])}”只有一个取值，无法比较其影响")

        def bounds(feature: dict[str, Any]) -> tuple[float, float]:
            code = feature["code"]
            values = [float(row[code]) for row in data]
            low = float(feature.get("minimum", min(values)))
            high = float(feature.get("maximum", max(values)))
            if not np.isfinite(low) or not np.isfinite(high) or low >= high:
                raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT,
                                        f"X 因素“{feature.get('name', code)}”的最小值必须小于最大值")
            return low, high

        feature_by_code = {feature["code"]: feature for feature in selected_features}
        composition_groups: list[tuple[list[str], float]] = []
        grouped_codes: set[str] = set()
        raw_groups = request.get("compositionGroups", [])
        if raw_groups is None:
            raw_groups = []
        if not isinstance(raw_groups, list):
            raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "配方比例设置格式不正确")
        for group in raw_groups:
            codes = group.get("codes") if isinstance(group, dict) else None
            try:
                codes = list(dict.fromkeys(str(code) for code in codes))
                total = float(group.get("total"))
            except (TypeError, ValueError, AttributeError):
                raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "配方比例设置格式不正确")
            if len(codes) < 2 or any(code not in feature_by_code or feature_by_code[code]["kind"] != "NUMERIC" for code in codes):
                raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "配方比例设置与所选 X 因素不匹配")
            if grouped_codes.intersection(codes) or not np.isfinite(total):
                raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT, "配方比例设置重复或总量无效")
            limits = [bounds(feature_by_code[code]) for code in codes]
            if total < sum(low for low, _ in limits) - 1e-8 or total > sum(high for _, high in limits) + 1e-8:
                raise FormulaModelError(ErrorCode.UNSUPPORTED_CONTRACT,
                                        "这组配方比例范围无法保持总量一致，请调整比例范围后重试")
            grouped_codes.update(codes)
            composition_groups.append((codes, total))

        def encode(record: dict[str, Any]) -> list[float]:
            vector: list[float] = []
            for feature in selected_features:
                code = feature["code"]
                if feature["kind"] == "NUMERIC":
                    low, high = bounds(feature)
                    vector.append((float(record[code]) - low) / max(high - low, 1e-9))
                else:
                    vector.extend(1.0 if str(record[code]) == label else 0.0 for label in categories[code])
            return vector

        x = np.asarray([encode(row) for row in data], dtype=float)
        observed = np.asarray([row["__y"] for row in data], dtype=float)
        if np.unique(np.round(x, 10), axis=0).shape[0] < 2 or np.std(observed) < 1e-12:
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "历史数据中的 X 或 Y 没有变化，无法学习优化方向")

        seed = int(request.get("seed", 0)) & 0xFFFFFFFF
        rng = np.random.default_rng(seed)
        candidate_count = max(1, min(int(request.get("candidateCount", 4)), 8))
        pool_size = max(2048, min(16384, candidate_count * 1024))
        candidates: list[dict[str, Any]] = []
        attempt_limit = pool_size * (20 if composition_groups else 1)
        for _ in range(attempt_limit):
            candidate: dict[str, Any] = {}
            for feature in selected_features:
                code = feature["code"]
                if code in grouped_codes:
                    continue
                if feature["kind"] == "NUMERIC":
                    low, high = bounds(feature)
                    candidate[code] = float(rng.uniform(low, high))
                else:
                    candidate[code] = str(rng.choice(categories[code]))
            feasible = True
            for codes, total in composition_groups:
                derived_code = max(codes, key=lambda code: bounds(feature_by_code[code])[1] - bounds(feature_by_code[code])[0])
                assigned = 0.0
                for code in codes:
                    if code == derived_code:
                        continue
                    low, high = bounds(feature_by_code[code])
                    value = float(rng.uniform(low, high))
                    candidate[code] = value
                    assigned += value
                low, high = bounds(feature_by_code[derived_code])
                remainder = total - assigned
                if remainder < low - 1e-8 or remainder > high + 1e-8:
                    feasible = False
                    break
                candidate[derived_code] = float(np.clip(remainder, low, high))
            if feasible:
                candidates.append(candidate)
                if len(candidates) >= pool_size:
                    break
        if not candidates:
            raise FormulaModelError(ErrorCode.NO_FEASIBLE_CANDIDATE,
                                    "这组配方比例范围无法生成总量一致的新方案，请调整比例范围")
        encoded_candidates = np.asarray([encode(row) for row in candidates], dtype=float)

        kernel = Matern(length_scale=0.5, nu=2.5) + WhiteKernel(noise_level=1e-5, noise_level_bounds="fixed")
        gp = GaussianProcessRegressor(kernel=kernel, normalize_y=True, alpha=1e-8,
                                      random_state=seed, n_restarts_optimizer=0)
        oriented_y = observed if direction == "MAXIMIZE" else -observed
        gp.fit(x, oriented_y)
        mean, std = gp.predict(encoded_candidates, return_std=True)
        improvement = mean - float(np.max(oriented_y)) - 0.01 * max(float(np.std(observed)), 1e-9)
        z = np.divide(improvement, std, out=np.zeros_like(improvement), where=std > 1e-12)
        ei = improvement * norm.cdf(z) + std * norm.pdf(z)

        ranked = np.argsort(-ei)
        selected: list[dict[str, Any]] = []
        selected_vectors: list[np.ndarray] = []
        for index in ranked:
            vector = encoded_candidates[index]
            # Reject practically unchanged designs as well as exact repeats.
            # Use the same normalized spacing as the new batch itself.
            if np.any(np.linalg.norm(x - vector, axis=1) < 0.025):
                continue
            if any(np.linalg.norm(vector - prior) < 0.025 for prior in selected_vectors):
                continue
            selected_vectors.append(vector)
            selected.append({
                "values": candidates[index],
                "predictedValue": float(mean[index] if direction == "MAXIMIZE" else -mean[index]),
                "uncertainty": float(std[index]),
                "expectedImprovement": float(max(ei[index], 0.0)),
            })
            if len(selected) >= candidate_count:
                break
        if not selected:
            raise FormulaModelError(ErrorCode.NO_FEASIBLE_CANDIDATE, "当前搜索范围内没有新的实验组合")
        return {
            "status": "READY" if len(selected) == candidate_count else "PARTIAL",
            "candidates": selected,
            "searchEngine": "GAUSSIAN_PROCESS_EI",
            "searchEvidence": {
                "trainingRowCount": len(data), "featureCount": len(selected_features),
                "kernel": "Matern 5/2 + WhiteKernel", "acquisition": "Expected Improvement",
                "bestObservedValue": float(max(observed) if direction == "MAXIMIZE" else min(observed)),
                "candidatePoolSize": pool_size,
            },
        }

    def _search_engine(self, request: RecommendRequestV2) -> tuple[str, str]:
        """Select the search implementation from the frozen Y semantics."""
        has_continuous = any(binding.target.value_type == ValueTypeV2.CONTINUOUS
                             for binding in request.model_bindings)
        return ("BAYBE", "CONTINUOUS_BAYBE") if has_continuous else (
            "DETERMINISTIC_CANDIDATE_POOL", "DISCRETE_DETERMINISTIC_POOL")






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
        rng = np.random.default_rng(int(seed) & 0xFFFFFFFF)
        for i in range(attempts):
            ratios = [float(c.ratio or 0) for c in components]
            fixed_total = sum(ratios[index] for index in range(len(ratios)) if index not in variable_indexes)
            variable_total = total - fixed_total
            # Include each material's feasible extremes, then sample every
            # free dimension. A midpoint complement for later materials
            # incorrectly restricted a three-material space to one line.
            extreme = i < 2 * len(variable_indexes)
            if extreme:
                first = variable_indexes[i // 2]
                order = [first] + [index for index in variable_indexes if index != first]
            else:
                order = [int(index) for index in rng.permutation(variable_indexes)]
            remaining_total = variable_total
            for position, index in enumerate(order):
                low, high = bounds.get(components[index].material_id, (0.0, variable_total))
                later_min = sum(bounds.get(components[j].material_id, (0.0, variable_total))[0]
                                for j in order[position + 1:])
                later_max = sum(bounds.get(components[j].material_id, (0.0, variable_total))[1]
                                for j in order[position + 1:])
                feasible_low = max(low, remaining_total - later_max)
                feasible_high = min(high, remaining_total - later_min)
                if feasible_high < feasible_low - 1e-8:
                    break
                fraction = float(i % 2) if extreme and position == 0 else float(rng.random())
                assigned = remaining_total if position == len(order) - 1 else feasible_low + (feasible_high - feasible_low) * fraction
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
    def _formula_inputs(request: RecommendRequestV2, formula: FormulaV2, conditions: dict[str, Any]) -> dict[str, Any]:
        """Resolve candidate ratios into original X codes without changing performance prediction."""
        inputs = dict(conditions)
        ratios = {component.material_id: component.ratio for component in formula.components}
        mappings: dict[str, str] = {}
        for binding in request.model_bindings:
            known = {material.material_id for material in binding.material_dictionary.materials}
            for field in binding.input_scheme.fields:
                material_id = field.encoding.get("formulaMaterialId")
                if material_id is None:
                    continue
                if material_id not in known or field.code != f"formula:{material_id}" or field.value_type != "NUMBER":
                    raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "模型的材料比例关联不一致，请检查模型输入")
                if material_id not in ratios or ratios[material_id] is None:
                    raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, f"候选配方缺少材料比例：{field.name or field.code}")
                if field.code in mappings and mappings[field.code] != material_id:
                    raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "所选模型的材料比例关联冲突")
                mappings[field.code] = material_id
                inputs[field.code] = float(ratios[material_id])
        if mappings and (not formula.composition_complete or len(ratios) != len(formula.components)
                         or any(value is None or not np.isfinite(value) or value < 0 for value in ratios.values())
                         or abs(sum(float(value) for value in ratios.values()) - 100) > 1e-4):
            raise FormulaModelError(ErrorCode.INSUFFICIENT_DATA, "候选配方的材料比例需完整、非负且合计为 100%")
        return inputs

    @staticmethod
    def _candidate_inputs(request: RecommendRequestV2, index: int, count: int) -> dict[str, Any]:
        values=dict(request.fixed_inputs)
        variables=request.search_space.get("variableInputs") or {}
        for position,(code,spec) in enumerate(sorted(variables.items())):
            if not isinstance(spec,dict): continue
            minimum=spec.get("minimum");maximum=spec.get("maximum")
            if minimum is None or maximum is None: continue
            fraction = float(np.random.default_rng(np.random.SeedSequence([
                int(request.seed) & 0xFFFFFFFF, index, position, 20261015])).random()) if count > 1 else 0.5
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
                # The goal is compared with the predicted value shown to the
                # user. The prediction interval is uncertainty context, not a
                # second hidden pass/fail threshold.
                if goal.operator == "AT_LEAST" and result.value < float(value): return False
                if goal.operator == "AT_MOST" and result.value > float(value): return False
                if goal.operator == "RANGE":
                    lo, hi = float(value[0]), float(value[1])
                    if result.value < lo or result.value > hi: return False
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
        weighted_score = 0.0
        total_weight = 0.0
        for index, goal in enumerate(goals):
            result = next((item.result for item in successes if item.target_id == goal.target_id), None)
            if result is None and index < len(successes):
                result = successes[index].result
            if result is None: continue
            weight = max(float(goal.weight), 0.0)
            if isinstance(result, ContinuousResultV2):
                value = float(result.value)
                reference = float(goal.value) if isinstance(goal.value, (int, float)) else value
                scale = max(abs(reference), abs(float(result.upper) - float(result.lower)), 1e-9)
                if goal.operator in {"MINIMIZE", "AT_MOST"}:
                    margin = reference - value
                elif goal.operator in {"MAXIMIZE", "AT_LEAST"}:
                    margin = value - reference
                elif goal.operator == "RANGE" and isinstance(goal.value, (list, tuple)) and len(goal.value) == 2:
                    lower, upper = float(goal.value[0]), float(goal.value[1])
                    margin = -max(lower - value, value - upper, 0.0)
                elif goal.operator == "MATCH" and isinstance(goal.value, dict):
                    desired = float(goal.value.get("target", value))
                    tolerance = max(float(goal.value.get("tolerance", 0.0)), 0.0)
                    margin = tolerance - abs(value - desired)
                else:
                    margin = 0.0
                utility = 0.5 + 0.5 * max(-1.0, min(1.0, margin / scale))
            elif hasattr(result, "probabilities") or isinstance(result, BinaryResultV2):
                desired = goal.value.get("label") if isinstance(goal.value, dict) else str(goal.value)
                if isinstance(result, BinaryResultV2):
                    probability = result.probability if desired == result.positive_class else 1.0 - result.probability
                else:
                    probability = float(result.probabilities.get(desired, 0.0))
                utility = max(0.0, min(1.0, probability))
            else:
                utility = max(0.0, min(1.0, float(getattr(result, "probability", 0.0))))
            weighted_score += utility * weight
            total_weight += weight
        return weighted_score / total_weight if total_weight else 0.0

    def _load_bundle(self, data: bytes) -> dict[str, Any]:
        try:
            with zipfile.ZipFile(io.BytesIO(data), "r") as archive:
                payload_name = "model.joblib"
                if payload_name not in archive.namelist() or "model-card.json" not in archive.namelist():
                    raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "模型制品缺少必需文件")
                payload = joblib.load(io.BytesIO(archive.read(payload_name)))
        except FormulaModelError:
            raise
        except Exception as exc:
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "模型制品无法加载") from exc
        if not isinstance(payload, dict):
            raise FormulaModelError(ErrorCode.INVALID_SNAPSHOT, "模型制品内容无效")
        return payload



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
