import base64
import json
from uuid import uuid4

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from aird_agent.hosts.jsd.request_inputs import request_inputs
from aird_agent.server.contracts import ContextRef


def reference(**patch):
    return {"kind": "SPECTRUM", "resource_id": str(uuid4()), "version_id": "a" * 64, "pages": [2, 4], **patch}


def marker(ref):
    token = base64.urlsafe_b64encode(json.dumps(ref).encode()).decode().rstrip("=")
    return f"[样品A · 第2、4页](jsd-resource:{token})"


def test_spectrum_selection_is_message_scoped_and_keeps_original_pages_and_version():
    ref = reference()
    parsed = request_inputs("请比较峰形。" + marker(ref))
    assert parsed["context_refs"] == [ref]
    assert parsed["retrieval_scope"] == {}
    assert "资料引用：样品A" in parsed["request"]
    assert "jsd-resource:" not in parsed["request"]
    assert request_inputs("继续解释这两页")["context_refs"] == []


@pytest.mark.parametrize("patch", [
    {"pages": []}, {"pages": [0]}, {"pages": [2, 2]}, {"pages": list(range(1, 7))},
    {"pages": [True]}, {"pages": ["2"]}, {"pages": [2.5]},
    {"version_id": None}, {"version_id": "unversioned"},
])
def test_spectrum_selection_rejects_invalid_pages_or_missing_original_version(patch):
    with pytest.raises(ValidationError):
        ContextRef.model_validate(reference(**patch))


def test_page_limits_apply_across_all_spectra_in_a_message():
    text = "\n".join(marker(reference(pages=[1, 2, 3, 4, 5])) for _ in range(5))
    with pytest.raises(HTTPException, match="SPECTRUM_PAGE_LIMIT_EXCEEDED"):
        request_inputs(text)
