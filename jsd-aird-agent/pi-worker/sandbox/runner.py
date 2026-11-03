"""Container-only entry point. Stdout is a bounded data protocol, never executable paths."""
import base64
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


def main():
    body = json.loads(base64.b64decode(sys.argv[1], validate=True))
    shutil.copytree("/work/source-scratch", "/work/scratch", dirs_exist_ok=True, symlinks=True)
    command = [sys.executable, "-c", body["script"]] if body["language"] == "python" else ["/bin/sh", "-c", body["script"]]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd="/work")
    output, truncated = bytearray(), False
    while chunk := process.stdout.read(8192):
        if len(output) + len(chunk) > 1024 * 1024:
            truncated = True
            process.kill()
            break
        output.extend(chunk)
    code = process.wait()
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
            files.append({"path": path.relative_to("/work/output").as_posix(), "base64": base64.b64encode(path.read_bytes()).decode()})
    print(json.dumps({"exit_code": code, "output": output.decode(errors="replace")[:12000], "truncated": truncated or len(output) > 12000, "files": files}))


if __name__ == "__main__":
    main()
