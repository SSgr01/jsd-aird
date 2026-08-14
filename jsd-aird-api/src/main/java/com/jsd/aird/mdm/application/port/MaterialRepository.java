package com.jsd.aird.mdm.application.port;

import com.jsd.aird.mdm.domain.model.Material;
import com.jsd.aird.shared.api.PageResponse;

import java.util.Optional;
import java.util.UUID;

public interface MaterialRepository {
    PageResponse<Material> list(int page, int size);

    PageResponse<Material> listByProject(UUID projectId, String keyword, String category, String owner, int page, int size);

    Optional<Material> findById(UUID id);

    Optional<Material> findByCode(String code);

    void insert(Material record, String operator);

    boolean update(Material record, long currentVersion, String operator);

    boolean delete(UUID id, long version);
}