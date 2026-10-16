import hashlib

import pytest
from fastapi.testclient import TestClient

import jsd_aird_ai.service_v2 as runtime
from jsd_aird_ai.api import create_app
from jsd_aird_ai.contracts_v2 import RecommendRequestV2
from jsd_aird_ai.settings import Settings


MATERIALS = ["829e5306-ada7-4949-b7a4-3d571df9ca8c", "f44a3459-05c1-4958-a246-b5736c0aa682", "fa09039b-f0a2-48bd-9acc-71ed839ce94d"]


def setup_model(tmp_path, monkeypatch, categorical=False):
    # The training-pipeline suite covers all learners; these tests exercise real
    # persisted numeric/category models through formula generation and scoring.
    factory = runtime.workbench_candidates
    monkeypatch.setattr(runtime, "workbench_candidates", lambda *args: factory(*args)[:1])
    api = TestClient(create_app(Settings(allow_file_urls=True, model_threads=1)))
    columns = [dict(code=f"formula:{material}", name=name, kind="NUMERIC", unit="%")
               for material, name in zip(MATERIALS, ["树脂 A 比例", "树脂 B 比例", "助剂比例"])]
    columns += [dict(code="temperature", name="温度", kind="NUMERIC", unit="°C"),
                dict(code="substrate", name="基材", kind="CATEGORICAL")]
    rows = []
    for i in range(18):
        a, b, temperature = 20 + i * 2, 22 + i % 3 * 3, 20 + i % 4
        c = 100 - a - b
        substrate = "PET" if i % 2 else "PC"
        y = "优" if a >= 35 else "良" if categorical else None
        if not categorical:
            y = 1.7 * a + .8 * b + .4 * c + .5 * temperature + (3 if substrate == "PET" else 0)
        rows.append(dict(values=dict(zip([column["code"] for column in columns], [a, b, c, temperature, substrate])) | dict(y=y)))
    output = tmp_path / "model.zip"
    trained = api.post("/internal/v2/modeling/workbench/train", json=dict(
        requestId="formula-model", xColumns=columns, yColumn=dict(code="y", name="涂层性能", kind="CATEGORICAL" if categorical else "NUMERIC"),
        rows=rows, output=output.as_uri()))
    assert trained.status_code == 200, trained.text
    ref = dict(id="version-1", version=1, sha256="0" * 64)
    fields = []
    for index, column in enumerate(columns):
        fields.append(dict(fieldVersionId=f"field-{index}", code=column["code"], name=column["name"], unit=column.get("unit"),
                           valueType="NUMBER" if column["kind"] == "NUMERIC" else "CATEGORY", required=True,
                           acquisitionTiming="PRE_EXPERIMENT", encoding=dict(formulaMaterialId=MATERIALS[index]) if index < 3 else {}))
    binding = dict(target=ref | dict(code="coating", valueType="CATEGORICAL" if categorical else "CONTINUOUS", unit=None,
                                    classes=["优", "良"] if categorical else [], observationSemantics={}),
                   modelVersionId="model-1", modelBundle=trained.json()["modelBundle"],
                   inputScheme=ref | dict(targetVersionId="version-1", fields=fields),
                   materialDictionary=ref | dict(materials=[dict(materialId=material, code=material, encoderIndex=index) for index, material in enumerate(MATERIALS)]),
                   applicabilityDomain=trained.json()["applicabilityDomain"] | dict(nearBoundaryRatio=.05))
    formula = dict(basis="MASS_PERCENT", compositionComplete=True, recordedTotal=100,
                   components=[dict(materialId=material, ratio=value, unit="PERCENT", amountKnown=True) for material, value in zip(MATERIALS, [35, 25, 40])])
    request = dict(contractVersion="formula-model.v2", requestId="inverse", seed=17, runId="formula-run", mode="FORMULA_PREDICTION", candidateCount=3,
                   baselineFormula=formula, fixedInputs=dict(temperature=24, substrate="PET"),
                   goals=[dict(targetId="version-1", operator="MATCH" if categorical else "AT_LEAST", mandatory=True, weight=1,
                               value=dict(label="优") if categorical else 100, minimumProbability=.5 if categorical else None)],
                   constraints={}, searchSpace=dict(variableMaterials=[dict(materialId=material, minimum=low, maximum=high)
                                                                        for material, low, high in zip(MATERIALS, [20, 20, 10], [60, 45, 60])]), modelBindings=[binding])
    return api, request, output


@pytest.mark.parametrize("categorical", [False, True])
def test_registered_workbench_generates_and_scores_real_formula_candidates(tmp_path, monkeypatch, categorical):
    api, request, output = setup_model(tmp_path, monkeypatch, categorical)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    response = api.post("/internal/v2/models/recommend", json=request)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["executionStatus"] == "SUCCEEDED", result
    candidates = result["candidates"]
    assert len(candidates) >= 2, result
    predictions = []
    for candidate in candidates:
        components = candidate["formula"]["components"]
        ratios = {item["materialId"]: item["ratio"] for item in components}
        assert sum(ratios.values()) == pytest.approx(100, abs=1e-6)
        assert set(ratios) == set(MATERIALS)
        assert candidate["inputs"] == dict(temperature=24, substrate="PET")
        prediction = candidate["results"][0]["result"]
        if categorical:
            assert prediction["label"] == "优"
            predictions.append(prediction["probabilities"]["优"])
        else:
            expected = 1.7 * ratios[MATERIALS[0]] + .8 * ratios[MATERIALS[1]] + .4 * ratios[MATERIALS[2]] + 15
            assert prediction["value"] == pytest.approx(expected, abs=1e-6)
            assert prediction["value"] >= 100
            predictions.append(prediction["value"])
    assert len(set(round(value, 7) for value in predictions)) > 1
    assert hashlib.sha256(output.read_bytes()).hexdigest() == digest
    assert result["searchEngine"] == ("DETERMINISTIC_CANDIDATE_POOL" if categorical else "BAYBE")


def test_mapping_does_not_change_ordinary_performance_prediction(tmp_path, monkeypatch):
    api, request, _ = setup_model(tmp_path, monkeypatch)
    inputs = dict(temperature=24, substrate="PET") | {f"formula:{material}": value for material, value in zip(MATERIALS, [35, 25, 40])}
    response = api.post("/internal/v2/models/score", json=dict(requestId="direct", seed=17, runId="prediction", inputs=inputs, modelBindings=request["modelBindings"]))
    assert response.json()["results"][0]["result"]["value"] == pytest.approx(110.5)


def test_missing_or_duplicate_recipe_components_are_rejected(tmp_path, monkeypatch):
    _, payload, _ = setup_model(tmp_path, monkeypatch)
    request = RecommendRequestV2.model_validate(payload)
    formula = request.baseline_formula
    service = runtime.FormulaModelV2Service(Settings())
    with pytest.raises(Exception, match="缺少材料比例"):
        service._formula_inputs(request, formula.model_copy(update=dict(components=formula.components[:2])), {})
    with pytest.raises(Exception, match="合计为 100%"):
        service._formula_inputs(request, formula.model_copy(update=dict(components=formula.components + [formula.components[0]])), {})
