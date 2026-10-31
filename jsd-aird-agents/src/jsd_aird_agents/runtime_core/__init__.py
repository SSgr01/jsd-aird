"""Engine/host-neutral contracts, not a second scheduler or Agent loop."""

from .capabilities import CapabilityPack, CapabilityRegistry, ExecutionContext

__all__ = ["CapabilityPack", "CapabilityRegistry", "ExecutionContext"]
