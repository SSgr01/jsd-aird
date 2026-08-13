package com.jsd.aird.data.application.port;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.api.DataAssetSearchFacade;

public interface DataAssetIndexRepository {

    List<DataAssetSearchFacade.DataHit> search(UUID organizationId, String query, List<UUID> scopeIds, int limit);

    default List<DataAssetSearchFacade.DataHit> search(UUID organizationId, String query, List<UUID> scopeIds,
                                                       List<UUID> categoryIds, int limit) {
        return search(organizationId, query, scopeIds, limit);
    }

    default List<DataAssetSearchFacade.DataHit> searchHybrid(UUID organizationId, String query, String queryVector,
                                                             int vectorDimension, List<UUID> scopeIds,
                                                             List<UUID> categoryIds, int limit) {
        return search(organizationId, query, scopeIds, categoryIds, limit);
    }

    int indexPublished(UUID organizationId, List<UUID> assetIds);
}
