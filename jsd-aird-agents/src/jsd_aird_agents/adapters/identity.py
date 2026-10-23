import secrets

from jsd_aird_agents.adapters.fixtures import ACTOR, APPROVER, OUTSIDER, principals
from jsd_aird_agents.errors import AgentError


class FixtureIdentity:
    """Local simulated identity, never an implementation of Java authentication."""

    def __init__(self, settings):
        self.principals = principals()
        self.tokens = [
            (settings.actor_token.get_secret_value(), ACTOR),
            (settings.approver_token.get_secret_value(), APPROVER),
            (settings.outsider_token.get_secret_value(), OUTSIDER),
        ]

    def authenticate(self, authorization):
        if not authorization or not authorization.startswith("Bearer "):
            raise AgentError("AUTH_REQUIRED", "需要本机演示凭证", 401)
        token = authorization[7:]
        for expected, actor_id in self.tokens:
            if expected and secrets.compare_digest(token, expected):
                return self.lookup(actor_id)
        raise AgentError("AUTH_REQUIRED", "无效演示凭证", 401)

    def lookup(self, actor_id):
        principal = self.principals.get(str(actor_id))
        if not principal:
            raise AgentError("AUTH_REQUIRED", "模拟身份已停用", 401)
        return principal
