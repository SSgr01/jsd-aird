package com.jsd.aird.data.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataCategoryRepository {

    List<Category> list(UUID organizationId);

    Optional<Category> find(UUID organizationId, UUID categoryId);

    Optional<Category> findForTargetType(UUID organizationId, String targetDataType);

    Category create(UUID organizationId, UUID actorId, String name, String targetDataType);

    Category rename(UUID organizationId, UUID categoryId, String name);

    void delete(UUID organizationId, UUID categoryId, UUID replacementCategoryId);

    int assignAsset(UUID organizationId, UUID assetId, UUID categoryId);

    record Category(UUID id, String name, String targetDataType, int sortOrder, long assetCount) {
    }
}
