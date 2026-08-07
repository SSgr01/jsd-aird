package com.jsd.aird.tpl.application;

import java.util.Set;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The single owner of matrix geometry-derived projection metadata. Semantic
 * recognizers may propose meaning, but slots, projection mode and training
 * policy are deterministic compiler output.
 */
public final class CanonicalMatrixCompiler {

    private final ObjectMapper objectMapper;

    public CanonicalMatrixCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Single canonical entry point used by import and model adapters.  The
     * geometry is already resolved; this method never infers or trims it.
     */
    public CompiledMatrix compile(
            JsonNode facts, CanonicalMatrixGeometry geometry, MatrixSemanticAssessment semantics
    ) {
        var corner = rangeBounds(geometry.cornerRange());
        var data = rangeBounds(geometry.crossDataRange());
        if (corner == null || data == null) {
            throw new IllegalArgumentException("Canonical MATRIX geometry is incomplete");
        }
        var axis = normalizeAxis(geometry.recordAxis());
        var projection = recordProjection(corner[3], data[0], data[2], corner[3], data[1], data[3], axis);
        var columns = "COLUMN".equals(axis)
                ? columnSlots(geometry.sheetId(), geometry.regionId(), geometry.sourceRange(),
                data[0], data[2], corner[3], data[3]) : objectMapper.createArrayNode();
        var rows = "ROW".equals(axis)
                ? rowSlots(geometry.sheetId(), geometry.regionId(), geometry.sourceRange(),
                data[1], data[3], Math.max(1, data[0] - 1), data[2])
                : objectMapper.createArrayNode();
        var artifacts = compileMatrixArtifacts(facts, geometry.sheetId(), geometry.regionId(),
                geometry.sourceRange(), geometry.cornerRange(), geometry.rowHeaderRange(),
                geometry.columnHeaderRange(), geometry.crossDataRange(), semantics.headerTree(),
                projection, columns, rows, geometry.canonicalStatus(), semantics.rowDimensions(),
                semantics.rowAttributes());
        return new CompiledMatrix(artifacts.path("matrixModel").deepCopy(),
                artifacts.path("tableModel").deepCopy(), artifacts.path("longTableModel").deepCopy(),
                artifacts.path("recordProjection").deepCopy(), artifacts.path("columnSlots").deepCopy(),
                artifacts.path("rowSlots").deepCopy(), artifacts.path("bindings").deepCopy(),
                artifacts.path("trainingSummary").deepCopy());
    }

    public record CanonicalMatrixGeometry(
            String sheetId, String regionId, String sourceRange, String cornerRange,
            String rowHeaderRange, String columnHeaderRange, String crossDataRange,
            String recordAxis, String canonicalStatus
    ) {
    }

    public record MatrixSemanticAssessment(
            JsonNode rowDimensions, JsonNode rowAttributes, JsonNode headerTree
    ) {
        public MatrixSemanticAssessment {
            rowDimensions = rowDimensions == null ? null : rowDimensions.deepCopy();
            rowAttributes = rowAttributes == null ? null : rowAttributes.deepCopy();
            headerTree = headerTree == null ? null : headerTree.deepCopy();
        }
    }

    public record CompiledMatrix(
            JsonNode matrixModel, JsonNode tableModel, JsonNode longTableModel,
            JsonNode recordProjection, JsonNode columnSlots, JsonNode rowSlots,
            JsonNode bindings, JsonNode trainingSummary
    ) {
    }

    public String normalizeAxis(String axis) {
        return Set.of("ROW", "COLUMN").contains(axis) ? axis : "UNKNOWN";
    }

