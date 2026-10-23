from pathlib import Path


def make_skill(root: Path, name="sample-workflow", *, body="先读取任务，再形成有证据的建议。", allowed=None):
    folder = root / name
    folder.mkdir(parents=True, exist_ok=True)
    tools = "" if allowed is None else f"allowed-tools: {allowed}\n"
    (folder / "SKILL.md").write_text(
        f'---\nname: {name}\ndescription: 项目任务分析工作流\n{tools}metadata:\n  version: "1.0.0"\n---\n\n{body}\n',
        encoding="utf-8",
    )
    (folder / "references").mkdir(exist_ok=True)
    (folder / "references" / "guide.md").write_text(
        "仅按需加载的参考资料，不能作为业务事实。", encoding="utf-8"
    )
    return folder


async def enable_skill(
    services, root, *, name="sample-workflow", allowed=None, body="读取任务并遵循现有审批。"
):
    folder = make_skill(root, name, allowed=allowed, body=body)
    services.settings.skill_roots = [root]
    scanned = await services.skills.sync()
    assert not scanned["errors"]
    row = scanned["registered"][0]
    await services.skills.set_enabled(name, row["sha256"], True)
    return folder, row
