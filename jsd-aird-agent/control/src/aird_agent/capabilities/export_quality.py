"""Compatibility entry point; format and literature checks have separate owners."""

from aird_agent.capabilities.files.quality import validate_format
from aird_agent.capabilities.literature.export import validate_literature_export


def validate_export(path, content, source_quotes):
    validate_literature_export(path, validate_format(path, content), source_quotes)