    public ObjectNode recordProjection(
            int identityRow, int startColumn, int endColumn,
            int cornerRow, int valueStartRow, int valueEndRow, String axis
    ) {
        var resolvedAxis = normalizeAxis(axis);
        var columnProjection = "COLUMN".equals(resolvedAxis);
        var projection = objectMapper.createObjectNode()
                .put("mode", columnProjection ? "COLUMN_RECORDS"
                        : "ROW".equals(resolvedAxis) ? "ROW_RECORDS" : "UNRESOLVED")
                .put("recordAxis", resolvedAxis)
                .put("identityRow", identityRow)
                .put("valueStartRow", valueStartRow)
                .put("valueEndRow", valueEndRow)
                .put("recordHeight", columnProjection ? valueEndRow - identityRow + 1 : 1)
                .put("measureHeight", columnProjection ? valueEndRow - valueStartRow + 1 : 1)
                .put("recordWidth", columnProjection ? 1 : endColumn - startColumn + 1)
                .put("measureWidth", columnProjection ? 1 : endColumn - startColumn + 1)
                .put("recordStride", 1)
                .put("recordHeightIncludesIdentity", columnProjection)
                 .put("identityColumn", columnProjection ? 0 : Math.max(1, startColumn - 1))
                .put("measureStartColumn", startColumn)
                .put("measureEndColumn", endColumn)
                .put("identityRange", columnProjection
                        ? excelRange(startColumn, identityRow, endColumn, identityRow)
                        : excelRange(Math.max(1, startColumn - 1), valueStartRow,
                        Math.max(1, startColumn - 1), valueEndRow))
                .put("measureRange", excelRange(startColumn, valueStartRow, endColumn, valueEndRow));
        var columns = projection.putArray("recordColumns");
        var rows = projection.putArray("recordRows");
        if (columnProjection) {
            for (int column = startColumn; column <= endColumn; column++) columns.add(columnName(column));
        } else if ("ROW".equals(resolvedAxis)) {
            for (int row = valueStartRow; row <= valueEndRow; row++) rows.add(row);
        }
        return projection;
    }

    public void normalizeProjection(
            ObjectNode projection, int identityRow, int startColumn, int endColumn,
            int cornerRow, int valueStartRow, int valueEndRow, String axis
    ) {
        var normalized = recordProjection(identityRow, startColumn, endColumn, cornerRow,
                valueStartRow, valueEndRow, axis);
        projection.removeAll();
        projection.setAll(normalized);
    }

    public ArrayNode columnSlots(int startColumn, int endColumn, int identityRow, int valueEndRow) {
        return columnSlots("", "", "", startColumn, endColumn, identityRow, valueEndRow);
    }

    public ArrayNode columnSlots(
            String sheetId, String regionId, String matrixRange,
            int startColumn, int endColumn, int identityRow, int valueEndRow
    ) {
        var slots = objectMapper.createArrayNode();
        for (int column = startColumn; column <= endColumn; column++) {
            var coordinate = columnName(column);
            var stableRegion = stableRegionId(sheetId, regionId, matrixRange);
            slots.add(objectMapper.createObjectNode()
                    .put("slotId", stableRegion + "|COLUMN|" + coordinate)
                    .put("bindingInstanceId", "matrix-slot-"
                            + RecognitionIdentity.shortHash(sheetId + "|" + stableRegion + "|"
                            + matrixRange + "|COLUMN|" + coordinate, 16))
                    .put("column", coordinate)
                    .put("identityAddress", excelAddress(column, identityRow))
                    .put("recordRange", excelRange(column, identityRow, column, valueEndRow))
                    .put("identityRange", excelAddress(column, identityRow))
                    .put("measureRange", excelRange(column, identityRow + 1, column, valueEndRow))
                    .put("templateStatus", "RUNTIME_INPUT")
                    .put("role", "COLUMN_MEMBER_INPUT")
                    .put("editability", "EDITABLE")
                    .put("valueSource", "USER_INPUT"));
        }
        return slots;
    }

    public ArrayNode rowSlots(int startRow, int endRow, int identityColumn, int valueEndColumn) {
        return rowSlots("", "", "", startRow, endRow, identityColumn, valueEndColumn);
    }

    public ArrayNode rowSlots(
            String sheetId, String regionId, String matrixRange,
            int startRow, int endRow, int identityColumn, int valueEndColumn
    ) {
        var slots = objectMapper.createArrayNode();
        var stableRegion = stableRegionId(sheetId, regionId, matrixRange);
        for (int row = startRow; row <= endRow; row++) {
            slots.add(objectMapper.createObjectNode()
                    .put("slotId", stableRegion + "|ROW|" + row)
                    .put("identityAddress", excelAddress(identityColumn, row))
                    .put("recordRange", excelRange(identityColumn, row, valueEndColumn, row))
                    .put("identityRange", excelAddress(identityColumn, row))
                    .put("templateStatus", "RUNTIME_INPUT")
                    .put("role", "ROW_MEMBER_INPUT"));
        }
        return slots;
    }

    public boolean trainingEligible(String memberStatus, String rowRole, boolean formulaValue) {
        return "POPULATED".equals(memberStatus)
                && Set.of("TEST_ITEM", "REPLICATE").contains(rowRole)
                && !formulaValue;
    }

