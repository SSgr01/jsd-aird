from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class AlgorithmEquivalence:
    equivalent: bool
    left_hash: str
    right_hash: str
    differing_targets: tuple[str, ...]


def algorithm_equivalence_hash(model_card: dict[str, Any]) -> str:
    try:
        return str(model_card["reproducibility"]["algorithmEquivalenceSha256"])
    except (KeyError, TypeError) as exc:
        raise ValueError("model card does not contain an algorithm-equivalence hash") from exc


def compare_model_cards(
    left: dict[str, Any], right: dict[str, Any]
) -> AlgorithmEquivalence:
    left_hash = algorithm_equivalence_hash(left)
    right_hash = algorithm_equivalence_hash(right)
    left_targets = {
        item["targetCode"]: item.get("scorerType") for item in left.get("targets", [])
    }
    right_targets = {
        item["targetCode"]: item.get("scorerType") for item in right.get("targets", [])
    }
    differing = tuple(
        sorted(
            code
            for code in set(left_targets) | set(right_targets)
            if left_targets.get(code) != right_targets.get(code)
        )
    )
    return AlgorithmEquivalence(
        equivalent=left_hash == right_hash,
        left_hash=left_hash,
        right_hash=right_hash,
        differing_targets=differing,
    )
