package com.jsd.aird.data.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;

/**
 * Executes the physical shape described by a published data template.
 *
 * The generic tabular parser intentionally only exposes a two-dimensional grid.
 * This class is the bridge from that grid to logical records: rows for ROW_TABLE,
 * columns for COLUMN_TABLE and cell intersections for MATRIX_FIELD.
 */
final class StructuredDataExtractor {

    private static final String ROW_DIMENSION_CODE = "DATA.DIMENSION.ROW";
    private static final String COLUMN_DIMENSION_CODE = "DATA.DIMENSION.COLUMN";
    private static final String ROW_DIMENSION_KEY = "__dimension_row";
    private static final String COLUMN_DIMENSION_KEY = "__dimension_column";

    private final ObjectMapper objectMapper;

    StructuredDataExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Optional<Result> extract(
            TemplateDataImportFacade.ParsedSheet sheet,
            List<TemplateDataImportFacade.FieldDefinition> definitions,
            List<TemplateDataImportFacade.ImportBinding> bindings,
            int dataStart,
            int dataEnd
    ) {
        var sheetBindings = bindings.stream()
                .filter(binding -> sameSheet(binding, sheet.sheetId(), sheet.sheetName()))
                .toList();
        var components = componentBindings(sheetBindings);
        var explicitComponents = sheetBindings.stream().anyMatch(binding -> binding.locator() != null
                && !binding.locator().path("componentId").asText("").isBlank());
        var results = new ArrayList<Result>();
        var formResults = new LinkedHashMap<String, Result>();
        for (var entry : components.entrySet()) {
            var componentId = entry.getKey();
            var component = entry.getValue();
            var hasFormRegion = component.stream().anyMatch(binding ->
                    binding.mappingKind() != null
                            && binding.mappingKind().toUpperCase(Locale.ROOT).contains("FORM_REGION"));
            var formFields = component.stream().filter(binding -> isFormBinding(binding)
                            || hasFormRegion && !isStructuredField(binding) && !isMatrixBinding(binding))
                    .filter(binding -> binding.fieldCode() != null && !binding.fieldCode().isBlank()).toList();
            if (!formFields.isEmpty()) {
                var form = extractFormRegion(sheet, definitions, componentId, component, formFields, dataStart, dataEnd);
                if (!form.rows().isEmpty()) formResults.put(entry.getKey(), form);
            }
            var structuredFields = component.stream().filter(this::isStructuredField).toList();
            Result structured = null;
            if (component.stream().anyMatch(this::isMatrixBinding)) {
                structured = extractMatrix(sheet, definitions, componentId, component, dataStart, dataEnd);
            } else if (!structuredFields.isEmpty()) {
                structured = structuredFields.stream().anyMatch(this::isColumnBinding)
                        ? extractColumnTable(sheet, definitions, componentId, structuredFields, dataStart, dataEnd)
                        : extractRowTable(sheet, definitions, componentId, structuredFields, dataStart, dataEnd);
            }
            if (structured != null && !structured.rows().isEmpty()) {
                var form = declaredFormContext(entry.getKey(), component, formResults);
                // A non-repeating form region on the same sheet is common
                // context (batch, product, test date, etc.). Copy it into
                // every detail record instead of creating a standalone row.
                if (form == null && formResults.size() == 1) {
                    form = formResults.values().iterator().next();
                }
                results.add(form == null ? structured : mergeFormIntoStructured(structured, form));
            }
        }
        // Standalone forms are records of their own. They are not copied into
        // unrelated tables merely because they share a sheet. A single form
        // beside a structured region is the explicit common-context exception
        // above, including templates that declare component ids.
        var mergedCommonForm = !results.isEmpty() && formResults.size() == 1;
        for (var entry : formResults.entrySet()) {
            if (mergedCommonForm) break;
            var usedAsContext = components.entrySet().stream().anyMatch(component ->
                    entry.getKey().equals(formContextId(component.getKey(), component.getValue())));
            if (!usedAsContext) results.add(entry.getValue());
        }
        return results.isEmpty() ? Optional.empty() : Optional.of(combine(results));
    }

    private Map<String, List<TemplateDataImportFacade.ImportBinding>> componentBindings(
            List<TemplateDataImportFacade.ImportBinding> bindings
    ) {
        var result = new LinkedHashMap<String, List<TemplateDataImportFacade.ImportBinding>>();
        for (var binding : bindings) {
            var locator = binding.locator();
            var componentId = locator == null ? "" : locator.path("componentId").asText("");
            if (componentId.isBlank()) componentId = binding.parentBindingId();
            if (componentId == null || componentId.isBlank()) componentId = binding.bindingId();
            result.computeIfAbsent(componentId, ignored -> new ArrayList<>()).add(binding);
        }
        return result;
    }