    public String entityRecordId(String sheetId, String regionId, String axis, String coordinate) {
        return sheetId + "|" + stableRegionId(sheetId, regionId, "") + "|"
                + axis + "|" + coordinate;
    }

    public String recordId(String entityRecordId, String measureCoordinate) {
        return entityRecordId + "|" + measureCoordinate;
    }

    /**
     * Compiles every geometry-derived matrix artifact in one place. The import
     * workflow may decorate the returned payload with business names, but it
     * must not rebuild axes, bindings, slots or the long-form projection.
     */
    public ObjectNode compileMatrixArtifacts(
            JsonNode facts, String sheetId, String regionId, String sourceRange,
            String cornerRange, String rowHeaderRange, String columnHeaderRange,
            String crossDataRange, JsonNode headerTree, ObjectNode projection,
            ArrayNode columnSlots, ArrayNode rowSlots, String canonicalStatus
    ) {
        return compileMatrixArtifacts(facts, sheetId, regionId, sourceRange, cornerRange,
                rowHeaderRange, columnHeaderRange, crossDataRange, headerTree, projection,
                columnSlots, rowSlots, canonicalStatus, null, null);
    }

    /**
     * Compiles using semantic row-axis definitions supplied by the semantic
     * stage.  The compiler preserves their ranges, including merged cells;
     * it does not invent one hierarchy level per physical column.
     */
    public ObjectNode compileMatrixArtifacts(
            JsonNode facts, String sheetId, String regionId, String sourceRange,
            String cornerRange, String rowHeaderRange, String columnHeaderRange,
            String crossDataRange, JsonNode headerTree, ObjectNode projection,
            ArrayNode columnSlots, ArrayNode rowSlots, String canonicalStatus,
            JsonNode semanticRowDimensions, JsonNode semanticRowAttributes
    ) {
        var artifacts = objectMapper.createObjectNode();
        var axes = suppliedAxes(semanticRowDimensions, semanticRowAttributes);
        var dimensions = objectMapper.createArrayNode();
        var attributes = objectMapper.createArrayNode();
        for (var axis : axes) {
            if ("ROW_ATTRIBUTE".equals(axis.path("role").asText())) attributes.add(axis.deepCopy());
            else dimensions.add(axis.deepCopy());
        }
        var bindings = objectMapper.createArrayNode();
        for (var axis : axes) {
            var binding = objectMapper.createObjectNode()
                    .put("bindingKind", "ROW_ATTRIBUTE".equals(axis.path("role").asText())
                            ? "ROW_ATTRIBUTE" : "ROW_DIMENSION")
                    .put("code", axis.path("code").asText())
                    .put("name", axis.path("name").asText())
                    .put("sourceRange", axis.path("sourceRange").asText())
                    .put("fillMerged", axis.path("fillMerged").asBoolean(true));
            if (!"ROW_ATTRIBUTE".equals(axis.path("role").asText())) {
                binding.put("level", axis.path("code").asText().replace("row_dimension_", ""));
                binding.put("optional", axis.path("code").asText().endsWith("2"));
            }
            bindings.add(binding);
        }
        var columnBounds = rangeBounds(columnHeaderRange);
        var dataBounds = rangeBounds(crossDataRange);
        var columnBinding = objectMapper.createObjectNode()
                .put("bindingKind", "COLUMN_MEMBER")
                .put("code", "column_member")
                .put("name", "列成员")
                .put("valueType", "string")
                .put("sourceRange", columnHeaderRange)
                .put("role", "COLUMN_MEMBER_INPUT")
                .put("memberMode", "RUNTIME_INPUT")
                .put("dataPathTemplate", "/records/*/columnMember/label");
        if (columnBounds != null) columnBinding.put("sourceRow", columnBounds[1]);
        bindings.add(columnBinding);
        bindings.add(objectMapper.createObjectNode().put("bindingKind", "MEASURE")
                .put("code", "measure").put("name", "交叉值")
                .put("sourceRange", crossDataRange)
                .put("sourceRows", dataBounds == null ? ""
                        : excelRange(dataBounds[0], dataBounds[1], dataBounds[2], dataBounds[3]))
                .put("valueType", "number"));

        var matrix = objectMapper.createObjectNode()
                .put("semanticMode", "CROSS_TAB").put("layoutMode", "CROSS_TAB")
                .put("canonicalStatus", canonicalStatus == null ? "PROVISIONAL" : canonicalStatus)
                .put("sourceRange", sourceRange).put("cornerRange", cornerRange)
                .put("rowHeaderRange", rowHeaderRange).put("columnHeaderRange", columnHeaderRange)
                .put("crossDataRange", crossDataRange)
                .put("columnMemberRole", "COLUMN_MEMBER_INPUT").put("memberMode", "RUNTIME_INPUT");
        if (projection != null) {
            matrix.put("recordAxis", projection.path("recordAxis").asText("UNKNOWN"));
            matrix.put("repeatAxis", projection.path("recordAxis").asText("UNKNOWN"));
            matrix.put("recordHeight", projection.path("recordHeight").asInt(1));
            matrix.put("recordWidth", projection.path("recordWidth").asInt(1));
            matrix.put("recordStride", projection.path("recordStride").asInt(1));
        }
        matrix.set("headerTree", headerTree == null || !headerTree.isArray()
                ? objectMapper.createArrayNode() : headerTree.deepCopy());
        matrix.set("recordProjection", projection == null ? objectMapper.createObjectNode() : projection.deepCopy());
        matrix.set("columnSlots", columnSlots == null ? objectMapper.createArrayNode() : columnSlots.deepCopy());
        matrix.set("rowSlots", rowSlots == null ? objectMapper.createArrayNode() : rowSlots.deepCopy());
        matrix.set("bindings", bindings.deepCopy());
        matrix.set("rowDimensions", dimensions);
        matrix.set("rowAttributes", attributes);

        var table = objectMapper.createObjectNode()
                .put("headerRange", columnHeaderRange).put("dataRange", crossDataRange)
                .put("semanticMode", "CROSS_TAB").put("layoutMode", "CROSS_TAB")
                .put("rowHeaderRange", rowHeaderRange).put("columnHeaderRange", columnHeaderRange)
                .put("crossDataRange", crossDataRange)
                .put("cornerRange", cornerRange)
                .put("recordAxis", projection == null ? "UNKNOWN" : projection.path("recordAxis").asText("UNKNOWN"));
        table.set("headerTree", matrix.path("headerTree").deepCopy());
        table.set("columns", objectMapper.createArrayNode());
        table.set("recordProjection", matrix.path("recordProjection").deepCopy());
        table.set("columnSlots", matrix.path("columnSlots").deepCopy());
        table.set("rowSlots", matrix.path("rowSlots").deepCopy());

        var longTable = compileLongTableModel(facts, sheetId, regionId, "MATRIX", sourceRange,
                cornerRange, rowHeaderRange, columnHeaderRange, crossDataRange,
                projection, columnSlots, rowSlots, dimensions, attributes);
        matrix.set("longTableModel", longTable.deepCopy());
        table.set("longTableModel", longTable.deepCopy());
        artifacts.set("matrixModel", matrix);
        artifacts.set("tableModel", table);
        artifacts.set("longTableModel", longTable);
        artifacts.set("bindings", bindings);
        artifacts.set("recordProjection", projection == null ? objectMapper.createObjectNode() : projection.deepCopy());
        artifacts.set("columnSlots", columnSlots == null ? objectMapper.createArrayNode() : columnSlots.deepCopy());
        artifacts.set("rowSlots", rowSlots == null ? objectMapper.createArrayNode() : rowSlots.deepCopy());
        artifacts.set("trainingSummary", longTable.path("trainingSummary").deepCopy());
        return artifacts;
    }

