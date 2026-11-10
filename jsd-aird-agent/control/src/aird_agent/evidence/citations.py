"""Canonical citation policy shared by fixed Q&A and capability adapters."""

from fastapi import HTTPException

PAIRED_CITATION = r"\[\s*E:\s*([a-zA-Z0-9_-]+)\s*,\s*([a-zA-Z0-9_-]+)\s*\]"
CITATION_MARKER = (r"\[\s*E:\s*([^\]\n]*)(?:\]|(?=\n|$))|"
                   r"〔\s*E:\s*([^〕\n]*)(?:〕|(?=\n|$))|"
                   r"\(\s*E:\s*([^)\n]*)(?:\)|(?=\n|$))|\[\s*(e[0-9a-f]{24})\s*\]")


def citation_syntax_valid(marker):
    import re

    return bool(re.fullmatch(PAIRED_CITATION, marker) or re.fullmatch(
        r"\[\s*(?:E:\s*)?[a-zA-Z0-9_-]+\s*\]|〔\s*E:\s*[a-zA-Z0-9_-]+\s*〕|"
        r"\(\s*E:\s*[a-zA-Z0-9_-]+\s*\)", marker))




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
    return sorted(set(cited) | {eid for eid, item in evidence.items() if item.get("label") in used})


def normalize_citations(text, evidence):
    """Normalize citation spacing/prefixes for exact known IDs, never guess a source."""
    import re

    from aird_agent.evidence.quotes import quotes, resolve_quote

    # A quote is an exact, hashed span of an already authorized evidence item.
    # Accept its issued identity as an alias, never infer a source from prose.
    wanted = {qid for marker in re.finditer(CITATION_MARKER, text)
              for qid in re.findall(r"\bq[0-9a-f]{24}\b", marker[0])}
    quote_owners = {}
    if wanted:
        for eid, candidate in evidence.items():
            for quote in quotes(candidate):
                if quote["quote_id"] in wanted:
                    quote_owners[quote["quote_id"]] = eid

    def known_group(match):
        body = match[1] if match[1] is not None else match[2]
        # Repeated E: prefixes distinguish a list from the evidence/quote pair contract.
        handles = [part.strip() for part in re.split(r"\s*[,，、;；]\s*E:\s*", body)]
        if len(handles) > 1 and all(handle in evidence or handle in quote_owners for handle in handles):
            return "".join("[E:" + quote_owners.get(handle, handle) + "]" for handle in handles)
        annotated = re.fullmatch(r"([eq][0-9a-f]{24})\s+(所在段落)", body.strip())
        if annotated and (annotated[1] in evidence or annotated[1] in quote_owners):
            return "[E:" + quote_owners.get(annotated[1], annotated[1]) + "] " + annotated[2]
        return match[0]

    text = re.sub(r"\[\s*E:\s*([^\]\n]*)\]|〔\s*E:\s*([^〕\n]*)〕", known_group, text)
    text = re.sub(r"\[\s*E:\s*(q[0-9a-f]{24})\s*\]|〔\s*E:\s*(q[0-9a-f]{24})\s*〕",
        lambda match: "[E:" + quote_owners[match[1] or match[2]] + "]"
        if (match[1] or match[2]) in quote_owners else match[0], text)

    def paired(match):
        try:
            resolve_quote(evidence, {"evidence_id": match[1], "quote_id": match[2]})
        except HTTPException:
            return match[0]  # Preserve invalid handles for the validator to reject.
        return "[E:" + match[1] + "]"

    text = re.sub(PAIRED_CITATION, paired, text)
    text = re.sub(
        r"〔\s*E:\s*(e[0-9a-f]{24})\s*〕",
        lambda match: "[E:" + match[1] + "]" if match[1] in evidence else match[0],
        text,
    )
    text = re.sub(
        r"\(\s*E:\s*(e[0-9a-f]{24})\s*\)",
        lambda match: "[E:" + match[1] + "]" if match[1] in evidence else match[0],
        text,
    )
    return re.sub(
        r"\[\s*(?:E:\s*)?(e[0-9a-f]{24})\s*\]",
        lambda match: "[E:" + match[1] + "]" if match[1] in evidence else match[0],
        text,
    )


def validate_report(text, evidence):
    import re

    from aird_agent.evidence.quotes import resolve_quote

    for marker in re.finditer(CITATION_MARKER, text):
        if not citation_syntax_valid(marker[0]):
            raise HTTPException(422, "MALFORMED_CITATION")

    paired = set()
    for match in re.finditer(PAIRED_CITATION, text):
        resolve_quote(evidence, {"evidence_id": match[1], "quote_id": match[2]})
        paired.add(match[1])
    cited = set(re.findall(r"\[\s*E:\s*([a-zA-Z0-9_-]+)\s*\]", text)) | set(
        re.findall(r"\[\s*(e[0-9a-f]{24})\s*\]", text)
    ) | set(re.findall(r"\(\s*E:\s*([a-zA-Z0-9_-]+)\s*\)", text)) | set(
        re.findall(r"〔\s*E:\s*([a-zA-Z0-9_-]+)\s*〕", text)
    ) | paired
    missing = cited - set(evidence)
    if missing:
        raise HTTPException(422, "Report references unknown evidence IDs")
    return sorted(cited)
