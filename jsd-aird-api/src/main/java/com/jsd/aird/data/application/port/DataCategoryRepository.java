package com.jsd.aird.data.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataCategoryRepository {

    List<Category> list(UUID organizationId);

    Optional<Category> find(UUID organizationId, UUID categoryId);

    Category create(UUID organizationId, UUID actorId, String name, String description);

    Category rename(UUID organizationId, UUID categoryId, String name, String description);

    void delete(UUID organizationId, UUID categoryId, UUID replacementCategoryId);

    record Category(UUID id, String name, String description, int sortOrder, long sourceCount) {
    }
}