    private List<JsonNode> suppliedAxes(JsonNode dimensions, JsonNode attributes) {
        var result = new java.util.ArrayList<JsonNode>();
        if (dimensions != null && dimensions.isArray()) {
            for (var item : dimensions) if (item.isObject()) {
                var copy = item.deepCopy();
                if (copy instanceof ObjectNode object) {
                    object.put("role", "ROW_DIMENSION");
                    object.put("code", object.path("code").asText(
                            object.path("name").asText("row_dimension_" + result.size())));
                    object.put("sourceRange", object.path("sourceRange").asText(""));
                    object.put("fillMerged", object.path("fillMerged").asBoolean(true));
                }
                result.add(copy);
            }
        }
        if (attributes != null && attributes.isArray()) {
            for (var item : attributes) if (item.isObject()) {
                var copy = item.deepCopy();
                if (copy instanceof ObjectNode object) {
                    object.put("role", "ROW_ATTRIBUTE");
                    object.put("code", object.path("code").asText(
                            object.path("name").asText("row_attribute_" + result.size())));
                    object.put("sourceRange", object.path("sourceRange").asText(""));
                    object.put("fillMerged", object.path("fillMerged").asBoolean(false));
                }
                result.add(copy);
            }
        }
        return result;
    }

    /**
     * Compiles the long-form projection from one canonical matrix geometry.
     * Both the import service and the model adapter call this method; neither
     * is allowed to invent a second record or training algorithm.
     */
    public ObjectNode compileLongTableModel(
            com.fasterxml.jackson.databind.JsonNode facts,
            String sheetId, String regionId, String sourceKind,
            String sourceRange, String cornerRange, String rowHeaderRange,
            String columnHeaderRange, String dataRange,
            ObjectNode projection, com.fasterxml.jackson.databind.node.ArrayNode columnSlots,
            com.fasterxml.jackson.databind.node.ArrayNode rowSlots
    ) {
        return compileLongTableModel(facts, sheetId, regionId, sourceKind, sourceRange, cornerRange,
                rowHeaderRange, columnHeaderRange, dataRange, projection, columnSlots, rowSlots, null, null);
    }

