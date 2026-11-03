"""Workspace paths never grant filesystem access outside the run's execution root."""

import hashlib
import os
import shutil
from pathlib import Path, PurePosixPath

from fastapi import HTTPException


def relative_path(value: str, *, writable: bool = False) -> PurePosixPath:
    path = PurePosixPath(value)
    reserved = {
        "CON",
        "PRN",
        "AUX",
        "NUL",
        "CLOCK$",
        *(f"COM{i}" for i in range(1, 10)),
        *(f"LPT{i}" for i in range(1, 10)),
    }
    if (
        not value
        or "\\" in value
        or ":" in value
        or "\x00" in value
        or path.is_absolute()
        or any(p in {"..", "."} for p in value.split("/"))
        or any(ord(character) < 32 for character in value)
        or any(part.endswith((" ", ".")) or part.split(".")[0].upper() in reserved for part in path.parts)
    ):
        raise HTTPException(400, "Invalid workspace path")
    if path.parts[0] not in ({"scratch", "output"} if writable else {"input", "scratch", "output"}):
        raise HTTPException(403, "Workspace path is outside the permitted roots")
    return path


class Workspace:
    def __init__(self, root: Path, org: str, run_id: str):
        # IDs are validated by the API; still reject traversal at this filesystem boundary.
        from uuid import UUID

        self.base = root.resolve()
        self.root = self.base / str(UUID(org)) / str(UUID(run_id))

    def check_root(self):
        for probe in (self.root.parent, self.root):
            if probe.is_symlink() or (hasattr(os.path, "isjunction") and os.path.isjunction(probe)):
                raise HTTPException(403, "Workspace roots cannot be links")
        if self.root.resolve() != self.root or not self.root.is_relative_to(self.base):
            raise HTTPException(403, "Workspace root escaped storage boundary")

    def discard(self):
        """Remove only this exact canonical UUID/UUID working copy, never authority storage."""
        self.check_root()
        if self.root.exists():
            shutil.rmtree(self.root)

    def prepare(self):
        self.check_root()
        for name in ("input", "scratch", "output"):
            (self.root / name).mkdir(parents=True, exist_ok=True)

    def resolve(self, value: str, *, writable=False) -> Path:
        self.check_root()
        rel = relative_path(value, writable=writable)
        target = self.root.joinpath(*rel.parts)
        probe = self.root
        for part in rel.parts:
            probe /= part
            if probe.is_symlink() or (hasattr(os.path, "isjunction") and os.path.isjunction(probe)):
                raise HTTPException(403, "Links are not permitted in workspaces")
        if not target.resolve().is_relative_to(self.root.resolve()):
            raise HTTPException(403, "Workspace path escaped execution root")
        return target

    def write(self, path: str, content: str):
        if len(content.encode()) > 1024 * 1024:
            raise HTTPException(413, "Workspace file exceeds 1 MiB")
        return self.write_bytes(path, content.encode())

    def write_bytes(self, path: str, content: bytes):
        if len(content) > 10 * 1024 * 1024:
            raise HTTPException(413, "Workspace artifact exceeds 10 MiB")
        target = self.resolve(path, writable=True)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        return {
            "path": path,
            "sha256": hashlib.sha256(content).hexdigest(),
            "bytes": len(content),
        }

    def materialize(self, path: str, content: bytes):
        """Trusted service only: rebuild an authorized input projection, never an agent write."""
        if relative_path(path).parts[0] != "input" or len(content) > 10 * 1024 * 1024:
            raise HTTPException(400, "Invalid input projection")
        target = self.resolve(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        return {"path": path, "sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content)}

    def read(self, path: str, offset: int = 0, limit: int = 12000):
        if offset < 0 or limit < 1 or limit > 12000:
            raise HTTPException(400, "Invalid read window")
        target = self.resolve(path)
        if not target.is_file() or target.stat().st_size > 10 * 1024 * 1024:
            raise HTTPException(400, "File missing or too large for text reading")
        content = target.read_text(encoding="utf-8")
        return {
            "path": path,
            "content": content[offset : offset + limit],
            "next_offset": offset + limit if offset + limit < len(content) else None,
        }

    def manifest(self):
        files = []
        for path in self.root.rglob("*"):
            if path.is_symlink() or not path.is_file():
                continue
            safe = self.resolve(path.relative_to(self.root).as_posix())
            files.append({"path": safe.relative_to(self.root).as_posix(), "bytes": safe.stat().st_size})
            if len(files) >= 500:
                break
        return {"files": files, "limited": len(files) == 500}
