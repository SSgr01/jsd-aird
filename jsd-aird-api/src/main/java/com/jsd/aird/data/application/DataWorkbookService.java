package com.jsd.aird.data.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.excel.WorkbookInstanceParser;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.springframework.stereotype.Service;

/** Builds the immutable source workbook plus the current correction overlay for data workbenches. */
@Service
public class DataWorkbookService {

    private final DataRepository repository;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final WorkbookInstanceParser workbookParser;
    private final TemplateDataImportFacade templates;
    private final ObjectMapper objectMapper;

    public DataWorkbookService(DataRepository repository, FileObjectRepository files, ObjectStorage storage,
                               WorkbookInstanceParser workbookParser, TemplateDataImportFacade templates,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.files = files;
        this.storage = storage;
        this.workbookParser = workbookParser;
        this.templates = templates;
        this.objectMapper = objectMapper;
    }

    public WorkbookContext importJob(UUID importJobId) {
        var actor = ActorContext.required();
        var job = repository.findJob(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        var sheets = repository.listSheets(actor.organizationId(), importJobId);
        var mappings = repository.listMappings(actor.organizationId(), importJobId);
        var rows = repository.listRows(actor.organizationId(), importJobId);
        var definition = templates.getVersion(actor.organizationId(), job.templateVersionId());
        var bindings = templates.getBindings(actor.organizationId(), job.templateVersionId());
        var file = requireFile(actor.organizationId(), job.sourceFileId());
        var snapshot = sourceSnapshot(file, job.sourceFormat(), sheets);
        var fields = importFields(rows, mappings, sheets, componentByBinding(definition));
        var fieldDefinitions = fieldDefinitions(definition, bindings, fields);
        applyCorrections(snapshot, fields);
        return context(file, job.sourceSha256(), job.sourceFormat(), snapshot, sheets,
                sheets.stream().filter(DataRepository.Sheet::selected).findFirst()
                        .map(DataRepository.Sheet::sheetId).orElse(null),
                List.of("WAITING_MAPPING", "WAITING_CONFIRM").contains(job.status()),
                fieldDefinitions, fields, definition);
    }

    private FileObjectRepository.FileObject requireFile(UUID organizationId, UUID fileId) {
        return files.find(organizationId, fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "原始数据文件不存在"));
    }

    private JsonNode sourceSnapshot(FileObjectRepository.FileObject file, String sourceFormat,
                                    List<DataRepository.Sheet> sheets) {
        var format = sourceFormat == null ? "" : sourceFormat.toUpperCase(Locale.ROOT);
        if ("XLSX".equals(format)) {
            try (var stored = storage.get(file.objectKey())) {
                return workbookParser.parseInstance(stored.stream()).snapshot().deepCopy();
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ApiException(ApiErrorCode.FILE_NOT_READY, "原始 Excel 工作簿读取失败");
            }
        }
        if ("CSV".equals(format)) return csvSnapshot(file.originalName(), sheets);
        return objectMapper.createObjectNode();
    }