    public ObjectNode compileLongTableModel(
            com.fasterxml.jackson.databind.JsonNode facts,
            String sheetId, String regionId, String sourceKind,
            String sourceRange, String cornerRange, String rowHeaderRange,
            String columnHeaderRange, String dataRange,
            ObjectNode projection, com.fasterxml.jackson.databind.node.ArrayNode columnSlots,
            com.fasterxml.jackson.databind.node.ArrayNode rowSlots,
            JsonNode semanticRowDimensions, JsonNode semanticRowAttributes
    ) {
        var result = objectMapper.createObjectNode()
                .put("schemaVersion", 1).put("sourceKind", sourceKind)
                .put("semanticMode", "RECORD_SET").put("layoutMode", "LONG_FORM")
                .put("sourceRange", sourceRange).put("cornerRange", cornerRange)
                .put("rowHeaderRange", rowHeaderRange).put("columnHeaderRange", columnHeaderRange)
                .put("dataRange", dataRange).put("aggregatePolicy", "INCLUDE_MARKED")
                .put("blankAxisPolicy", "SKIP_EMPTY_RUNTIME_MEMBER")
                .put("trainingPolicy", "REQUIRE_RUNTIME_MEMBER");
        result.set("recordProjection", projection == null ? objectMapper.createObjectNode() : projection.deepCopy());
        result.set("columnSlots", columnSlots == null ? objectMapper.createArrayNode() : columnSlots.deepCopy());
        result.set("rowSlots", rowSlots == null ? objectMapper.createArrayNode() : rowSlots.deepCopy());
        var axis = projection == null ? "UNKNOWN" : projection.path("recordAxis").asText("UNKNOWN");
        var header = rangeBounds(rowHeaderRange);
        var data = rangeBounds(dataRange);
        if (!Set.of("COLUMN", "ROW").contains(axis) || header == null || data == null) {
            result.put("projectionStatus", "UNKNOWN".equals(axis) ? "UNRESOLVED" : axis + "_RECORDS");
            result.set("trainingSummary", trainingSummary(result.putArray("records")));
            return result;
        }
        var axes = semanticRowDimensions == null && semanticRowAttributes == null
                ? rowAxes(header, columnHeaderRange)
                : suppliedAxes(semanticRowDimensions, semanticRowAttributes);
        var dimensions = result.putArray("dimensions");
        var attributes = result.putArray("rowAttributes");
        axes.forEach(axisNode -> {
            if ("ROW_ATTRIBUTE".equals(axisNode.path("role").asText())) attributes.add(axisNode.deepCopy());
            else dimensions.add(axisNode.deepCopy());
        });
        result.set("measure", objectMapper.createObjectNode().put("code", "measure")
                .put("name", "交叉值").put("sourceRange", dataRange));
        result.put("projectionStatus", axis + "_RECORDS");
        var records = result.putArray("records");
        var previousLabels = new java.util.HashMap<Integer, String>();
        var seenRowPaths = new java.util.HashSet<String>();
        var columnHeader = rangeBounds(columnHeaderRange);
        for (int row = data[1]; row <= data[3]; row++) {
            var rowPath = objectMapper.createArrayNode();
            for (int column = header[0]; column <= header[2]; column++) {
                var text = cellText(facts, sheetId, column, row);
                if (text.isBlank()) text = previousLabels.getOrDefault(column, "");
                else previousLabels.put(column, text);
                rowPath.add(text);
            }
            var rowRole = rowRole(rowPath, facts, sheetId, row, data[0], data[2], seenRowPaths);
            var rowStatus = rowPath.toString().replace("\"\"", "").isBlank() ? "EMPTY" : "POPULATED";
            var rowEntity = entityRecordId(sheetId, regionId, "ROW", "row-" + row);
            // COLUMN projection emits one measure record per runtime column.
            // ROW projection emits one record per row; all measure columns are
            // carried by that record instead of creating one record per cell.
            var columns = axis.equals("ROW") ? java.util.List.of(data[0])
                    : java.util.stream.IntStream.rangeClosed(data[0], data[2]).boxed().toList();
            for (var column : columns) {
                var address = excelAddress(column, row);
                var valueCell = cellAt(facts, sheetId, column, row);
                var formula = isFormula(valueCell);
                var memberStatus = axis.equals("COLUMN")
                        ? cellText(facts, sheetId, column, projection.path("identityRow").asInt(1)).isBlank()
                            ? "EMPTY" : "POPULATED"
                        : rowStatus;
                var columnMemberStatus = axis.equals("ROW") && columnHeader != null
                        ? cellText(facts, sheetId, column, columnHeader[1]).isBlank() ? "EMPTY" : "POPULATED"
                        : memberStatus;
                var entity = axis.equals("COLUMN")
                        ? entityRecordId(sheetId, regionId, "COLUMN", columnName(column)) : rowEntity;
                var rowFormula = formula;
                if (axis.equals("ROW")) {
                    for (int measureColumn = data[0]; measureColumn <= data[2]; measureColumn++) {
                        rowFormula |= isFormula(cellAt(facts, sheetId, measureColumn, row));
                    }
                }
                var rowMemberStatus = "ROW".equals(axis)
                        && "POPULATED".equals(rowStatus)
                        && "POPULATED".equals(columnMemberStatus) ? "POPULATED" : "EMPTY";
                var eligible = trainingEligible(
                        axis.equals("ROW") ? rowMemberStatus : memberStatus, rowRole, rowFormula);
                var record = objectMapper.createObjectNode()
                        .put("recordKey", sheetId + "|" + regionId + "|" + row + "|" + address)
                        .put("recordId", recordId(entity, axis.equals("COLUMN") ? address : columnName(column)))
                        .put("entityRecordId", entity).put("rowIndex", row).put("columnIndex", column)
                        .put("rowRole", rowRole).put("trainingEligible", eligible)
                        .put("sampleAddress", axis.equals("COLUMN")
                                ? excelAddress(column, projection.path("identityRow").asInt(1))
                                : excelAddress(header[0], row))
                        .put("sampleName", axis.equals("COLUMN")
                                ? cellText(facts, sheetId, column, projection.path("identityRow").asInt(1))
                                : lastNonBlank(rowPath));
                record.set("rowPath", rowPath.deepCopy());
                var recordDimensions = record.putArray("rowDimensions");
                var recordAttributes = record.putArray("rowAttributes");
                for (var axisNode : axes) {
                    var axisRange = rangeBounds(axisNode.path("sourceRange").asText(""));
                    var axisColumn = axisNode.path("column").asInt(
                            axisRange == null ? header[0] : axisRange[0]);
                    var entry = objectMapper.createObjectNode().put("code", axisNode.path("code").asText())
                            .put("value", cellText(facts, sheetId, axisColumn, row))
                            .put("sourceAddress", excelAddress(axisColumn, row));
                    if ("ROW_ATTRIBUTE".equals(axisNode.path("role").asText())) recordAttributes.add(entry);
                    else recordDimensions.add(entry);
                }
                if (axis.equals("COLUMN")) {
                    record.set("columnMember", objectMapper.createObjectNode()
                            .put("coordinate", columnName(column))
                            .put("address", excelAddress(column, projection.path("identityRow").asInt(1)))
                            .put("label", cellText(facts, sheetId, column, projection.path("identityRow").asInt(1)))
                            .put("status", memberStatus).put("instanceStatus", memberStatus)
                            .put("role", "COLUMN_MEMBER_INPUT"));
                } else {
                    record.set("rowMember", objectMapper.createObjectNode()
                            .put("address", excelRange(header[0], row, header[2], row))
                            .put("label", lastNonBlank(rowPath)).put("status", memberStatus)
                            .put("instanceStatus", memberStatus).put("role", "ROW_MEMBER_INPUT"));
                    record.set("columnMember", objectMapper.createObjectNode()
                            .put("coordinate", columnName(column))
                            .put("address", excelAddress(column, columnHeader == null ? 1 : columnHeader[1]))
                            .put("label", columnHeader == null ? "" : cellText(facts, sheetId, column, columnHeader[1]))
                            .put("status", columnMemberStatus).put("instanceStatus", columnMemberStatus)
                            .put("role", "MEASURE_MEMBER"));
                }
                var value = objectMapper.createObjectNode().put("address", address)
                        .put("valueSource", formula ? "FORMULA" : "USER_INPUT")
                        .put("trainingEligible", eligible);
                if (valueCell != null) {
                    if (valueCell.has("value")) value.set("value", valueCell.path("value").deepCopy());
                    if (valueCell.has("formula")) value.set("formula", valueCell.path("formula").deepCopy());
                }
                record.set("value", value);
                if (axis.equals("ROW")) {
                    var measures = record.putArray("measures");
                    for (int measureColumn = data[0]; measureColumn <= data[2]; measureColumn++) {
                        var measureAddress = excelAddress(measureColumn, row);
                        var measureCell = cellAt(facts, sheetId, measureColumn, row);
                        var measure = objectMapper.createObjectNode()
                                .put("code", columnName(measureColumn))
                                .put("address", measureAddress)
                                .put("valueSource", isFormula(measureCell) ? "FORMULA" : "USER_INPUT")
                                .put("trainingEligible", eligible);
                        if (measureCell != null && measureCell.has("value")) {
                            measure.set("value", measureCell.path("value").deepCopy());
                        }
                        if (measureCell != null && measureCell.has("formula")) {
                            measure.set("formula", measureCell.path("formula").deepCopy());
                        }
                        measures.add(measure);
                    }
                    record.put("valueRange", excelRange(data[0], row, data[2], row));
                    record.put("valueAddress", excelRange(data[0], row, data[2], row));
                } else {
                    record.put("valueAddress", address);
                }
                records.add(record);
            }
        }
        result.set("trainingSummary", trainingSummary(records));
        return result;
    }

