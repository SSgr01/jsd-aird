package com.jsd.aird.data.api;

import java.util.List;
import java.util.UUID;

/** Read-only boundary used by the AI module; it never exposes arbitrary SQL. */
public interface DataAssetSearchFacade {

    List<DataHit> search(UUID organizationId, String query, List<UUID> scopeIds, int limit);

    int indexPublished(UUID organizationId, List<UUID> assetIds);

    record DataHit(UUID entryId, UUID scopeId, UUID assetId, UUID revisionId, Integer rowNumber,
                   String fieldCode, String assetName, String content, double score, String sourceLocator) {
    }
}
