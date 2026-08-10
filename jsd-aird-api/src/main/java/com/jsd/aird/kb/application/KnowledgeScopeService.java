package com.jsd.aird.kb.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.KnowledgeScopeFacade;
import com.jsd.aird.kb.application.port.KnowledgeScopeRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeScopeService implements KnowledgeScopeFacade {

    private static final Set<String> TYPES = Set.of("PROJECT", "PRODUCT", "KNOWLEDGE_BASE", "DATA_ASSET");
    private static final Set<String> RESOURCE_TYPES = Set.of("KNOWLEDGE_DOCUMENT", "KNOWLEDGE_VERSION", "DATA_ASSET", "DATA_ASSET_REVISION");

    private final KnowledgeScopeRepository repository;
    private final ObjectMapper objectMapper;

    public KnowledgeScopeService(KnowledgeScopeRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ScopeView> list(UUID organizationId, String scopeType, String keyword) {
        var type = normalizedType(scopeType, false);
        return repository.list(organizationId, type, keyword).stream().map(this::view).toList();
    }

    @Override
    public ScopeView create(UUID organizationId, UUID actorId, CreateScope command) {
        var type = normalizedType(command.scopeType(), true);
        if (command.externalId() == null || command.externalId().isBlank() || command.name() == null || command.name().isBlank()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "范围 externalId 和 name 不能为空");
        }
        var row = new KnowledgeScopeRepository.ScopeRow(UUID.randomUUID(), organizationId, type,
                command.externalId().trim(), command.name().trim(), "ACTIVE",
                command.metadata() == null ? objectMapper.createObjectNode() : command.metadata().deepCopy());
        repository.insert(row);
        return view(row);
    }

    @Override
    public List<ScopeResource> resources(UUID organizationId, UUID scopeId) {
        require(organizationId, scopeId);
        return repository.resources(organizationId, scopeId).stream()
                .map(row -> new ScopeResource(row.scopeId(), row.resourceType(), row.resourceId(), row.relationType())).toList();
    }

    @Override
    public void attach(UUID organizationId, UUID scopeId, AttachResource resource) {
        require(organizationId, scopeId);
        var type = resource.resourceType() == null ? "" : resource.resourceType().trim().toUpperCase(Locale.ROOT);
        if (!RESOURCE_TYPES.contains(type) || resource.resourceId() == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的范围资源类型");
        }
        repository.attach(scopeId, type, resource.resourceId(),
                resource.relationType() == null || resource.relationType().isBlank() ? "IN_SCOPE" : resource.relationType());
    }

    @Override
    public Set<UUID> validate(UUID organizationId, List<UUID> scopeIds, List<String> scopeTypes) {
        if (scopeIds == null || scopeIds.isEmpty()) return Set.of();
        var requested = scopeIds.stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        var existing = repository.list(organizationId, null, null).stream().map(KnowledgeScopeRepository.ScopeRow::id)
                .filter(requested::contains).collect(java.util.stream.Collectors.toSet());
        if (existing.size() != requested.size()) throw new ApiException(ApiErrorCode.NOT_FOUND, "部分 AI 范围不存在");
        if (scopeTypes != null && !scopeTypes.isEmpty()) {
            var normalized = scopeTypes.stream().map(value -> normalizedType(value, true)).collect(java.util.stream.Collectors.toSet());
            repository.list(organizationId, null, null).stream().filter(row -> requested.contains(row.id()))
                    .filter(row -> !normalized.contains(row.scopeType())).findAny()
                    .ifPresent(row -> { throw new ApiException(ApiErrorCode.BAD_REQUEST, "范围类型与范围 ID 不一致"); });
        }
        return existing;
    }

    private KnowledgeScopeRepository.ScopeRow require(UUID organizationId, UUID id) {
        return repository.find(organizationId, id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "AI 范围不存在"));
    }

    private String normalizedType(String value, boolean required) {
        var type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!required && type.isBlank()) return null;
        if (!TYPES.contains(type)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的 AI 范围类型");
        return type;
    }

    private ScopeView view(KnowledgeScopeRepository.ScopeRow row) {
        return new ScopeView(row.id(), row.scopeType(), row.externalId(), row.name(), row.status(), row.metadata());
    }
}