    private ArrayNode rowAxes(int[] header, String columnHeaderRange) {
        var result = objectMapper.createArrayNode();
        // Without semantic axes the compiler must not invent one hierarchy
        // level per physical column.  Preserve the whole row-header surface as
        // one unresolved dimension; merge topology/model semantics may refine it.
        result.add(objectMapper.createObjectNode()
                .put("code", "row_dimension_1")
                .put("name", "行维度")
                .put("axis", "ROW").put("role", "ROW_DIMENSION")
                .put("sourceRange", excelRange(header[0], header[1], header[2], header[3]))
                .put("fillMerged", true));
        return result;
    }

    private String rowRole(JsonNode rowPath, JsonNode facts, String sheetId, int row,
                           int startColumn, int endColumn, Set<String> seen) {
        for (int column = startColumn; column <= endColumn; column++) {
            if (isFormula(cellAt(facts, sheetId, column, row))) return "AGGREGATE";
        }
        var signature = rowPath.toString();
        if (signature.replace("\"\"", "").isBlank()) return "UNKNOWN";
        if (!seen.add(signature)) return "REPLICATE";
        var normalized = rowPath.toString().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("第?[0-9一二三四五六七八九十]+(次|组|号)?$", "")
                .replaceAll("(replicate|repeat|sample|测量|重复)[-_ ]*[a-z0-9一二三四五六七八九十]*$", "");
        if (rowPath.toString().matches(".*(第?[0-9一二三四五六七八九十]+|replicate|repeat|sample|测量|重复).*")
                && !seen.add("PATTERN|" + normalized)) return "REPLICATE";
        return "TEST_ITEM";
    }

