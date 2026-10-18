import io
import zipfile
from pathlib import Path

import joblib
import numpy as np
import pytest
from sklearn.base import clone
from sklearn.linear_model import Ridge

import jsd_aird_ai.service_v2 as runtime
from jsd_aird_ai.service_v2 import FormulaModelV2Service
from jsd_aird_ai.settings import Settings
from jsd_aird_ai.workbench_training import candidates, checked_value, prepare_data


def request(tmp_path: Path, rows: list[dict], columns: list[dict] | None = None) -> dict:
    return dict(requestId="workbench", seed=17, xColumns=columns or [dict(code="x", name="固含", kind="NUMERIC")],
                yColumn=dict(code="y", name="耐磨次数", kind="NUMERIC", unit="次"), rows=rows,
                output=(tmp_path / "model.zip").as_uri())


def service() -> FormulaModelV2Service:
    return FormulaModelV2Service(Settings(allow_file_urls=True, model_threads=1))


def load(tmp_path: Path) -> dict:
    with zipfile.ZipFile(tmp_path / "model.zip") as bundle:
        return joblib.load(io.BytesIO(bundle.read("model.joblib")))


def test_diagnostics_keep_exclusions_field_names_constants_and_conflicting_records():
    columns = [dict(code="x", name="固含", kind="NUMERIC"), dict(code="substrate", name="基材", kind="CATEGORICAL")]
    rows = [dict(label=f"涂层 {i}", values=dict(x=i, substrate=" PET ", y=i * 2)) for i in range(6)]
    rows += [dict(label="补充配方", values=dict(x="0.0", substrate="PET", y=7)),
             dict(values=dict(x=None, substrate="PET", y=1)), dict(values=dict(x=8, substrate="PET", y="abc"))]
    x, y, checks = prepare_data(columns, dict(code="y", kind="NUMERIC"), rows)
    assert len(x) == len(y) == 7
    assert checks["excludedRowCount"] == 2
    assert checks["fields"][0]["missingCount"] == 1
    assert checks["fields"][2]["invalidCount"] == 1
    assert checks["constantXColumns"] == [dict(code="substrate", name="基材")]
    assert checks["conflictingInputGroupCount"] == 1
    assert checks["conflictingExamples"][0]["results"] == [0.0, 7.0]
    assert checks["conflictingExamples"][0]["records"][1]["label"] == "补充配方"


@pytest.mark.parametrize("value", [True, [], {}, "0x10", "1_000", "Infinity", "NaN", float("inf")])
def test_invalid_numeric_values_are_never_silently_used(value):
    assert checked_value(value, True)[1] == "invalid"


def test_small_formula_data_learns_linear_relation_and_saves_the_fitted_preprocessing(tmp_path):
    columns = [dict(code="x", name="树脂 B", kind="NUMERIC"), dict(code="additive", name="助剂", kind="NUMERIC"),
               dict(code="substrate", name="基材", kind="CATEGORICAL")]
    rows = [dict(values=dict(x=35 + i * .5, additive=.5 + (i % 3) * .1, substrate="PET" if i % 2 else "PC",
                             y=100 - (35 + i * .5) - (.5 + (i % 3) * .1))) for i in range(8)]
    result = service().train_workbench(request(tmp_path, rows, columns))
    metrics = result["metrics"]
    assert metrics["selectedAlgorithm"] == "LINEAR_REGRESSION"
    assert metrics["mae"] < 1e-8 and metrics["r2"] > .999999
    assert metrics["trainingMetrics"]["mae"] < 1e-8
    assert metrics["selectionMetric"] == "mae"
    assert metrics["baselineMetrics"]["mae"] > metrics["mae"]
    bundle = load(tmp_path)
    encoded = bundle["vectorizer"].transform([dict(x=39., additive=.8, substrate="PET")])
    assert bundle["model"].predict(encoded)[0] == pytest.approx(60.2)
    domain = result["applicabilityDomain"]
    assert domain["minimum"][domain["features"].index("x")] == 35
    assert len({item["candidateId"] for item in metrics["comparison"]}) == metrics["candidateCount"]


