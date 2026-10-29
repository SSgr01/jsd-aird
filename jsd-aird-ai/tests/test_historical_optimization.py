from __future__ import annotations

import numpy as np
import pytest

from jsd_aird_ai.errors import FormulaModelError
import jsd_aird_ai.service_v2 as runtime
from jsd_aird_ai.service_v2 import FormulaModelV2Service
from jsd_aird_ai.settings import Settings


def service() -> FormulaModelV2Service:
    return FormulaModelV2Service(Settings())


def rows() -> list[dict[str, float | str]]:
    result = []
    for temperature in np.linspace(20, 32, 13):
        result.append({"temperature": float(temperature), "substrate": "A" if temperature < 26 else "B",
                       "strength": float(10 - (temperature - 27) ** 2 / 8)})
    return result


def test_historical_gp_ei_uses_measured_xy_without_model_bindings() -> None:
    result = service().recommend_from_history({
        "features": [
            {"code": "temperature", "name": "温度", "kind": "NUMERIC", "minimum": 20, "maximum": 32},
            {"code": "substrate", "name": "基材", "kind": "CATEGORICAL"},
        ],
        "target": "strength", "direction": "MAXIMIZE", "rows": rows(), "candidateCount": 4, "seed": 7,
    })

    assert result["searchEngine"] == "GAUSSIAN_PROCESS_EI"
    assert result["searchEvidence"]["trainingRowCount"] == 13
    assert len(result["candidates"]) == 4
    assert all(20 <= candidate["values"]["temperature"] <= 32 for candidate in result["candidates"])
    assert all("substrate" in candidate["values"] for candidate in result["candidates"])
    for candidate in result["candidates"]:
        values = candidate["values"]
        same_substrate = [row for row in rows() if row["substrate"] == values["substrate"]]
        assert all(abs(values["temperature"] - row["temperature"]) >= 0.3 for row in same_substrate)


def test_noisy_gram_formulas_produce_distinct_plausible_predictions() -> None:
    rng = np.random.default_rng(89)
    history = []
    for _ in range(140):
        a, b, additive = rng.uniform(110, 360), rng.uniform(35, 165), rng.uniform(3, 27)
        center = 69 + 28 * a / (a + b + additive) + 0.08 * additive
        history.append({"a": a, "b": b, "additive": additive, "contact": center + rng.normal(0, 1.2)})
    result = service().recommend_from_history({
        "features": [{"code": code, "kind": "NUMERIC", "minimum": low, "maximum": high}
                     for code, low, high in [("a", 110, 360), ("b", 35, 165), ("additive", 3, 27)]],
        "target": "contact", "direction": "MAXIMIZE", "rows": history, "candidateCount": 4, "seed": 7,
    })
    predictions = []
    for candidate in result["candidates"]:
        values = candidate["values"]
        a, b, additive = values["a"], values["b"], values["additive"]
        center = 69 + 28 * a / (a + b + additive) + 0.08 * additive
        assert abs(candidate["predictedValue"] - center) < 2.5
        predictions.append(candidate["predictedValue"])
    assert np.ptp(predictions) > 0.05


def test_historical_optimization_rejects_insufficient_data_with_actionable_message() -> None:
    try:
        service().recommend_from_history({
            "features": [{"code": "temperature", "kind": "NUMERIC"}],
            "target": "strength", "direction": "MAXIMIZE",
            "rows": [{"temperature": 20, "strength": 1}, {"temperature": 22, "strength": 2}],
        })
    except FormulaModelError as error:
        assert error.message == "至少需要 4 条 X 和 Y 都填写完整的历史实验"
    else:
        raise AssertionError("insufficient history should not start a GP fit")


