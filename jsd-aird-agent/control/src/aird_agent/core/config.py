"""Portable coordinator configuration; identity, business database and credentials belong to adapters."""

from pathlib import Path

from pydantic import BaseModel, Field, SecretStr, model_validator


class RuntimeConfig(BaseModel):
    runtime_url: str = "http://127.0.0.1:8092"
    runtime_secret: SecretStr = Field(min_length=32)
    storage_root: Path
    model_context_window: int = Field(default=128000, ge=16000)
    model_output_tokens: int = Field(default=8192, ge=512)
    max_steps: int = Field(default=32, ge=1, le=128)
    max_model_calls: int = Field(default=128, ge=1, le=512)
    max_tool_calls: int = Field(default=128, ge=1, le=512)
    max_active_seconds: int = Field(default=1800, ge=10)
    parallelism: int = Field(default=4, ge=1, le=8)
    max_running_runs: int = Field(default=4, ge=1, le=32)
    workspace_retention_days: int = Field(default=7, ge=1, le=90)

    @model_validator(mode="after")
    def context_reservation(self):
        if self.model_context_window <= self.model_output_tokens + 8192:
            raise ValueError("Model context must leave room for tools and output")
        return self