    private Result declaredFormContext(String componentId,
                                       List<TemplateDataImportFacade.ImportBinding> bindings,
                                       Map<String, Result> forms) {
        var formId = formContextId(componentId, bindings);
        return formId == null ? null : forms.get(formId);
    }

    private String formContextId(String componentId, List<TemplateDataImportFacade.ImportBinding> bindings) {
        for (var binding : bindings) {
            var locator = binding.locator();
            var declared = locator == null ? "" : locator.path("formContextComponentId").asText("");
            if (!declared.isBlank()) return declared;
        }
        return null;
    }

    private Result combine(List<Result> results) {
        var shapes = results.stream().map(Result::shape).distinct().toList();
        var mappings = new ArrayList<DataRepository.Mapping>();
        var rows = new ArrayList<DataRepository.Row>();
        results.forEach(result -> {
            result.mappings().forEach(mapping -> {
                var exists = mappings.stream().anyMatch(current -> current.sheetId().equals(mapping.sheetId())
                        && current.sourceColumn().equals(mapping.sourceColumn())
                        && mappingComponentId(current).equals(mappingComponentId(mapping)));
                if (!exists) mappings.add(mapping);
            });
            rows.addAll(result.rows());
        });
        return new Result(shapes.size() == 1 ? shapes.getFirst() : "MULTI_COMPONENT", mappings, rows);
    }

    private String mappingComponentId(DataRepository.Mapping mapping) {
        return mapping.detail() == null ? "" : mapping.detail().path("componentId").asText("");
    }

    private Result extractFormRegion(
            TemplateDataImportFacade.ParsedSheet sheet,
            List<TemplateDataImportFacade.FieldDefinition> definitions,
            String componentId,
            List<TemplateDataImportFacade.ImportBinding> allBindings,
            List<TemplateDataImportFacade.ImportBinding> fields,
            int dataStart,
            int dataEnd
    ) {
        var region = allBindings.stream().filter(binding -> binding.mappingKind().toUpperCase(Locale.ROOT)
                .contains("FORM_REGION")).findFirst().orElse(null);
        var regionRange = region == null ? Optional.<Range>empty() : range(region.locator(), false);
        if (regionRange.isEmpty()) {
            regionRange = fields.stream().map(binding -> range(binding.locator(), false))
                    .filter(Optional::isPresent).map(Optional::get)
                    .reduce((left, right) -> new Range(
                            Math.min(left.startRow(), right.startRow()), Math.max(left.endRow(), right.endRow()),
                            Math.min(left.startColumn(), right.startColumn()), Math.max(left.endColumn(), right.endColumn())));
        }
        if (regionRange.isEmpty()) return new Result("FORM_REGION", List.of(), List.of());
        var shape = "FORM_REGION";
        var mappings = fieldMappings(sheet.sheetId(), componentId, fields, definitions, shape);
        var axis = region == null ? fields.stream().map(TemplateDataImportFacade.ImportBinding::repeatAxis)
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse("")
                : region.repeatAxis();
        var repeat = region != null && (region.mappingKind().toUpperCase(Locale.ROOT).contains("REPEAT_REGION")
                || axis != null && !axis.isBlank());
        var recordHeight = region == null ? 1 : Math.max(1, region.recordHeight());
        var recordWidth = region == null ? 1 : Math.max(1, region.recordWidth());
        var stride = region == null ? 1 : Math.max(1, region.recordStride());
        var blockCount = 1;
        if (repeat && axis.toUpperCase(Locale.ROOT).contains("ROW")) {
            blockCount = Math.max(1, (regionRange.get().endRow() - regionRange.get().startRow() + 1
                    - recordHeight) / stride + 1);
        } else if (repeat && axis.toUpperCase(Locale.ROOT).contains("COLUMN")) {
            blockCount = Math.max(1, (regionRange.get().endColumn() - regionRange.get().startColumn() + 1
                    - recordWidth) / stride + 1);
        }
        var rows = new ArrayList<DataRepository.Row>();
        for (int block = 0; block < blockCount; block++) {
            var raw = objectMapper.createObjectNode();
            var metadata = baseMetadata(shape, componentId);
            var nonBlank = false;
            String identity = null;
            for (var binding : fields) {
                var sourceRange = range(binding.locator(), false).orElse(regionRange.get());
                var valueCell = formValue(sheet, binding, sourceRange, axis, block,
                        recordHeight, recordWidth, stride);
                var key = bindingKey(binding);
                raw.put(key, valueCell.value());
                putCell(metadata, key, cellMetadata(sheet, valueCell.row(), valueCell.column(), binding));
                nonBlank |= !valueCell.value().isBlank();
                if (binding.identity() && !valueCell.value().isBlank()) identity = valueCell.value();
            }
            if (!nonBlank) continue;
            metadata.put("recordKey", identity == null || identity.isBlank()
                    ? sheet.sheetId() + ":" + componentId + ":form:" + block : identity);
            metadata.put("identitySynthetic", identity == null || identity.isBlank());
            rows.add(row(raw, metadata, sheet.sheetId(), regionRange.get().startRow() + block * stride));
        }
        return new Result(shape, mappings, rows);
    }

