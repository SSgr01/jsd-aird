package com.jsd.aird.tpl.api;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** Public template contract used by the data-center module. */
public interface TemplateDataImportFacade {

    List<DataTemplateOption> listPublished(UUID organizationId, String targetDataType);

    DataTemplateDefinition getPublished(UUID organizationId, UUID templateVersionId);

    ParsedTabularFile parse(UUID organizationId, UUID templateVersionId, UUID fileId);

    FieldRequest requestField(UUID organizationId, UUID templateVersionId, FieldRequestCommand command);

    /**
     * Renders one normalized data asset with a published DATA_CENTER template.
     * The implementation owns snapshot loading and workbook generation; callers
     * only see this stable cross-module contract.
     */
    WorkbookExport exportPublishedWorkbook(UUID organizationId, UUID templateVersionId,
                                           JsonNode data, UUID revisionId);

    record DataTemplateOption(
            UUID templateId,
            UUID versionId,
            String templateCode,
            String name,
            String category,
            String targetDataType,
            int versionNo,
            String format
    ) {
    }

    record DataTemplateDefinition(
            UUID templateId,
            UUID versionId,
            String templateCode,
            String name,
            String category,
            String targetDataType,
            int versionNo,
            String format,
            JsonNode schema,
            JsonNode mappings,
            List<FieldDefinition> fields
    ) {
    }

    record WorkbookExport(byte[] content, List<ExportWarning> warnings) {
    }

    record ExportWarning(String code, String bindingId, String dataPath, String message) {
    }

    record FieldDefinition(
            String fieldCode,
            String displayName,
            String dataType,
            String defaultUnit,
            boolean required,
            boolean identity,
            List<String> aliases,
            String dataPath
    ) {
    }

    record FieldRequestCommand(String fieldId, String displayName, String valueType,
                               String uiType, String groupCode, String description) {
    }

    record FieldRequest(UUID id, UUID templateVersionId, String displayName, String valueType, String status) {
    }

    record ParsedTabularFile(
            String format,
            String parserVersion,
            List<ParsedSheet> sheets
    ) {
    }

    record ParsedSheet(
            String sheetId,
            String sheetName,
            int sheetOrder,
            int firstRow,
            int lastRow,
            int firstColumn,
            int lastColumn,
            List<Integer> headerCandidates,
            int suggestedHeaderRow,
            int suggestedDataStartRow,
            List<List<String>> rows
    ) {
    }
}