def test_historical_gp_keeps_selected_formula_proportions_at_the_recorded_total() -> None:
    formula_codes = ["formula:additive", "formula:resin_a", "formula:resin_b"]
    history = []
    for index, additive in enumerate(np.linspace(51, 54, 13)):
        resin_a = 2 + index * 0.15
        resin_b = 100 - additive - resin_a
        history.append({
            "formula:additive": float(additive),
            "formula:resin_a": float(resin_a),
            "formula:resin_b": float(resin_b),
            "wear": float(900 + 30 * index - 2 * index**2),
        })

    result = service().recommend_from_history({
        "features": [
            {"code": "formula:additive", "name": "助剂比例", "kind": "NUMERIC", "minimum": 51, "maximum": 54},
            {"code": "formula:resin_a", "name": "树脂A比例", "kind": "NUMERIC", "minimum": 2, "maximum": 3.8},
            {"code": "formula:resin_b", "name": "树脂B比例", "kind": "NUMERIC", "minimum": 42.2, "maximum": 47},
        ],
        "compositionGroups": [{"codes": formula_codes, "total": 100}],
        "target": "wear", "direction": "MAXIMIZE", "rows": history, "candidateCount": 4, "seed": 19,
    })

    assert len(result["candidates"]) == 4
    for candidate in result["candidates"]:
        values = candidate["values"]
        assert abs(sum(values[code] for code in formula_codes) - 100) < 1e-8
        assert 51 <= values["formula:additive"] <= 54
        assert 2 <= values["formula:resin_a"] <= 3.8
        assert 42.2 <= values["formula:resin_b"] <= 47


def test_fixed_zero_and_nonzero_materials_are_retained_in_complete_candidates() -> None:
    history = [{"a": float(a), "b": 95.0 - float(a), "zero": 0.0, "fixed": 5.0,
                "y": 100.0 - (float(a) - 43.5) ** 2} for a in np.linspace(30, 60, 13)]
    result = service().recommend_from_history({
        "features": [{"code": code, "kind": "NUMERIC", "minimum": low, "maximum": high}
                     for code, low, high in [("a", 30, 60), ("b", 35, 65), ("zero", 0, 0), ("fixed", 5, 5)]],
        "compositionGroups": [{"codes": ["a", "b", "zero", "fixed"], "total": 100}],
        "target": "y", "rows": history, "seed": 19,
    })
    assert len(result["candidates"]) == 4
    for candidate in result["candidates"]:
        values = candidate["values"]
        assert values["zero"] == 0
        assert values["fixed"] == 5
        assert abs(sum(values.values()) - 100) < 1e-8
        assert all(abs(values["a"] - row["a"]) > 0.01 for row in history)


def test_fixing_a_previously_variable_factor_keeps_its_training_information() -> None:
    result = service().recommend_from_history({
        "features": [{"code": "temperature", "kind": "NUMERIC", "minimum": 27, "maximum": 27, "fixedValue": 27},
                     {"code": "substrate", "kind": "CATEGORICAL"}],
        "target": "strength", "rows": rows(), "seed": 9,
    })
    assert result["candidates"]
    assert all(candidate["values"]["temperature"] == 27 for candidate in result["candidates"])


def test_ranges_with_no_actual_freedom_are_rejected_before_fitting() -> None:
    with pytest.raises(FormulaModelError, match="没有可调整空间"):
        service().recommend_from_history({
            "features": [{"code": "temperature", "kind": "NUMERIC", "minimum": 27, "maximum": 27}],
            "target": "strength", "rows": rows(),
        })


def test_non_negative_count_predictions_are_filtered_but_unknown_units_keep_negative_values(monkeypatch) -> None:
    def negative_prediction(self, values, return_std=False, **kwargs):
        mean = np.full(len(values), -2.0)
        return (mean, np.full(len(values), 0.1)) if return_std else mean

    monkeypatch.setattr(runtime.GaussianProcessRegressor, "predict", negative_prediction)
    request = {
        "features": [{"code": "temperature", "kind": "NUMERIC", "minimum": 20, "maximum": 32}],
        "target": "strength", "direction": "MAXIMIZE", "rows": rows(), "candidateCount": 2, "seed": 7,
    }

    allowed = service().recommend_from_history(request)
    assert allowed["candidates"]
    assert all(candidate["predictedValue"] == -2.0 for candidate in allowed["candidates"])

    with pytest.raises(FormulaModelError, match="负的计数预测") as error:
        service().recommend_from_history(request | {"resultConstraints": {"nonNegative": True}})
    assert error.value.code.value == "NO_FEASIBLE_CANDIDATE"
