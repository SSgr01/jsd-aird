package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.port.MaterialRepository;
import com.jsd.aird.mdm.domain.model.Material;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

@Service
public class MaterialService {

    private final MaterialRepository repository;

    public MaterialService(MaterialRepository repository) {
        this.repository = repository;
    }

    public PageResponse<Material> list(int page, int size) {
        return repository.list(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
    }

    public PageResponse<Material> listByProject(UUID projectId, String keyword, String category, String owner, int page, int size) {
        return buildMockMaterials(keyword, category, owner, page, size);
    }

    private PageResponse<Material> buildMockMaterials(String keyword, String category, String owner, int page, int size) {
        var now = Instant.now();
        var all = List.of(
                mockMaterial("CH-0001", "UA-1117树脂红外谱图", "IR", "图谱中心", "UV高硬度树脂项目", now),
                mockMaterial("CH-0002", "UA-1117树脂核磁谱图", "NMR", "图谱中心", "UV高硬度树脂项目", now),
                mockMaterial("CH-0003", "原料批次质检报告", "QC", "质检中心", "UV高硬度树脂项目", now),
                mockMaterial("CH-0004", "工艺规程文件", "SOP", "工艺中心", "UV高硬度树脂项目", now),
                mockMaterial("CH-0005", "热重分析曲线", "TGA", "图谱中心", "通用树脂平台", now),
                mockMaterial("CH-0006", "差示扫描量热曲线", "DSC", "图谱中心", "通用树脂平台", now),
                mockMaterial("CH-0007", "供应商资质证明", "CERT", "采购中心", "UV高硬度树脂项目", now),
                mockMaterial("CH-0008", "安全标准说明书", "MSDS", "EHS中心", "通用树脂平台", now)
        );
        var filtered = all.stream()
                .filter(m -> keyword == null || keyword.isBlank()
                        || m.code().toLowerCase().contains(keyword.toLowerCase())
                        || m.name().toLowerCase().contains(keyword.toLowerCase()))
                .filter(m -> category == null || category.isBlank() || category.equals(m.category()))
                .filter(m -> owner == null || owner.isBlank() || owner.equals(m.sourceModule()))
                .collect(Collectors.toList());
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        var items = new ArrayList<>(filtered.subList(from, to));
        long total = filtered.size();
        return new PageResponse<>(items, page, size, total, (total + size - 1) / size);
    }

    private Material mockMaterial(String code, String name, String category, String sourceCategory, String sourceModule, Instant now) {
        var id = UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8));
        return new Material(id, code, name, category, sourceCategory, sourceModule,
                "DEVELOP", "张工", "ACTIVE", "演示数据", 0, now, now, false);
    }

    public Material get(UUID id) {
        return repository.findById(id).orElseThrow(notFound("资料不存在"));
    }

    @Transactional
    public UUID create(Material input) {
        Optional.ofNullable(repository.findByCode(input.code())).ifPresent(r -> {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "资料编号已存在");
        });
        var now = Instant.now();
        var value = new Material(
                UUID.randomUUID(),
                input.code(),
                input.name(),
                input.category(),
                input.sourceCategory(),
                input.sourceModule(),
                input.stage(),
                input.contactPerson(),
                input.status() == null ? "DRAFT" : input.status(),
                input.description(),
                0,
                now,
                now,
                false
        );
        repository.insert(value, CrmServiceSupport.OPERATOR);
        return value.id();
    }

    @Transactional
    public void update(UUID id, Material input) {
        var current = get(id);
        var value = new Material(
                id,
                current.code(),
                input.name(),
                input.category(),
                input.sourceCategory(),
                input.sourceModule(),
                input.stage(),
                input.contactPerson(),
                input.status() == null ? current.status() : input.status(),
                input.description(),
                input.version(),
                current.createdAt(),
                Instant.now(),
                false
        );
        if (!repository.update(value, current.version(), CrmServiceSupport.OPERATOR)) CrmServiceSupport.conflict();
    }

    @Transactional
    public void delete(UUID id, long version) {
        get(id);
        if (!repository.delete(id, version)) CrmServiceSupport.conflict();
    }

    private static Supplier<ApiException> notFound(String message) {
        return () -> new ApiException(ApiErrorCode.NOT_FOUND, message);
    }
}