    private ObjectNode trainingSummary(JsonNode records) {
        var result = objectMapper.createObjectNode().put("total", 0).put("eligible", 0)
                .put("testItem", 0).put("replicate", 0).put("aggregate", 0).put("unknown", 0)
                .put("emptyRuntimeMember", 0).put("formulaExcluded", 0);
        for (var record : records) {
            result.put("total", result.path("total").asInt() + 1);
            var roleKey = switch (record.path("rowRole").asText("UNKNOWN")) {
                case "TEST_ITEM" -> "testItem";
                case "REPLICATE" -> "replicate";
                case "AGGREGATE" -> "aggregate";
                default -> "unknown";
            };
            result.put(roleKey, result.path(roleKey).asInt() + 1);
            if (record.path("trainingEligible").asBoolean(false)) result.put("eligible", result.path("eligible").asInt() + 1);
            if ("FORMULA".equals(record.path("value").path("valueSource").asText())) result.put("formulaExcluded", result.path("formulaExcluded").asInt() + 1);
            if ("EMPTY".equals(record.path("columnMember").path("instanceStatus").asText())
                    || "EMPTY".equals(record.path("rowMember").path("instanceStatus").asText())) {
                result.put("emptyRuntimeMember", result.path("emptyRuntimeMember").asInt() + 1);
            }
        }
        return result;
    }

