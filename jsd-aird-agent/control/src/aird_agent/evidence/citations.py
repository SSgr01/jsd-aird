"""Canonical citation policy shared by fixed Q&A and capability adapters."""

from fastapi import HTTPException


def project_aliases(text, aliases):
    """Map known canonical references to this answer's display aliases, never guess a source."""
    import json
    import re

    reverse = {}
    for alias, eid in aliases.items():
        reverse.setdefault(eid, alias)
    text = re.sub(
        r"\[E:([a-zA-Z0-9_-]+)\]",
        lambda match: "[[" + reverse[match[1]] + "]]" if match[1] in reverse else match[0],
        text,
    )
    if text.strip().startswith("{"):
        try:
            value = json.loads(text)
            if isinstance(value, dict):
                for key in ("usedEvidenceRefs", "used_evidence_refs"):
                    if isinstance(value.get(key), list):
                        value[key] = [
                            reverse.get(ref, ref) if isinstance(ref, str) else ref for ref in value[key]
                        ]
                return json.dumps(value, ensure_ascii=False)
        except (ValueError, TypeError):
            pass  # The shared validator reports malformed output; projection never repairs content.
    return text


def validate_generated(text, evidence, allowed_aliases=None):
    """Validate reference identity only; source authorization belongs to the host."""
    import json
    import re

    cited = validate_report(text, evidence)
    if allowed_aliases is None:
        return cited
    used = set(re.findall(r"\[+\s*([KDE][0-9]+)\s*\]+", text))
    if text.strip().startswith("{"):
        try:
            value = json.loads(text)
            if isinstance(value, dict):
                refs = value.get("usedEvidenceRefs", value.get("used_evidence_refs", []))
                if not isinstance(refs, list) or any(not isinstance(ref, str) for ref in refs):
                    raise ValueError("Invalid alias list")
                used.update(refs)
        except (ValueError, TypeError):
            raise HTTPException(422, "PREPARED_ANSWER_INVALID") from None
    if used - set(allowed_aliases):
        raise HTTPException(422, "PREPARED_ANSWER_UNKNOWN_CITATION")
    return cited


def normalize_citations(text, evidence):
    """Repair only omitted prefixes for real canonical IDs; never invent sources."""
    import re

    return re.sub(
        r"\[(e[0-9a-f]{24})\]",
        lambda match: "[E:" + match[1] + "]" if match[1] in evidence else match[0],
        text,
    )


def validate_report(text, evidence):
    import re

    cited = set(re.findall(r"\[E:([a-zA-Z0-9_-]+)\]", text)) | set(re.findall(r"\[(e[0-9a-f]{24})\]", text))
    missing = cited - set(evidence)
    if missing:
        raise HTTPException(422, "Report references unknown evidence IDs")
    return sorted(cited)
