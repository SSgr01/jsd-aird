from uuid import uuid4

import pytest
from pydantic import ValidationError

from aird_agent.hosts.jsd.bindings import JsdBindings
from aird_agent.server.contracts import RunRequest


def test_conversation_authorization_uses_its_own_id_for_private_source_scope():
    cid = str(uuid4())
    assert (
        JsdBindings.context_request({"id": cid, "private_source_ids": [str(uuid4())]})["conversation_id"]
        == cid
    )
    assert JsdBindings.context_request({"id": str(uuid4()), "conversation_id": cid})["conversation_id"] == cid


def test_fast_qa_does_not_silently_ignore_attached_fulltext_sources():
    with pytest.raises(ValidationError):
        RunRequest(request="read", profile="fast_qa", private_source_ids=[uuid4()])


@pytest.mark.parametrize("kind", ["RUN_UPLOAD", "EXTERNAL_FULLTEXT", "KB_PUBLICATION"])
def test_registered_cards_count_all_authorized_fulltext_kinds_for_completion(kind):
    source = {"kind": kind, "document_id": str(uuid4()), "publication_id": str(uuid4())}
    output = JsdBindings.output_receipt(
        None, {"evidence": {}}, "evidence_save", {"fields": [{"source": source}]}, {"id": str(uuid4())}
    )
    assert output["publications"] == [{key: source[key] for key in ("document_id", "publication_id")}]