    private Result mergeFormIntoStructured(Result structured, Result form) {
        if (form.rows().isEmpty() || form.mappings().isEmpty()) return structured;
        var formRow = form.rows().getFirst();
        var mappings = new ArrayList<DataRepository.Mapping>(structured.mappings());
        for (var mapping : form.mappings()) {
            var detail = mapping.detail() != null && mapping.detail().isObject()
                    ? (ObjectNode) mapping.detail().deepCopy() : objectMapper.createObjectNode();
            var originalComponentId = detail.path("componentId").asText("");
            detail.put("componentId", structured.rows().getFirst().sourceMetadata().path("componentId").asText(""));
            if (!originalComponentId.isBlank()) detail.put("sourceComponentId", originalComponentId);
            mappings.add(new DataRepository.Mapping(mapping.id(), mapping.sheetId(), mapping.sourceColumn(),
                    mapping.sourceHeader(), mapping.fieldCode(), mapping.fieldName(), mapping.action(), mapping.valueType(),
                    mapping.sourceUnit(), mapping.standardUnit(), detail, mapping.status()));
        }
        var rows = structured.rows().stream().map(row -> {
            var raw = (ObjectNode) row.rawValues().deepCopy();
            var metadata = (ObjectNode) row.sourceMetadata().deepCopy();
            formRow.rawValues().fields().forEachRemaining(entry -> raw.set(entry.getKey(), entry.getValue().deepCopy()));
            formRow.sourceMetadata().path("cells").fields().forEachRemaining(entry ->
                    metadata.with("cells").set(entry.getKey(), entry.getValue().deepCopy()));
            return new DataRepository.Row(row.id(), row.sheetId(), row.rowNumber(), raw,
                    raw.deepCopy(), raw.deepCopy(), row.status(), metadata);
        }).toList();
        return new Result(structured.shape(), mappings, rows);
    }

    private Result extractRowTable(
            TemplateDataImportFacade.ParsedSheet sheet,
            List<TemplateDataImportFacade.FieldDefinition> definitions,
            String componentId,
            List<TemplateDataImportFacade.ImportBinding> fields,
            int dataStart,
            int dataEnd
    ) {
        var ranges = fields.stream().map(binding -> range(binding.locator(), false))
                .filter(Optional::isPresent).map(Optional::get).toList();
        if (ranges.isEmpty()) return new Result("ROW_TABLE", List.of(), List.of());
        var start = Math.max(dataStart, ranges.stream().mapToInt(Range::startRow).min().orElse(dataStart));
        var end = Math.min(dataEnd, ranges.stream().mapToInt(Range::endRow).max().orElse(dataEnd));
        var mappings = fieldMappings(sheet.sheetId(), componentId, fields, definitions, "ROW_TABLE");
        var rows = new ArrayList<DataRepository.Row>();
        for (int rowNumber = start; rowNumber <= end; rowNumber++) {
            var raw = objectMapper.createObjectNode();
            var metadata = baseMetadata("ROW_TABLE", componentId);
            var nonBlank = false;
            var nonSequenceBlank = false;
            String identity = null;
            for (var binding : fields) {
                var fieldRange = range(binding.locator(), false).orElse(null);
                if (fieldRange == null || rowNumber < fieldRange.startRow() || rowNumber > fieldRange.endRow()) continue;
                var key = bindingKey(binding);
                var value = value(sheet.rows(), rowNumber, fieldRange.startColumn());
                raw.put(key, value);
                putCell(metadata, key, cellMetadata(sheet, rowNumber, fieldRange.startColumn(), binding));
                nonBlank |= !value.isBlank();
                nonSequenceBlank |= !value.isBlank() && !isGeneratedSequenceField(binding);
                if (binding.identity() && !value.isBlank()) identity = value;
            }
            // Preformatted templates commonly pre-fill the row sequence while
            // leaving every business input blank. Such reserved rows are not
            // records and must not enter review, RAG or training projections.
            if (!nonBlank || !nonSequenceBlank || aggregateRow(raw)) continue;
            metadata.put("recordKey", identity == null || identity.isBlank()
                    ? sheet.sheetId() + ":" + componentId + ":row:" + rowNumber : identity);
            metadata.put("identitySynthetic", identity == null || identity.isBlank());
            rows.add(row(raw, metadata, sheet.sheetId(), rowNumber));
        }
        return new Result("ROW_TABLE", mappings, rows);
    }

