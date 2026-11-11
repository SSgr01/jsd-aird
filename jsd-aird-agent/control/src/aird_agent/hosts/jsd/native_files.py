"""Transport between native dsh capabilities and the existing sandbox/receipt services."""
import base64
import hashlib
import io
import zipfile
from pathlib import PurePosixPath

from fastapi import HTTPException

from aird_agent.core.contracts import digest
from aird_agent.core.workspace import Workspace

MAX_BYTES = 10 * 1024 * 1024


def relative_file(value):
    path = PurePosixPath(value)
    if not value or path.is_absolute() or any(p in {"", ".", ".."} for p in value.split("/")) or any(c in value for c in "\\:\x00"):
        raise HTTPException(422, "INVALID_NATIVE_FILE_PATH")
    return path.as_posix()


def storage_path(path):
    path = relative_file(path)
    return "output/native-" + digest(path)[:16] + "/" + PurePosixPath(path).name


def decode_files(values):
    files, total = {}, 0
    if len(values) > 50:
        raise HTTPException(413, "SANDBOX_FILE_COUNT_EXCEEDED")
    for value in values:
        name = relative_file(value["path"])
        content = base64.b64decode(value["base64"], validate=True)
        total += len(content)
        if name in files or total > MAX_BYTES or hashlib.sha256(content).hexdigest() != value["sha256"]:
            raise HTTPException(422, "INVALID_NATIVE_FILE_TRANSFER")
        files[name] = content
    return files


def read_export(content):
    files, total = [], 0
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        if len(archive.infolist()) > 50:
            raise HTTPException(413, "SANDBOX_FILE_COUNT_EXCEEDED")
        names = set()
        for entry in archive.infolist():
            name = relative_file(entry.orig_filename)
            total += entry.file_size
            if entry.is_dir() or name in names or total > MAX_BYTES or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise HTTPException(422, "INVALID_NATIVE_EXPORT")
            value = archive.read(entry)
            names.add(name)
            files.append({"path": name, "base64": base64.b64encode(value).decode(), "sha256": hashlib.sha256(value).hexdigest()})
    return files


# The sandbox's unchanged runner exports only output/. Package a bounded native
# working copy there; never mount a host directory writable inside the container.
EXPORT_SCRIPT = r'''
import os, pathlib, stat, zipfile
root=pathlib.Path("/work/scratch/native")
count=total=0
with zipfile.ZipFile("/work/output/native-files.zip","w",compression=zipfile.ZIP_DEFLATED) as archive:
    for parent, directories, names in os.walk(root,followlinks=False):
        # Native process logs are already persisted by the subprocess provider.
        # They may be read as inputs, but are never redelivered as business files.
        directories[:]=[name for name in directories if name!='.dsh-process-output' and not pathlib.Path(parent,name).is_symlink()]
        for name in names:
            path=pathlib.Path(parent,name)
            if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode): continue
            count+=1;total+=path.stat().st_size
            if count>50 or total>10485760: raise RuntimeError("Native workspace export quota exceeded")
            archive.write(path,path.relative_to(root).as_posix())
'''


