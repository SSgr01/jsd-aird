package com.jsd.aird.data.application;

import java.util.List;
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

    private final DataCategoryRepository repository;

    public DataCategoryService(DataCategoryRepository repository) {
        this.repository = repository;
    }

    public List<DataCategoryRepository.Category> list() {
        return repository.list(ActorContext.required().organizationId());
    }

    public DataCategoryRepository.DataSourceFacets facets() {
        return repository.facets(ActorContext.required().organizationId());
    }

    @Transactional
    public DataCategoryRepository.Category create(String name, String description) {
        var actor = ActorContext.required();
        var normalizedName = normalizeName(name);
        ensureNameAvailable(actor.organizationId(), normalizedName, null);
        return repository.create(actor.organizationId(), actor.userId(), normalizedName, normalizeDescription(description));
    }

    @Transactional
    public DataCategoryRepository.Category rename(UUID id, String name, String description) {
        var actor = ActorContext.required();
        require(actor.organizationId(), id);
        var normalizedName = normalizeName(name);
        ensureNameAvailable(actor.organizationId(), normalizedName, id);
        return repository.rename(actor.organizationId(), id, normalizedName, normalizeDescription(description));
    }

    @Transactional
    public void delete(UUID id, UUID replacementId) {
        var actor = ActorContext.required();
        if (replacementId != null) {
            if (id.equals(replacementId)) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "替代分类不能是待删除分类本身");
            }
            require(actor.organizationId(), replacementId);
        }
        require(actor.organizationId(), id);
        repository.delete(actor.organizationId(), id, replacementId);
    }

    private DataCategoryRepository.Category require(UUID organizationId, UUID id) {
        return repository.find(organizationId, id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据分类不存在"));
    }

    private void ensureNameAvailable(UUID organizationId, String name, UUID excludingCategoryId) {
        repository.lockCategoryName(organizationId, name);
        if (repository.categoryNameExists(organizationId, name, excludingCategoryId)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "数据分类名称已存在");
        }
    }

    private String normalizeName(String value) {
        if (!StringUtils.hasText(value)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类名称不能为空");
        var name = value.trim();
        if (name.length() > 120) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类名称不能超过 120 个字符");
        return name;
    }

    private String normalizeDescription(String value) {
        if (!StringUtils.hasText(value)) return null;
        var description = value.trim();
        if (description.length() > 240) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类简介不能超过 240 个字符");
        return description;
    }
}
