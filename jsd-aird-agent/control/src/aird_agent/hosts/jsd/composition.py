"""JSD composition root; generic execution lives in runtime_core.Coordinator."""

import httpx

from aird_agent.capabilities.fast_qa.history import qa_history
from aird_agent.capabilities.registry import default_registry
from aird_agent.core.coordinator import Coordinator
from aird_agent.hosts.jsd.bindings import JsdBindings
from aird_agent.hosts.jsd.host import JsdHostGateway


class Service(Coordinator):
    qa_history = staticmethod(qa_history)

    def __init__(self, settings, store, *, registry=None, host=None):
        http = httpx.AsyncClient(timeout=120, follow_redirects=False)
        super().__init__(
            settings,
            store,
            registry=registry or default_registry(),
            host=host or JsdHostGateway(settings, http),
            bindings=JsdBindings(),
            http=http,
        )

    async def java(self, principal, action, arguments=None, run_id=None):
        return await self.host_call(principal, action, arguments, run_id)

    @property
    def literature(self):
        return self.bindings.literature
