package com.jsd.aird.mfg.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.DocxContentControlExporter;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;

@Service
public class ProductionOfficeExportService {
    private final ProductionOrderRepository repository;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final ObjectMapper objectMapper;
    private final SnapshotWorkbookExporter workbookExporter;
    private final DocxContentControlExporter docxExporter;

    public ProductionOfficeExportService(
            ProductionOrderRepository repository,
            FileObjectRepository files,
            ObjectStorage storage,
            ObjectMapper objectMapper,
            SnapshotWorkbookExporter workbookExporter,
            DocxContentControlExporter docxExporter
    ) {
        this.repository = repository;
        this.files = files;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.workbookExporter = workbookExporter;
        this.docxExporter = docxExporter;
    }

    public List<ProductionOrderRepository.RevisionSummary> revisions(UUID orderId) {
        return repository.listRevisions(ActorContext.required().organizationId(), orderId);
    }

    public Check check(UUID orderId, String format, UUID revisionId) {
        var source = source(orderId, revisionId);
        requireFormat(source.template().format(), format);
        return new Check(true, inspect(source.mapping(), format));
    }

    public Download export(UUID orderId, String format, UUID revisionId) {
        var source = source(orderId, revisionId);
        requireFormat(source.template().format(), format);
        if ("XLSX".equalsIgnoreCase(format)) {
            var snapshotId = source.snapshotFileId() == null ? source.template().snapshotFileId() : source.snapshotFileId();
            var snapshot = readSnapshot(snapshotId, source.template().inlineSnapshot());
            var result = workbookExporter.export(snapshot, source.mapping(), source.data(),
                    new SnapshotWorkbookExporter.Manifest(source.template().versionId().toString(),
                            source.schemaHash(), source.mappingHash(), source.kind(), source.revisionId() == null ? null : source.revisionId().toString()));
            return new Download(source.order().orderNo() + "-" + source.kind().toLowerCase() + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", result.content(), result.warnings());
        }
        var wordId = source.template().wordDocument().path("publishedDocxFileId").asText(
                source.template().wordDocument().path("workingDocxFileId").asText(
                        source.template().wordDocument().path("sourceDocxFileId").asText("")));
        if (wordId.isBlank()) throw new ApiException(ApiErrorCode.NOT_FOUND, "生产单对应的 Word 原生模板不存在");
        var result = docxExporter.export(readBytes(UUID.fromString(wordId)), source.mapping(), source.data());
        return new Download(source.order().orderNo() + "-" + source.kind().toLowerCase() + ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", result.content(), result.warnings());
    }

    private Source source(UUID orderId, UUID revisionId) {
        var actor = ActorContext.required();
        var order = repository.findWorkspace(actor.organizationId(), orderId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单不存在"));
        var template = repository.findPublishedTemplate(actor.organizationId(), order.templateVersionId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单对应模板不存在"));
        if (revisionId == null) {
            if ("DRAFT".equals(order.status())) {
                return new Source(order, template, order.schema(), order.mapping(), order.data(), order.snapshotFileId(),
                        order.schemaHash(), order.mappingHash(), "DRAFT", null);
            }
            var latest = repository.listRevisions(actor.organizationId(), orderId).stream().findFirst()
                    .flatMap(item -> repository.findRevision(actor.organizationId(), orderId, item.revisionId()))
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单没有提交修订"));
            return new Source(order, template, latest.schema(), latest.mapping(), latest.data(), latest.snapshotFileId(),
                    latest.schemaHash(), latest.mappingHash(), "REVISION-" + latest.revisionNo(), latest.revisionId());
        }
        var revision = repository.findRevision(actor.organizationId(), orderId, revisionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单修订不存在"));
        return new Source(order, template, revision.schema(), revision.mapping(), revision.data(), revision.snapshotFileId(),
                revision.schemaHash(), revision.mappingHash(), "REVISION-" + revision.revisionNo(), revision.revisionId());
    }

    private void requireFormat(String actual, String requested) {
        if (requested == null || !actual.equalsIgnoreCase(requested)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "导出格式必须与生产单模板原格式一致");
    }

    private List<SnapshotWorkbookExporter.Warning> inspect(JsonNode mapping, String format) {
        var warnings = new ArrayList<SnapshotWorkbookExporter.Warning>();
        if (mapping == null || !mapping.isArray()) return warnings;
        for (var binding : mapping) {
            var locator = binding.path("locator");
            var present = "DOCX".equalsIgnoreCase(format)
                    ? !binding.path("markerId").asText("").isBlank() || !locator.path("markerId").asText("").isBlank()
                    : !first(locator, "logicalInputRange", "valueRange", "address", "range", "dataRange").isBlank();
            if (!present) warnings.add(new SnapshotWorkbookExporter.Warning("BINDING_MISSING", binding.path("bindingId").asText(""), binding.path("dataPath").asText(""), "字段没有有效位置，已留空"));
        }
        return warnings;
    }

    private JsonNode readJson(UUID fileId) {
        try (var object = storage.get(file(fileId).objectKey())) { return objectMapper.readTree(object.stream()); }
        catch (Exception exception) { throw new ApiException(ApiErrorCode.FILE_NOT_READY, "生产单工作簿快照读取失败"); }
    }
    private JsonNode readSnapshot(UUID fileId, JsonNode inlineSnapshot) {
        if (inlineSnapshot != null && inlineSnapshot.isObject() && !inlineSnapshot.isEmpty()) return inlineSnapshot;
        if (fileId == null) throw new ApiException(ApiErrorCode.FILE_NOT_READY, "生产单工作簿快照不存在");
        return readJson(fileId);
    }
    private byte[] readBytes(UUID fileId) {
        try (var object = storage.get(file(fileId).objectKey())) { return object.stream().readAllBytes(); }
        catch (Exception exception) { throw new ApiException(ApiErrorCode.FILE_NOT_READY, "生产单 Word 模板读取失败"); }
    }
    private FileObjectRepository.FileObject file(UUID fileId) { return files.find(ActorContext.required().organizationId(), fileId).orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY)); }
    private String first(JsonNode node, String... keys) { for (var key : keys) if (!node.path(key).asText("").isBlank()) return node.path(key).asText(); return ""; }

    private record Source(
            ProductionOrderRepository.ProductionWorkspace order,
            ProductionOrderRepository.PublishedTemplate template,
            JsonNode schema,
            JsonNode mapping,
            JsonNode data,
            UUID snapshotFileId,
            String schemaHash,
            String mappingHash,
            String kind,
            UUID revisionId
    ) {}

    public record Check(boolean canDownload, List<SnapshotWorkbookExporter.Warning> warnings) {}
    public record Download(String fileName, String contentType, byte[] content, List<SnapshotWorkbookExporter.Warning> warnings) {}
}
