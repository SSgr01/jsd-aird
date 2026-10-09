package com.jsd.aird.mfg.upload.application;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.mfg.ingest.application.WorkbookSnapshotValueWriter;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;

/**
 * Builds an editable workbook snapshot for uploads that do not have a sheet of
 * their own.  A photo upload recognized against an XLSX template owns that
 * template's workbook plus the values read from the image, so cloning the
 * template and writing the recognized values into its bindings turns the record
 * into a normal Excel record: editable in the sheet workspace and exportable as
 * .xlsx.
 */
@Service
public class ProductionUploadWorkbookBuilder {

    private final ProductionOrderRepository orders;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final WorkbookSnapshotValueWriter valueWriter;
    private final ObjectMapper objectMapper;

    public ProductionUploadWorkbookBuilder(ProductionOrderRepository orders, FileObjectRepository files,
                                           ObjectStorage storage, WorkbookSnapshotValueWriter valueWriter,
                                           ObjectMapper objectMapper) {
        this.orders = orders;
        this.files = files;
        this.storage = storage;
        this.valueWriter = valueWriter;
        this.objectMapper = objectMapper;
    }

    /** Template workbook snapshot with the recognized values written into it. */
    public JsonNode build(UUID organizationId, UUID templateVersionId, JsonNode data) {
        var template = publishedTemplate(organizationId, templateVersionId);
        return fill(readSnapshot(organizationId, template), template.mapping(), data);
    }

    /**
     * The published template's own workbook snapshot.  Callers that derive values
     * before filling — a Word document is read through the template's field
     * labels — reuse it instead of loading it twice.
     */
    public JsonNode templateSnapshot(UUID organizationId, UUID templateVersionId) {
        return readSnapshot(organizationId, publishedTemplate(organizationId, templateVersionId));
    }

    /** Template snapshot with the given data written into the bindings' cells. */
    public JsonNode fill(JsonNode templateSnapshot, JsonNode mapping, JsonNode data) {
        return valueWriter.write(templateSnapshot, mapping,
                data == null || !data.isObject() ? objectMapper.createObjectNode() : data);
    }

    private ProductionOrderRepository.PublishedTemplate publishedTemplate(
            UUID organizationId, UUID templateVersionId) {
        if (templateVersionId == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "生产单尚未选择识别模板，无法生成 Excel");
        }
        var template = orders.findPublishedTemplate(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "已发布模板不存在或已失效"));
        if (!"XLSX".equalsIgnoreCase(template.format())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "只有 XLSX 模板可以生成 Excel 工作簿");
        }
        return template;
    }

    /**
     * Templates keep small snapshots inline and larger ones as a file object; both
     * shapes are accepted, mirroring the production order export path.
     */
    private JsonNode readSnapshot(UUID organizationId, ProductionOrderRepository.PublishedTemplate template) {
        var inline = template.inlineSnapshot();
        if (inline != null && inline.isObject() && !inline.isEmpty()) return inline;
        if (template.snapshotFileId() == null) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照不存在");
        }
        var file = files.find(organizationId, template.snapshotFileId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照文件不存在"));
        try (var stored = storage.get(file.objectKey())) {
            return objectMapper.readTree(stored.stream());
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照读取失败");
        }
    }
}
