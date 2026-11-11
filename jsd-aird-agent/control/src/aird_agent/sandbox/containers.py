"""Prestarted, single-use OCI containers. No business input is present while warming."""

import asyncio
import contextlib
import hashlib
import json
import logging
import shutil
import time
from dataclasses import dataclass, field
from uuid import uuid4

from fastapi import HTTPException

from aird_agent.core.workspace import Workspace

logger = logging.getLogger(__name__)
LABEL = "com.jsd.aird.sandbox.scope"


async def bounded(stream, maximum):
    output = bytearray()
    while chunk := await stream.read(65536):
        output.extend(chunk)
        if len(output) > maximum:
            raise HTTPException(413, "SANDBOX_TRANSPORT_OUTPUT_EXCEEDED")
    return bytes(output)


async def docker(*args, timeout=10):
    """All CLI waits, including cleanup, have their own finite deadline."""
    process = await asyncio.create_subprocess_exec(
        "docker", *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
    )
    try:
        async with asyncio.timeout(timeout):
            out, err = await asyncio.gather(bounded(process.stdout, 65536), bounded(process.stderr, 65536))
            return await process.wait(), out, err
    finally:
        if process.returncode is None:
            with contextlib.suppress(ProcessLookupError):
                process.kill()
            with contextlib.suppress(TimeoutError):
                async with asyncio.timeout(2):
                    await process.wait()


@dataclass
class PreparedContainer:
    name: str
    workspace: Workspace
    process: object = None
    stderr: object = None
    released: asyncio.Event = field(default_factory=asyncio.Event)
    cleaned: asyncio.Event = field(default_factory=asyncio.Event)
    cleanup_complete: bool = False
    startup_seconds: float = 0