def test_category_encoding_and_scaling_fit_only_the_training_fold():
    pipeline = candidates(True, 8, 6, 17, 1)[0][2]
    train = np.asarray([dict(x=10., material="PET"), dict(x=20., material="PC"), dict(x=30., material="PET")], dtype=object)
    fitted = clone(pipeline).fit(train, [1, 2, 3])
    assert "material=铝" not in fitted.named_steps["vectorizer"].get_feature_names_out()
    assert fitted.predict([dict(x=100000., material="铝")]).shape == (1,)
    raw = fitted.named_steps["vectorizer"].transform(train)
    selected = fitted.named_steps["nonconstant"].transform(raw)
    np.testing.assert_allclose(fitted.named_steps["scaler"].mean_, selected.mean(axis=0))


def test_gaussian_process_is_invariant_to_input_units_and_survives_serialization(tmp_path, monkeypatch):
    factory = runtime.workbench_candidates
    monkeypatch.setattr(runtime, "workbench_candidates", lambda *args: [item for item in factory(*args) if item[0] == "GAUSSIAN_PROCESS"][:1])
    scores = []
    for scale in (1., 100000.):
        rows = [dict(values=dict(x=scale * (i + 1), y=(i + 1) ** 1.5)) for i in range(8)]
        result = service().train_workbench(request(tmp_path, rows))
        scores.append(result["metrics"]["mae"])
        bundle = load(tmp_path)
        assert np.isfinite(bundle["model"].predict(bundle["vectorizer"].transform([dict(x=scale * 4.5)]))[0])
    assert scores[0] == pytest.approx(scores[1], rel=1e-5)


def test_repeated_inputs_share_validation_folds_and_missing_rows_remain_explainable(tmp_path, monkeypatch):
    trainer = service()
    original = trainer._cross_validate
    def checked(estimator, x, y, folds, *args):
        for value in {record["x"] for record in x}:
            assert len({fold for record, fold in zip(x, folds) if record["x"] == value}) == 1
        return original(estimator, x, y, folds, *args)
    monkeypatch.setattr(trainer, "_cross_validate", checked)
    rows = [dict(values=dict(x=i // 2, y=i * 2)) for i in range(8)]
    selected = rows + [dict(label="缺少固含", values=dict(y=10))]
    result = trainer.train_workbench(request(tmp_path, rows) | dict(selectedRows=selected))
    assert result["excludedRowCount"] == 1
    assert result["metrics"]["validationGrouping"] == "IDENTICAL_X"
    assert result["metrics"]["dataChecks"]["conflictingInputGroupCount"] == 4


def test_failed_candidate_does_not_hide_success_or_lose_diagnostics(tmp_path, monkeypatch):
    items = candidates(True, 8, 6, 17, 1)[:1]
    broken = clone(items[0][2]).set_params(estimator=Ridge(alpha=-1))
    monkeypatch.setattr(runtime, "workbench_candidates", lambda *args: items + [("RIDGE", dict(alpha=-1), broken)])
    result = service().train_workbench(request(tmp_path, [dict(values=dict(x=i, y=i * 2)) for i in range(8)]))
    assert result["metrics"]["candidateCount"] == 1
    assert result["metrics"]["failedCandidates"][0]["algorithm"] == "RIDGE"


def test_constant_target_and_inputs_report_actionable_errors(tmp_path):
    with pytest.raises(Exception, match="Y 列只有一种结果"):
        service().train_workbench(request(tmp_path, [dict(values=dict(x=i, y=1)) for i in range(6)]))
    with pytest.raises(Exception, match="所选 X 在完整记录中都没有变化"):
        service().train_workbench(request(tmp_path, [dict(values=dict(x=1, y=i)) for i in range(6)]))


def test_high_cardinality_inputs_fail_before_allocating_a_dense_feature_matrix(tmp_path):
    rows = [dict(values=dict(record=f"记录-{i}", y=i)) for i in range(5000)]
    with pytest.raises(Exception, match="实验编号（5000 种类别）"):
        service().train_workbench(request(tmp_path, rows, [dict(code="record", name="实验编号", kind="CATEGORICAL")]))
