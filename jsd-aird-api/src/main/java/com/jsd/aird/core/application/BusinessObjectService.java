package com.jsd.aird.core.application;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.core.application.port.BusinessObjectRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BusinessObjectService {

    private static final List<String> TYPES = List.of(
            "PRODUCT", "PROJECT", "EXPERIMENT", "FORMULA", "PROCESS", "BATCH", "QUALITY_CASE", "OTHER");

    private final BusinessObjectRepository repository;

    public BusinessObjectService(BusinessObjectRepository repository) {
        this.repository = repository;
    }

    public List<BusinessObjectRepository.ObjectRow> list(String type, String keyword, int limit) {
        var normalizedType = StringUtils.hasText(type) ? normalizeType(type) : null;
        return repository.list(ActorContext.required().organizationId(), normalizedType, keyword, limit);
    }

    public BusinessObjectRepository.ObjectRow create(String type, String externalId, String name,
                                                       String sourceSystem, JsonNode metadata) {
        var actor = ActorContext.required();
        var normalizedId = normalizeText(externalId, 160, "外部编号");
        var normalizedName = normalizeText(name, 260, "对象名称");
        var source = StringUtils.hasText(sourceSystem) ? sourceSystem.trim().toUpperCase(Locale.ROOT) : "MANUAL";
        if (source.length() > 80) throw new ApiException(ApiErrorCode.BAD_REQUEST, "来源系统不能超过 80 个字符");
        return repository.insert(actor.organizationId(), actor.userId(), new BusinessObjectRepository.CreateRow(
                normalizeType(type), normalizedId, normalizedName, source, metadata));
    }

    private String normalizeType(String type) {
        var value = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(value)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的关联对象类型");
        return value;
    }

    private String normalizeText(String value, int max, String label) {
        if (!StringUtils.hasText(value)) throw new ApiException(ApiErrorCode.BAD_REQUEST, label + "不能为空");
        var normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC);
        if (normalized.length() > max) throw new ApiException(ApiErrorCode.BAD_REQUEST, label + "过长");
        return normalized;
    }
}