class ContainerPool:
    def __init__(self, settings, command):
        self.settings, self.command = settings, command
        self.root = (settings.workspace_root / "prepared").resolve()
        self.scope = hashlib.sha256(str(settings.workspace_root.resolve()).encode()).hexdigest()[:24]
        self.ready = asyncio.Queue(maxsize=settings.parallelism)
        self.slots = {}
        self.pending = {}
        self.workers = []
        self.closed = False
        self.reaper = None

    async def start(self):
        if self.closed:
            raise HTTPException(503, "SANDBOX_UNAVAILABLE")
        if not self.workers:
            self.root.mkdir(parents=True, exist_ok=True)
            self.workers = [asyncio.create_task(self.prepare()) for _ in range(self.settings.parallelism)]
            self.reaper = asyncio.create_task(self.reap())

    async def prepare(self):
        while not self.closed:
            identity = uuid4()
            workspace = Workspace(self.root, str(identity), str(uuid4()))
            workspace.prepare()
            slot = PreparedContainer("jsd-analysis-" + identity.hex, workspace)
            self.slots[slot.name] = slot
            started = time.monotonic()
            try:
                async with asyncio.timeout(self.settings.startup_seconds):
                    slot.process = await asyncio.create_subprocess_exec(
                        *self.command(workspace, "", "python", identity.hex, prepared=True),
                        stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
                        limit=16 * 1024 * 1024,
                    )
                    slot.stderr = asyncio.create_task(bounded(slot.process.stderr, 65536))
                    greeting = await slot.process.stdout.readline()
                    if json.loads(greeting) != {"ready": True, "protocol": 3}:
                        raise ValueError("Sandbox image does not support the prepared protocol")
                slot.startup_seconds = time.monotonic() - started
                await self.ready.put(slot)
                await slot.released.wait()
            except asyncio.CancelledError:
                raise
            except Exception as error:
                logger.warning("Sandbox preparation failed container=%s failure=%s", slot.name, type(error).__name__)
            finally:
                try:
                    slot.cleanup_complete = await self.cleanup(slot)
                except Exception as error:
                    logger.warning("Sandbox cleanup pending container=%s failure=%s", slot.name, type(error).__name__)
                finally:
                    slot.cleaned.set()
                    if not slot.cleanup_complete:
                        self.pending[slot.name] = slot
                    self.slots.pop(slot.name, None)
            if not slot.released.is_set() and not self.closed:
                await asyncio.sleep(1)

    async def acquire(self):
        await self.start()
        while True:
            slot = await self.ready.get()
            # A prepared process can exit while idle (e.g. the daemon restarts).
            if slot.process.returncode is None and not slot.cleaned.is_set():
                return slot
            slot.released.set()

    async def release(self, slot):
        slot.released.set()
        await slot.cleaned.wait()
        return slot.cleanup_complete

    async def cleanup(self, slot):
        process = slot.process
        confirmed = process is None or process.returncode == 0
        if not confirmed:
            try:
                code, _, err = await docker("rm", "--force", slot.name, timeout=self.settings.cleanup_seconds)
                confirmed = code == 0
                # A failed/late create is swept again after its CLI has exited.
                if not confirmed and b"No such container" in err:
                    confirmed = process.returncode is not None
            except (OSError, HTTPException, TimeoutError):
                pass
        if process is not None and process.returncode is None:
            with contextlib.suppress(ProcessLookupError):
                process.kill()
            with contextlib.suppress(TimeoutError):
                async with asyncio.timeout(2):
                    await process.wait()
        if slot.stderr:
            slot.stderr.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await slot.stderr
        if confirmed:
            # Container termination determines the execution outcome; slow host
            # directory removal must not hold the receipt or the next command.
            with contextlib.suppress(TimeoutError):
                async with asyncio.timeout(2):
                    await self.remove_staging(slot)
        else:
            logger.warning("Sandbox cleanup pending container=%s", slot.name)
        return confirmed

    async def remove_staging(self, slot):
        # Only the pool's disposable staging directory may be removed.
        target = slot.workspace.root.resolve()
        if target != self.root and target.is_relative_to(self.root) and not slot.workspace.root.is_symlink():
            await asyncio.to_thread(shutil.rmtree, target, True)

    async def reap_once(self):
        code, output, _ = await docker("ps", "-a", "--filter", f"label={LABEL}={self.scope}",
                                       "--format", "{{.Names}}", timeout=self.settings.cleanup_seconds)
        if code:
            return
        for name in output.decode().splitlines():
            if name.startswith("jsd-analysis-") and name not in self.slots:
                code, _, _ = await docker("rm", "--force", name, timeout=self.settings.cleanup_seconds)
                if code == 0 and (slot := self.pending.pop(name, None)):
                    await self.remove_staging(slot)

    async def reap(self):
        while not self.closed:
            try:
                await self.reap_once()
            except (OSError, HTTPException, TimeoutError):
                logger.warning("Sandbox container sweep pending")
            await asyncio.sleep(10)

    async def close(self):
        self.closed = True
        if self.reaper:
            self.reaper.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self.reaper
        for worker in self.workers:
            worker.cancel()
        await asyncio.gather(*self.workers, return_exceptions=True)
        with contextlib.suppress(OSError, HTTPException, TimeoutError):
            await self.reap_once()


def stage_inputs(source, destination):
    """Populate empty, read-only container mounts before releasing its stdin gate."""
    count = size = 0
    for directory in ("input", "scratch"):
        root = source.root / directory
        for path in root.rglob("*"):
            if path.is_symlink():
                raise HTTPException(422, "INVALID_SANDBOX_INPUT")
            if not path.is_file():
                continue
            content = path.read_bytes()
            count += 1
            size += len(content)
            if count > 50 or size > 10 * 1024 * 1024:
                raise HTTPException(413, "SANDBOX_INPUT_BUDGET_EXCEEDED")
            name = directory + "/" + path.relative_to(root).as_posix()
            if directory == "input":
                destination.materialize(name, content)
            else:
                destination.write_bytes(name, content)