    private Result extractColumnTable(
            TemplateDataImportFacade.ParsedSheet sheet,
            List<TemplateDataImportFacade.FieldDefinition> definitions,
            String componentId,
            List<TemplateDataImportFacade.ImportBinding> fields,
            int dataStart,
            int dataEnd
    ) {
        var ranges = fields.stream().map(binding -> range(binding.locator(), false))
                .filter(Optional::isPresent).map(Optional::get).toList();
        if (ranges.isEmpty()) return new Result("COLUMN_TABLE", List.of(), List.of());
        var start = ranges.stream().mapToInt(Range::startColumn).min().orElse(1);
        var end = ranges.stream().mapToInt(Range::endColumn).max().orElse(start);
        var mappings = fieldMappings(sheet.sheetId(), componentId, fields, definitions, "COLUMN_TABLE");
        var rows = new ArrayList<DataRepository.Row>();
        int recordIndex = 0;
        for (int column = start; column <= end; column++) {
            var raw = objectMapper.createObjectNode();
            var metadata = baseMetadata("COLUMN_TABLE", componentId);
            var nonBlank = false;
            String identity = null;
            for (var binding : fields) {
                var fieldRange = range(binding.locator(), false).orElse(null);
                if (fieldRange == null || column < fieldRange.startColumn() || column > fieldRange.endColumn()) continue;
                var sourceRow = fieldRange.startRow();
                var key = bindingKey(binding);
                var value = value(sheet.rows(), sourceRow, column);
                raw.put(key, value);
                putCell(metadata, key, cellMetadata(sheet, sourceRow, column, binding));
                nonBlank |= !value.isBlank();
                if (binding.identity() && !value.isBlank()) identity = value;
            }
            if (!nonBlank || aggregateRow(raw)) continue;
            recordIndex++;
            metadata.put("recordKey", identity == null || identity.isBlank()
                    ? sheet.sheetId() + ":" + componentId + ":column:" + column : identity);
            metadata.put("identitySynthetic", identity == null || identity.isBlank());
            rows.add(row(raw, metadata, sheet.sheetId(), recordIndex));
        }
        return new Result("COLUMN_TABLE", mappings, rows);
    }

    private Result extractMatrix(
            TemplateDataImportFacade.ParsedSheet sheet,
            List<TemplateDataImportFacade.FieldDefinition> definitions,
            String componentId,
            List<TemplateDataImportFacade.ImportBinding> bindings,
            int dataStart,
            int dataEnd
    ) {
        var region = bindings.stream().filter(this::isMatrixRegion).findFirst().orElse(null);
        var fields = bindings.stream().filter(this::isMatrixField).toList();
        if (fields.isEmpty() && region != null && !region.fieldCode().isBlank()) fields = List.of(region);
        var baseLocator = region == null ? objectMapper.createObjectNode() : region.locator();
        var rowHeaders = range(baseLocator, "rowHeaderRange");
        var columnHeaders = range(baseLocator, "columnHeaderRange");
        var cross = range(baseLocator, "crossDataRange");
        if (cross.isEmpty()) {
            for (var field : fields) {
                cross = range(field.locator(), "crossDataRange");
                if (cross.isPresent()) break;
            }
        }
        if (cross.isEmpty()) return new Result("MATRIX", List.of(), List.of());
        var crossRange = cross.get();
        var rowDimensionFields = fields.stream()
                .filter(field -> isMatrixRowDimension(field, rowHeaders, crossRange)).toList();
        var columnDimensionFields = fields.stream()
                .filter(field -> isMatrixColumnDimension(field, columnHeaders, crossRange)).toList();
        var measureFields = fields.stream()
                .filter(field -> !rowDimensionFields.contains(field) && !columnDimensionFields.contains(field))
                .toList();
        var mappings = fieldMappings(sheet.sheetId(), componentId, fields, definitions, "MATRIX");
        mappings = new ArrayList<>(mappings);
        if (rowDimensionFields.isEmpty()) {
            mappings.add(syntheticMapping(sheet.sheetId(), componentId, ROW_DIMENSION_KEY,
                    ROW_DIMENSION_CODE, "行维度", "/dimensions/row"));
        }
        if (columnDimensionFields.isEmpty()) {
            mappings.add(syntheticMapping(sheet.sheetId(), componentId, COLUMN_DIMENSION_KEY,
                    COLUMN_DIMENSION_CODE, "列维度", "/dimensions/column"));
        }
        var rows = new ArrayList<DataRepository.Row>();
        int recordIndex = 0;
        for (int rowNumber = crossRange.startRow(); rowNumber <= crossRange.endRow(); rowNumber++) {
            var rowLabel = rowHeaders.isPresent()
                    ? dimensionValue(sheet.rows(), rowHeaders.get(), rowNumber, true)
                    : Integer.toString(rowNumber);
            for (int column = crossRange.startColumn(); column <= crossRange.endColumn(); column++) {
                var columnLabel = columnHeaders.isPresent()
                        ? dimensionValue(sheet.rows(), columnHeaders.get(), column, false)
                        : columnName(column);
                if (aggregateLabel(rowLabel) || aggregateLabel(columnLabel)) continue;
                var value = value(sheet.rows(), rowNumber, column);
                if (value.isBlank()) continue;
                var raw = objectMapper.createObjectNode();
                var metadata = baseMetadata("MATRIX", componentId);
                for (var field : rowDimensionFields) {
                    var sourceRange = matrixBindingRange(field).orElseGet(() -> rowHeaders.orElse(null));
                    if (sourceRange == null) continue;
                    var key = bindingKey(field);
                    raw.put(key, dimensionValue(sheet.rows(), sourceRange, rowNumber, true));
                    putCell(metadata, key, cellMetadata(sheet, rowNumber, sourceRange.startColumn(), field));
                }
                for (var field : columnDimensionFields) {
                    var sourceRange = matrixBindingRange(field).orElseGet(() -> columnHeaders.orElse(null));
                    if (sourceRange == null) continue;
                    var key = bindingKey(field);
                    raw.put(key, dimensionValue(sheet.rows(), sourceRange, column, false));
                    putCell(metadata, key, cellMetadata(sheet, sourceRange.startRow(), column, field));
                }
                for (var field : measureFields) {
                    var valueRange = matrixBindingRange(field).orElse(crossRange);
                    if (!valueRange.contains(rowNumber, column)) continue;
                    var key = bindingKey(field);
                    raw.put(key, value);
                    putCell(metadata, key, cellMetadata(sheet, rowNumber, column, field));
                }
                if (measureFields.stream().noneMatch(field -> raw.has(bindingKey(field)))) continue;
                if (rowDimensionFields.isEmpty()) {
                    raw.put(ROW_DIMENSION_KEY, rowLabel);
                    putCell(metadata, ROW_DIMENSION_KEY, firstDimensionCell(sheet, rowHeaders, rowNumber));
                }
                if (columnDimensionFields.isEmpty()) {
                    raw.put(COLUMN_DIMENSION_KEY, columnLabel);
                    putCell(metadata, COLUMN_DIMENSION_KEY, firstDimensionCell(sheet, columnHeaders, column));
                }
                metadata.put("recordKey", sheet.sheetId() + ":" + componentId + ":matrix:"
                        + rowNumber + ":" + column);
                metadata.putObject("dimensions").put("row", rowLabel).put("column", columnLabel);
                recordIndex++;
                rows.add(row(raw, metadata, sheet.sheetId(), recordIndex));
            }
        }
        return new Result("MATRIX", mappings, rows);
    }

