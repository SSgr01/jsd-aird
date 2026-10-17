from __future__ import annotations

import numpy as np

from jsd_aird_ai.errors import FormulaModelError
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
