import pytest
from uuid import uuid4
from fastapi import HTTPException
from aird_agent.core.workspace import Workspace
from aird_agent.sandbox.client import Sandbox


def test_literal_brackets_and_unicode_are_exact_file_paths(tmp_path, settings):
    workspace = Workspace(tmp_path, str(uuid4()), str(uuid4()))
    workspace.prepare()
    name = 'scratch/原始实验/客户[20250325](1).docx'
    workspace.write_bytes(name, b'original')
    _, result = Sandbox(settings).inputs(workspace, [name])
    assert result['files'][0]['path'] == name
    assert result['files'][0]['sha256']


@pytest.mark.parametrize('name', ['scratch/*.xlsx', 'scratch/a?.xlsx', 'scratch/../../outside.xlsx'])
def test_wildcards_and_escape_remain_rejected(tmp_path, settings, name):
    workspace = Workspace(tmp_path, str(uuid4()), str(uuid4()))
    workspace.prepare()
    with pytest.raises(HTTPException):
        Sandbox(settings).inputs(workspace, [name])