async def native_execute(service, principal, run, args, call_id):
    workspace = Workspace(service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"])
    workspace.prepare()
    files = decode_files(args["files"])
    # Each invocation gets its own transfer paths; stale scratch copies never leak into execution.
    prefix = "scratch/native-" + digest(call_id)[:16] + "/"
    selected = []
    for name, content in files.items():
        path = prefix + name
        workspace.write_bytes(path, content)
        selected.append(path)
    command = args["command"]
    cwd = args.get("cwd", "")
    if cwd:
        cwd = relative_file(cwd)
    script = "import os,pathlib,shutil,subprocess,sys\n"
    script += "root=pathlib.Path('/work/scratch/native');root.mkdir(parents=True,exist_ok=True)\n"
    script += "shutil.copytree(" + repr("/work/" + prefix.rstrip('/')) + ",root,dirs_exist_ok=True)\n" if selected else ""
    script += "env=os.environ.copy()\n"
    script += "overrides=" + repr(args.get("env", {})) + "\n"
    script += "for key,value in overrides.items():\n if value is None: env.pop(key,None)\n else: env[key]=value\n"
    script += "code=subprocess.run(['/bin/bash','-c'," + repr(command) + "],cwd=root/" + repr(cwd) + ",env=env"
    script += (",input=" + repr(args["stdin"]) + ",text=True" if "stdin" in args else "") + ").returncode\n"
    script += EXPORT_SCRIPT + "\nsys.exit(code)\n"
    if len(script) > 65536:
        raise HTTPException(422, "SANDBOX_SCRIPT_TOO_LARGE: write the program with native write/apply_patch, then run it with a short bash command")
    result = await service.bindings.sandbox.execute(workspace, script, "python", digest([run["id"], run["epoch"], call_id])[:32], selected,
        **({"timeout_ms": args["timeout_ms"]} if "timeout_ms" in args else {}))
    if "error" in result and result.get("exit_code") is None:
        return result
    return accept_native_result(workspace, result)


def accept_native_result(workspace, result):
    """Unpack only the export bound to this broker receipt, never a stale ZIP."""
    export = next((item for item in result.get("artifacts", []) if item["path"] == "output/native-files.zip"), None)
    if not export and result.get("error"):
        return result
    if not export or result.get("receipt", {}).get("status") != "SUCCEEDED":
        raise HTTPException(502, "NATIVE_EXPORT_RECEIPT_MISSING")
    content = workspace.resolve(export["path"]).read_bytes()
    if hashlib.sha256(content).hexdigest() != export["sha256"]:
        raise HTTPException(502, "NATIVE_EXPORT_HASH_MISMATCH")
    exported = read_export(content)
    artifacts = []
    for value in exported:
        content = base64.b64decode(value["base64"])
        path = storage_path(value["path"])
        # A sandbox receipt binds every exported file, even if the command exited nonzero.
        workspace.write_bytes(path, content)
        artifacts.append({"path": path, "sha256": value["sha256"], "bytes": len(content)})
    result.update(files=exported, artifacts=artifacts)
    return result


async def reconcile_native(service, principal, run, operation):
    """Recover authoritative bytes and lineage without resubmitting the command."""
    workspace = Workspace(service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"])
    execution_id = digest([run["id"], operation["execution_epoch"], operation["call_id"]])[:32]
    authority = await service.bindings.sandbox.reconcile(workspace, execution_id)
    if authority.get("status") != "SUCCEEDED":
        return authority
    files = decode_files(operation["arguments"]["files"])
    prefix = "scratch/native-" + digest(operation["call_id"])[:16] + "/"
    for name, content in files.items():
        # The committed call payload is the original transfer, not a new execution.
        workspace.write_bytes(prefix + name, content)
    authority["result"]["inputs"] = service.bindings.sandbox.prepare(workspace, [prefix + name for name in files])
    authority["result"] = accept_native_result(workspace, authority["result"])
    return authority


async def native_output(service, principal, body):
    """Read only the execution admitted for this owner, session, turn and tool call."""
    run = await service.session_run(principal, body)
    await service.authorized_topic(principal, run, execution=False)
    call_id = body.get("call_id")
    if not isinstance(call_id, str) or not 1 <= len(call_id) <= 200:
        raise HTTPException(422, "INVALID_CALL_ID")
    offsets = [body.get("stdout_offset", 0), body.get("stderr_offset", 0)]
    if any(type(value) is not int or value < 0 for value in offsets):
        raise HTTPException(422, "INVALID_OUTPUT_CURSOR")
    async with service.store.pool.connection() as conn:
        operation = await (await conn.execute(
            "SELECT tool_name,execution_epoch FROM agent_runtime.harness_calls WHERE run_id=%s AND call_id=%s",
            (run["id"], call_id))).fetchone()
    if operation is None:
        if run["status"] in {"RUNNING", "WAITING_APPROVAL"}:
            return {"status": "QUEUED", "streams": {}, "pending_registration": True}
        raise HTTPException(404, "OPERATION_NOT_FOUND")
    if operation["tool_name"] != "dsh_execute":
        raise HTTPException(403, "NATIVE_EXECUTION_REQUIRED")
    execution_id = digest([run["id"], operation["execution_epoch"], call_id])[:32]
    return await service.bindings.sandbox.output(execution_id, *offsets)


async def native_artifact(service, principal, run, args, call_id):
    name = relative_file(args["path"])
    content = base64.b64decode(args["base64"], validate=True)
    if len(content) > MAX_BYTES or hashlib.sha256(content).hexdigest() != args["sha256"]:
        raise HTTPException(422, "INVALID_NATIVE_FILE_TRANSFER")
    workspace = Workspace(service.settings.storage_root / "workspaces", str(principal.organization_id), run["id"])
    workspace.prepare()
    path = storage_path(name)
    workspace.write_bytes(path, content)
    return await service.bindings.files.dispatch(principal, run, "artifact_save", {"path": path, "source_quotes": args.get("source_quotes", [])}, call_id)


def native_spec(name):
    from aird_agent.capabilities.schema import STRING, tool
    if name == "dsh_execute":
        # Native bash carries scripts, rather than short business text fields.
        return tool(name, "Internal native shell transport", {"command": {"type": "string", "maxLength": 128 * 1024}, "cwd": STRING,
            "timeout_ms": {"type": "integer", "minimum": 1, "maximum": 600000},
            "env": {"type": "object", "maxProperties": 128, "additionalProperties": {"type": ["string", "null"], "maxLength": 8192}},
            "stdin": {"type": "string", "maxLength": 65536},
            "files": {"type": "array", "maxItems": 50}}, ["command", "files"])
    if name == "artifact_save":
        encoded_file = {"type": "string", "maxLength": 4 * ((MAX_BYTES + 2) // 3)}
        return tool(name, "Internal native artifact transport", {"path": STRING, "base64": encoded_file, "sha256": STRING, "source_quotes": {"type": "array", "maxItems": 50}}, ["path", "base64", "sha256"])
    raise HTTPException(422, "UNKNOWN_NATIVE_OPERATION")