    private boolean isMatrixRowDimension(
            TemplateDataImportFacade.ImportBinding binding,
            Optional<Range> rowHeaders,
            Range crossRange
    ) {
        var code = binding.fieldCode() == null ? "" : binding.fieldCode().toUpperCase(Locale.ROOT);
        if (code.contains("ROW_DIMENSION") || code.contains("ROW_ATTRIBUTE")) return true;
        var sourceRange = matrixBindingRange(binding);
        return sourceRange.isPresent() && rowHeaders.isPresent()
                && rangesOverlap(sourceRange.get(), rowHeaders.get())
                && !rangesOverlap(sourceRange.get(), crossRange);
    }

    private boolean isMatrixColumnDimension(
            TemplateDataImportFacade.ImportBinding binding,
            Optional<Range> columnHeaders,
            Range crossRange
    ) {
        var code = binding.fieldCode() == null ? "" : binding.fieldCode().toUpperCase(Locale.ROOT);
        if (code.contains("COLUMN_DIMENSION") || code.contains("COLUMN_MEMBER")) return true;
        var sourceRange = matrixBindingRange(binding);
        return sourceRange.isPresent() && columnHeaders.isPresent()
                && rangesOverlap(sourceRange.get(), columnHeaders.get())
                && !rangesOverlap(sourceRange.get(), crossRange);
    }

    private Optional<Range> matrixBindingRange(TemplateDataImportFacade.ImportBinding binding) {
        if (binding.locator() == null || !binding.locator().isObject()) return Optional.empty();
        for (var key : List.of("logicalInputRange", "sourceRange", "valueRange", "measureRange", "crossDataRange")) {
            var parsed = range(binding.locator(), key);
            if (parsed.isPresent()) return parsed;
        }
        return Optional.empty();
    }

    private boolean rangesOverlap(Range left, Range right) {
        return left.startRow() <= right.endRow() && left.endRow() >= right.startRow()
                && left.startColumn() <= right.endColumn() && left.endColumn() >= right.startColumn();
    }

