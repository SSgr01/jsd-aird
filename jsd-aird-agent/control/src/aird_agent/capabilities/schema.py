"""Shared JSON tool declarations, independent of domain capability packs."""

def schema(properties=None, required=None):
    return {
        "type": "object",
        "properties": properties or {},
        "required": required or [],
        "additionalProperties": False,
    }


STRING = {"type": "string", "maxLength": 12000}
INTEGER = {"type": "integer", "minimum": 0}
EVIDENCE_ID = {"type": "string", "pattern": "^e[0-9a-f]{24}$"}
QUOTE_ID = {"type": "string", "pattern": "^q[0-9a-f]{24}$"}
QUOTE_REF = schema({"evidence_id": EVIDENCE_ID, "quote_id": QUOTE_ID}, ["evidence_id", "quote_id"])


def tool(name, description, properties=None, required=None):
    return {"name": name, "description": description, "parameters": schema(properties, required)}
