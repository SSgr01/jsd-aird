"""JSD composition root; DeepSeek owns the native execution loop."""

import httpx

from aird_agent.evidence.retrieval_context import qa_history
from aird_agent.capabilities.registry import default_registry
from aird_agent.core.business_service import BusinessService
from aird_agent.hosts.jsd.bindings import JsdBindings
from aird_agent.hosts.jsd.host import JsdHostGateway
from aird_agent.hosts.jsd.session_views import SessionViews


class Service(SessionViews, BusinessService):
    qa_history = staticmethod(qa_history)

    def __init__(self, settings, store, *, registry=None, host=None):
        http = httpx.AsyncClient(timeout=120, follow_redirects=False, trust_env=False)
        super().__init__(
            settings,
            store,
            registry=registry or default_registry(),
            host=host or JsdHostGateway(settings, http),
            bindings=JsdBindings(),
            http=http,
        )

        from aird_agent.hosts.jsd.briefings import Briefings
        self.briefings = Briefings(self)

    async def java(self, principal, action, arguments=None, run_id=None):
        return await self.host_call(principal, action, arguments, run_id)

    @property
    def literature(self):
        return self.bindings.literature