    private JsonNode csvSnapshot(String fileName, List<DataRepository.Sheet> sheets) {
        var root = objectMapper.createObjectNode()
                .put("id", UUID.randomUUID().toString())
                .put("name", fileName)
                .put("appVersion", "univer-0.25.1")
                .put("snapshotFormatVersion", 3);
        root.set("styles", objectMapper.createObjectNode());
        var order = root.putArray("sheetOrder");
        var workbookSheets = root.putObject("sheets");
        for (var sheet : sheets) {
            order.add(sheet.sheetId());
            var rows = sheet.structure().path("rows");
            var cellData = objectMapper.createObjectNode();
            int maxColumns = 0;
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var values = rows.path(rowIndex);
                maxColumns = Math.max(maxColumns, values.size());
                var cells = objectMapper.createObjectNode();
                for (int column = 0; column < values.size(); column++) {
                    var value = values.path(column).asText("");
                    if (!value.isBlank()) cells.set(String.valueOf(column), cellValue(value));
                }
                if (!cells.isEmpty()) cellData.set(String.valueOf(rowIndex), cells);
            }
            var item = objectMapper.createObjectNode()
                    .put("id", sheet.sheetId()).put("name", sheet.sheetName())
                    .put("rowCount", Math.max(200, rows.size() + 20))
                    .put("columnCount", Math.max(50, maxColumns + 10));
            item.set("cellData", cellData);
            item.set("mergeData", objectMapper.createArrayNode());
            item.set("rowData", objectMapper.createObjectNode());
            item.set("columnData", objectMapper.createObjectNode());
            item.set("rowHeader", objectMapper.createObjectNode().put("width", 46));
            item.set("columnHeader", objectMapper.createObjectNode().put("height", 20));
            workbookSheets.set(sheet.sheetId(), item);
        }
        return root;
    }

    List<FieldValue> importFields(List<DataRepository.Row> rows, List<DataRepository.Mapping> mappings,
                                  List<DataRepository.Sheet> sheets) {
        return importFields(rows, mappings, sheets, Map.of());
    }

    private List<FieldValue> importFields(List<DataRepository.Row> rows, List<DataRepository.Mapping> mappings,
                                          List<DataRepository.Sheet> sheets,
                                          Map<String, String> componentByBinding) {
        var sheetNames = new LinkedHashMap<String, String>();
        sheets.forEach(item -> sheetNames.put(item.sheetId(), item.sheetName()));
        var result = new ArrayList<FieldValue>();
        for (var row : rows) {
            var normalized = row.normalizedValues();
            var corrected = row.correctedValues();
            var entries = normalized.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                var value = entry.getValue();
                // Before the user saves mappings, staged rows intentionally contain plain values keyed by the
                // extractor's stable source-column id (for example b_xxx).  Only validated rows contain the
                // structured value wrapper.  Treating a text node as that wrapper exposed the internal key as
                // the field name and discarded its source address and value in the workbench.
                var wrapped = value != null && value.isObject();
                var sourceMapping = wrapped ? null : mappingBySource(mappings, row.sheetId(), entry.getKey());
                var fieldCode = wrapped
                        ? value.path("fieldCode").asText(entry.getKey())
                        : sourceMapping != null && sourceMapping.fieldCode() != null
                        ? sourceMapping.fieldCode() : entry.getKey();
                var provisionalBindingId = wrapped ? value.path("bindingId").asText(fieldCode) : fieldCode;
                var provisionalValuePath = wrapped
                        ? value.path("valuePath").asText(value.path("dataPath").asText("/" + fieldCode))
                        : "/" + fieldCode;
                var mapping = sourceMapping == null
                        ? mapping(mappings, row.sheetId(), fieldCode, provisionalBindingId, provisionalValuePath)
                        : sourceMapping;
                var sourceColumn = wrapped
                        ? value.path("sourceColumn").asText(mapping == null ? "" : mapping.sourceColumn())
                        : entry.getKey();
                var source = row.sourceMetadata() == null
                        ? JsonNodeFactory.instance.missingNode()
                        : row.sourceMetadata().path("cells").path(sourceColumn);
                var bindingId = firstText(value, "bindingId",
                        source.path("bindingId").asText(""),
                        mapping == null ? "" : mapping.detail().path("bindingId").asText(""), fieldCode);
                var valuePath = firstText(value, "valuePath",
                        source.path("valuePath").asText(""),
                        mapping == null ? "" : mapping.detail().path("dataPath").asText(""),
                        "/" + fieldCode);
                var address = source.path("cellAddress").asText(sourceColumn.isBlank() ? "" : sourceColumn + row.rowNumber());
                var correctedWrapper = corrected.path(entry.getKey());
                // Newly parsed rows mirror raw values into corrected_values_jsonb. That mirror is not an
                // audit correction wrapper and must not replace a staged value with null in the workbench.
                if (!correctedWrapper.isObject() || !correctedWrapper.has("correctedValue")) {
                    correctedWrapper = JsonNodeFactory.instance.missingNode();
                }
                var correctedValue = correctedWrapper.has("correctedValue")
                        ? correctedWrapper.path("correctedValue") : JsonNodeFactory.instance.nullNode();
                var rawValue = wrapped && value.has("rawValue")
                        ? value.path("rawValue") : row.rawValues().path(sourceColumn);
                var normalizedValue = wrapped && value.has("normalizedValue")
                        ? value.path("normalizedValue") : value;
                var effective = correctedValue.isNull() ? normalizedValue : correctedValue;
                var valueSource = firstText(value, "valueSource", source.path("valueSource").asText(""),
                        mapping == null ? "" : mapping.detail().path("valueSource").asText(""), "INPUT");
                var fieldName = mapping == null || mapping.fieldName() == null || mapping.fieldName().isBlank()
                        ? mapping == null || mapping.sourceHeader() == null || mapping.sourceHeader().isBlank()
                        ? fieldCode : mapping.sourceHeader()
                        : mapping.fieldName();
                var labelPath = firstText(value, "labelPath", source.path("labelPath").asText(""),
                        mapping == null ? "" : mapping.detail().path("labelPath").asText(""), "");
                var visibleLabelPath = userLabelPath(labelPath);
                var locator = mapping == null ? JsonNodeFactory.instance.missingNode() : mapping.detail().path("locator");
                var mappingKind = firstText(value, "mappingKind", source.path("mappingKind").asText(""),
                        mapping == null ? "" : mapping.detail().path("mappingKind").asText(""),
                        row.sourceMetadata() == null ? "" : row.sourceMetadata().path("shape").asText(""));
                var parentBindingId = firstText(value, "parentBindingId", source.path("parentBindingId").asText(""),
                        mapping == null ? "" : mapping.detail().path("parentBindingId").asText(""));
                var componentId = firstNonBlank(mapping == null ? "" : mapping.detail().path("componentId").asText(""),
                        value != null && value.isObject() ? value.path("componentId").asText("") : "",
                        source.path("componentId").asText(""),
                        componentByBinding.get(bindingId), componentByBinding.get(parentBindingId),
                        locator.path("componentId").asText(""), parentBindingId);
                if (componentId.isBlank()) componentId = row.sheetId() + ":" + mappingKind;
                var repeatAxis = firstText(value, "repeatAxis", source.path("repeatAxis").asText(""),
                        mapping == null ? "" : mapping.detail().path("repeatAxis").asText(""));
                var recordKey = row.sourceMetadata() == null ? row.id().toString()
                        : row.sourceMetadata().path("recordKey").asText(row.id().toString());
                var dimensions = row.sourceMetadata() == null ? JsonNodeFactory.instance.missingNode()
                        : row.sourceMetadata().path("dimensions");
                var recordGroupId = recordGroupId(row.id().toString(), componentId, dimensions);
                result.add(new FieldValue(row.id().toString(), fieldCode,
                        userFieldName(fieldName, mapping == null ? null : mapping.sourceHeader(), fieldCode),
                        visibleLabelPath,
                        bindingId, valuePath, valueSource, row.status(),
                        mapping == null ? "TEXT" : mapping.valueType(), mapping == null ? "" : mapping.standardUnit(),
                        mapping != null && mapping.detail().path("required").asBoolean(false),
                        mapping != null && mapping.detail().path("identity").asBoolean(false),
                        !wrapped || value.path("trainingEligible").asBoolean(true),
                        mapping == null || mapping.detail().path("ragEligible").asBoolean(true),
                        row.sheetId(), sheetNames.getOrDefault(row.sheetId(), row.sheetId()), row.rowNumber(), address,
                        rawValue.deepCopy(), normalizedValue.deepCopy(), correctedValue.deepCopy(), effective.deepCopy(),
                        mapping != null && "MAP".equals(mapping.action())
                                && !mapping.detail().path("syntheticDimension").asBoolean(false)
                                && editableValue(valueSource)
                                && !address.isBlank(),
                        row.excluded(), row.exclusionReason(), componentId, mappingKind, repeatAxis,
                        parentBindingId, fieldGroup(visibleLabelPath, fieldName), recordKey,
                        dimensions.isObject() ? dimensions.deepCopy() : JsonNodeFactory.instance.nullNode(),
                        recordGroupId));
            }
        }
        return List.copyOf(result);
    }

    private DataRepository.Mapping mappingBySource(List<DataRepository.Mapping> mappings, String sheetId,
                                                   String sourceColumn) {
        return mappings.stream()
                .filter(item -> sheetId.equals(item.sheetId()) && sourceColumn.equals(item.sourceColumn()))
                .findFirst().orElse(null);
    }

    private DataRepository.Mapping mapping(List<DataRepository.Mapping> mappings, String sheetId, String fieldCode,
                                           String bindingId, String valuePath) {
        return mappings.stream().filter(item -> sheetId.equals(item.sheetId()))
                .filter(item -> fieldCode.equals(item.fieldCode()))
                .filter(item -> bindingId.equals(item.detail().path("bindingId").asText(item.fieldCode())))
                .filter(item -> valuePath.equals(item.detail().path("dataPath").asText("/" + item.fieldCode())))
                .findFirst().orElseGet(() -> mappings.stream()
                        .filter(item -> sheetId.equals(item.sheetId()) && fieldCode.equals(item.fieldCode()))
                        .findFirst().orElse(null));
    }

    private void applyCorrections(JsonNode snapshot, List<FieldValue> fields) {
        if (!(snapshot instanceof ObjectNode root)) return;
        for (var field : fields) {
            if (field.correctedValue() == null || field.correctedValue().isNull()
                    || field.sheetId() == null || field.address() == null || field.address().isBlank()) continue;
            var coordinate = coordinate(field.address());
            if (coordinate == null) continue;
            var sheet = root.path("sheets").path(field.sheetId());
            if (!(sheet instanceof ObjectNode sheetObject)) continue;
            var rows = sheetObject.withObject("cellData");
            var columns = rows.withObject(String.valueOf(coordinate.row() - 1));
            var existing = columns.path(String.valueOf(coordinate.column() - 1));
            var cell = existing instanceof ObjectNode object ? object : objectMapper.createObjectNode();
            cell.setAll(cellValue(field.correctedValue()));
            cell.remove("f");
            columns.set(String.valueOf(coordinate.column() - 1), cell);
        }
    }

    private ObjectNode cellValue(JsonNode value) {
        var cell = objectMapper.createObjectNode();
        if (value == null || value.isNull()) return cell;
        if (value.isNumber()) {
            cell.set("v", value.deepCopy());
            cell.put("t", 2);
        } else if (value.isBoolean()) {
            cell.set("v", value.deepCopy());
            cell.put("t", 3);
        } else cell.put("v", value.asText()).put("t", 1);
        return cell;
    }

    private ObjectNode cellValue(String value) {
        return objectMapper.createObjectNode().put("v", value).put("t", 1);
    }

    private Cell coordinate(String address) {
        var matcher = java.util.regex.Pattern.compile("^([A-Za-z]+)([1-9][0-9]*)$").matcher(address.trim());
        if (!matcher.matches()) return null;
        int column = 0;
        for (char value : matcher.group(1).toUpperCase(Locale.ROOT).toCharArray()) {
            column = column * 26 + value - 'A' + 1;
        }
        return new Cell(Integer.parseInt(matcher.group(2)), column);
    }

    private boolean editableValue(String source) {
        return source == null || !List.of("FORMULA", "DERIVED", "STATIC")
                .contains(source.toUpperCase(Locale.ROOT));
    }

    private String firstText(JsonNode value, String key, String... fallbacks) {
        if (value != null && value.isObject()) {
            var direct = value.path(key).asText("").trim();
            if (!direct.isBlank()) return direct;
        }
        for (var fallback : fallbacks) {
            if (fallback != null && !fallback.isBlank()) return fallback.trim();
        }
        return "";
    }

    private String userLabelPath(String value) {
        if (value == null || value.isBlank()) return "";
        var normalized = value.trim();
        // Contract codes are useful for execution and tracing, but not as a customer-facing field title.
        if (internalCode(normalized)) return "";
        return normalized;
    }

    private String userFieldName(String fieldName, String sourceHeader, String fieldCode) {
        for (var candidate : List.of(fieldName == null ? "" : fieldName,
                sourceHeader == null ? "" : sourceHeader)) {
            var value = candidate.trim();
            if (!value.isBlank() && !internalCode(value)) return value;
        }
        return "未命名字段";
    }

    private boolean internalCode(String value) {
        if (value == null || value.isBlank()) return false;
        var normalized = value.trim();
        return normalized.matches("(?i)b_[a-f0-9]+")
                || normalized.matches("(?i)^(?:AUTO|TABLE|MATRIX|DATA|MATERIAL|PRODUCTION|WORKFLOW|FIELD)\\..+$")
                || normalized.matches("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}");
    }

    private String fieldGroup(String labelPath, String fieldName) {
        if (labelPath == null || labelPath.isBlank()) return "";
        var parts = java.util.Arrays.stream(labelPath.split("\\s*(?:>|/|›)\\s*"))
                .filter(value -> !value.isBlank()).toList();
        if (parts.size() > 1) return String.join(" / ", parts.subList(0, parts.size() - 1));
        return !labelPath.equals(fieldName) ? labelPath : "";
    }

    private Map<String, String> componentByBinding(TemplateDataImportFacade.DataTemplateDefinition definition) {
        var result = new LinkedHashMap<String, String>();
        if (definition == null || definition.importContract() == null) return result;
        for (var component : definition.importContract().path("components")) {
            var componentId = component.path("componentId").asText("");
            if (componentId.isBlank()) continue;
            for (var binding : component.path("bindings")) {
                var bindingId = binding.path("bindingId").asText("");
                if (!bindingId.isBlank()) result.put(bindingId, componentId);
            }
        }
        return result;
    }

    /**
     * Compiles the immutable template fields separately from per-record values. Empty columns therefore remain
     * visible in the field structure and two imported rows never become two copies of the template definition.
     */
    List<FieldDefinitionView> fieldDefinitions(
            TemplateDataImportFacade.DataTemplateDefinition definition,
            List<TemplateDataImportFacade.ImportBinding> bindings,
            List<FieldValue> values
    ) {
        var names = new LinkedHashMap<String, String>();
        var dataTypes = new LinkedHashMap<String, String>();
        var units = new LinkedHashMap<String, String>();
        if (definition != null) {
            definition.fields().forEach(item -> {
                names.put(item.fieldCode(), item.displayName());
                dataTypes.put(item.fieldCode(), item.dataType());
                units.put(item.fieldCode(), item.defaultUnit());
            });
        }
        var descriptions = new LinkedHashMap<String, String>();
        if (definition != null && definition.importContract() != null) {
            for (var field : definition.importContract().path("fields")) {
                descriptions.put(field.path("fieldCode").asText(""), field.path("description").asText(""));
                var name = field.path("name").asText("");
                if (!name.isBlank()) names.putIfAbsent(field.path("fieldCode").asText(""), name);
            }
        }
        var components = componentByBinding(definition);
        var sheetNames = new LinkedHashMap<String, String>();
        values.forEach(item -> {
            if (item.sheetId() != null && item.sheetName() != null) sheetNames.put(item.sheetId(), item.sheetName());
        });
        var result = new LinkedHashMap<String, FieldDefinitionView>();
        for (var binding : bindings == null ? List.<TemplateDataImportFacade.ImportBinding>of() : bindings) {
            if (isRegionBinding(binding)) continue;
            var bindingId = firstNonBlank(binding.bindingId(), binding.fieldCode(), binding.dataPath());
            if (bindingId.isBlank() || result.containsKey(bindingId)) continue;
            var locator = binding.locator() == null ? JsonNodeFactory.instance.missingNode() : binding.locator();
            var value = values.stream().filter(item -> bindingId.equals(item.bindingId())).findFirst().orElse(null);
            var componentId = firstNonBlank(value == null ? "" : value.componentId(), components.get(bindingId),
                    components.get(binding.parentBindingId()), locator.path("componentId").asText(""),
                     binding.parentBindingId());
            if (value == null) {
                var sameCode = values.stream()
                        .filter(item -> binding.fieldCode() != null && binding.fieldCode().equals(item.fieldCode()))
                        .toList();
                var matchingComponents = sameCode.stream().map(FieldValue::componentId)
                        .filter(item -> item != null && !item.isBlank()).distinct().toList();
                if (matchingComponents.size() == 1 && !sameCode.isEmpty()) value = sameCode.getFirst();
            }
            if (componentId.isBlank() && value != null) componentId = value.componentId();
            var sheetId = firstNonBlank(locator.path("sheetId").asText(""), locator.path("sheet").asText(""),
                    value == null ? "" : value.sheetId());
            var labelPath = userLabelPath(binding.labelPath());
            var displayName = userFieldName(names.get(binding.fieldCode()), labelLeaf(labelPath), binding.fieldCode());
            if ("未命名字段".equals(displayName) && value != null) displayName = value.fieldName();
            var valueType = firstNonBlank(binding.valueType(), dataTypes.get(binding.fieldCode()),
                    value == null ? "" : value.valueType(), "TEXT");
            var unit = firstNonBlank(binding.unit(), units.get(binding.fieldCode()), value == null ? "" : value.unit());
            var sourceRange = firstNonBlank(locator.path("valueRange").asText(""),
                    locator.path("inputRange").asText(""), locator.path("logicalInputRange").asText(""),
                    locator.path("sourceRange").asText(""), locator.path("range").asText(""),
                    locator.path("address").asText(""),
                    value == null ? "" : value.address());
            var groupPath = fieldDefinitionGroup(binding, labelPath, displayName);
            result.put(bindingId, new FieldDefinitionView(componentId, bindingId, binding.parentBindingId(),
                    binding.fieldCode(), displayName, descriptions.getOrDefault(binding.fieldCode(), ""),
                    labelPath, binding.mappingKind(), binding.repeatAxis(), valueType, unit,
                    binding.required(), binding.identity(), groupPath,
                    sheetId, sheetNames.getOrDefault(sheetId, sheetId), sourceRange));
        }
        // Recognition may produce both the business paragraph and a generic
        // "签名/日期" candidate for the exact same merged input range. Prefer
        // the binding that actually produced values; distinct empty fields at
        // different locations remain visible in the template structure.
        var valuedBindings = values.stream().map(FieldValue::bindingId)
                .filter(item -> item != null && !item.isBlank()).collect(java.util.stream.Collectors.toSet());
        var chosenByLocation = new LinkedHashMap<String, FieldDefinitionView>();
        for (var item : result.values()) {
            if (item.sheetId() == null || item.sheetId().isBlank()
                    || item.sourceRange() == null || item.sourceRange().isBlank()) {
                chosenByLocation.put(item.bindingId(), item);
                continue;
            }
            var key = item.sheetId() + "|" + item.sourceRange().replace("$", "").toUpperCase(Locale.ROOT);
            var existing = chosenByLocation.get(key);
            if (existing == null) {
                chosenByLocation.put(key, item);
                continue;
            }
            var existingValued = valuedBindings.contains(existing.bindingId());
            var currentValued = valuedBindings.contains(item.bindingId());
            if (currentValued && !existingValued) chosenByLocation.put(key, item);
            else if (currentValued == existingValued) chosenByLocation.put(item.bindingId(), item);
        }
        return List.copyOf(chosenByLocation.values());
    }

    private String fieldDefinitionGroup(TemplateDataImportFacade.ImportBinding binding,
                                        String labelPath, String displayName) {
        var code = binding.fieldCode() == null ? "" : binding.fieldCode().toUpperCase(Locale.ROOT);
        if (code.contains("ROW_DIMENSION") || code.contains("ROW_ATTRIBUTE")) return "行维度";
        if (code.contains("COLUMN_DIMENSION") || code.contains("COLUMN_MEMBER")) return "列维度";
        if (code.contains("MATRIX.MEASURE")) return "指标值";
        return fieldGroup(labelPath, displayName);
    }

    private boolean isRegionBinding(TemplateDataImportFacade.ImportBinding binding) {
        var kind = binding.mappingKind() == null ? "" : binding.mappingKind().toUpperCase(Locale.ROOT);
        return kind.endsWith("_REGION") || List.of("ROW_TABLE", "COLUMN_TABLE", "MATRIX", "TABLE_REGION")
                .contains(kind);
    }

    private String labelLeaf(String labelPath) {
        if (labelPath == null || labelPath.isBlank()) return "";
        var parts = labelPath.split("\\s*(?:>|/|›)\\s*");
        return parts.length == 0 ? labelPath : parts[parts.length - 1];
    }

    private String firstNonBlank(String... values) {
        for (var value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private WorkbookContext context(FileObjectRepository.FileObject file, String hash, String format,
                                    JsonNode snapshot, List<DataRepository.Sheet> sheets,
                                    String selectedSheetId, boolean editable,
                                    List<FieldDefinitionView> fieldDefinitions, List<FieldValue> fields,
                                    TemplateDataImportFacade.DataTemplateDefinition definition) {
        var regions = workbookRegions(definition, sheets, fieldDefinitions, fields);
        var records = workbookRecords(regions, fields);
        return new WorkbookContext(file.originalName(), file.contentType(), hash, format, snapshot,
                sheets.stream().map(item -> new WorkbookSheet(item.sheetId(), item.sheetName(), item.sheetOrder(),
                        item.selected(), item.confirmationStatus())).toList(), selectedSheetId, editable,
                regions, fieldDefinitions, records, fields);
    }

    List<WorkbookRegion> workbookRegions(TemplateDataImportFacade.DataTemplateDefinition definition,
                                         List<DataRepository.Sheet> sheets,
                                         List<FieldDefinitionView> fieldDefinitions,
                                         List<FieldValue> fields) {
        var result = new ArrayList<WorkbookRegion>();
        var known = new java.util.LinkedHashSet<String>();
        var sheetNames = new LinkedHashMap<String, String>();
        sheets.forEach(item -> sheetNames.put(item.sheetId(), item.sheetName()));
        var components = definition == null || definition.importContract() == null
                ? JsonNodeFactory.instance.arrayNode() : definition.importContract().path("components");
        for (var component : components) {
            var componentId = component.path("componentId").asText("");
            if (componentId.isBlank()) continue;
            if (known.contains(componentId)) continue;
            known.add(componentId);
            var componentFields = fields.stream().filter(item -> componentId.equals(item.componentId())).toList();
            var definitions = fieldDefinitions.stream()
                    .filter(item -> componentId.equals(item.componentId())).toList();
            // Region-only contract nodes are execution scaffolding. When no
            // field definition or imported value belongs to the node, showing
            // it creates an empty duplicate region in the customer workbench.
            if (definitions.isEmpty() && componentFields.isEmpty()) continue;
            if (componentFields.isEmpty() && !definitions.isEmpty()
                    && definitions.stream().allMatch(item -> item.sourceRange() == null
                    || item.sourceRange().isBlank())) continue;
            result.add(region(componentId, component, definitions, componentFields, definition, sheetNames));
        }
        var definitionComponentIds = fieldDefinitions.stream().map(FieldDefinitionView::componentId).toList();
        java.util.stream.Stream.concat(definitionComponentIds.stream(), fields.stream().map(FieldValue::componentId))
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !known.contains(value)).distinct().forEach(componentId -> {
                    var componentFields = fields.stream().filter(item -> componentId.equals(item.componentId())).toList();
                    var definitions = fieldDefinitions.stream()
                            .filter(item -> componentId.equals(item.componentId())).toList();
                    var seed = objectMapper.createObjectNode()
                            .put("componentId", componentId)
                            .put("sheetId", !definitions.isEmpty() ? definitions.getFirst().sheetId()
                                    : componentFields.isEmpty() ? "" : componentFields.getFirst().sheetId())
                            .put("structureType", !definitions.isEmpty() ? definitions.getFirst().mappingKind()
                                    : componentFields.isEmpty() ? "SCALAR" : componentFields.getFirst().mappingKind())
                            .put("repeatAxis", !definitions.isEmpty() ? definitions.getFirst().repeatAxis()
                                    : componentFields.isEmpty() ? "" : componentFields.getFirst().repeatAxis());
                    result.add(region(componentId, seed, definitions, componentFields, definition, sheetNames));
                });
        if (result.isEmpty() && !fields.isEmpty()) {
            var first = fields.getFirst();
            var seed = objectMapper.createObjectNode().put("componentId", first.sheetId() + ":data")
                    .put("sheetId", first.sheetId()).put("structureType", "SCALAR");
            result.add(region(seed.path("componentId").asText(), seed, fieldDefinitions,
                    fields, definition, sheetNames));
        }
        return List.copyOf(result);
    }

    private WorkbookRegion region(String componentId, JsonNode component,
                                  List<FieldDefinitionView> definitions, List<FieldValue> fields,
                                  TemplateDataImportFacade.DataTemplateDefinition definition,
                                  Map<String, String> sheetNames) {
        var sheetId = firstNonBlank(component.path("sheetId").asText(""),
                definitions.isEmpty() ? "" : definitions.getFirst().sheetId(),
                fields.isEmpty() ? "" : fields.getFirst().sheetId());
        var structureType = firstNonBlank(component.path("structureType").asText(""),
                definitions.isEmpty() ? "" : definitions.getFirst().mappingKind(),
                fields.isEmpty() ? "SCALAR" : fields.getFirst().mappingKind());
        var repeatAxis = firstNonBlank(component.path("repeatAxis").asText(""),
                definitions.isEmpty() ? "" : definitions.getFirst().repeatAxis(),
                fields.isEmpty() ? "" : fields.getFirst().repeatAxis());
        var name = regionName(componentId, component, definitions, definition,
                sheetNames.getOrDefault(sheetId, sheetId), structureType);
        var groups = definitions.stream().map(FieldDefinitionView::groupPath)
                .filter(value -> value != null && !value.isBlank())
                .distinct().map(group -> new WorkbookFieldGroup(group, group,
                        (int) definitions.stream().filter(item -> group.equals(item.groupPath()))
                                .map(FieldDefinitionView::bindingId).distinct().count())).toList();
        return new WorkbookRegion(componentId, name, structureType, sheetId,
                sheetNames.getOrDefault(sheetId, sheetId), component.path("range").asText(""), repeatAxis,
                definitions.size(),
                fields.stream().map(FieldValue::recordGroupId)
                        .filter(value -> value != null && !value.isBlank()).distinct().count(),
                groups);
    }

    private String regionName(String componentId, JsonNode component, List<FieldDefinitionView> definitions,
                              TemplateDataImportFacade.DataTemplateDefinition definition,
                              String sheetName, String structureType) {
        // A business region name declared by the published template is more
        // authoritative than a coincidental single child field. Otherwise a
        // one-column region such as "操作步骤" or "配方明细" is renamed after
        // its first field and no longer matches the template-side field tree.
        var contractName = "";
        for (var key : List.of("name", "displayName", "groupName", "label", "title")) {
            var value = userLabelPath(component.path(key).asText(""));
            if (!value.isBlank()) {
                contractName = normalizeBusinessRegionName(value);
                break;
            }
        }
        var templateFieldName = templateComponentName(definition, componentId);
        templateFieldName = normalizeBusinessRegionName(templateFieldName);
        if (isGenericRegionName(contractName) && !definitions.isEmpty()
                && definitions.stream().allMatch(item -> item.displayName() != null
                && item.displayName().matches(".*(?:签名|签字|日期).*"))) {
            return "处理与签字";
        }
        // Prefer a concrete template-side business name over a generic contract
        // bucket, but never let an internal placeholder such as "重复记录区域"
        // replace the published contract's customer-facing name.
        if (!templateFieldName.isBlank() && !isInternalRegionPlaceholder(templateFieldName)
                && (contractName.isBlank() || isGenericRegionName(contractName))) {
            return templateFieldName;
        }
        if (!contractName.isBlank()) return contractName;
        if (!templateFieldName.isBlank() && !isInternalRegionPlaceholder(templateFieldName)) return templateFieldName;
        if (definitions.size() == 1 && isRepeatedStructure(structureType)
                && conciseRegionFieldName(definitions.getFirst().displayName())) {
            var fieldName = userLabelPath(definitions.getFirst().displayName());
            if (!fieldName.isBlank()) return normalizeBusinessRegionName(fieldName);
        }
        if (definition != null) {
            var names = new LinkedHashMap<String, String>();
            definition.fields().forEach(item -> names.put(item.fieldCode(), item.displayName()));
            for (var binding : component.path("bindings")) {
                if (!binding.path("mappingKind").asText("").toUpperCase(Locale.ROOT).contains("REGION")) continue;
                var value = userFieldName(names.get(binding.path("fieldCode").asText("")),
                        binding.path("labelPath").asText(""), binding.path("fieldCode").asText(""));
                if (!"未命名字段".equals(value)) return normalizeBusinessRegionName(value);
            }
        }
        var fieldGroups = definitions.stream().map(FieldDefinitionView::groupPath)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (fieldGroups.size() == 1) return normalizeBusinessRegionName(fieldGroups.getFirst());
        if (definition != null && isRepeatedStructure(structureType)) {
            var templateName = cleanTemplateName(definition.name());
            if (!templateName.isBlank()) return templateName;
        }
        return structureTypeLabel(structureType);
    }

    private String templateComponentName(TemplateDataImportFacade.DataTemplateDefinition definition,
                                         String componentId) {
        if (definition == null || componentId == null || componentId.isBlank()) return "";
        var fieldId = "";
        var fieldCode = "";
        if (definition.mappings() != null && definition.mappings().isArray()) {
            for (var mapping : definition.mappings()) {
                if (!componentId.equals(mapping.path("bindingId").asText(""))) continue;
                fieldId = mapping.path("fieldId").asText("");
                fieldCode = mapping.path("fieldCode").asText("");
                break;
            }
        }
        var fields = definition.schema() == null ? JsonNodeFactory.instance.arrayNode()
                : definition.schema().path("x-jsd-field-model").path("fields");
        for (var field : fields) {
            if ((!fieldId.isBlank() && fieldId.equals(field.path("fieldId").asText(field.path("id").asText(""))))
                    || (!fieldCode.isBlank() && fieldCode.equals(field.path("fieldCode").asText("")))) {
                var name = userLabelPath(field.path("name").asText(""));
                if (!name.isBlank()) return name;
            }
        }
        return "";
    }

    private boolean isRepeatedStructure(String value) {
        var type = value == null ? "" : value.toUpperCase(Locale.ROOT);
        return type.contains("REPEAT") || type.contains("TABLE") || type.contains("ROW")
                || type.contains("COLUMN") || type.contains("MATRIX");
    }

    private String normalizeBusinessRegionName(String value) {
        var normalized = value == null ? "" : value.trim();
        if ("操作程序".equals(normalized)) return "操作步骤";
        return normalized;
    }

    private boolean isGenericRegionName(String value) {
        return Set.of("基本信息", "基础信息", "测试数据", "矩阵数据", "按行记录", "按列记录", "表单信息", "其他信息")
                .contains(value);
    }

    private boolean isInternalRegionPlaceholder(String value) {
        return Set.of("重复记录区域", "数据区域", "未分区字段", "模型建议结构").contains(value);
    }

    private boolean conciseRegionFieldName(String value) {
        if (value == null) return false;
        var normalized = value.trim();
        return !normalized.isBlank() && normalized.length() <= 12
                && !normalized.contains("/") && !normalized.contains("／");
    }

    private String cleanTemplateName(String value) {
        if (value == null) return "";
        return value.trim().replaceFirst("(?:模板|表单|工作簿)$", "").trim();
    }

    private String structureTypeLabel(String value) {
        var type = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (type.contains("MATRIX")) return "矩阵数据";
        if (type.contains("COLUMN")) return "按列记录";
        if (type.contains("ROW") || type.contains("REPEAT")) return "按行记录";
        if (type.contains("FORM")) return "表单信息";
        return "其他信息";
    }

    private List<WorkbookRecord> workbookRecords(List<WorkbookRegion> regions, List<FieldValue> fields) {
        var result = new ArrayList<WorkbookRecord>();
        for (var region : regions) {
            var recordIds = fields.stream().filter(item -> region.regionId().equals(item.componentId()))
                    .map(FieldValue::recordGroupId).filter(value -> value != null && !value.isBlank()).distinct().toList();
            for (int index = 0; index < recordIds.size(); index++) {
                var recordId = recordIds.get(index);
                var recordFields = fields.stream().filter(item -> region.regionId().equals(item.componentId())
                        && recordId.equals(item.recordGroupId())).toList();
                var identity = recordFields.stream().filter(FieldValue::identity)
                        .map(FieldValue::effectiveValue).filter(value -> value != null && !value.isNull())
                        .map(JsonNode::asText).filter(value -> !value.isBlank()).findFirst().orElse("");
                var first = recordFields.isEmpty() ? null : recordFields.getFirst();
                var label = !identity.isBlank() ? identity : recordLabel(region, first, index + 1);
                result.add(new WorkbookRecord(recordId, region.regionId(), label, index + 1,
                        first == null ? null : first.sheetId(), first == null ? null : first.sheetName(),
                        first == null ? null : first.address(),
                        recordFields.stream().anyMatch(FieldValue::excluded)));
            }
        }
        return List.copyOf(result);
    }

    private String recordGroupId(String recordId, String componentId, JsonNode dimensions) {
        if (dimensions != null && dimensions.isObject()) {
            var row = dimensions.path("row").asText("").trim();
            var column = dimensions.path("column").asText("").trim();
            if (!row.isBlank() || !column.isBlank()) return componentId + ":matrix:" + row + ":" + column;
        }
        return recordId;
    }

    private String recordLabel(WorkbookRegion region, FieldValue field, int sequence) {
        if (structureTypeLabel(region.structureType()).equals("表单信息") && region.recordCount() <= 1) {
            return "表单信息";
        }
        if (field != null && field.dimensions() != null && field.dimensions().isObject()) {
            var row = field.dimensions().path("row").asText("").trim();
            var column = field.dimensions().path("column").asText("").trim();
            if (!row.isBlank() && !column.isBlank()) return row + " / " + column;
            if (!row.isBlank()) return row;
            if (!column.isBlank()) return column;
        }
        if (field != null && "COLUMN".equalsIgnoreCase(region.recordAxis())) {
            var recordKey = field.recordKey() == null ? "" : field.recordKey();
            if (!recordKey.matches(".*:column:[0-9]+$") && !recordKey.isBlank()
                    && !recordKey.equals(field.recordId())) return recordKey;
        }
        if (field != null && "COLUMN".equalsIgnoreCase(region.recordAxis()) && field.address() != null) {
            var match = java.util.regex.Pattern.compile("^([A-Za-z]+)").matcher(field.address());
            if (match.find()) return "记录列 " + match.group(1).toUpperCase(Locale.ROOT);
        }
        return "第 " + sequence + " 条记录";
    }

    private record Cell(int row, int column) {}

    public record WorkbookSheet(String sheetId, String sheetName, int sheetOrder,
                                boolean selected, String confirmationStatus) {}

    public record WorkbookFieldGroup(String groupId, String name, long fieldCount) {}

    public record WorkbookRegion(String regionId, String name, String structureType,
                                 String sheetId, String sheetName, String range, String recordAxis,
                                 long fieldCount, long recordCount, List<WorkbookFieldGroup> fieldGroups) {}

    public record WorkbookRecord(String recordId, String regionId, String label, int sequence,
                                 String sheetId, String sheetName, String address, boolean excluded) {}

    public record FieldDefinitionView(String componentId, String bindingId, String parentBindingId,
                                      String fieldCode, String displayName, String description,
                                      String labelPath, String mappingKind, String repeatAxis,
                                      String valueType, String unit, boolean required, boolean identity,
                                      String groupPath, String sheetId, String sheetName, String sourceRange) {}

    public record FieldValue(String recordId, String fieldCode, String fieldName, String labelPath,
                             String bindingId, String valuePath, String valueSource, String valueStatus,
                             String valueType, String unit, boolean required, boolean identity,
                             boolean trainingEligible, boolean ragEligible, String sheetId, String sheetName,
                             Integer rowNumber, String address, JsonNode rawValue, JsonNode normalizedValue,
                             JsonNode correctedValue, JsonNode effectiveValue, boolean editable,
                             boolean excluded, String exclusionReason, String componentId, String mappingKind,
                             String repeatAxis, String parentBindingId, String groupPath, String recordKey,
                             JsonNode dimensions, String recordGroupId) {}

    public record WorkbookContext(String fileName, String contentType, String sourceFileHash,
                                  String format, JsonNode snapshot, List<WorkbookSheet> sheets,
                                  String selectedSheetId, boolean editable, List<WorkbookRegion> regions,
                                  List<FieldDefinitionView> fieldDefinitions,
                                  List<WorkbookRecord> records, List<FieldValue> fields) {}
}
