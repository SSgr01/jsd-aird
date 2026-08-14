package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.port.MaterialRepository;
import com.jsd.aird.mdm.domain.model.Material;
import com.jsd.aird.mdm.infrastructure.model.MaterialRow;
import com.jsd.aird.shared.api.PageResponse;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisMaterialRepository implements MaterialRepository {

    private final MaterialMapper mapper;

    public MyBatisMaterialRepository(MaterialMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResponse<Material> list(int page, int size) {
        int offset = (Math.max(page, 1) - 1) * size;
        var rows = mapper.list(offset, size);
        var total = mapper.count();
        return new PageResponse<>(rows.stream().map(this::toDomain).toList(), page, size, total, (total + size - 1) / size);
    }

    @Override
    public PageResponse<Material> listByProject(UUID projectId, String keyword, String category, String owner, int page, int size) {
        int offset = (Math.max(page, 1) - 1) * size;
        var rows = mapper.listByProject(projectId, keyword, category, owner, offset, size);
        var total = mapper.countByProject(projectId, keyword, category, owner);
        return new PageResponse<>(rows.stream().map(this::toDomain).toList(), page, size, total, (total + size - 1) / size);
    }

    @Override
    public Optional<Material> findById(UUID id) {
        var row = mapper.findById(id);
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    public Optional<Material> findByCode(String code) {
        var row = mapper.findByCode(code);
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    public void insert(Material record, String operator) {
        mapper.insert(toRow(record), operator);
    }

    @Override
    public boolean update(Material record, long currentVersion, String operator) {
        return mapper.update(toRow(record), currentVersion, operator) > 0;
    }

    @Override
    public boolean delete(UUID id, long version) {
        return mapper.delete(id, version) > 0;
    }

    private Material toDomain(MaterialRow row) {
        return new Material(
                row.id(),
                row.code(),
                row.name(),
                row.category(),
                row.sourceCategory(),
                row.sourceModule(),
                row.stage(),
                row.contactPerson(),
                row.status(),
                row.description(),
                row.version(),
                row.createdAt(),
                row.updatedAt(),
                row.linked()
        );
    }

    private MaterialRow toRow(Material value) {
        return new MaterialRow(
                value.id(),
                value.code(),
                value.name(),
                value.category(),
                value.sourceCategory(),
                value.sourceModule(),
                value.stage(),
                value.contactPerson(),
                value.status(),
                value.description(),
                value.version(),
                value.createdAt(),
                value.updatedAt(),
                false
        );
    }
}