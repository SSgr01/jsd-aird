from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd
from sklearn.metrics import pairwise_distances

from jsd_aird_ai.contracts import (
    ApplicabilityDomainEvidence,
    ApplicabilityDomainPolicy,
    DomainStatus,
)
from jsd_aird_ai.features import FeatureLayout
from jsd_aird_ai.model_adapters import common_numeric_preprocessor


@dataclass
class ApplicabilityDomainModel:
    """Training-data support envelope bound to one target's effective rows."""

    layout: FeatureLayout
    policy: ApplicabilityDomainPolicy
    preprocessor: object
    reference_matrix: np.ndarray
    reference_row_ids: tuple[str, ...]
    numeric_observed: dict[str, tuple[float, float]]
    numeric_robust: dict[str, tuple[float, float]]
    categorical_values: dict[str, tuple[str, ...]]
    near_boundary_threshold: float
    out_of_domain_threshold: float

    def assess(self, features: pd.DataFrame) -> list[ApplicabilityDomainEvidence]:
        transformed = np.asarray(self.preprocessor.transform(features), dtype=float)
        nearest_indices = np.empty(len(features), dtype=int)
        nearest_distances = np.empty(len(features), dtype=float)
        distance_scale = max(np.sqrt(self.reference_matrix.shape[1]), 1.0)
        # Keep 20,000-row scoring bounded: never allocate the full query x reference
        # distance matrix in one block.
        for start in range(0, len(features), 2_048):
            stop = min(start + 2_048, len(features))
            distances = pairwise_distances(
                transformed[start:stop], self.reference_matrix, metric="euclidean"
            )
            distances /= distance_scale
            local_indices = np.argmin(distances, axis=1)
            nearest_indices[start:stop] = local_indices
            nearest_distances[start:stop] = distances[
                np.arange(stop - start), local_indices
            ]
        results: list[ApplicabilityDomainEvidence] = []

        for row_index, (_, row) in enumerate(features.iterrows()):
            unknown: list[str] = []
            outside: list[str] = []
            boundary: list[str] = []
            reasons: list[str] = []

            for column, allowed in self.categorical_values.items():
                if str(row[column]) not in allowed:
                    unknown.append(column)
            for column, (minimum, maximum) in self.numeric_observed.items():
                value = float(row[column])
                if value < minimum or value > maximum:
                    outside.append(column)
                else:
                    robust_minimum, robust_maximum = self.numeric_robust[column]
                    if value < robust_minimum or value > robust_maximum:
                        boundary.append(column)

            distance = float(nearest_distances[row_index])
            if not self.policy.enabled:
                results.append(
                    ApplicabilityDomainEvidence(
                        status=DomainStatus.IN_DOMAIN,
                        model_usable=True,
                        nearest_training_row_id=self.reference_row_ids[
                            int(nearest_indices[row_index])
                        ],
                        nearest_distance=distance,
                        near_boundary_threshold=self.near_boundary_threshold,
                        out_of_domain_threshold=self.out_of_domain_threshold,
                        reasons=["APPLICABILITY_DOMAIN_DISABLED"],
                    )
                )
                continue
            if unknown:
                reasons.append("UNKNOWN_CATEGORY")
            if outside:
                reasons.append("NUMERIC_OUTSIDE_OBSERVED_RANGE")
            if distance > self.out_of_domain_threshold:
                reasons.append("DISTANCE_EXCEEDS_OUT_OF_DOMAIN_THRESHOLD")
            elif distance > self.near_boundary_threshold:
                reasons.append("DISTANCE_EXCEEDS_NEAR_BOUNDARY_THRESHOLD")
            if boundary:
                reasons.append("NUMERIC_NEAR_TRAINING_BOUNDARY")

            if unknown or outside or distance > self.out_of_domain_threshold:
                status = DomainStatus.OUT_OF_DOMAIN
            elif boundary or distance > self.near_boundary_threshold:
                status = DomainStatus.NEAR_BOUNDARY
            else:
                status = DomainStatus.IN_DOMAIN
            usable = not (
                status == DomainStatus.OUT_OF_DOMAIN
                and self.policy.out_of_domain_requires_fallback
            )
            results.append(
                ApplicabilityDomainEvidence(
                    status=status,
                    model_usable=usable,
                    nearest_training_row_id=self.reference_row_ids[
                        int(nearest_indices[row_index])
                    ],
                    nearest_distance=distance,
                    near_boundary_threshold=self.near_boundary_threshold,
                    out_of_domain_threshold=self.out_of_domain_threshold,
                    unknown_categories=unknown,
                    outside_observed_range=outside,
                    near_boundary_features=boundary,
                    reasons=reasons,
                )
            )
        return results


def fit_applicability_domain(
    features: pd.DataFrame,
    layout: FeatureLayout,
    row_ids: np.ndarray,
    lineage_groups: np.ndarray,
    source_groups: np.ndarray,
    policy: ApplicabilityDomainPolicy,
) -> ApplicabilityDomainModel:
    processor = common_numeric_preprocessor(layout)
    reference = np.asarray(processor.fit_transform(features), dtype=float)
    normalized = pairwise_distances(reference, reference, metric="euclidean")
    normalized /= max(np.sqrt(reference.shape[1]), 1.0)
    np.fill_diagonal(normalized, np.inf)

    isolated_distances = np.empty(len(features), dtype=float)
    for index in range(len(features)):
        isolated = (lineage_groups != lineage_groups[index]) & (
            source_groups != source_groups[index]
        )
        if not np.any(isolated):
            isolated = np.arange(len(features)) != index
        isolated_distances[index] = float(np.min(normalized[index, isolated]))

    near = float(
        np.quantile(
            isolated_distances,
            policy.near_boundary_distance_quantile,
            method="higher",
        )
    )
    outside = float(
        np.quantile(
            isolated_distances,
            policy.out_of_domain_distance_quantile,
            method="higher",
        )
    )
    outside = max(outside, near)
    numeric_observed = {
        column: (float(features[column].min()), float(features[column].max()))
        for column in layout.numeric
    }
    numeric_robust = {
        column: (
            float(features[column].quantile(policy.robust_lower_quantile)),
            float(features[column].quantile(policy.robust_upper_quantile)),
        )
        for column in layout.numeric
    }
    categorical_values = {
        column: tuple(sorted(features[column].astype(str).unique().tolist()))
        for column in layout.categorical
    }
    return ApplicabilityDomainModel(
        layout=layout,
        policy=policy,
        preprocessor=processor,
        reference_matrix=reference,
        reference_row_ids=tuple(str(item) for item in row_ids),
        numeric_observed=numeric_observed,
        numeric_robust=numeric_robust,
        categorical_values=categorical_values,
        near_boundary_threshold=near,
        out_of_domain_threshold=outside,
    )
