"""Actionable citation diagnostics and bounded repair against existing evidence."""

import re

from fastapi import HTTPException

from aird_agent.evidence.citations import CITATION_MARKER, citation_syntax_valid
from aird_agent.evidence.quotes import resolve_quote

MARKER = re.compile(CITATION_MARKER)
READERS = frozenset({"evidence_read", "evidence_context", "evidence_image_read", "document_read", "private_document_read", "table_read"})


def diagnostics(run, text, reason):
    evidence = run.get("evidence", {})
    issues = []
    for match in MARKER.finditer(text):
        handle = next(group for group in match.groups() if group is not None).strip()
        parts = [part.strip() for part in handle.split(",")]
        code = None
        if not citation_syntax_valid(match[0]):
            code = "MALFORMED_CITATION"
        elif parts[0] not in evidence:
            code = "UNKNOWN_EVIDENCE_ID"
        elif len(parts) > 1:
            try:
                resolve_quote(evidence, {"evidence_id": parts[0], "quote_id": parts[1]})
            except HTTPException:
                code = "QUOTE_HANDLE_INVALID_OR_WRONG_EVIDENCE"
        if code:
            issues.append({"code": code, "citation": match[0], "start": match.start(), "end": match.end(),
                           "line": text.count("\n", 0, match.start()) + 1,
                           "excerpt": text[max(0, match.start() - 80):min(len(text), match.end() + 80)]})
    if not issues:
        issues.append({"code": reason, "scope": "answer"})
    delivered = set(run.get("evidence_delivery", {})) | set(run.get("turn_evidence_ids", []))
    available = [{**evidence[eid], "evidence_id": eid} for eid in evidence if eid in delivered]
    # Keep an exact known handle useful even for legacy runs without delivery receipts.
    if not available:
        available = [{**item, "evidence_id": eid} for eid, item in evidence.items()]
    words = set(re.findall(r"[A-Za-z0-9]{3,}|[\u4e00-\u9fff]{2,6}", " ".join(
        issue.get("excerpt", text[:500]) for issue in issues[:8]).lower()))
    available.sort(key=lambda item: sum(word in ((item.get("text") or "") + " " +
        (item.get("source", {}).get("title") or "")).lower() for word in words), reverse=True)
    shortlist = []
    for item in available[:12]:
        source = item.get("source", {})
        anchor = source.get("anchor") or {}
        shortlist.append({"evidence_id": item["evidence_id"], "title": source.get("title"),
                          "page": anchor.get("page_no") or anchor.get("page") or
                          (anchor.get("original") or {}).get("page"),
                          "excerpt": (item.get("model_text") or item.get("text") or "")[:160]})
    return {"code": reason, "issues": issues[:8], "omitted_issues": max(0, len(issues) - 8),
            "available_evidence": shortlist, "available_evidence_count": len(available),
            "instruction": "位置基于将已知原文链接规范为[E:证据ID]后的答复，按excerpt定位。仅用确实支持该事实的证据替换引用；缺少依据时说明缺口。"}


def authorize_read(run, name, arguments):
    """Repair cannot discover new sources or start new business mutations."""
    repair = run.get("output_repair")
    if not repair:
        return
    if name == "artifact_save":
        return  # Existing native present still verifies file hashes and registration receipts.
    if name not in READERS:
        raise HTTPException(409, "CITATION_REPAIR_TARGETED_READ_ONLY")
    if repair["model_calls"] >= repair["max_model_calls"]:
        raise HTTPException(409, "CITATION_REPAIR_FINAL_RESPONSE_REQUIRED")
    allowed = set(repair["allowed_evidence_ids"])
    if name in {"evidence_read", "evidence_context", "evidence_image_read"}:
        if arguments.get("evidence_id") in allowed:
            return
    else:
        for eid in allowed:
            source = run.get("evidence", {}).get(eid, {}).get("source", {})
            if (source.get("document_id") == arguments.get("document_id")
                    and source.get("publication_id") == arguments.get("publication_id")
                    and (name != "private_document_read" or source.get("kind") in {"RUN_UPLOAD", "EXTERNAL_FULLTEXT"})):
                return
    raise HTTPException(409, "CITATION_REPAIR_SOURCE_NOT_READ")
