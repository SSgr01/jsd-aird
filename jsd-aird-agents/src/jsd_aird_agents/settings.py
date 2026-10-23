from pathlib import Path
from urllib.parse import urlsplit

from pydantic import AliasChoices, Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="JSD_AIRD_AGENTS_", env_file=".env", extra="ignore", populate_by_name=True
    )
    host: str = "127.0.0.1"
    port: int = 8091
    database_url: SecretStr
    domain_backend: str = "mock"
    model_backend: str = "openai_compatible"
    actor_token: SecretStr
    approver_token: SecretStr
    outsider_token: SecretStr = SecretStr("")
    model_base_url: str = Field(
        default="", validation_alias=AliasChoices("JSD_AIRD_AGENTS_MODEL_BASE_URL", "JSD_AIRD_MODEL_BASE_URL")
    )
    model_api_key: SecretStr = Field(
        default=SecretStr(""),
        validation_alias=AliasChoices("JSD_AIRD_AGENTS_MODEL_API_KEY", "JSD_AIRD_MODEL_API_KEY"),
    )
    model_name: str = Field(
        default="", validation_alias=AliasChoices("JSD_AIRD_AGENTS_MODEL_NAME", "JSD_AIRD_MODEL_NAME")
    )
    model_timeout_seconds: float = 45
    model_max_output_tokens: int = Field(default=4096, ge=128, le=32000)
    model_enable_thinking: bool | None = None
    model_attempts: int = Field(default=2, ge=1, le=4)
    model_structured_method: str = Field(
        default="function_calling",
        validation_alias=AliasChoices(
            "JSD_AIRD_AGENTS_MODEL_STRUCTURED_METHOD", "JSD_AIRD_MODEL_RESPONSE_FORMAT"
        ),
    )
    context_tokens: int = Field(default=4000, ge=128, le=64000)
    scheduler_poll_seconds: float = 1
    max_steps: int = Field(default=12, ge=1, le=50)
    max_model_calls: int = Field(default=16, ge=1, le=100)
    max_tool_calls: int = Field(default=32, ge=1, le=200)
    max_active_seconds: float = Field(default=300, gt=0)
    read_parallelism: int = Field(default=4, ge=1, le=8)
    tool_timeout_seconds: float = 15
    approval_ttl_seconds: int = Field(default=900, ge=1)
    worker_poll_seconds: float = 0.5
    worker_heartbeat_seconds: float = 2
    worker_stale_seconds: float = 15
    sse_poll_seconds: float = 0.25
    sse_heartbeat_seconds: float = 10
    skill_roots: list[Path] = Field(
        default_factory=lambda: [
            Path(__file__).parent / "app" / "verticals" / "project" / "skills",
            Path(__file__).parent / "app" / "verticals" / "quality" / "skills",
        ]
    )
    skill_instruction_bytes: int = Field(default=32768, ge=256, le=262144)
    skill_package_bytes: int = Field(default=2097152, ge=1024, le=16777216)
    skill_package_files: int = Field(default=128, ge=1, le=512)
    skill_resource_bytes: int = Field(default=65536, ge=256, le=1048576)
    max_selected_skills: int = Field(default=3, ge=1, le=6)

    @classmethod
    def settings_customise_sources(
        cls, settings_cls, init_settings, env_settings, dotenv_settings, file_secret_settings
    ):
        def module_model_settings():
            # A legacy process variable must not override this module's explicit .env.
            preferred = {}
            for suffix in ("BASE_URL", "API_KEY", "NAME", "STRUCTURED_METHOD"):
                key = "JSD_AIRD_AGENTS_MODEL_" + suffix
                for source in (env_settings, dotenv_settings):
                    value = source.env_vars.get(key.lower())
                    if value is not None:
                        preferred[key] = value
                        break
            return preferred

        return init_settings, module_model_settings, env_settings, dotenv_settings, file_secret_settings

    @model_validator(mode="after")
    def validate_boundaries(self):
        database = urlsplit(self.database_url.get_secret_value()).path.strip("/")
        if not database.startswith("jsd_aird_agents"):
            raise ValueError("Use a dedicated database whose name starts with jsd_aird_agents")
        if self.domain_backend != "mock":
            raise ValueError("The real Java adapter has not been accepted; only mock is available")
        if self.host not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("Fixture identity mode must bind to loopback")
        if self.model_backend not in {"disabled", "scripted", "openai_compatible"}:
            raise ValueError("Unsupported model backend")
        tokens = [self.actor_token.get_secret_value(), self.approver_token.get_secret_value()]
        if self.outsider_token.get_secret_value():
            tokens.append(self.outsider_token.get_secret_value())
        if any(len(t) < 16 for t in tokens) or len(set(tokens)) != len(tokens):
            raise ValueError("Distinct actor and approver tokens of at least 16 characters are required")
        if self.model_structured_method not in {"function_calling", "json_schema", "json_mode"}:
            raise ValueError("Unsupported structured output method")
        return self

    @property
    def model_configured(self) -> bool:
        return self.model_backend == "scripted" or bool(
            self.model_backend == "openai_compatible"
            and self.model_base_url
            and self.model_name
            and self.model_api_key.get_secret_value()
        )
