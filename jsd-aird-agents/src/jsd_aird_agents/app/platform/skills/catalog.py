from __future__ import annotations

import hashlib
import os
import re
import stat
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

import yaml

from jsd_aird_agents.contracts import digest
from jsd_aird_agents.errors import AgentError

NAME = r"[a-z0-9]+(?:-[a-z0-9]+)*"


class SkillYamlLoader(yaml.SafeLoader):
    def compose_node(self, parent, index):
        if self.check_event(yaml.AliasEvent):
            raise ValueError("YAML aliases are not supported")
        return super().compose_node(parent, index)

    def construct_mapping(self, node, deep=False):
        mapping = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            if not isinstance(key, str) or key in mapping:
                raise ValueError("Duplicate or non-string YAML key")
            mapping[key] = self.construct_object(value_node, deep=deep)
        return mapping


def regular_path(path: Path):
    info = path.lstat()
    if not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
        raise AgentError("SKILL_PATH_UNSAFE", "技能只能包含普通目录和文件", 422)
    if path.is_symlink() or getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
        raise AgentError("SKILL_PATH_UNSAFE", "技能目录不能包含符号链接或重解析点", 422)
    if path.is_file() and info.st_nlink > 1:
        raise AgentError("SKILL_PATH_UNSAFE", "技能文件不能使用硬链接", 422)


def bounded_read(path: Path, limit: int) -> bytes:
    regular_path(path)
    with path.open("rb") as source:
        content = source.read(limit + 1)
    if len(content) > limit:
        raise AgentError("SKILL_SIZE_EXCEEDED", "技能内容超过配置上限", 422)
    return content


def resource_path(value: str) -> str:
    if not value or any(c in value for c in ("\\", ":", "\x00", "\n", "\r")):
        raise AgentError("SKILL_PATH_UNSAFE", "技能资源必须使用受控相对路径", 422)
    parts = value.split("/")
    if (
        len(parts) < 2
        or parts[0] not in {"references", "assets", "scripts"}
        or any(p in {"", ".", ".."} or p.startswith(".") for p in parts)
        or PurePosixPath(value).is_absolute()
    ):
        raise AgentError("SKILL_PATH_UNSAFE", "资源只能来自 references、assets 或 scripts", 422)
    return value


@dataclass(frozen=True)
class SkillPackage:
    name: str
    sha256: str
    metadata: dict
    instructions: str
    manifest: dict
    source: str


