import copy
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from jsonschema import ValidationError, validate

from aird_agent.capabilities.business_checks import CHECK_HANDLERS, CHECK_TOOLS
from aird_agent.capabilities.registry import default_registry
from aird_agent.hosts.jsd.bindings import JsdBindings
from aird_agent.hosts.jsd.experts import role_instructions

CATALOG = json.loads((Path(__file__).parents[3] / "jsd-aird-api/src/main/resources/ai/expert-presets.json").read_text(encoding="utf-8"))


def context(expert_id=None):
    expert = None
    if expert_id:
        row = next(item for item in CATALOG["experts"] if item["id"] == expert_id)
        expert = copy.deepcopy(row)
    return SimpleNamespace(run={"expert": expert})


async def check(name, args, ctx=None):
    definition = next(item for item in CHECK_TOOLS if item["name"] == name)
    validate(args, definition["parameters"])
    return await CHECK_HANDLERS[name](ctx or context(), args)


def quantity(value, unit="g"):
    return {"value": value, "unit": unit}


async def test_fixed_expert_required_fields_and_composite_identity_cannot_be_erased():
    ctx = context("experiment-assistant")
    role_before = copy.deepcopy(ctx.run["expert"])
    base = dict(experiment_date="2026-11-11", operator="A", formulation_revision="f1", raw_material_batches=["b1"],
                process_conditions="25℃", sample_id="s1", experiment_id="e1", record_revision="1", temperature=0)
    rows = [{"source": "record-v1:1", "values": base}, {"source": "file:2", "values": base.copy()},
            {"source": "file:3", "values": {**base, "operator": "B"}},
            {"source": "file:4", "values": {**base, "experiment_id": "e2"}},
            {"source": "file:5", "values": {**base, "operator": " ", "raw_material_batches": [], "experiment_id": None}}]
    result = await check("record_validate", {"records": rows, "required_fields": ["temperature"], "identity_fields": []}, ctx)
    assert result["status"] == "ISSUES_FOUND"
    assert result["exact_duplicates"] == [{"row": 2, "same_as": 1, "source": "file:2"}]
    assert result["identity_conflicts"] == [{"row": 3, "same_identity_as": 1, "fields": ["operator"], "source": "file:3"}]
    assert result["missing"][0]["fields"] == ["operator", "raw_material_batches"]
    assert result["incomplete_identity"][0]["row"] == 5
    assert ctx.run["expert"] == role_before
    assert result["expert"] == {"id": ctx.run["expert"]["id"]}
    instructions = role_instructions(ctx.run["expert"])
    assert '"formulation_revision"' in instructions and "record_validate" in instructions


async def test_record_types_zero_and_unconfigured_identity_are_preserved():
    args = {"records": [{"source": "file:1", "values": {"id": "001", "value": 0}},
                        {"source": "file:2", "values": {"id": "001", "value": False}}],
            "required_fields": ["value"], "identity_fields": ["id"]}
    result = await check("record_validate", args)
    assert not result["missing"] and not result["exact_duplicates"]
    assert result["identity_conflicts"][0]["fields"] == ["value"]
    args.pop("identity_fields")
    assert (await check("record_validate", args))["identity_check"] == "NOT_CONFIGURED"


@pytest.mark.parametrize("actual,target,expected", [
    (quantity("0.52", "Pa·s"), "mPa·s", "520"),
    (quantity("0.1", "kg"), "g", "100"),
    (quantity("273.15", "K"), "℃", "0"),
    (quantity("52", "%"), "1", "0.52"),
    (quantity("0.000000000001", "g"), "mg", "0.000000001"),
])
async def test_unit_conversions_are_decimal_and_preserve_inputs(actual, target, expected):
    args = {"operation": "convert", "actual": actual, "target_unit": target, "source": "record:v1"}
    original = copy.deepcopy(args)
    result = await check("quantity_check", args)
    assert result["value"] == expected
    assert result["inputs"] == original == args
    assert result["review_state"] == "DRAFT"
    assert result["input_authority"] == "CALLER_SUPPLIED_NOT_VERIFIED"
    assert len(result["input_sha256"]) == 64


@pytest.mark.parametrize("unit,target", [("kg", "L"), ("cP", "mPa·s"), ("桶", "g")])
async def test_unknown_or_cross_dimension_units_never_guessed(unit, target):
    result = await check("quantity_check", {"operation": "convert", "actual": quantity("1", unit), "target_unit": target, "source": "file:1"})
    assert result["status"] == "INCOMPARABLE"


