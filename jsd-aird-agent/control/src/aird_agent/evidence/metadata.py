"""Optional bibliography context. It never changes a source identity or supplies a quote."""

import copy
import json

from fastapi import HTTPException


def metadata_key(item):
    return tuple(item.get(key) for key in ("document_id", "version_id", "publication_id", "bibliography_revision"))


def merge_metadata(previous, incoming):
    values = {metadata_key(item): item for item in previous}
    for item in incoming:
        if not isinstance(item, dict) or any(not item.get(key) for key in ("document_id", "version_id", "publication_id")):
            raise HTTPException(502, "INVALID_DOCUMENT_METADATA")
        values[metadata_key(item)] = copy.deepcopy(item)
    if len(values) > 100:
        raise HTTPException(409, "SOURCE_BUDGET_EXCEEDED")
    return list(values.values())


def metadata_preview(values, budget=6000):
    """Spend only the budget remaining after evidence; keep a real read handle when shrinking."""
    result = []
    for value in merge_metadata([], values):
        item = copy.deepcopy(value)
        summary = item.get("abstract")
        if isinstance(summary, str) and len(summary) > 800:
            item.update(abstract=summary[:800], abstract_truncated=True)
        if len(json.dumps([*result, item], ensure_ascii=False)) > budget:
            if "abstract" in item:
                item.pop("abstract")
                item["abstract_truncated"] = True
        if len(json.dumps([*result, item], ensure_ascii=False)) > budget:
            item = {key: item[key] for key in ("document_id", "version_id", "publication_id", "bibliography_revision", "title", "original_url") if key in item}
            item["metadata_truncated"] = True
        if len(json.dumps([*result, item], ensure_ascii=False)) <= budget:
            result.append(item)
    return result


def attach_metadata(page, values, budget, *, projection=None):
    size = len(json.dumps(projection(page) if projection else page, ensure_ascii=False, default=str))
    metadata = metadata_preview(values, min(6000, max(0, budget - size - 64)))
    if metadata:
        page["document_metadata"] = metadata
    if len(metadata) < len(values):
        page.setdefault("trace", {})["bibliography_omitted_for_budget"] = True
    return page