class SkillLoader:
    def __init__(self, settings):
        self.settings = settings

    def package(self, folder: Path) -> SkillPackage:
        regular_path(folder)
        entry = bounded_read(folder / "SKILL.md", self.settings.skill_instruction_bytes)
        try:
            text = entry.decode("utf-8-sig")
            lines = text.splitlines()
            if not lines or lines[0] != "---":
                raise ValueError("Missing frontmatter")
            end = lines.index("---", 1)
            metadata = yaml.load("\n".join(lines[1:end]), Loader=SkillYamlLoader)
            if not isinstance(metadata, dict):
                raise ValueError("Frontmatter must be a mapping")
            name, description = metadata.get("name"), metadata.get("description")
            if (
                not isinstance(name, str)
                or not re.fullmatch(NAME, name)
                or len(name) > 64
                or name != folder.name
            ):
                raise ValueError("Invalid name or directory mismatch")
            if not isinstance(description, str) or not 1 <= len(description.strip()) <= 1024:
                raise ValueError("Invalid description")
            for key, limit in (("compatibility", 500), ("license", 1024), ("allowed-tools", 4096)):
                if key in metadata and (
                    not isinstance(metadata[key], str) or not 1 <= len(metadata[key]) <= limit
                ):
                    raise ValueError("Invalid optional field")
            if not isinstance(metadata.get("metadata", {}), dict) or any(
                not isinstance(k, str) or not isinstance(v, str)
                for k, v in metadata.get("metadata", {}).items()
            ):
                raise ValueError("metadata must map strings to strings")
            # Only standard, supported metadata is persisted; hooks and unknown extension fields never execute.
            metadata = {
                k: metadata[k]
                for k in ("name", "description", "license", "compatibility", "metadata", "allowed-tools")
                if k in metadata
            }
        except (UnicodeError, ValueError, yaml.YAMLError, RecursionError) as exc:
            raise AgentError("SKILL_INVALID", "SKILL.md 不符合 Agent Skills 格式", 422) from exc
        manifest, total, directory_count = {}, 0, 0
        for parent, directories, names in os.walk(folder, followlinks=False):
            directory_count += 1
            if directory_count > 256 or len(Path(parent).relative_to(folder).parts) > 16:
                raise AgentError("SKILL_SIZE_EXCEEDED", "技能目录深度或数量超过上限", 422)
            directories[:] = sorted(d for d in directories if d not in {".git", "__pycache__"})
            for directory in directories:
                regular_path(Path(parent) / directory)
            for filename in sorted(names):
                path = Path(parent) / filename
                content = bounded_read(path, self.settings.skill_package_bytes)
                total += len(content)
                manifest[path.relative_to(folder).as_posix()] = {
                    "sha256": hashlib.sha256(content).hexdigest(),
                    "bytes": len(content),
                }
                if (
                    total > self.settings.skill_package_bytes
                    or len(manifest) > self.settings.skill_package_files
                ):
                    raise AgentError("SKILL_SIZE_EXCEEDED", "技能包超过配置上限", 422)
        if manifest["SKILL.md"]["sha256"] != hashlib.sha256(entry).hexdigest():
            raise AgentError("SKILL_CONTENT_CHANGED", "扫描期间技能内容发生变化", 409)
        return SkillPackage(
            name,
            digest(manifest),
            metadata,
            "\n".join(lines[end + 1 :]).strip(),
            manifest,
            str(folder.absolute()),
        )

    def scan(self):
        packages, errors, seen = [], [], set()
        for root in self.settings.skill_roots:
            try:
                regular_path(root)
                folders = sorted(p for p in root.iterdir() if p.is_dir() and not p.name.startswith("."))
                if len(folders) > 128:
                    raise AgentError("SKILL_SIZE_EXCEEDED", "技能目录数量超过上限", 422)
            except (OSError, AgentError) as exc:
                errors.append(
                    {
                        "source": str(root),
                        "code": exc.code if isinstance(exc, AgentError) else "SKILL_ROOT_UNAVAILABLE",
                    }
                )
                continue
            for folder in folders:
                try:
                    package = self.package(folder)
                    if package.name in seen:
                        raise AgentError("SKILL_NAME_COLLISION", "配置的技能根目录存在重名技能", 409)
                    seen.add(package.name)
                    packages.append(package)
                except (OSError, AgentError) as exc:
                    errors.append(
                        {
                            "source": str(folder),
                            "code": exc.code if isinstance(exc, AgentError) else "SKILL_UNREADABLE",
                        }
                    )
        # A collision cannot silently shadow a different package with the same trusted name.
        collisions = {Path(e["source"]).name for e in errors if e["code"] == "SKILL_NAME_COLLISION"}
        return [p for p in packages if p.name not in collisions], errors

    def resource(self, source: str, relative: str, expected: dict) -> bytes:
        relative = resource_path(relative)
        root = Path(source)
        regular_path(root)
        current = root
        for part in relative.split("/"):
            current = current / part
            regular_path(current)
        if not current.resolve().is_relative_to(root.resolve()):
            raise AgentError("SKILL_PATH_UNSAFE", "资源超出技能目录", 422)
        content = bounded_read(current, self.settings.skill_resource_bytes)
        if len(content) != expected["bytes"] or hashlib.sha256(content).hexdigest() != expected["sha256"]:
            raise AgentError("SKILL_CONTENT_CHANGED", "技能资源已改变，请注册和审核新版本", 409)
        return content
