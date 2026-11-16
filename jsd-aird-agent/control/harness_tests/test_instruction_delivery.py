"""Exercise the actual JSD policy and incremental binding seen by native dsh."""

from dataclasses import replace
from importlib.resources import files
from types import SimpleNamespace

import pytest

from aird_agent.capabilities.files.definitions import FILE_INSTRUCTIONS
from aird_agent.capabilities.registry import (
    ANSWER_INSTRUCTIONS,
    CORE_INSTRUCTIONS,
    LITERATURE_INSTRUCTIONS,
    PROJECT_INSTRUCTIONS,
    default_registry,
)
from aird_agent.core.binding_projection import binding_projection
from aird_agent.core.capabilities import CapabilityRegistry
from aird_agent.hosts.jsd.bindings import JsdBindings


def policy(settings):
    bindings = JsdBindings()
    bindings.settings, bindings.packs = settings, default_registry()
    return bindings


@pytest.mark.parametrize("selected", [
    ["core"], ["core", "literature"], ["core", "files"], ["core", "project-readonly"],
    ["core", "literature", "files", "project-readonly"],
    ["literature"], ["files"],
])
def test_jsd_delivers_shared_file_and_only_selected_capability_guidance(settings, selected):
    bindings = policy(settings)
    prompt = bindings.instructions_for({"pack_ids": selected})
    original = files("aird_agent.capabilities").joinpath("answer_guidelines.txt").read_text(encoding="utf-8").strip()
    assert ANSWER_INSTRUCTIONS == original and original != CORE_INSTRUCTIONS
    assert prompt.count(original) == 1
    assert "涉及过程总耗时时" in prompt
    assert "未给时长的装夹、转移等步骤不能按零计" in prompt
    for pack, instructions in [("literature", LITERATURE_INSTRUCTIONS),
                               ("files", FILE_INSTRUCTIONS), ("project-readonly", PROJECT_INSTRUCTIONS)]:
        assert (instructions in prompt) == (pack in selected)


def test_legacy_runs_keep_literature_guidance_and_shared_rules(settings):
    prompt = policy(settings).instructions_for({})
    assert ANSWER_INSTRUCTIONS in prompt and LITERATURE_INSTRUCTIONS in prompt


@pytest.mark.parametrize("sandbox_enabled", [False, True])
def test_expert_role_keeps_selected_guidance_sandbox_policy_and_pack_versions(settings, sandbox_enabled):
    from aird_agent.hosts.jsd.experts import role_instructions

    bindings = policy(settings.model_copy(update={"sandbox_enabled": sandbox_enabled}))
    expert = {"name": "材料研发参谋", "instructions": "核对实验条件与适用边界。",
              "skills": ["research-design"]}
    prompt = bindings.instructions_for({"pack_ids": ["core", "literature"], "expert": expert})
    assert prompt.count(ANSWER_INSTRUCTIONS) == 1
    assert LITERATURE_INSTRUCTIONS in prompt and FILE_INSTRUCTIONS not in prompt
    assert prompt.endswith(role_instructions(expert))
    assert ("当前命令沙箱已启用" in prompt) == sandbox_enabled
    assert ("当前命令沙箱未启用" in prompt) != sandbox_enabled
    versions = bindings.packs.pin(["core", "literature"])
    assert versions["core"]["version"] == "3"
    assert versions["literature"]["version"] == "5"


def test_instruction_delta_tracks_actual_policy_and_sandbox_availability(settings):
    bindings = policy(settings.model_copy(update={"sandbox_enabled": False}))
    run = {"id": "run", "dsh_turn": 1, "epoch": 1, "pack_ids": ["core", "literature"]}
    service = SimpleNamespace(bindings=bindings, settings=settings,
        tools_for=bindings.tools_for, instructions_for=bindings.instructions_for)
    first = binding_projection(service, run, {}, "{}")
    assert ANSWER_INSTRUCTIONS in first["instructions"]
    assert "先读取 literature-review 技能" in first["instructions"]
    assert "当前命令沙箱未启用" in first["instructions"]
    cursor = {"binding_etags": first["binding_etags"]}
    assert "instructions" not in binding_projection(service, run, cursor, "{}")

    before = bindings.packs.pin(run["pack_ids"])
    changed = CapabilityRegistry()
    for pack in bindings.packs.select(run["pack_ids"]):
        changed.register(replace(pack, instructions=pack.instructions + "\nChanged shared policy")
                         if pack.id == "core" else pack)
    bindings.packs = changed
    after = binding_projection(service, run, cursor, "{}")
    assert "Changed shared policy" in after["instructions"]
    assert after["binding_etags"]["instructions"] != first["binding_etags"]["instructions"]
    assert bindings.packs.pin(run["pack_ids"])["core"] != before["core"]

    bindings.settings = settings.model_copy(update={"sandbox_enabled": True})
    enabled = binding_projection(service, run, {"binding_etags": after["binding_etags"]}, "{}")
    assert "当前命令沙箱已启用" in enabled["instructions"]
    assert "当前命令沙箱未启用" not in enabled["instructions"]
