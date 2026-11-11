"""Bounded, restart-readable execution logs, separate from delivered artifacts."""

import base64
import json
from pathlib import Path

from fastapi import HTTPException


class ExecutionStreams:
    def __init__(self, root: Path, maximum=8 * 1024 * 1024):
        self.root, self.maximum = root, maximum

    def state(self, name):
        path = self.root / (name + ".json")
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"base": 0, "total": 0}

    def append(self, name, content):
        self.root.mkdir(parents=True, exist_ok=True)
        state = self.state(name)
        path = self.root / (name + ".log")
        total = state["total"] + len(content)
        if state["total"] - state["base"] + len(content) > self.maximum:
            prior = path.read_bytes() if path.exists() else b""
            path.write_bytes((prior + content)[-self.maximum:])
            base = max(0, total - self.maximum)
        else:
            with path.open("ab") as stream:
                stream.write(content)
            base = state["base"]
        temporary = self.root / (name + ".tmp")
        temporary.write_text(json.dumps({"base": base, "total": total}), encoding="utf-8")
        temporary.replace(self.root / (name + ".json"))

    def read(self, name, offset=0, limit=65536):
        if name not in {"stdout", "stderr"} or type(offset) is not int or offset < 0:
            raise HTTPException(422, "INVALID_OUTPUT_CURSOR")
        state = self.state(name)
        if offset > state["total"]:
            raise HTTPException(409, "OUTPUT_CURSOR_AHEAD")
        start = max(offset, state["base"])
        path = self.root / (name + ".log")
        content = b""
        if path.exists():
            with path.open("rb") as stream:
                stream.seek(start - state["base"])
                content = stream.read(min(max(1, limit), 65536))
        return {"base64": base64.b64encode(content).decode(), "from_offset": start,
                "next_offset": start + len(content), "total_bytes": state["total"],
                "lossy": offset < state["base"]}

    def summary(self, maximum=12000):
        values, truncated = [], False
        for name in ("stdout", "stderr"):
            state = self.state(name)
            path = self.root / (name + ".log")
            if not path.exists():
                continue
            with path.open("rb") as stream:
                stream.seek(max(0, state["total"] - state["base"] - maximum))
                text = stream.read(maximum).decode("utf-8", errors="replace")
            values.append(("[stderr]\n" if name == "stderr" else "") + text)
            truncated |= state["total"] > maximum
        return "\n".join(values), truncated