    private List<DataRepository.Mapping> fieldMappings(
            String sheetId,
            String componentId,
            List<TemplateDataImportFacade.ImportBinding> fields,
            List<TemplateDataImportFacade.FieldDefinition> definitions,
            String shape
    ) {
        var definitionsByCode = definitions.stream().collect(java.util.stream.Collectors.toMap(
                TemplateDataImportFacade.FieldDefinition::fieldCode, item -> item, (left, right) -> left));
        return fields.stream().map(binding -> {
            var definition = definitionsByCode.get(binding.fieldCode());
            var detail = objectMapper.createObjectNode()
                    .put("dataPath", binding.dataPath())
                    .put("structured", true)
                    .put("componentId", componentId)
                    .put("shape", shape)
                    .put("bindingId", binding.bindingId())
                    .put("mappingKind", binding.mappingKind())
                    .put("repeatAxis", binding.repeatAxis())
                    .put("identity", binding.identity() || definition != null && definition.identity())
                    .put("required", binding.required() || definition != null && definition.required())
                    .put("trainingEligible", binding.trainingEligible())
                    .put("trainingRole", binding.trainingRole())
                    .put("ragEligible", binding.ragEligible())
                    .put("labelPath", binding.labelPath() == null ? "" : binding.labelPath())
                    .put("valueSource", binding.valueSource());
            detail.set("locator", binding.locator() == null ? objectMapper.createObjectNode() : binding.locator().deepCopy());
            return new DataRepository.Mapping(null, sheetId, bindingKey(binding),
                    definition == null ? binding.fieldCode() : definition.displayName(), binding.fieldCode(),
                    definition == null ? binding.fieldCode() : definition.displayName(), "MAP", binding.valueType(),
                    null, binding.unit(), detail, "MATCHED");
        }).toList();
    }

    private DataRepository.Mapping syntheticMapping(String sheetId, String componentId, String key,
                                                    String fieldCode, String name, String dataPath) {
        var detail = objectMapper.createObjectNode()
                .put("dataPath", dataPath)
                .put("structured", true)
                .put("componentId", componentId)
                .put("bindingId", key)
                .put("syntheticDimension", true)
                .put("identity", false)
                .put("required", false);
        return new DataRepository.Mapping(null, sheetId, key, name, fieldCode, name, "MAP", "TEXT",
                null, null, detail, "MATCHED");
    }

    private DataRepository.Row row(ObjectNode raw, ObjectNode metadata, String sheetId, int rowNumber) {
        return new DataRepository.Row(UUID.randomUUID(), sheetId, rowNumber, raw, raw.deepCopy(), raw.deepCopy(),
                "STAGED", metadata);
    }

    private ObjectNode baseMetadata(String shape, String componentId) {
        return objectMapper.createObjectNode().put("shape", shape).put("componentId", componentId)
                .set("cells", objectMapper.createObjectNode());
    }

    private void putCell(ObjectNode metadata, String key, ObjectNode cell) {
        metadata.with("cells").set(key, cell);
    }

    private ObjectNode cellMetadata(TemplateDataImportFacade.ParsedSheet sheet, int row, int column) {
        return objectMapper.createObjectNode()
                .put("sheetId", sheet.sheetId())
                .put("sheetName", sheet.sheetName())
                .put("rowNumber", row)
                .put("columnNumber", column)
                .put("columnName", columnName(column))
                .put("cellAddress", columnName(column) + row);
    }

    private ObjectNode cellMetadata(TemplateDataImportFacade.ParsedSheet sheet, int row, int column,
                                    TemplateDataImportFacade.ImportBinding binding) {
        var metadata = cellMetadata(sheet, row, column)
                .put("bindingId", binding.bindingId())
                .put("valuePath", binding.dataPath())
                .put("valueSource", binding.valueSource() == null ? "INPUT" : binding.valueSource())
                .put("labelPath", binding.labelPath() == null ? "" : binding.labelPath())
                .put("ragEligible", binding.ragEligible());
        var address = columnName(column) + row;
        if (sheet.layoutIr() != null) {
            for (var cell : sheet.layoutIr().path("cells")) {
                if (!address.equalsIgnoreCase(cell.path("address").asText(""))) continue;
                if ("FORMULA".equals(cell.path("valueSource").asText())) {
                    for (var key : List.of("formulaExpression", "cachedValue", "calculationSource", "calculationStatus", "formulaTrustStatus")) {
                        if (cell.has(key)) metadata.set(key, cell.path(key).deepCopy());
                    }
                    metadata.put("valueSource", "FORMULA");
                }
                break;
            }
        }
        return metadata;
    }

    private ObjectNode firstDimensionCell(
            TemplateDataImportFacade.ParsedSheet sheet,
            Optional<Range> range,
            int coordinate
    ) {
        if (range.isEmpty()) return cellMetadata(sheet, coordinate, 1);
        var value = range.get();
        if (value.startColumn() == value.endColumn()) return cellMetadata(sheet, coordinate, value.startColumn());
        if (value.startRow() == value.endRow()) return cellMetadata(sheet, value.startRow(), coordinate);
        return cellMetadata(sheet, coordinate, value.startColumn());
    }

