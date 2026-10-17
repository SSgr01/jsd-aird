from types import SimpleNamespace
import numpy as np
import pytest
from jsd_aird_ai.contracts_v2 import FormulaV2
from jsd_aird_ai.service_v2 import FormulaModelV2Service
from jsd_aird_ai.settings import Settings


def test_all_free_materials_vary_while_fixed_material_and_total_are_preserved():
    service = FormulaModelV2Service(Settings())
    baseline = FormulaV2.model_validate({'basis': 'MASS_PERCENT', 'compositionComplete': True, 'recordedTotal': 100,
        'components': [{'materialId': key, 'ratio': ratio, 'unit': '%', 'amountKnown': True}
                       for key, ratio in [('additive', 8), ('resin-a', 60), ('resin-b', 27), ('fixed', 5)]]})
    limits = {'additive': (3, 12), 'resin-a': (45, 75), 'resin-b': (10, 47)}
    space = {'variableMaterials': [{'materialId': key, 'minimum': low, 'maximum': high}
                                   for key, (low, high) in limits.items()]}
    pool = service._formula_pool(baseline, 100, 32, 17, space)
    assert len(pool) == 32
    values = np.array([[component.ratio for component in formula.components] for formula in pool])
    assert np.allclose(values.sum(axis=1), 100)
    assert np.all(values[:, 3] == 5)
    assert np.linalg.matrix_rank(values[:, :3] - values[0, :3], tol=1e-6) == 2
    for index, (low, high) in enumerate(limits.values()):
        assert np.all(values[:, index] >= low - 1e-8)
        assert np.all(values[:, index] <= high + 1e-8)
        assert np.ptp(values[:, index]) > 5
    assert pool == service._formula_pool(baseline, 100, 32, 17, space)
    assert pool != service._formula_pool(baseline, 100, 32, 18, space)
    impossible = {'variableMaterials': [{'materialId': key, 'minimum': 40, 'maximum': 50} for key in limits]}
    assert service._formula_pool(baseline, 100, 32, 17, impossible) == []


def test_variable_conditions_are_not_forced_to_move_together_and_step_is_respected():
    request = SimpleNamespace(seed=17, fixed_inputs={'substrate': 'PET'}, search_space={'variableInputs': {
        'temperature': {'minimum': 20, 'maximum': 35, 'step': 0.5},
        'energy': {'minimum': 400, 'maximum': 900, 'step': 10},
    }})
    values = [FormulaModelV2Service._candidate_inputs(request, index, 32) for index in range(32)]
    assert all(value['substrate'] == 'PET' for value in values)
    for value in values:
        assert 20 <= value['temperature'] <= 35
        assert 400 <= value['energy'] <= 900
        assert value['temperature'] * 2 == pytest.approx(round(value['temperature'] * 2))
        assert value['energy'] / 10 == pytest.approx(round(value['energy'] / 10))
    normalized = np.array([[(value['temperature']-20)/15, (value['energy']-400)/500] for value in values])
    assert abs(np.corrcoef(normalized.T)[0, 1]) < 0.6
