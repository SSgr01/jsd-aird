import hashlib
from pathlib import Path
from urllib.parse import urlparse

import pytest

from jsd_aird_ai.errors import FormulaModelError
from jsd_aird_ai.service_v2 import FormulaModelV2Service
from jsd_aird_ai.settings import Settings


def test_local_storage_reads_and_writes_only_beneath_the_configured_root(tmp_path):
    root = tmp_path / "objects"
    root.mkdir()
    service = FormulaModelV2Service(Settings(local_artifact_root=root, allow_file_urls=False))
    target = root / "models" / "bundle.zip"
    data = b"model bundle"
    service._write(target.as_uri(), data)
    assert service._read(target.as_uri(), hashlib.sha256(data).hexdigest()) == data


def test_file_urls_remain_disabled_without_a_local_root(tmp_path):
    service = FormulaModelV2Service(Settings(local_artifact_root=None, allow_file_urls=False))
    with pytest.raises(FormulaModelError, match="file URLs are disabled"):
        service._write((tmp_path / "bundle.zip").as_uri(), b"bundle")


@pytest.mark.parametrize("url", ["file://server/share/bundle.zip", "file:///etc/passwd"])
def test_remote_and_outside_paths_are_rejected(tmp_path, url):
    service = FormulaModelV2Service(Settings(local_artifact_root=tmp_path, allow_file_urls=False))
    with pytest.raises(FormulaModelError, match="outside the local storage root"):
        service._local_artifact_path(urlparse(url))


def test_traversal_and_prefix_siblings_are_rejected(tmp_path):
    root = tmp_path / "objects"
    root.mkdir()
    service = FormulaModelV2Service(Settings(local_artifact_root=root, allow_file_urls=False))
    for path in [root / ".." / "secret.zip", tmp_path / "objects-other" / "bundle.zip"]:
        with pytest.raises(FormulaModelError, match="outside the local storage root"):
            service._write(path.as_uri(), b"bundle")


def test_links_cannot_escape_the_local_root(tmp_path):
    root = tmp_path / "objects"
    root.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    link = root / "linked"
    try:
        link.symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("symbolic links are unavailable")
    service = FormulaModelV2Service(Settings(local_artifact_root=root, allow_file_urls=False))
    with pytest.raises(FormulaModelError, match="outside the local storage root"):
        service._write((link / "bundle.zip").as_uri(), b"bundle")
    assert not (outside / "bundle.zip").exists()