    private String dimensionValue(List<List<String>> rows, Range range, int coordinate, boolean rowDimension) {
        if (rowDimension) {
            var value = joinRow(rows, coordinate, range.startColumn(), range.endColumn());
            if (!value.isBlank()) return value;
            for (int row = coordinate - 1; row >= range.startRow(); row--) {
                value = joinRow(rows, row, range.startColumn(), range.endColumn());
                if (!value.isBlank()) return value;
            }
            return "";
        }
        var values = new ArrayList<String>();
        for (int row = range.startRow(); row <= range.endRow(); row++) {
            var value = value(rows, row, coordinate);
            if (!value.isBlank()) values.add(value);
        }
        return String.join(" / ", values);
    }

    private String joinRow(List<List<String>> rows, int row, int startColumn, int endColumn) {
        var values = new ArrayList<String>();
        for (int column = startColumn; column <= endColumn; column++) {
            var value = value(rows, row, column);
            if (!value.isBlank()) values.add(value);
        }
        return String.join(" / ", values);
    }

    private boolean aggregateRow(ObjectNode raw) {
        var fields = raw.fields();
        while (fields.hasNext()) if (aggregateLabel(fields.next().getValue().asText())) return true;
        return false;
    }

    private boolean aggregateLabel(String value) {
        var normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("合计") || normalized.equals("总计") || normalized.equals("小计")
                || normalized.equals("total") || normalized.equals("subtotal");
    }

    private boolean isGeneratedSequenceField(TemplateDataImportFacade.ImportBinding binding) {
        var normalized = (binding.fieldCode() + " " + binding.labelPath())
                .replace(" ", "").toLowerCase(Locale.ROOT);
        return normalized.contains("序号") || normalized.equals("no") || normalized.equals("编号")
                || normalized.contains("rownumber") || normalized.contains("sequence");
    }

    private boolean sameSheet(TemplateDataImportFacade.ImportBinding binding, String sheetId, String sheetName) {
        var locatorSheet = binding.locator() == null ? "" : binding.locator().path("sheetId").asText(
                binding.locator().path("sheet").asText(""));
        return locatorSheet.isBlank() || locatorSheet.equals(sheetId) || locatorSheet.equals(sheetName);
    }

    private boolean isMatrixBinding(TemplateDataImportFacade.ImportBinding binding) {
        var kind = binding.mappingKind().toUpperCase(Locale.ROOT);
        return kind.contains("MATRIX") || range(binding.locator(), "crossDataRange").isPresent();
    }

    private boolean isMatrixRegion(TemplateDataImportFacade.ImportBinding binding) {
        return binding.mappingKind().toUpperCase(Locale.ROOT).contains("MATRIX_REGION");
    }

    private boolean isMatrixField(TemplateDataImportFacade.ImportBinding binding) {
        return binding.mappingKind().toUpperCase(Locale.ROOT).contains("MATRIX_FIELD");
    }

    private boolean isFormBinding(TemplateDataImportFacade.ImportBinding binding) {
        var kind = binding.mappingKind() == null ? "" : binding.mappingKind().toUpperCase(Locale.ROOT);
        if (kind.contains("FORM_REGION")) return true;
        var locator = binding.locator();
        if (locator == null || !locator.isObject()) return false;
        if ("FORM_REGION".equalsIgnoreCase(locator.path("kind").asText())
                || "FORM_REGION".equalsIgnoreCase(locator.path("blockType").asText())) return true;
        return "SCALAR".equals(kind) && locator.has("labelRange") && locator.has("valueRange");
    }

    private boolean isStructuredField(TemplateDataImportFacade.ImportBinding binding) {
        var kind = binding.mappingKind().toUpperCase(Locale.ROOT);
        // A repeat region is the container for the records, not a business
        // field. Including it here makes a row table produce one extra value
        // from the header row and also causes the extractor to treat the
        // container range as a field. Only its child bindings are executable
        // fields.
        if (kind.contains("REGION")) return false;
        return !binding.fieldCode().isBlank() && (kind.contains("REPEAT_FIELD")
                || kind.equals("COLUMN_TABLE") || kind.equals("ROW_TABLE")
                || binding.repeatAxis() != null && !binding.repeatAxis().isBlank());
    }

    private boolean isColumnBinding(TemplateDataImportFacade.ImportBinding binding) {
        var axis = binding.repeatAxis() == null ? "" : binding.repeatAxis().toUpperCase(Locale.ROOT);
        // repeatAxis is the contract's source of truth. ARRAY_COLUMN means
        // values are stored down a column and therefore represents one record
        // per ROW; ARRAY_ROW represents one record per COLUMN. The previous
        // fallback inverted ordinary row tables and yielded one record per
        // header column.
        if (axis.contains("ROW")) return false;
        if (axis.contains("COLUMN")) return true;
        return binding.locator() != null
                && "ARRAY_ROW".equals(binding.locator().path("valueMode").asText(""));
    }

