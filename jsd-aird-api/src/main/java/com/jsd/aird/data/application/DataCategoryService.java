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

    @Transactional
    public DataCategoryRepository.Category create(String name, String description) {
        var actor = ActorContext.required();
        return repository.create(actor.organizationId(), actor.userId(), normalizeName(name), normalizeDescription(description));
    }

    @Transactional
    public DataCategoryRepository.Category rename(UUID id, String name, String description) {
        var actor = ActorContext.required();
        require(actor.organizationId(), id);
        return repository.rename(actor.organizationId(), id, normalizeName(name), normalizeDescription(description));
    }

    @Transactional
    public void delete(UUID id, UUID replacementId) {
        var actor = ActorContext.required();
        var category = require(actor.organizationId(), id);
        if (replacementId != null) {
            var replacement = require(actor.organizationId(), replacementId);
        }
        repository.delete(actor.organizationId(), id, replacementId);
    }

    private DataCategoryRepository.Category require(UUID organizationId, UUID id) {
        return repository.find(organizationId, id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据分类不存在"));
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
