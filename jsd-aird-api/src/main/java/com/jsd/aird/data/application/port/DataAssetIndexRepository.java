package com.jsd.aird.data.application.port;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.api.DataAssetSearchFacade;

public interface DataAssetIndexRepository {

    List<DataAssetSearchFacade.DataHit> search(UUID organizationId, String query, List<UUID> scopeIds, int limit);

    int indexPublished(UUID organizationId, List<UUID> assetIds);
}
