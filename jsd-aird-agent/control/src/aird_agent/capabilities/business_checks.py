"""Read-only checks over supplied inputs, never certificates or authority over sources."""
import hashlib
import json
import re
from decimal import Decimal, InvalidOperation, localcontext

from aird_agent.capabilities.schema import schema, tool

TEXT = {"type": "string", "maxLength": 500, "minLength": 1}
NUMBER = {"type": "string", "pattern": r"^-?\d{1,18}(\.\d{1,12})?$", "maxLength": 32}
QUANTITY = schema({"value": NUMBER, "unit": TEXT}, ["value", "unit"])
FIELDS = {"type": "array", "maxItems": 80, "uniqueItems": True, "items": TEXT}
SCALAR = {"type": ["string", "number", "boolean", "null"], "maxLength": 4000}
VALUE = {"anyOf": [SCALAR, {"type": "array", "maxItems": 80, "items": SCALAR}]}

CHECK_TOOLS = (
    tool("record_validate", "核验已读取记录的必填字段、精确重复和同组合标识冲突。自动叠加固定专家必填字段，不修改资料；输入应保留来源，不证明关联真实。", {
        "records": {"type": "array", "minItems": 1, "maxItems": 200, "items": schema({
            "source": TEXT, "values": {"type": "object", "maxProperties": 100, "additionalProperties": VALUE},
        }, ["source", "values"])},
        "required_fields": FIELDS, "identity_fields": FIELDS,
    }, ["records"]),
    tool("quantity_check", "以十进制原始精度做同量纲单位换算或实际与理论数量差异。expected为理论，差异=实际−理论；理论为零时差异率未定义。容差须有来源，仅为输入数值核对。", {
        "operation": {"type": "string", "enum": ["convert", "difference"]},
        "actual": QUANTITY, "target_unit": TEXT, "expected": QUANTITY,
        "basis": TEXT, "tolerance_percent": NUMBER, "rule_source": TEXT,
        "source": TEXT,
    }, ["operation", "actual", "source"]),
    tool("specification_compare", "按原始精度核对实测值与带来源版本的规格上下限。须核实方法与条件；未知或不相容返回待确认/不可比。需标准修约时交复核，结果不是质量放行。", {
        "measured": QUANTITY, "lower": QUANTITY, "upper": QUANTITY,
        "lower_inclusive": {"type": "boolean"}, "upper_inclusive": {"type": "boolean"},
        "method": TEXT, "required_method": TEXT, "conditions": TEXT, "required_conditions": TEXT,
        "specification_source": TEXT, "specification_revision": TEXT, "measurement_source": TEXT,
        "comparison_policy": {"type": "string", "enum": ["raw", "requires_rounding"]},
    }, ["measured", "measurement_source"]),
)

# Canonical scale and offset. No density, assay, active-content or ambiguous unit inference.
UNITS = {}
for dimension, entries in {
    "mass": [("mg", "0.001"), ("g", "1"), ("kg", "1000"), ("t", "1000000")],
    "volume": [("mL", "1"), ("L", "1000")],
    "length": [("mm", "0.001"), ("cm", "0.01"), ("m", "1")],
    "pressure": [("Pa", "1"), ("kPa", "1000"), ("MPa", "1000000")],
    "viscosity": [("mPa·s", "0.001"), ("Pa·s", "1")],
    "ratio": [("%", "0.01"), ("1", "1")],
}.items():
    for unit, scale in entries:
        UNITS[unit] = (dimension, Decimal(scale), Decimal(0))
UNITS.update({"℃": ("temperature", Decimal(1), Decimal("273.15")),
              "°C": ("temperature", Decimal(1), Decimal("273.15")),
              "K": ("temperature", Decimal(1), Decimal(0))})


class CheckIssue(ValueError):
    def __init__(self, status, reason):
        self.status, self.reason = status, reason


def number(value):
    if not isinstance(value, str) or not re.fullmatch(NUMBER["pattern"], value):
        raise CheckIssue("INVALID_INPUT", "数量须为有限十进制字符串，最多18位整数和12位小数。")
    try:
        return Decimal(value)
    except InvalidOperation:
        raise CheckIssue("INVALID_INPUT", "无法解析数值。") from None


