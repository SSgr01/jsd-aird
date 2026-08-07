package com.jsd.aird.tpl.application;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.StandardFieldRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class StandardFieldService {

    private final StandardFieldRepository repository;

    public StandardFieldService(StandardFieldRepository repository) {
        this.repository = repository;
    }

    public List<StandardFieldRepository.StandardField> search(String keyword, String valueType) {
        return repository.search(keyword, valueType);
    }

    @Transactional
    public StandardFieldRepository.Request request(RequestCommand command) {
        var actor = ActorContext.required();
        if (!repository.belongsToOrganization(actor.organizationId(), command.templateVersionId())) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "模板版本不存在");
        }
        if (!StringUtils.hasText(command.displayName())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "标准字段名称不能为空");
        }
        return repository.insertRequest(new StandardFieldRepository.RequestDraft(
                UUID.randomUUID(), actor.organizationId(), command.templateVersionId(), command.fieldId(),
                command.displayName().trim(), normalizeType(command.valueType()), normalizeUiType(command.uiType()),
                trimToNull(command.groupCode()), trimToNull(command.description()), actor.userId()
        ));
    }

    public List<StandardFieldRepository.Request> requests(String status) {
        var actor = ActorContext.required();
        requireAdmin(actor.organizationId(), actor.userId());
        return repository.listRequests(actor.organizationId(), trimToNull(status));
    }

    @Transactional
    public StandardFieldRepository.StandardField approve(UUID requestId, ApprovalCommand command) {
        var actor = ActorContext.required();
        requireAdmin(actor.organizationId(), actor.userId());
        var request = repository.findRequest(actor.organizationId(), requestId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "标准字段申请不存在"));
        if (!"PENDING".equals(request.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "该申请已经处理，不能重复审核");
        }
        var fieldCode = StringUtils.hasText(command.fieldCode())
                ? normalizeCode(command.fieldCode())
                : generatedCode(request);
        var approved = repository.approveRequest(
                actor.organizationId(), requestId, actor.userId(), fieldCode, trimToNull(command.reviewComment())
        );
        repository.backfillTemplateField(actor.organizationId(), request, approved);
        return approved;
    }

    @Transactional
    public void reject(UUID requestId, String reviewComment) {
        var actor = ActorContext.required();
        requireAdmin(actor.organizationId(), actor.userId());
        var request = repository.findRequest(actor.organizationId(), requestId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "标准字段申请不存在"));
        if (!"PENDING".equals(request.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "该申请已经处理，不能重复审核");
        }
        repository.rejectRequest(actor.organizationId(), requestId, actor.userId(), trimToNull(reviewComment));
    }

    public void validateFormalFields(JsonNode schema) {
        var model = schema.path("x-jsd-field-model");
        for (var field : model.path("fields")) {
            if (field.path("candidate").asBoolean(false)) continue;
            var origin = field.path("fieldOrigin").asText("");
            if ("PENDING_STANDARD".equals(origin)
                    || field.path("requiresStandardConfirmation").asBoolean(false)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA,
                        "字段“" + field.path("name").asText("未命名") + "”尚未确认标准字段，请选择标准字段或转为模板自定义字段");
            }
            if (!"STANDARD".equals(origin)) continue;
            var id = parseUuid(field.path("standardFieldId").asText(""));
            var code = field.path("fieldCode").asText("");
            var version = field.path("standardFieldVersion").asInt(0);
            if (id == null || code.isBlank() || version < 1
                    || repository.findActive(code, version, id).isEmpty()) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA,
                        "字段“" + field.path("name").asText("未命名") + "”的标准字段已失效，请重新选择");
            }
        }
    }

    private void requireAdmin(UUID organizationId, UUID userId) {
        if (!repository.isDictionaryAdmin(organizationId, userId)) {
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "当前身份不是标准字段字典管理员");
        }
    }

    private String generatedCode(StandardFieldRepository.Request request) {
        var slug = request.displayName().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        if (slug.isBlank()) slug = "FIELD";
        return "CUSTOM." + slug + "." + request.id().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String normalizeCode(String value) {
        var code = value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_.-]{2,159}")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "标准字段编码格式非法");
        }
        return code;
    }

    private String normalizeType(String value) {
        var type = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "string";
        return switch (type) {
            case "string", "number", "integer", "date", "datetime", "time", "duration", "boolean" -> type;
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的字段类型");
        };
    }

    private String normalizeUiType(String value) {
        var type = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "TEXT";
        if (!"TEXT".equals(type) && !"SIGNATURE".equals(type)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的填写方式");
        }
        return type;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record RequestCommand(
            UUID templateVersionId,
            String fieldId,
            String displayName,
            String valueType,
            String uiType,
            String groupCode,
            String description
    ) {
    }

    public record ApprovalCommand(String fieldCode, String reviewComment) {
    }
}
