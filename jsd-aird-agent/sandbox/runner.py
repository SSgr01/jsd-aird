"""Container-only entry point. Stdout is a bounded data protocol, never executable paths."""
import base64
import json
import os
import selectors
import shutil
import subprocess
import sys
import time
from pathlib import Path

from aird_files import check_file


def main():
    if sys.argv[1] == "--stdin":
        print(json.dumps({"ready": True, "protocol": 3}), flush=True)
        request = sys.stdin.buffer.readline(1024 * 1024 + 1)
        if len(request) > 1024 * 1024:
            raise ValueError("Sandbox request too large")
        body = json.loads(request)
    else:
        body = json.loads(base64.b64decode(sys.argv[1], validate=True))
    started = time.monotonic()
    shutil.copytree("/work/source-scratch", "/work/scratch", dirs_exist_ok=True, symlinks=True)
    prepared = time.monotonic()
    command = [sys.executable, "-c", body["script"]] if body["language"] == "python" else ["/bin/sh", "-c", body["script"]]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd="/work")
    # Only the trusted runner frames output. Child bytes never become protocol objects.
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ, "stdout")
        selector.register(process.stderr, selectors.EVENT_READ, "stderr")
        while selector.get_map():
            for key, _ in selector.select():
                chunk = os.read(key.fd, 16384)
                if chunk:
                    print(json.dumps({"event": "output", "stream": key.data,
                                      "base64": base64.b64encode(chunk).decode()}), flush=True)
                else:
                    selector.unregister(key.fileobj)
    code = process.wait()
    executed = time.monotonic()
    files, total = [], 0
    for root, directories, names in os.walk("/work/output", followlinks=False):
        directories[:] = [name for name in directories if not Path(root, name).is_symlink()]
        for name in names:
            path = Path(root, name)
            if path.is_symlink() or not path.is_file():
                continue
            size = path.stat().st_size
            total += size
            if total > 10 * 1024 * 1024 or len(files) >= 50:
                raise RuntimeError("Artifact export quota exceeded")
            if path.suffix.lower() in {".docx", ".xlsx", ".pdf"}:
                check_file(path)
            files.append({"path": path.relative_to("/work/output").as_posix(), "base64": base64.b64encode(path.read_bytes()).decode()})
    print(json.dumps({"event": "result", "result": {"exit_code": code, "files": files,
        "timings": {"workspace_copy_seconds": round(prepared-started, 3), "script_seconds": round(executed-prepared, 3),
                    "export_seconds": round(time.monotonic()-executed, 3)}}}), flush=True)


if __name__ == "__main__":
    main()
