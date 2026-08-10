package com.jsd.aird.data.application;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.springframework.stereotype.Service;

/** Synchronous, bounded batch export of formal data assets. */
@Service
public class DataAssetExportService {

    public static final int MAX_ASSETS = 200;
    private static final Set<String> SUPPORTED_TARGET_DATA_TYPES = Set.of(
            "MATERIAL", "FORMULA", "PROCESS", "EQUIPMENT", "TEST_STANDARD");

    private final DataRepository repository;
    private final TemplateDataImportFacade templates;
    private final ObjectMapper objectMapper;

    public DataAssetExportService(DataRepository repository, TemplateDataImportFacade templates,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.templates = templates;
        this.objectMapper = objectMapper;
    }

    public ExportedZip export(ExportCommand command) {
        var actor = ActorContext.required();
        var targetDataType = normalizeTargetDataType(command.targetDataType());
        if (command.assetIds() == null || command.assetIds().isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "至少选择一个数据资产");
        }
        var assetIds = new LinkedHashSet<>(command.assetIds());
        if (assetIds.size() > MAX_ASSETS) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "单次最多导出 " + MAX_ASSETS + " 个数据资产");
        }
        var template = templates.getPublished(actor.organizationId(), command.templateVersionId());
        if (!targetDataType.equals(template.targetDataType())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "所选模板与数据类型不匹配");
        }
        if (!"XLSX".equalsIgnoreCase(template.format())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "批量文件导出首期仅支持 XLSX 模板");
        }

        var assets = assetIds.stream()
                .map(id -> repository.findAsset(actor.organizationId(), id)
                        .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据资产不存在：" + id)))
                .toList();
        if (assets.stream().anyMatch(item -> !targetDataType.equals(item.targetDataType()))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "所选资产必须属于同一数据类型");
        }
        if (assets.stream().anyMatch(item -> item.currentRevisionId() == null || item.currentRevisionNo() == null
                || item.importJobId() == null || item.templateVersionId() == null)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "所选资产缺少当前正式修订，无法导出");
        }

        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            var usedNames = new HashSet<String>();
            var manifest = new StringBuilder("\uFEFF资产ID,资产编码,资产名称,数据类型,修订号,修订ID,模板版本ID,导入批次ID,原文件SHA-256,导出警告\n");
            for (var asset : assets) {
                var workbook = templates.exportPublishedWorkbook(
                        actor.organizationId(), command.templateVersionId(), exportData(asset), asset.currentRevisionId());
                var entryName = uniqueEntryName(
                        safeFilePart(targetDataType) + "_" + safeFilePart(asset.assetKey())
                                + "_v" + asset.currentRevisionNo() + ".xlsx", usedNames);
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(workbook.content());
                zip.closeEntry();
                manifest.append(csv(asset.id())).append(',')
                        .append(csv(asset.assetKey())).append(',')
                        .append(csv(asset.displayName())).append(',')
                        .append(csv(targetDataType)).append(',')
                        .append(csv(asset.currentRevisionNo())).append(',')
                        .append(csv(asset.currentRevisionId())).append(',')
                        .append(csv(command.templateVersionId())).append(',')
                        .append(csv(asset.importJobId())).append(',')
                        .append(csv(asset.sourceSha256())).append(',')
                        .append(csv(warnings(workbook.warnings()))).append('\n');
            }
            zip.putNextEntry(new ZipEntry("manifest.csv"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return new ExportedZip(output.toByteArray(), "data-assets-" + Instant.now().toEpochMilli() + ".zip");
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.INTERNAL_ERROR, "批量文件导出失败：" + safeMessage(exception));
        }
    }

    private JsonNode exportData(DataRepository.AssetDetail asset) {
        var source = asset.correctedData();
        if (source == null || !source.isObject() || source.isEmpty()) source = asset.normalizedData();
        var result = objectMapper.createObjectNode();
        if (source != null && source.isObject()) {
            source.fields().forEachRemaining(entry -> result.set(entry.getKey(), standardValue(entry.getValue())));
        }
        result.put("assetKey", asset.assetKey());
        result.put("displayName", asset.displayName() == null ? "" : asset.displayName());
        return result;
    }

    private JsonNode standardValue(JsonNode field) {
        if (field != null && field.isObject() && field.has("normalizedValue")) return field.get("normalizedValue");
        return field == null ? objectMapper.nullNode() : field;
    }

    private String warnings(List<TemplateDataImportFacade.ExportWarning> warnings) {
        return warnings == null ? "" : warnings.stream()
                .map(item -> item.code() + ":" + item.dataPath() + ":" + item.message())
                .reduce((left, right) -> left + " | " + right).orElse("");
    }

    private String uniqueEntryName(String candidate, Set<String> usedNames) {
        var name = candidate;
        var suffix = 2;
        while (!usedNames.add(name)) {
            name = candidate.substring(0, candidate.length() - ".xlsx".length()) + "-" + suffix++ + ".xlsx";
        }
        return name;
    }

    private String safeFilePart(String value) {
        var safe = value == null ? "" : value.trim()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replace("..", "_");
        safe = safe.replaceAll("[. ]+$", "").trim();
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) safe = "未命名资产";
        return safe.length() > 100 ? safe.substring(0, 100) : safe;
    }

    private String csv(Object value) {
        var text = value == null ? "" : value.toString();
        return '"' + text.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + '"';
    }

    private String normalizeTargetDataType(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TARGET_DATA_TYPES.contains(normalized)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的数据类型：" + value);
        }
        return normalized;
    }

    private String safeMessage(Throwable error) {
        var value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName()
                : value.substring(0, Math.min(500, value.length()));
    }

    public record ExportCommand(String targetDataType, UUID templateVersionId, List<UUID> assetIds) {
    }

    public record ExportedZip(byte[] content, String fileName) {
    }
}
