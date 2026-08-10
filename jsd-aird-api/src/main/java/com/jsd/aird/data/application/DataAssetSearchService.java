package com.jsd.aird.data.application;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.api.DataAssetSearchFacade;
import com.jsd.aird.data.application.port.DataAssetIndexRepository;
import org.springframework.stereotype.Service;

@Service
public class DataAssetSearchService implements DataAssetSearchFacade {

    private final DataAssetIndexRepository repository;

    public DataAssetSearchService(DataAssetIndexRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<DataHit> search(UUID organizationId, String query, List<UUID> scopeIds, int limit) {
        if (query == null || query.isBlank()) return List.of();
        return repository.search(organizationId, query.strip(), scopeIds, Math.min(50, Math.max(1, limit)));
    }

    @Override
    public List<DataHit> search(UUID organizationId, String query, List<UUID> scopeIds,
                                List<UUID> categoryIds, int limit) {
        if (query == null || query.isBlank()) return List.of();
        return repository.search(organizationId, query.strip(), scopeIds, categoryIds,
                Math.min(50, Math.max(1, limit)));
    }

    @Override
    public int indexPublished(UUID organizationId, List<UUID> assetIds) {
        return repository.indexPublished(organizationId, assetIds);
    }
}