    private boolean isFormula(JsonNode cell) {
        return cell != null && (cell.path("formula").isTextual() || "FORMULA".equals(cell.path("factType").asText()));
    }

    private String cellText(JsonNode facts, String sheetId, int column, int row) {
        var cell = cellAt(facts, sheetId, column, row);
        return cell == null ? "" : cell.path("value").asText("").replaceAll("[\\r\\n]+", " ").strip();
    }

    private JsonNode cellAt(JsonNode facts, String sheetId, int column, int row) {
        var address = excelAddress(column, row);
        for (var sheet : facts.path("sheets")) {
            if (!sheetId.equals(sheet.path("id").asText(sheet.path("sheetId").asText("")))) continue;
            for (var key : List.of("semanticCells", "physicalCells", "candidateCells")) {
                for (var cell : sheet.path(key)) {
                    if (address.equalsIgnoreCase(cell.path("address").asText(""))) return cell;
                    var merged = rangeBounds(cell.path("mergedRange").asText(""));
                    if (merged != null && merged[0] <= column && merged[1] <= row
                            && merged[2] >= column && merged[3] >= row) return cell;
                }
            }
        }
        return null;
    }

    private int[] rangeBounds(String range) {
        if (range == null || range.isBlank()) return null;
        var parts = range.toUpperCase(java.util.Locale.ROOT).split(":", 2);
        var start = cellBounds(parts[0]);
        var end = cellBounds(parts.length == 1 ? parts[0] : parts[1]);
        if (start == null || end == null) return null;
        return new int[]{Math.min(start[0], end[0]), Math.min(start[1], end[1]),
                Math.max(start[0], end[0]), Math.max(start[1], end[1])};
    }

    private int[] cellBounds(String address) {
        if (address == null || address.isBlank()) return null;
        var matcher = java.util.regex.Pattern.compile("^([A-Z]+)([1-9][0-9]*)$")
                .matcher(address.toUpperCase(java.util.Locale.ROOT));
        if (!matcher.matches()) return null;
        var column = 0;
        for (var character : matcher.group(1).toCharArray()) column = column * 26 + character - 'A' + 1;
        return new int[]{column, Integer.parseInt(matcher.group(2))};
    }

    private String lastNonBlank(JsonNode values) {
        var result = "";
        for (var value : values) if (!value.asText("").strip().isBlank()) result = value.asText().strip();
        return result;
    }

    private String stableRegionId(String sheetId, String regionId, String matrixRange) {
        if (regionId != null && !regionId.isBlank()) return regionId;
        return "region-" + RecognitionIdentity.shortHash(sheetId + "|" + matrixRange, 16);
    }

    private String excelRange(int startColumn, int startRow, int endColumn, int endRow) {
        var start = excelAddress(startColumn, startRow);
        var end = excelAddress(endColumn, endRow);
        return start.equals(end) ? start : start + ":" + end;
    }

    private String excelAddress(int column, int row) {
        var result = new StringBuilder();
        for (var value = Math.max(1, column); value > 0; value = (value - 1) / 26) {
            result.insert(0, (char) ('A' + (value - 1) % 26));
        }
        return result + Integer.toString(Math.max(1, row));
    }

    private String columnName(int column) {
        return excelAddress(column, 1).replace("1", "");
    }
}