async def test_difference_zero_baseline_and_rule_source_boundaries():
    args = {"operation": "difference", "actual": quantity("0.103", "kg"), "expected": quantity("100"),
            "basis": "同批次干基投料", "source": "batch:v1"}
    result = await check("quantity_check", args)
    assert (result["difference"], result["difference_percent"], result["tolerance_status"]) == ("3", "3", "NOT_CONFIGURED")
    args["tolerance_percent"] = "3"
    assert (await check("quantity_check", args))["tolerance_status"] == "NEEDS_RULE_SOURCE"
    args["rule_source"] = "approved-procedure:v2"
    assert (await check("quantity_check", args))["tolerance_status"] == "WITHIN_TOLERANCE"
    args["tolerance_percent"] = "2.999999999999"
    assert (await check("quantity_check", args))["tolerance_status"] == "OUTSIDE_TOLERANCE"
    args["expected"] = quantity("0")
    result = await check("quantity_check", args)
    assert result["difference_percent"] is None and result["tolerance_status"] == "UNDEFINED_PERCENT"
    args["tolerance_percent"] = "-1"
    assert (await check("quantity_check", args))["status"] == "INVALID_INPUT"
    args.pop("basis")
    assert (await check("quantity_check", args))["status"] == "NEEDS_INFO"


def spec_args():
    return {"measured": quantity("0.52", "Pa·s"), "lower": quantity("500", "mPa·s"),
            "upper": quantity("520", "mPa·s"), "lower_inclusive": True, "upper_inclusive": True,
            "method": "M1", "required_method": "M1", "conditions": "25℃; spindle=3", "required_conditions": "25℃; spindle=3",
            "specification_source": "spec:v2", "specification_revision": "2", "measurement_source": "test:v1", "comparison_policy": "raw"}


async def test_spec_bounds_are_exact_after_conversion_and_exclusive_bound_is_respected():
    args = spec_args()
    assert (await check("specification_compare", args))["status"] == "WITHIN_LIMITS"
    args["upper_inclusive"] = False
    assert (await check("specification_compare", args))["status"] == "OUTSIDE_LIMITS"
    args["measured"]["value"] = "0.519999999999"
    assert (await check("specification_compare", args))["status"] == "WITHIN_LIMITS"
    args["lower"] = quantity("600", "mPa·s")
    assert (await check("specification_compare", args))["status"] == "INVALID_INPUT"


@pytest.mark.parametrize("change,status", [
    ({"method": "M2"}, "INCOMPARABLE"), ({"conditions": "30℃; spindle=3"}, "INCOMPARABLE"),
    ({"comparison_policy": "requires_rounding"}, "NEEDS_REVIEW"),
    ({"lower": quantity("1", "kg")}, "INCOMPARABLE"),
])
async def test_spec_incompatible_conditions_and_rounding_are_not_passes(change, status):
    assert (await check("specification_compare", {**spec_args(), **change}))["status"] == status


@pytest.mark.parametrize("field", ["method", "conditions", "specification_revision", "specification_source", "upper_inclusive"])
async def test_missing_spec_prerequisites_require_info(field):
    args = spec_args()
    args.pop(field)
    assert (await check("specification_compare", args))["status"] == "NEEDS_INFO"


@pytest.mark.parametrize("value", ["NaN", "Infinity", "1e6", "1,000", "0.1234567890123", 0.1, None, ""])
async def test_invalid_numbers_rejected_before_execution(value):
    with pytest.raises(ValidationError):
        await check("quantity_check", {"operation": "convert", "actual": quantity(value), "target_unit": "kg", "source": "file:1"})


async def test_tools_discover_through_authorized_core_without_extra_business_access():
    registry = default_registry()
    bindings = JsdBindings()
    bindings.tools_for = lambda run: registry.tools(["core"])
    for name in CHECK_HANDLERS:
        result = await bindings.dispatch(None, {}, {}, "tool_search", {"query": name}, "discovery")
        assert name in result["activate_tools"]
        assert registry.handler(["core"], name) is not None
        assert name not in registry.initial_tools(["core"])
    assert not set(CHECK_HANDLERS) & registry.mutations(["core"])
