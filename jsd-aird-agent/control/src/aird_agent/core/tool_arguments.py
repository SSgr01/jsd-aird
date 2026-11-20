"""One deterministic repair of read pagination; never guess filters or write inputs."""
import copy


def repair_read_arguments(arguments, schema, *, read_only, allow_pending_batch=False):
    value = copy.deepcopy(arguments)
    repairs = []
    if not read_only or not isinstance(value, dict):
        return value, repairs
    if allow_pending_batch and value.get("requests") == [] and "requests" not in schema.get("required", []):
        del value["requests"]
        repairs.append({"path": ["requests"], "rule": "continue_pending_batch"})

    def visit(item, contract, path):
        if isinstance(item, dict):
            # A read's fixed identity lives in ref; page selectors live beside
            # it. Relocate only fields with one declared destination and no
            # competing value. No IDs, values or authority are inferred.
            properties = contract.get("properties", {})
            ref = item.get("ref")
            ref_fields = properties.get("ref", {}).get("properties", {})
            if isinstance(ref, dict) and {"type", "id"} <= ref_fields.keys():
                for name in list(ref):
                    if name not in ref_fields and name in properties and name not in item:
                        item[name] = ref.pop(name)
                        repairs.append({"path": [*path, "ref", name], "rule": "move_to_read", "destination": [*path, name]})
                for name in list(item):
                    if name not in properties and name in ref_fields and name not in ref:
                        ref[name] = item.pop(name)
                        repairs.append({"path": [*path, name], "rule": "move_to_ref", "destination": [*path, "ref", name]})
            for name, field in contract.get("properties", {}).items():
                if name not in item:
                    continue
                current = item[name]
                if name not in contract.get("required", []) and (
                    current is None or name in {"sample_key", "field_codes"} and not current
                ):
                    del item[name]
                    repairs.append({"path": [*path, name], "rule": "omit_empty_optional"})
                elif name == "limit" and type(current) is int and current > field.get("maximum", current):
                    item[name] = field["maximum"]
                    repairs.append({"path": [*path, name], "rule": "maximum", "effective": item[name]})
                else:
                    visit(current, field, [*path, name])
        elif isinstance(item, list):
            for index, child in enumerate(item):
                visit(child, contract.get("items", {}), [*path, index])

    visit(value, schema, [])
    return value, repairs
