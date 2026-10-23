import os
from types import SimpleNamespace

import pytest
from skill_helpers import make_skill

from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.skills.catalog import SkillLoader, resource_path


def loader(root, **overrides):
    settings = dict(
        skill_roots=[root],
        skill_instruction_bytes=32768,
        skill_package_bytes=2097152,
        skill_package_files=128,
        skill_resource_bytes=65536,
    )
    return SkillLoader(SimpleNamespace(**{**settings, **overrides}))


def test_standard_metadata_manifest_and_incremental_resource_loading(tmp_path):
    folder = make_skill(tmp_path, allowed="list_project_tasks")
    (folder / "assets").mkdir()
    (folder / "assets" / "binary.bin").write_bytes(b"\x00\xff")
    catalog = loader(tmp_path)
    packages, errors = catalog.scan()
    assert not errors and len(packages) == 1
    package = packages[0]
    assert package.metadata["metadata"]["version"] == "1.0.0"
    assert "references/guide.md" in package.manifest
    assert "仅按需加载" not in package.instructions
    assert (
        catalog.resource(package.source, "assets/binary.bin", package.manifest["assets/binary.bin"])
        == b"\x00\xff"
    )
    (folder / "references" / "guide.md").write_text("changed", encoding="utf-8")
    with pytest.raises(AgentError) as exc:
        catalog.resource(package.source, "references/guide.md", package.manifest["references/guide.md"])
    assert exc.value.code == "SKILL_CONTENT_CHANGED"
    assert catalog.package(folder).sha256 != package.sha256


@pytest.mark.parametrize(
    "path",
    [
        "../secret",
        "/etc/passwd",
        "references/../SKILL.md",
        "C:/secret",
        "references\\secret",
        "https://example.com/x",
        "scripts//tool.py",
        "assets/.env",
        "SKILL.md",
        "references/./x",
    ],
)
def test_resource_paths_cannot_escape_the_selected_package(path):
    with pytest.raises(AgentError) as exc:
        resource_path(path)
    assert exc.value.code == "SKILL_PATH_UNSAFE"


@pytest.mark.parametrize(
    "frontmatter",
    [
        "name: other-name\ndescription: text",
        "name: sample-workflow\nname: sample-workflow\ndescription: duplicate",
        "name: sample-workflow\ndescription: [bad]",
        "name: sample-workflow\ndescription: &data value\nmetadata: *data",
        "name: sample-workflow\ndescription: !!python/object/apply:os.system ['echo not-executed']",
        "name: sample-workflow\ndescription: fine\nmetadata:\n  version: 1.0",
    ],
)
def test_invalid_or_executable_yaml_is_rejected(frontmatter, tmp_path):
    folder = make_skill(tmp_path)
    (folder / "SKILL.md").write_text(f"---\n{frontmatter}\n---\nbody", encoding="utf-8")
    packages, errors = loader(tmp_path).scan()
    assert not packages and errors[0]["code"] == "SKILL_INVALID"


def test_hardlink_resource_is_rejected(tmp_path):
    folder = make_skill(tmp_path)
    outside = tmp_path / "outside.txt"
    outside.write_text("outside", encoding="utf-8")
    os.link(outside, folder / "references" / "linked.txt")
    packages, errors = loader(tmp_path).scan()
    assert not packages and errors[0]["code"] == "SKILL_PATH_UNSAFE"


def test_limits_and_missing_roots_are_reported(tmp_path):
    make_skill(tmp_path)
    packages, errors = loader(tmp_path, skill_package_files=1).scan()
    assert not packages and errors[0]["code"] == "SKILL_SIZE_EXCEEDED"
    packages, errors = loader(tmp_path / "missing").scan()
    assert not packages and errors[0]["code"] == "SKILL_ROOT_UNAVAILABLE"


def test_duplicate_names_in_configured_roots_cannot_shadow_each_other(tmp_path):
    left, right = tmp_path / "left", tmp_path / "right"
    make_skill(left)
    make_skill(right)
    catalog = loader(left)
    catalog.settings.skill_roots = [left, right]
    packages, errors = catalog.scan()
    assert not packages and errors[0]["code"] == "SKILL_NAME_COLLISION"
