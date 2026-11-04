"""Citation requirements for exports that actually use original literature."""

from pathlib import PurePosixPath

from fastapi import HTTPException


def validate_literature_export(path, parsed, source_quotes):
    allowed = {(q["evidence_id"], q["quote_id"]) for q in source_quotes}
    if not allowed:
        return

    def check_refs(value, location="JSON"):
        found = set()
        if isinstance(value, dict):
            if "quote_id" in value or "evidence_id" in value:
                pair = (value.get("evidence_id"), value.get("quote_id"))
                if not all(isinstance(part, str) for part in pair) or pair not in allowed:
                    raise HTTPException(422, f"EXPORT_QUOTE_INVALID: {location} has an invalid evidence_id/quote_id pair")
                found.add(pair)
            for child in value.values():
                found.update(check_refs(child))
        elif isinstance(value, list):
            for child in value:
                found.update(check_refs(child))
        return found

    suffix = PurePosixPath(path).suffix.lower()
    if suffix == ".json":
        try:
            cited = check_refs(parsed)
        except RecursionError as error:
            raise HTTPException(422, "EXPORT_FORMAT_INVALID: citation nesting exceeds limits") from error
        if not cited:
            raise HTTPException(422, "EXPORT_CITATIONS_MISSING: include source_quotes with evidence_id and quote_id")
    elif suffix in {".csv", ".tsv"}:
        keys = parsed.keys
        if not {"evidence_id", "quote_id"} <= set(keys):
            raise HTTPException(422, "EXPORT_CITATIONS_MISSING: include evidence_id and quote_id columns")
        cited = set()
        for row_number, row in parsed.rows():
            ref = {key: row[key].strip() for key in ("evidence_id", "quote_id")}
            # User-supplied bases may leave both fields empty, never invent a source.
            if any(ref.values()):
                cited.update(check_refs(ref, f"row {row_number}"))
        if not cited:
            raise HTTPException(422, "EXPORT_CITATIONS_MISSING: cite the source data in at least one data row")
