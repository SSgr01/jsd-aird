"""Bounded run-local read reuse. Callers must reauthorize before consulting it."""
import asyncio
import copy
import json
from collections import OrderedDict

from aird_agent.core.contracts import digest
from aird_agent.core.stage_timing import fields


READS = frozenset({
    "business_search", "business_read", "business_links", "business_query",
})


class ReadCache:
    def __init__(self, max_bytes=16 * 1024 * 1024, max_entries=128):
        self.entries, self.pending = OrderedDict(), {}
        self.max_bytes, self.max_entries, self.bytes = max_bytes, max_entries, 0
        self.generation = 0

    def invalidate(self):
        # In-flight reads may finish, but must never repopulate a newer snapshot.
        self.generation += 1
        self.entries.clear()
        self.bytes = 0

    async def read(self, principal, run, name, arguments, operation, validate=None):
        if name not in READS or arguments.get("include_file"):
            return await operation()
        refresh = arguments.get("refresh", False)
        query = {key: value for key, value in arguments.items() if key != "refresh"}
        generation = self.generation
        key = digest([generation, list(principal.key), run["id"], run.get("epoch"), run.get("scope_hash"),
                      run.get("retrieval_policy", {}).get("fingerprint"), name, query])
        if not refresh and key in self.entries:
            fields(read_cache="hit")
            result, _ = self.entries[key]
            self.entries.move_to_end(key)
            if validate:
                await validate(result)
            return copy.deepcopy(result)
        if not refresh and key in self.pending:
            fields(read_cache="shared")
            result = await asyncio.shield(self.pending[key])
            if validate:
                await validate(result)
            return copy.deepcopy(result)
        fields(read_cache="refresh" if refresh else "miss")
        task = asyncio.create_task(operation())
        self.pending[key] = task
        try:
            result = await task
            size = len(json.dumps(result, ensure_ascii=False, default=str).encode())
            if (generation == self.generation and self.pending.get(key) is task
                    and not result.get("error") and result.get("records_complete") is not False
                    and size <= min(2 * 1024 * 1024, self.max_bytes)):
                if key in self.entries:
                    self.bytes -= self.entries.pop(key)[1]
                self.entries[key] = (copy.deepcopy(result), size)
                self.bytes += size
                while self.bytes > self.max_bytes or len(self.entries) > self.max_entries:
                    _, (_, removed) = self.entries.popitem(last=False)
                    self.bytes -= removed
            return result
        finally:
            if self.pending.get(key) is task:
                self.pending.pop(key, None)
