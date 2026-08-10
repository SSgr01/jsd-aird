package com.jsd.aird.data.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.data.application.port.DataCategoryRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DataCategoryService {

    private static final Set<String> TYPES = Set.of("MATERIAL", "FORMULA", "PROCESS", "EQUIPMENT", "TEST_STANDARD");
    private final DataCategoryRepository repository;

    public DataCategoryService(DataCategoryRepository repository) {
        this.repository = repository;
    }

    public List<DataCategoryRepository.Category> list() {
        return repository.list(ActorContext.required().organizationId());
    }

    @Transactional
    public DataCategoryRepository.Category create(String name, String targetDataType) {
        var actor = ActorContext.required();
        return repository.create(actor.organizationId(), actor.userId(), normalizeName(name), normalizeType(targetDataType));
    }

    @Transactional
    public DataCategoryRepository.Category rename(UUID id, String name) {
        var actor = ActorContext.required();
        require(actor.organizationId(), id);
        return repository.rename(actor.organizationId(), id, normalizeName(name));
    }

    @Transactional
    public void delete(UUID id, UUID replacementId) {
        var actor = ActorContext.required();
        var category = require(actor.organizationId(), id);
        if (replacementId != null) {
            var replacement = require(actor.organizationId(), replacementId);
            if (category.targetDataType() != null && replacement.targetDataType() != null
                    && !category.targetDataType().equals(replacement.targetDataType())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "替代分类的数据类型不匹配");
            }
        }
        repository.delete(actor.organizationId(), id, replacementId);
    }

    @Transactional
    public void assignAsset(UUID assetId, UUID categoryId) {
        var actor = ActorContext.required();
        require(actor.organizationId(), categoryId);
        if (repository.assignAsset(actor.organizationId(), assetId, categoryId) == 0) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "资产不存在或分类与数据类型不匹配");
        }
    }

    public DataCategoryRepository.Category defaultForTargetType(UUID organizationId, String targetDataType) {
        return repository.findForTargetType(organizationId, targetDataType).orElse(null);
    }

    private DataCategoryRepository.Category require(UUID organizationId, UUID id) {
        return repository.find(organizationId, id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据分类不存在"));
    }

    private String normalizeType(String value) {
        if (!StringUtils.hasText(value)) return null;
        var type = value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据类型不受支持");
        return type;
    }

    private String normalizeName(String value) {
        if (!StringUtils.hasText(value)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类名称不能为空");
        var name = value.trim();
        if (name.length() > 120) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类名称不能超过 120 个字符");
        return name;
    }
}