def unit_info(unit):
    if unit not in UNITS:
        raise CheckIssue("INCOMPARABLE", f"不支持或含义未明确的单位：{unit}；需要明确的业务换算依据。")
    return UNITS[unit]


def convert(quantity, target):
    value = number(quantity["value"])
    dimension, scale, offset = unit_info(quantity["unit"])
    other_dimension, other_scale, other_offset = unit_info(target)
    if dimension != other_dimension:
        raise CheckIssue("INCOMPARABLE", "单位量纲不同，不自动推断密度或其他转换条件。")
    return (value * scale + offset - other_offset) / other_scale


def decimal_text(value):
    if value == 0:
        return "0"
    rendered = format(value, "f")
    return rendered.rstrip("0").rstrip(".") if "." in rendered else rendered


def missing(value):
    return value is None or isinstance(value, str) and not value.strip() or isinstance(value, list) and (not value or all(missing(item) for item in value))


def validate_records(context, args):
    checks = (context.run.get("expert") or {}).get("work_config", {}).get("checks", {})
    required = list(dict.fromkeys([field["key"] for field in checks.get("required_fields", [])] + args.get("required_fields", [])))
    # Extra identity fields may refine, never erase the fixed role's composite identity.
    identity = list(dict.fromkeys(checks.get("identity_fields", []) + args.get("identity_fields", [])))
    missing_rows, duplicate_rows, conflicts, incomplete = [], [], [], []
    seen_values, seen_ids = {}, {}
    for i, row in enumerate(args["records"], 1):
        values, source = row["values"], row["source"]
        absent = [key for key in required if missing(values.get(key))]
        if absent:
            missing_rows.append({"row": i, "source": source, "fields": absent})
        signature = json.dumps(values, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        if signature in seen_values:
            duplicate_rows.append({"row": i, "same_as": seen_values[signature], "source": source})
        else:
            seen_values[signature] = i
        if not identity:
            continue
        if any(missing(values.get(key)) for key in identity):
            incomplete.append({"row": i, "source": source, "fields": [key for key in identity if missing(values.get(key))]})
            continue
        key = json.dumps([values[field] for field in identity], ensure_ascii=False, separators=(",", ":"))
        if key in seen_ids:
            first_index, first_values = seen_ids[key]
            changed = sorted(field for field in set(first_values) | set(values)
                             if (field not in first_values or field not in values or
                                 json.dumps(first_values[field], sort_keys=True) != json.dumps(values[field], sort_keys=True)))
            if changed:
                conflicts.append({"row": i, "same_identity_as": first_index, "fields": changed, "source": source})
        else:
            seen_ids[key] = (i, values)
    return {"status": "ISSUES_FOUND" if missing_rows or duplicate_rows or conflicts or incomplete else "CHECKED",
            "row_count": len(args["records"]), "required_fields": required, "identity_fields": identity,
            "missing": missing_rows, "exact_duplicates": duplicate_rows, "identity_conflicts": conflicts,
            "incomplete_identity": incomplete, "identity_check": "CHECKED" if identity else "NOT_CONFIGURED",
            "coverage": "仅检查所提交字段；未配置的业务格式、编号、真实关联及疑似重复尚未验证。"}


def check_quantity(args):
    actual = args["actual"]
    if args["operation"] == "convert":
        if not args.get("target_unit"):
            raise CheckIssue("NEEDS_INFO", "需提供目标单位。")
        converted = convert(actual, args["target_unit"])
        return {"status": "CALCULATED", "value": decimal_text(converted), "unit": args["target_unit"]}
    if not args.get("expected") or not args.get("basis", "").strip():
        raise CheckIssue("NEEDS_INFO", "需提供理论量和已核实的数量基准。")
    expected = args["expected"]
    dimension = unit_info(expected["unit"])[0]
    if dimension == "temperature":
        raise CheckIssue("INCOMPARABLE", "温度有绝对标尺偏移，不能按投料数量计算差异率。")
    observed, baseline = convert(actual, expected["unit"]), number(expected["value"])
    difference = observed - baseline
    percent = difference / baseline * 100 if baseline != 0 else None
    result = {"status": "CALCULATED", "actual": decimal_text(observed), "expected": decimal_text(baseline),
              "unit": expected["unit"], "difference": decimal_text(difference),
              "difference_percent": decimal_text(percent) if percent is not None else None,
              "percent_reason": "理论量为零，差异率未定义。" if percent is None else None,
              "formula": "actual - expected; (actual - expected) / expected * 100",
              "tolerance_status": "NOT_CONFIGURED"}
    if "tolerance_percent" in args:
        tolerance = number(args["tolerance_percent"])
        if tolerance < 0:
            raise CheckIssue("INVALID_INPUT", "容差不能为负。")
        if not args.get("rule_source", "").strip():
            result["tolerance_status"] = "NEEDS_RULE_SOURCE"
        elif percent is None:
            result["tolerance_status"] = "UNDEFINED_PERCENT"
        else:
            result["tolerance_status"] = "WITHIN_TOLERANCE" if abs(percent) <= tolerance else "OUTSIDE_TOLERANCE"
    return result


def compare_specification(args):
    required = ["method", "required_method", "conditions", "required_conditions", "specification_source", "specification_revision", "comparison_policy"]
    if absent := [key for key in required if not args.get(key, "").strip()]:
        return {"status": "NEEDS_INFO", "missing_fields": absent}
    if args["method"].strip() != args["required_method"].strip() or args["conditions"].strip() != args["required_conditions"].strip():
        raise CheckIssue("INCOMPARABLE", "方法或条件不一致；不按名称相似自动等同。")
    if args["comparison_policy"] != "raw":
        raise CheckIssue("NEEDS_REVIEW", "该规格需要修约；当前只支持原始精度比较，请按获批准规则复核。")
    if "lower" not in args and "upper" not in args:
        raise CheckIssue("NEEDS_INFO", "需至少一个明确的规格限值。")
    measured = args["measured"]
    value = convert(measured, measured["unit"])
    limits = {}
    for side in ("lower", "upper"):
        if side in args:
            if side + "_inclusive" not in args:
                raise CheckIssue("NEEDS_INFO", "需确认规格边界是否包含限值。")
            limits[side] = convert(args[side], measured["unit"])
    if "lower" in limits and "upper" in limits and (limits["lower"] > limits["upper"] or
            limits["lower"] == limits["upper"] and not (args["lower_inclusive"] and args["upper_inclusive"])):
        raise CheckIssue("INVALID_INPUT", "规格上下限构成空区间。")
    inside = True
    if "lower" in limits:
        inside &= value >= limits["lower"] if args["lower_inclusive"] else value > limits["lower"]
    if "upper" in limits:
        inside &= value <= limits["upper"] if args["upper_inclusive"] else value < limits["upper"]
    return {"status": "WITHIN_LIMITS" if inside else "OUTSIDE_LIMITS", "value": decimal_text(value),
            "unit": measured["unit"], "normalized_limits": {key: decimal_text(value) for key, value in limits.items()},
            "comparison_policy": "raw", "specification_revision": args["specification_revision"]}


async def business_check(context, args, name):
    inputs = json.loads(json.dumps(args, ensure_ascii=False, allow_nan=False))
    try:
        # Bounded decimal inputs: exact unit scaling and differences; repeating rates
        # retain 60 significant digits. Never round a measurement to decide compliance.
        with localcontext() as precision:
            precision.prec = 60
            result = (validate_records(context, inputs) if name == "record_validate" else
                      check_quantity(inputs) if name == "quantity_check" else compare_specification(inputs))
    except CheckIssue as error:
        result = {"status": error.status, "reason": error.reason}
    expert = context.run.get("expert") or {}
    return {**result, "review_state": "DRAFT", "engine_version": "business-checks/1",
            "input_authority": "CALLER_SUPPLIED_NOT_VERIFIED", "inputs": inputs,
            "input_sha256": hashlib.sha256(json.dumps(inputs, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest(),
            "expert_revision": {"id": expert.get("id"), "revision": expert.get("revision")},
            "precision": "decimal; 60 significant digits; no measurement rounding",
            "notice": "仅核对所提交输入，不验证来源、业务关联及标准适用性，不构成正式判定或放行。"}


def handler(name):
    async def call(context, args):
        return await business_check(context, args, name)
    return call


CHECK_HANDLERS = {item["name"]: handler(item["name"]) for item in CHECK_TOOLS}