    private String bindingKey(TemplateDataImportFacade.ImportBinding binding) {
        var raw = binding.bindingId() == null || binding.bindingId().isBlank()
                ? binding.fieldCode() : binding.bindingId();
        var normalized = raw.replaceAll("[^A-Za-z0-9]", "");
        if (normalized.length() <= 29) return "b_" + normalized;
        // source_column is varchar(32). Preserve a readable prefix while adding
        // a stable suffix so long binding ids cannot collide after truncation.
        return "b_" + normalized.substring(0, 18) + "_" + shortHash(raw);
    }

    private String shortHash(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 10);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String value(List<List<String>> rows, int row, int column) {
        if (row <= 0 || row > rows.size()) return "";
        var values = rows.get(row - 1);
        return column <= 0 || column > values.size() || values.get(column - 1) == null
                ? "" : values.get(column - 1).trim();
    }

    private String columnName(int column) {
        var result = new StringBuilder();
        var value = column;
        while (value > 0) {
            var remainder = (value - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.toString();
    }

    private Optional<Range> range(JsonNode locator, boolean ignored) {
        if (locator == null || !locator.isObject()) return Optional.empty();
        for (var key : List.of("logicalInputRange", "valueRange", "recordRange", "dataRange", "address", "range", "sourceRange")) {
            var value = locator.path(key).asText("");
            var parsed = parseRange(value);
            if (parsed.isPresent()) return parsed;
        }
        return Optional.empty();
    }

    private Optional<Range> range(JsonNode locator, String key) {
        if (locator == null || !locator.isObject()) return Optional.empty();
        return parseRange(locator.path(key).asText(""));
    }

    private Optional<Range> parseRange(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        var parts = text.replace("$", "").replace(" ", "").toUpperCase(Locale.ROOT).split(":", 2);
        var first = parseCell(parts[0]);
        var last = parseCell(parts.length == 1 ? parts[0] : parts[1]);
        if (first.isEmpty() || last.isEmpty()) return Optional.empty();
        var start = first.get();
        var end = last.get();
        return Optional.of(new Range(Math.min(start.row(), end.row()), Math.max(start.row(), end.row()),
                Math.min(start.column(), end.column()), Math.max(start.column(), end.column())));
    }

    private Optional<Cell> parseCell(String text) {
        var matcher = java.util.regex.Pattern.compile("^([A-Z]+)([1-9][0-9]*)$").matcher(text == null ? "" : text);
        if (!matcher.matches()) return Optional.empty();
        var column = 0;
        for (var letter : matcher.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        return Optional.of(new Cell(Integer.parseInt(matcher.group(2)), column));
    }

    private ValueCell formValue(TemplateDataImportFacade.ParsedSheet sheet,
                                TemplateDataImportFacade.ImportBinding binding, Range sourceRange,
                                String axis, int block, int recordHeight, int recordWidth, int stride) {
        var rowOffset = axis != null && axis.toUpperCase(Locale.ROOT).contains("ROW") ? block * stride : 0;
        var columnOffset = axis != null && axis.toUpperCase(Locale.ROOT).contains("COLUMN") ? block * stride : 0;
        var startRow = sourceRange.startRow() + rowOffset;
        var startColumn = sourceRange.startColumn() + columnOffset;
        var endRow = sourceRange.endRow() + rowOffset;
        var endColumn = sourceRange.endColumn() + columnOffset;
        if (rowOffset > 0 || columnOffset > 0) {
            endRow = Math.min(endRow, startRow + Math.max(1, recordHeight) - 1);
            endColumn = Math.min(endColumn, startColumn + Math.max(1, recordWidth) - 1);
        }
        for (int row = startRow; row <= endRow; row++) {
            for (int column = startColumn; column <= endColumn; column++) {
                var value = value(sheet.rows(), row, column);
                if (!value.isBlank()) return new ValueCell(inlineValue(binding, value), row, column);
            }
        }
        return new ValueCell("", Math.max(1, startRow), Math.max(1, startColumn));
    }

    private String inlineValue(TemplateDataImportFacade.ImportBinding binding, String value) {
        if (binding.locator() == null
                || !"INLINE".equalsIgnoreCase(binding.locator().path("valueMode").asText(""))) return value;
        var colon = Math.max(value.indexOf('：'), value.indexOf(':'));
        if (colon < 0 || colon + 1 >= value.length()) return "";
        return value.substring(colon + 1).trim();
    }

    record Result(String shape, List<DataRepository.Mapping> mappings, List<DataRepository.Row> rows) {}

    private record Range(int startRow, int endRow, int startColumn, int endColumn) {
        boolean contains(int row, int column) {
            return row >= startRow && row <= endRow && column >= startColumn && column <= endColumn;
        }
    }

    private record Cell(int row, int column) {}

    private record ValueCell(String value, int row, int column) {}
}
