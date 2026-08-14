package com.jsd.aird.data.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataImportService {

    private final DataRepository repository;
    private final FileObjectRepository files;
    private final TemplateDataImportFacade templates;
    private final ObjectMapper objectMapper;
    private final AuditLogFacade auditLog;
    private final OpsAsyncFacade opsAsync;
    private final DataCategoryService categories;
    private final StructuredDataExtractor structuredExtractor;
    private final ImportCompatibilityEvaluator compatibilityEvaluator;

    @Autowired
    public DataImportService(
            DataRepository repository,
            FileObjectRepository files,
            TemplateDataImportFacade templates,
            ObjectMapper objectMapper,
            AuditLogFacade auditLog,
            OpsAsyncFacade opsAsync,
            DataCategoryService categories
    ) {
        this.repository = repository;
        this.files = files;
        this.templates = templates;
        this.objectMapper = objectMapper;
        this.auditLog = auditLog;
        this.opsAsync = opsAsync;
        this.categories = categories;
        this.structuredExtractor = new StructuredDataExtractor(objectMapper);
        this.compatibilityEvaluator = new ImportCompatibilityEvaluator(objectMapper);
    }

    public DataImportService(
            DataRepository repository,
            FileObjectRepository files,
            TemplateDataImportFacade templates,
            ObjectMapper objectMapper,
            AuditLogFacade auditLog,
            OpsAsyncFacade opsAsync
    ) {
        this(repository, files, templates, objectMapper, auditLog, opsAsync, null);
    }

    @Transactional
    public DataRepository.Job create(CreateCommand command) {
        var actor = ActorContext.required();
        var template = templates.getPublished(actor.organizationId(), command.templateVersionId());
        var file = files.find(actor.organizationId(), command.sourceFileId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "数据源文件不存在"));
        var format = sourceFormat(file.originalName());
        var duplicate = repository.findCompletedDuplicate(actor.organizationId(), file.sha256(), command.templateVersionId());
        if (duplicate.isPresent() && !command.duplicateOverride()) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                    "该文件已按此模板完成导入，历史任务：" + duplicate.get().id());
        }
        files.activate(command.sourceFileId());
        var categoryId = command.categoryId();
        if (categoryId != null && categories != null) {
            var requestedCategoryId = categoryId;
            var category = categories.list().stream().filter(item -> item.id().equals(requestedCategoryId)).findFirst()
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据分类不存在"));
        }
        var id = UUID.randomUUID();
        repository.insertJob(new DataRepository.NewJob(
                id, actor.organizationId(), command.sourceFileId(), file.sha256(), file.originalName(), format,
                command.templateVersionId(), categoryId, command.duplicateOverride(), actor.userId(),
                template.importContractVersion() > 0 ? template.importContractVersion() : null,
                template.contractHash()));
        repository.enqueueParse(UUID.randomUUID(), actor.organizationId(), id);
        return get(id);
    }

    public DataRepository.Job get(UUID importJobId) {
        return repository.findJob(ActorContext.required().organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
    }

    public PageResponse<DataRepository.Job> listJobs(UUID templateVersionId,
                                                      String status, String keyword, int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        return repository.listJobs(actor.organizationId(), templateVersionId, status,
                keyword, safePage, safeSize);
    }

    public List<TemplateDataImportFacade.DataTemplateOption> listTemplates() {
        return templates.listPublished(ActorContext.required().organizationId());
    }

    public PageResponse<DataRepository.SourceFile> sourceFiles(UUID categoryId, String status, String keyword,
                                                               int page, int size) {
        var actor = ActorContext.required();
        return repository.listSourceFiles(actor.organizationId(), categoryId, status, keyword,
                Math.max(1, page), Math.min(100, Math.max(1, size)));
    }

    @Transactional
    public void assignSourceCategory(UUID importJobId, UUID categoryId) {
        var actor = ActorContext.required();
        if (categories != null) categories.list().stream().filter(item -> item.id().equals(categoryId)).findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据分类不存在"));
        if (repository.assignSourceCategory(actor.organizationId(), importJobId, categoryId) == 0) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "来源文件不存在");
        }
    }

    public TemplateDataImportFacade.FieldRequest requestField(UUID importJobId, FieldRequestCommand command) {
        var actor = ActorContext.required();
        var job = requireJob(actor.organizationId(), importJobId);
        return templates.requestField(actor.organizationId(), job.templateVersionId(), new TemplateDataImportFacade.FieldRequestCommand(
                command.fieldId(), command.displayName(), command.valueType(), command.uiType(), command.groupCode(), command.description()));
    }

    public void parse(UUID importJobId) {
        var actor = ActorContext.required();
        parseInternal(actor.organizationId(), importJobId);
    }

    public void parseInternal(UUID organizationId, UUID importJobId) {
        parseInternal(organizationId, importJobId, false);
    }

    @Transactional
    public void reExtract(UUID importJobId) {
        var actor = ActorContext.required();
        requireJob(actor.organizationId(), importJobId);
        parseInternal(actor.organizationId(), importJobId, true);
    }

    private void parseInternal(UUID organizationId, UUID importJobId, boolean force) {
        var job = repository.findJob(organizationId, importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        if (!force && List.of("WAITING_MAPPING", "VALIDATING", "WAITING_CONFIRM", "COMPLETED").contains(job.status())) return;
        repository.updateJobStatus(organizationId, importJobId, "PARSING", 10, "PARSING", null);
        try {
            var parsed = templates.parse(organizationId, job.templateVersionId(), job.sourceFileId());
            var definition = templates.getVersion(organizationId, job.templateVersionId());
            var originalBindings = templates.getBindings(organizationId, job.templateVersionId());
            var overrides = repository.listComponentOverrides(organizationId, importJobId);
            var bindings = applyComponentOverrides(originalBindings, overrides, definition.importContract());
            var compatibility = applyCompatibilityOverrides(
                    compatibilityEvaluator.evaluate(definition.importContract(), parsed.sheets()), overrides);
            var compatibilityReport = objectMapper.createObjectNode()
                    .put("status", compatibility.status())
                    .set("componentMatches", compatibility.componentMatches().deepCopy());
            repository.saveCompatibilityReport(organizationId, importJobId,
                    compatibility.status(), compatibilityReport);
            var configured = repository.listSheets(organizationId, importJobId).stream()
                    .collect(java.util.stream.Collectors.toMap(DataRepository.Sheet::sheetId, item -> item, (left, right) -> left));
            if (force) repository.clearParsed(organizationId, importJobId);
            var sheets = new ArrayList<DataRepository.Sheet>();
            var mappings = new ArrayList<DataRepository.Mapping>();
            var rows = new ArrayList<DataRepository.Row>();
            for (var parsedSheet : parsed.sheets()) {
                var previous = configured.get(parsedSheet.sheetId());
                var selected = previous == null || previous.selected();
                var noHeader = previous != null && previous.headerRows().isEmpty();
                var headerRows = previous == null
                        ? List.of(parsedSheet.suggestedHeaderRow())
                        : noHeader ? List.<Integer>of() : previous.headerRows();
                var dataStart = previous == null || previous.dataStartRow() == null
                        ? (noHeader ? parsedSheet.firstRow() : parsedSheet.suggestedDataStartRow()) : previous.dataStartRow();
                var dataEnd = previous == null || previous.dataEndRow() == null
                        ? parsedSheet.lastRow() : previous.dataEndRow();
                dataStart = Math.max(1, Math.min(dataStart, parsedSheet.lastRow()));
                dataEnd = Math.max(dataStart, Math.min(dataEnd, parsedSheet.lastRow()));
                ObjectNode structure = objectMapper.createObjectNode();
                structure.put("sheetId", parsedSheet.sheetId())
                        .put("sheetName", parsedSheet.sheetName())
                        .put("structureFingerprint", parsedSheet.structureFingerprint() == null ? "" : parsedSheet.structureFingerprint());
                structure.set("rows", objectMapper.valueToTree(parsedSheet.rows()));
                if (parsedSheet.layoutIr() != null) structure.set("layoutIr", parsedSheet.layoutIr().deepCopy());
                structure.set("componentMatches", compatibility.componentMatches().deepCopy());
                sheets.add(new DataRepository.Sheet(
                        UUID.randomUUID(), parsedSheet.sheetId(), parsedSheet.sheetName(), parsedSheet.sheetOrder(), selected,
                        headerRows, dataStart, dataEnd, structure, selected ? "PENDING" : "IGNORED"));
                if (!selected) continue;

                // Structured template bindings are executable import instructions. They
                // must run before the generic header mapper, otherwise a horizontal table
                // or matrix would be mistaken for a scalar row and lose its dimensions.
                var structured = structuredExtractor.extract(parsedSheet, definition.fields(), bindings, dataStart, dataEnd);
                if (structured.isPresent()) {
                    mappings.addAll(structured.get().mappings());
                    rows.addAll(structured.get().rows());
                    continue;
                }
                var header = noHeader ? List.<String>of() : headerAt(parsedSheet.rows(), headerRows);
                var resolvedDataStart = dataStart;
                var profile = repository.findMappingProfile(organizationId, job.templateVersionId(),
                        sourceFingerprint(parsedSheet.sheetId(), header));
                var profileByColumn = new HashMap<String, DataRepository.Mapping>();
                if (profile.isPresent() && profile.get().isArray()) {
                    for (var profileItem : profile.get()) {
                        try {
                            var item = objectMapper.treeToValue(profileItem, DataRepository.Mapping.class);
                            if (item != null && item.sourceColumn() != null) profileByColumn.put(item.sourceColumn(), item);
                        } catch (Exception ignored) {
                            // A stale profile is treated as a suggestion miss and never blocks parsing.
                        }
                    }
                }
                var maxColumns = Math.max(parsedSheet.lastColumn(), header.size());
                var semanticHeaderMatches = header.stream()
                        .filter(value -> matchField(definition.fields(), value) != null)
                        .count();
                var allowPhysicalMapping = semanticHeaderMatches > 0;
                for (int column = 0; column < maxColumns; column++) {
                    var sourceColumn = columnName(column + 1);
                    var sourceHeader = column < header.size() ? header.get(column) : "";
                    var field = matchField(definition.fields(), sourceHeader);
                    var profileMapping = profileByColumn.get(sourceColumn);
                    if (field == null && profileMapping != null && profileMapping.fieldCode() != null) {
                        field = definition.fields().stream().filter(item -> item.fieldCode().equals(profileMapping.fieldCode()))
                                .findFirst().orElse(null);
                    }
                    var binding = bindings.stream()
                            .filter(item -> locatorMatches(item, parsedSheet.sheetId(), sourceColumn,
                                    List.copyOf(headerRows), resolvedDataStart))
                            .findFirst().orElse(null);
                    if (field == null && binding != null && allowPhysicalMapping) {
                        var matchedBinding = binding;
                        field = definition.fields().stream()
                                .filter(item -> item.fieldCode().equals(matchedBinding.fieldCode())).findFirst().orElse(null);
                    }
                    if (binding == null && field != null) {
                        var matchedField = field;
                        binding = bindings.stream()
                                .filter(item -> item.fieldCode().equals(matchedField.fieldCode())).findFirst().orElse(null);
                    }
                    // A physical locator is only authoritative when it agrees with the
                    // semantic header match. Uploads are allowed to reorder columns; a
                    // locator for another template field must not silently remap a header
                    // to the wrong business field.
                    if (field != null && binding != null
                            && !field.fieldCode().equals(binding.fieldCode())) {
                        var matchedField = field;
                        binding = bindings.stream()
                                .filter(item -> item.fieldCode().equals(matchedField.fieldCode()))
                                .findFirst().orElse(null);
                    }
                    var detail = objectMapper.createObjectNode();
                    var profileIgnore = profileMapping != null
                            && "IGNORE".equalsIgnoreCase(profileMapping.action());
                    if (field != null) {
                        detail.put("identity", field.identity()).put("required", field.required())
                                .put("dataPath", field.dataPath())
                                .put("mappingSource", profileMapping != null ? "PROFILE"
                                        : binding != null && locatorMatches(binding, parsedSheet.sheetId(), sourceColumn, headerRows, resolvedDataStart)
                                        ? "PHYSICAL" : "HEADER")
                                .put("confidence", profileMapping != null ? 0.98
                                        : binding != null && locatorMatches(binding, parsedSheet.sheetId(), sourceColumn, headerRows, resolvedDataStart)
                                        ? 0.99 : 0.92);
                        if (binding != null) {
                            detail.put("bindingId", binding.bindingId())
                                    .put("labelPath", binding.labelPath() == null ? "" : binding.labelPath())
                                    .put("mappingKind", binding.mappingKind())
                                    .put("parentBindingId", binding.parentBindingId())
                                    .put("repeatAxis", binding.repeatAxis())
                                    .put("recordHeight", binding.recordHeight())
                                    .put("recordWidth", binding.recordWidth())
                                    .put("recordStride", binding.recordStride())
                                    .put("trainingEligible", binding.trainingEligible())
                                    .put("trainingRole", binding.trainingRole())
                                    .put("ragEligible", binding.ragEligible())
                                    .put("valueSource", binding.valueSource());
                            detail.set("locator", binding.locator());
                            detail.set("terminationRule", binding.terminationRule());
                        }
                    } else if (profileIgnore) {
                        detail.put("mappingSource", "PROFILE").put("confidence", 0.98);
                    }
                    var action = field == null ? (profileIgnore ? "IGNORE" : "PENDING") : "MAP";
                    var status = field == null ? (profileIgnore ? "IGNORED" : "PENDING") : "MATCHED";
                    mappings.add(new DataRepository.Mapping(
                            UUID.randomUUID(), parsedSheet.sheetId(), sourceColumn, sourceHeader,
                            field == null ? null : field.fieldCode(), field == null ? null : field.displayName(),
                            action, field == null ? null : field.dataType(),
                            null, field == null ? null : field.defaultUnit(), detail,
                            status));
                }
                for (int rowIndex = dataStart; rowIndex <= dataEnd; rowIndex++) {
                    var values = rowAt(parsedSheet.rows(), rowIndex);
                    if (values.stream().allMatch(String::isBlank)) continue;
                    var raw = objectMapper.createObjectNode();
                    for (int column = 0; column < values.size(); column++) raw.put(columnName(column + 1), values.get(column));
                    var metadata = sourceMetadata(parsedSheet, rowIndex, raw);
                    rows.add(new DataRepository.Row(UUID.randomUUID(), parsedSheet.sheetId(), rowIndex, raw, raw.deepCopy(),
                            raw.deepCopy(), "STAGED", metadata));
                }
            }
            repository.saveParsed(importJobId, parsed.parserVersion(), sheets, mappings, rows, importJobId);
        } catch (RuntimeException exception) {
            repository.updateJobStatus(organizationId, importJobId, "FAILED", 0, "FAILED", safeMessage(exception));
            throw exception;
        }
    }

    @Transactional
    public void confirmSheets(UUID importJobId, List<DataRepository.SheetUpdate> updates) {
        var actor = ActorContext.required();
        requireJob(actor.organizationId(), importJobId);
        for (var update : updates) repository.updateSheet(update);
        reExtract(importJobId);
    }

    @Transactional
    public void saveMappings(UUID importJobId, List<MappingCommand> commands) {
        var actor = ActorContext.required();
        var job = requireJob(actor.organizationId(), importJobId);
        var mappings = commands.stream().map(item -> new DataRepository.Mapping(
                null, item.sheetId(), item.sourceColumn(), item.sourceHeader(), item.fieldCode(), item.fieldName(),
                item.action(), item.valueType(), item.sourceUnit(), item.standardUnit(),
                item.detail() == null ? objectMapper.createObjectNode() : item.detail(), mappingStatus(item.action())
        )).toList();
        repository.replaceMappings(actor.organizationId(), importJobId, mappings);
        mappings.stream().collect(java.util.stream.Collectors.groupingBy(DataRepository.Mapping::sheetId))
                .forEach((sheetId, items) -> repository.saveMappingProfile(actor.organizationId(), job.templateVersionId(),
                        sourceFingerprint(sheetId, items.stream().sorted(java.util.Comparator.comparing(DataRepository.Mapping::sourceColumn))
                                .map(item -> item.sourceHeader() == null ? "" : item.sourceHeader()).toList()),
                        objectMapper.valueToTree(items), actor.userId()));
        validate(importJobId);
    }

    @Transactional
    public void validate(UUID importJobId) {
        var actor = ActorContext.required();
        validateInternal(actor.organizationId(), importJobId);
    }

    public void validateInternal(UUID organizationId, UUID importJobId) {
        var job = requireJob(organizationId, importJobId);
        var definition = templates.getVersion(organizationId, job.templateVersionId());
        var mappings = repository.listMappings(organizationId, importJobId);
        var rows = repository.listRows(organizationId, importJobId);
        var issues = new ArrayList<DataRepository.Issue>();
        var mappingByColumn = new HashMap<String, DataRepository.Mapping>();
        for (var mapping : mappings) mappingByColumn.put(mappingKey(mapping.sheetId(), mappingComponentId(mapping),
                mapping.sourceColumn()), mapping);
        var mappedSheetsByField = new HashMap<String, Set<String>>();
        for (var mapping : mappings) {
            if (!"MAP".equals(mapping.action()) || mapping.fieldCode() == null || mapping.fieldCode().isBlank()) continue;
            mappedSheetsByField.computeIfAbsent(mappingComponentId(mapping) + "|" + mapping.fieldCode(),
                    ignored -> new java.util.HashSet<>()).add(mapping.sheetId());
        }
        var normalizedRows = new ArrayList<DataRepository.Row>();
        for (var row : rows) {
            if (row.excluded()) {
                normalizedRows.add(row);
                continue;
            }
            var normalized = objectMapper.createObjectNode();
            var rowBlocked = false;
            var rowComponentId = row.sourceMetadata() == null ? ""
                    : row.sourceMetadata().path("componentId").asText("");
            var seenPaths = new HashMap<String, DataRepository.Mapping>();
            var entries = row.rawValues().fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                var mapping = mappingByColumn.get(mappingKey(row.sheetId(), rowComponentId, entry.getKey()));
                if (mapping == null || "IGNORE".equals(mapping.action())) continue;
                if (!"MAP".equals(mapping.action()) || mapping.fieldCode() == null || mapping.fieldCode().isBlank()) {
                    issues.add(issue(row, mapping, "BLOCKER", "UNMAPPED_FIELD", "字段尚未完成映射"));
                    rowBlocked = true;
                    continue;
                }
                var raw = entry.getValue().asText();
                var normalizedValue = normalize(raw, mapping, row, issues);
                var valuePath = mapping.detail().path("dataPath").asText("/" + mapping.fieldCode());
                var bindingId = mapping.detail().path("bindingId").asText(mapping.fieldCode());
                var value = objectMapper.createObjectNode().put("rawValue", raw)
                        .put("rawUnit", mapping.sourceUnit() == null ? "" : mapping.sourceUnit())
                        .put("normalizedValue", normalizedValue)
                        .put("normalizedUnit", mapping.standardUnit() == null ? "" : mapping.standardUnit())
                        .put("fieldCode", mapping.fieldCode())
                        .put("bindingId", bindingId)
                        .put("sourceColumn", mapping.sourceColumn())
                        .put("valuePath", valuePath)
                        .put("dataPath", valuePath)
                        .put("trainingEligible", mapping.detail().path("trainingEligible").asBoolean(true));
                var cell = row.sourceMetadata() == null ? null : row.sourceMetadata().path("cells").path(entry.getKey());
                if (cell != null && cell.isObject()) {
                    for (var key : List.of("bindingId", "valuePath", "labelPath", "valueSource",
                            "calculationSource", "calculationStatus", "formulaTrustStatus", "formulaExpression")) {
                        if (cell.has(key)) value.set(key, cell.path(key).deepCopy());
                    }
                }
                var valueSource = value.path("valueSource").asText("INPUT").toUpperCase(Locale.ROOT);
                if (Set.of("FORMULA", "DERIVED").contains(valueSource)) value.put("trainingEligible", false);
                if ("FORMULA".equals(valueSource)) {
                    var calculationStatus = value.path("calculationStatus").asText("FAILED");
                    var trustStatus = value.path("formulaTrustStatus").asText(
                            "VALID".equals(calculationStatus) ? "TRUSTED_RECALCULATED"
                                    : "STALE_POSSIBLE".equals(calculationStatus) ? "UNVERIFIED_CACHE" : "MISSING_RESULT");
                    value.put("formulaTrustStatus", trustStatus);
                    if (raw.trim().startsWith("=") || normalizedValue.trim().startsWith("=")) {
                        issues.add(issue(row, mapping, "BLOCKER", "FORMULA_EXPRESSION_AS_VALUE",
                                "公式表达式不能作为正式业务值，请重新计算并保存文件"));
                        rowBlocked = true;
                    }
                    if (!"VALID".equals(calculationStatus)) {
                        issues.add(issue(row, mapping, "BLOCKER", "FORMULA_RESULT_UNTRUSTED",
                                "公式结果缺失或缓存可信度不足，请在 Excel 中重新计算并保存"));
                        rowBlocked = true;
                    }
                }
                var pathKey = bindingId + "|" + valuePath;
                if (seenPaths.putIfAbsent(pathKey, mapping) != null) {
                    issues.add(issue(row, mapping, "BLOCKER", "DUPLICATE_FIELD_MAPPING", "同一字段被多个源列重复映射"));
                    rowBlocked = true;
                } else {
                    var storageKey = mapping.fieldCode();
                    if (normalized.has(storageKey)) storageKey = mapping.fieldCode() + "@" + shortKey(bindingId + "|" + valuePath);
                    normalized.set(storageKey, value);
                }
            }
            var corrected = correctedValues(normalized, row.correctedValues());
            for (var field : definition.fields()) {
                if (!field.required()) continue;
                var assignedComponents = mappings.stream()
                        .filter(item -> field.fieldCode().equals(item.fieldCode()))
                        .map(this::mappingComponentId).filter(value -> !value.isBlank()).distinct().toList();
                if (!assignedComponents.isEmpty() && !assignedComponents.contains(rowComponentId)) continue;
                var mappedSheets = mappedSheetsByField.getOrDefault(rowComponentId + "|" + field.fieldCode(), Set.of());
                if (mappedSheets.isEmpty()) {
                    issues.add(issue(row, null, "BLOCKER", "REQUIRED_MAPPING_MISSING", "模板必填字段尚未映射：" + field.displayName()));
                    rowBlocked = true;
                } else if (mappedSheets.contains(row.sheetId())
                        && !hasEffectiveField(corrected, field.fieldCode())) {
                    var mapping = mappings.stream().filter(item -> row.sheetId().equals(item.sheetId())
                            && rowComponentId.equals(mappingComponentId(item))
                            && field.fieldCode().equals(item.fieldCode())).findFirst().orElse(null);
                    issues.add(issue(row, mapping, "BLOCKER", "REQUIRED_MISSING", "必填字段为空"));
                    rowBlocked = true;
                }
            }
            normalizedRows.add(new DataRepository.Row(row.id(), row.sheetId(), row.rowNumber(), row.rawValues(), normalized,
                    corrected, rowBlocked ? "BLOCKED" : "VALID",
                    row.sourceMetadata(), false, null));
        }
        var blocker = issues.stream().anyMatch(item -> "BLOCKER".equals(item.severity()));
        repository.replaceValidation(organizationId, importJobId, normalizedRows, issues,
                blocker ? "WAITING_MAPPING" : "WAITING_CONFIRM");
    }

    private String mappingKey(String sheetId, String componentId, String sourceColumn) {
        return sheetId + "|" + (componentId == null ? "" : componentId) + "|" + sourceColumn;
    }

    private String mappingComponentId(DataRepository.Mapping mapping) {
        return mapping.detail() == null ? "" : mapping.detail().path("componentId").asText("");
    }

    public Preview preview(UUID importJobId) {
        var actor = ActorContext.required();
        var job = requireJob(actor.organizationId(), importJobId);
        var definition = templates.getVersion(actor.organizationId(), job.templateVersionId());
        return new Preview(job, repository.listSheets(actor.organizationId(), importJobId),
                repository.listMappings(actor.organizationId(), importJobId), repository.listRows(actor.organizationId(), importJobId),
                repository.listIssues(actor.organizationId(), importJobId),
                new TemplateContract(definition.importContractVersion(), definition.layoutStructureVersion(),
                        definition.contractHash(), definition.importContract(), definition.fields(),
                        templates.getBindings(actor.organizationId(), job.templateVersionId())),
                new ProjectionSummary(null, "NOT_COMMITTED", 0, 0, 0),
                repository.findCompatibilityReport(actor.organizationId(), importJobId),
                repository.listComponentOverrides(actor.organizationId(), importJobId));
    }

    @Transactional
    public void reanchorComponent(UUID importJobId, String componentId, String sheetId,
                                  String sourceRange, String reason) {
        var actor = ActorContext.required();
        var job = requireJob(actor.organizationId(), importJobId);
        if ("COMPLETED".equals(job.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "已完成的导入任务不能修改组件位置");
        }
        if (componentId == null || componentId.isBlank()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "组件不能为空");
        if (reason == null || reason.isBlank()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "重新定位时必须填写原因");
        var parsedRange = parseRange(sourceRange);
        if (parsedRange == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "请输入有效单元格范围，例如 E4:N100");
        var sheet = repository.listSheets(actor.organizationId(), importJobId).stream()
                .filter(item -> item.sheetId().equals(sheetId)).findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "工作表不存在"));
        var layout = sheet.structure().path("layoutIr");
        var rowGrid = sheet.structure().path("rows");
        var lastRow = Math.max(layout.path("lastRow").asInt(0), rowGrid.isArray() ? rowGrid.size() : 0);
        if (lastRow == 0) lastRow = sheet.dataEndRow() == null ? 0 : sheet.dataEndRow();
        var lastColumn = layout.path("lastColumn").asInt(0);
        if (lastColumn == 0 && rowGrid.isArray()) {
            for (var row : rowGrid) lastColumn = Math.max(lastColumn, row.isArray() ? row.size() : 0);
        }
        if (parsedRange.endRow() > lastRow || parsedRange.endColumn() > lastColumn) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "重新定位范围超出工作表有效区域");
        }
        var contract = templates.getVersion(actor.organizationId(), job.templateVersionId()).importContract();
        var contractComponent = contract == null ? null
                : java.util.stream.StreamSupport.stream(contract.path("components").spliterator(), false)
                .filter(item -> componentId.equals(item.path("componentId").asText())).findFirst().orElse(null);
        if (contractComponent == null) throw new ApiException(ApiErrorCode.NOT_FOUND, "导入契约中不存在该组件");
        var originalRange = parseRange(contractComponent.path("range").asText(""));
        if (originalRange == null || originalRange.endRow() - originalRange.startRow() != parsedRange.endRow() - parsedRange.startRow()
                || originalRange.endColumn() - originalRange.startColumn() != parsedRange.endColumn() - parsedRange.startColumn()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "重新定位必须保持组件原有行列尺寸；结构已改变时请创建模板新修订");
        }
        repository.saveComponentOverride(actor.organizationId(), importJobId,
                new DataRepository.ComponentOverride(componentId.trim(), sheetId, sourceRange.toUpperCase(Locale.ROOT),
                        reason.trim(), actor.userId(), null));
        parseInternal(actor.organizationId(), importJobId, true);
    }

    public List<DataRepository.Issue> issues(UUID importJobId) {
        var actor = ActorContext.required();
        requireJob(actor.organizationId(), importJobId);
        return repository.listIssues(actor.organizationId(), importJobId);
    }

    @Transactional
    public void resolveIssue(UUID importJobId, UUID issueId, String status, String reason) {
        var actor = ActorContext.required();
        requireJob(actor.organizationId(), importJobId);
        // Blockers cannot be dismissed by changing a status flag. They are
        // resolved only after a correction/exclusion triggers revalidation.
        if (!"IGNORED".equals(status)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "请先修正数据并重新校验，不能直接将异常标记为已处理");
        }
        var issue = repository.listIssues(actor.organizationId(), importJobId).stream()
                .filter(item -> item.id().equals(issueId)).findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入异常不存在"));
        if ("BLOCKER".equals(issue.severity())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "阻断异常不能忽略，必须修正数据或显式排除整条记录");
        }
        if (reason == null || reason.isBlank()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "忽略警告时必须填写原因");
        repository.resolveIssue(actor.organizationId(), issueId, actor.userId(), status, reason.trim());
    }

    @Transactional
    public void correctValue(UUID importJobId, UUID recordId, String bindingId, String valuePath,
                             JsonNode correctedValue, String reason) {
        var actor = ActorContext.required();
        requireJob(actor.organizationId(), importJobId);
        if (reason == null || reason.isBlank()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "修正数据时必须填写原因");
        var row = repository.listRows(actor.organizationId(), importJobId).stream()
                .filter(item -> item.id().equals(recordId)).findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "待修正记录不存在"));
        var target = findNormalizedValue(row.normalizedValues(), bindingId, valuePath)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "待修正字段不存在"));
        var valueSource = target.path("valueSource").asText("INPUT").toUpperCase(Locale.ROOT);
        if (Set.of("FORMULA", "DERIVED", "STATIC").contains(valueSource)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "公式、派生值和静态说明不能直接修改");
        }
        repository.correctValue(actor.organizationId(), importJobId, recordId, bindingId, valuePath,
                correctedValue, actor.userId(), reason);
        validateInternal(actor.organizationId(), importJobId);
    }

    private Optional<JsonNode> findNormalizedValue(JsonNode normalizedValues, String bindingId, String valuePath) {
        if (normalizedValues == null || !normalizedValues.isObject()) return Optional.empty();
        var entries = normalizedValues.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            var value = entry.getValue();
            var fieldCode = value.path("fieldCode").asText(entry.getKey());
            var effectiveBindingId = value.path("bindingId").asText(fieldCode);
            var effectiveValuePath = value.path("valuePath")
                    .asText(value.path("dataPath").asText("/" + fieldCode));
            if (bindingId.equals(effectiveBindingId) && valuePath.equals(effectiveValuePath)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    @Transactional
    public void excludeRow(UUID importJobId, UUID recordId, boolean excluded, String reason) {
        var actor = ActorContext.required();
        requireJob(actor.organizationId(), importJobId);
        if (excluded && (reason == null || reason.isBlank())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "排除记录时必须填写原因");
        }
        repository.excludeRow(actor.organizationId(), importJobId, recordId, excluded, actor.userId(), reason);
        validateInternal(actor.organizationId(), importJobId);
    }

    @Transactional
    public void commit(UUID importJobId) {
        var actor = ActorContext.required();
        var job = repository.findJobForUpdate(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        if ("COMPLETED".equals(job.status())) return;
        if ("INCOMPATIBLE".equals(job.compatibilityStatus())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "上传文件与模板存在硬结构冲突，不能提交正式数据");
        }
        if ("REVIEW_REQUIRED".equals(job.compatibilityStatus())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "仍有组件结构差异待复核，请先重新定位或修复上传文件");
        }
        if (!Set.of("WAITING_CONFIRM", "COMMITTING").contains(job.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "导入任务当前不可提交：" + job.status());
        }
        var rows = repository.listRows(actor.organizationId(), importJobId);
        var excludedIds = rows.stream().filter(DataRepository.Row::excluded)
                .map(DataRepository.Row::id).collect(java.util.stream.Collectors.toSet());
        var blockers = repository.listIssues(actor.organizationId(), importJobId).stream()
                .anyMatch(item -> "BLOCKER".equals(item.severity())
                        && !excludedIds.contains(issueRecordId(item)));
        if (blockers) throw new ApiException(ApiErrorCode.BAD_REQUEST, "仍有阻断异常未处理");
        repository.updateJobStatus(actor.organizationId(), importJobId, "COMMITTING", 90, "COMMITTING", null);
        var mappings = repository.listMappings(actor.organizationId(), importJobId);
        var sheetNames = repository.listSheets(actor.organizationId(), importJobId).stream()
                .collect(java.util.stream.Collectors.toMap(DataRepository.Sheet::sheetId, DataRepository.Sheet::sheetName));
        if (rows.stream().anyMatch(row -> !row.excluded() && "BLOCKED".equals(row.status()))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "仍有阻断记录，必须修正或显式排除后才能提交");
        }
        var committed = rows.stream().filter(row -> !row.excluded()).map(row -> {
            var effectiveValues = correctedValues(row.normalizedValues(), row.correctedValues());
            var identity = firstValue(effectiveValues, mappings, row, true);
            var anchors = mappings.stream().filter(item -> "MAP".equals(item.action()) && item.fieldCode() != null)
                    .map(item -> anchor(item, row, sheetNames.getOrDefault(item.sheetId(), item.sheetId())))
                    .filter(java.util.Objects::nonNull).toList();
            var metadata = row.sourceMetadata() == null ? objectMapper.createObjectNode() : row.sourceMetadata();
            var structuredKey = metadata.path("recordKey").asText("");
            // Structured projections already computed the logical record key. It must
            // win over a form-level identity field so each detail row remains its own
            // source record after common form fields are merged into it.
            var recordKey = structuredKey.isBlank()
                    ? (identity == null || identity.isBlank()
                    ? importScopedKey(job.id(), row) : identity)
                    : metadata.path("identitySynthetic").asBoolean(false)
                    ? importScopedKey(job.id(), row) : structuredKey;
            return new DataRepository.CommittedRow(row.sheetId(), sheetNames.getOrDefault(row.sheetId(), row.sheetId()), row.rowNumber(), row.rawValues(), row.normalizedValues(),
                    row.correctedValues(), recordKey, anchors);
        }).toList();
        var result = repository.commit(actor.organizationId(), importJobId, actor.userId(), committed);
        appendCommitAuditAndOutbox(actor.organizationId(), actor.userId(), job, result);
    }

    private void appendCommitAuditAndOutbox(UUID organizationId, UUID actorId, DataRepository.Job job,
                                            DataRepository.CommitResult result) {
        var records = objectMapper.createArrayNode();
        for (var item : result.records()) {
            records.add(objectMapper.createObjectNode()
                    .put("recordId", item.recordId().toString())
                    .put("recordKey", item.recordKey())
                    .put("dataHash", item.dataHash()));
        }
        var detail = objectMapper.createObjectNode()
                .put("templateVersionId", job.templateVersionId().toString())
                .put("sourceFileId", job.sourceFileId().toString())
                .put("sourceFileName", job.sourceFileName())
                .put("sourceSha256", job.sourceSha256())
                .put("recordCount", result.records().size())
                .put("rowCount", result.rowCount())
                .set("records", records);
        auditLog.append(organizationId, actorId, "DATA_IMPORT_COMMITTED", "DATA_IMPORT_JOB", job.id(), detail);

        var eventPayload = objectMapper.createObjectNode()
                .put("organizationId", organizationId.toString())
                .put("actorId", actorId.toString())
                .put("templateVersionId", job.templateVersionId().toString())
                .put("importJobId", job.id().toString())
                .put("sourceSha256", job.sourceSha256())
                .put("recordCount", result.records().size())
                .put("rowCount", result.rowCount())
                .set("records", records.deepCopy());
        opsAsync.appendOutbox("DATA_IMPORT_JOB", job.id(), "DATA_RECORDS_COMMITTED", eventPayload);
        var recordSetKey = recordSetKey(result);
        opsAsync.enqueue(organizationId, "DATA_PROJECT_IMPORT", eventPayload,
                "data-project:" + job.id() + ":" + recordSetKey, 30);
    }

    private String recordSetKey(DataRepository.CommitResult result) {
        var records = result.records().stream()
                .map(DataRepository.CommittedRecord::recordId)
                .map(UUID::toString)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(records.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (var item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    private String importScopedKey(UUID importJobId, DataRepository.Row row) {
        return "IMPORT:" + importJobId + ":" + row.sheetId() + ":" + row.rowNumber() + ":" + row.id();
    }

    private DataRepository.Job requireJob(UUID organizationId, UUID id) {
        return repository.findJob(organizationId, id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
    }


    private DataRepository.Issue issue(DataRepository.Row row, DataRepository.Mapping mapping, String severity,
                                       String type, String message) {
        var detail = objectMapper.createObjectNode().put("recordId", row.id().toString());
        return new DataRepository.Issue(null, row.sheetId(), mapping == null ? null : mapping.fieldCode(), severity, type,
                row.rowNumber(), mapping == null ? null : mapping.sourceColumn(), mapping == null ? null : mapping.sourceColumn() + row.rowNumber(),
                message, detail, "OPEN");
    }

    private ObjectNode sourceMetadata(TemplateDataImportFacade.ParsedSheet sheet, int rowNumber, JsonNode raw) {
        var metadata = objectMapper.createObjectNode().put("shape", "GENERIC_TABLE");
        var cells = metadata.putObject("cells");
        raw.fieldNames().forEachRemaining(column -> {
            var address = column + rowNumber;
            var cell = cells.putObject(column)
                    .put("sheetId", sheet.sheetId()).put("sheetName", sheet.sheetName())
                    .put("rowNumber", rowNumber).put("columnNumber", columnNumber(column))
                    .put("columnName", column).put("cellAddress", address);
            if (sheet.layoutIr() != null) for (var fact : sheet.layoutIr().path("cells")) {
                if (!address.equalsIgnoreCase(fact.path("address").asText(""))) continue;
                for (var key : List.of("valueSource", "formulaExpression", "cachedValue",
                        "calculationSource", "calculationStatus", "formulaTrustStatus")) {
                    if (fact.has(key)) cell.set(key, fact.path(key).deepCopy());
                }
                break;
            }
        });
        return metadata;
    }

    private UUID issueRecordId(DataRepository.Issue issue) {
        var value = issue.detail().path("recordId").asText("");
        try { return value.isBlank() ? null : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private JsonNode correctedValues(JsonNode normalized, JsonNode corrected) {
        var result = normalized == null || !normalized.isObject()
                ? objectMapper.createObjectNode() : (ObjectNode) normalized.deepCopy();
        if (corrected != null && corrected.isObject()) corrected.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) result.set(entry.getKey(), entry.getValue().deepCopy());
        });
        return result;
    }

    private String effectiveFieldValue(JsonNode wrapper) {
        if (wrapper == null || wrapper.isMissingNode() || wrapper.isNull()) return "";
        if (wrapper.has("correctedValue") && !wrapper.path("correctedValue").isNull()) return wrapper.path("correctedValue").asText("");
        return wrapper.path("normalizedValue").asText("");
    }

    private boolean hasEffectiveField(JsonNode values, String fieldCode) {
        if (values == null || !values.isObject()) return false;
        var fields = values.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            var wrapper = entry.getValue();
            if ((entry.getKey().equals(fieldCode) || fieldCode.equals(wrapper.path("fieldCode").asText("")))
                    && !effectiveFieldValue(wrapper).isBlank()) return true;
        }
        return false;
    }

    private String shortKey(String value) {
        return sha256(value).substring(0, 12);
    }

    private String normalize(String raw, DataRepository.Mapping mapping, DataRepository.Row row,
                             List<DataRepository.Issue> issues) {
        if (raw == null || raw.isBlank()) return "";
        var type = mapping.valueType() == null ? "TEXT" : mapping.valueType().toUpperCase(Locale.ROOT);
        BigDecimal numeric = null;
        if (type.contains("NUM") || type.contains("DECIMAL")) {
            try { numeric = new BigDecimal(raw.trim().replace(",", "")); }
            catch (NumberFormatException exception) {
                issues.add(issue(row, mapping, "BLOCKER", "TYPE_ERROR", "数值字段无法解析：" + raw));
            }
        }
        if (type.contains("DATE") || type.contains("TIME")) {
            try {
                var value = raw.trim();
                if (value.matches("\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}")) {
                    var parts = value.split("[./-]");
                    value = "%s-%02d-%02d".formatted(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                }
                java.time.LocalDate.parse(value);
                return value;
            } catch (java.time.DateTimeException | NumberFormatException exception) {
                issues.add(issue(row, mapping, "BLOCKER", "TYPE_ERROR", "日期字段无法解析：" + raw));
            }
        }
        if (type.contains("BOOL")) {
            var value = raw.trim().toLowerCase(Locale.ROOT);
            if (!Set.of("true", "false", "是", "否", "1", "0", "y", "n", "yes", "no").contains(value)) {
                issues.add(issue(row, mapping, "BLOCKER", "TYPE_ERROR", "布尔字段无法解析：" + raw));
            }
        }
        if (mapping.sourceUnit() != null && mapping.standardUnit() != null
                && !mapping.sourceUnit().isBlank() && !mapping.standardUnit().isBlank()
                && !mapping.sourceUnit().equalsIgnoreCase(mapping.standardUnit())) {
            var source = mapping.sourceUnit().toLowerCase(Locale.ROOT);
            var target = mapping.standardUnit().toLowerCase(Locale.ROOT);
            var factor = conversionFactor(source, target);
            if (factor == null || numeric == null) {
                issues.add(issue(row, mapping, "BLOCKER", "UNIT_INCOMPATIBLE", "单位维度不兼容或缺少数值转换"));
            } else numeric = numeric.multiply(factor);
        }
        return numeric == null ? raw.trim() : numeric.stripTrailingZeros().toPlainString();
    }

    private BigDecimal conversionFactor(String source, String target) {
        if (source.equals(target)) return BigDecimal.ONE;
        return switch (source + "->" + target) {
            case "cps->pa·s", "cp->pa·s" -> new BigDecimal("0.001");
            case "pa·s->cps", "pa·s->cp" -> new BigDecimal("1000");
            case "cps->cp", "cp->cps" -> BigDecimal.ONE;
            case "mg->g", "g->kg", "mm->m" -> new BigDecimal("0.001");
            case "g->mg", "kg->g", "m->mm" -> new BigDecimal("1000");
            case "kg->mg" -> new BigDecimal("1000000");
            case "mg->kg" -> new BigDecimal("0.000001");
            case "cm->m" -> new BigDecimal("0.01");
            case "m->cm" -> new BigDecimal("100");
            default -> null;
        };
    }

    private String firstValue(JsonNode normalized, List<DataRepository.Mapping> mappings,
                              DataRepository.Row row, boolean identity) {
        for (var mapping : mappings) {
            if (!"MAP".equals(mapping.action()) || mapping.fieldCode() == null) continue;
            if (identity && mapping.detail().path("identity").asBoolean(false)
                    || !identity && mapping.fieldName() != null && mapping.fieldName().contains("名称")) {
                var value = mappedValue(normalized, mapping);
                if (!value.isBlank()) return value;
            }
        }
        return null;
    }

    private DataRepository.Anchor anchor(DataRepository.Mapping mapping, DataRepository.Row row, String sheetName) {
        var metadata = row.sourceMetadata();
        if (metadata != null && metadata.path("cells").isObject()) {
            var cell = metadata.path("cells").path(mapping.sourceColumn());
            if (cell.isObject()) {
                var sourceSheetId = cell.path("sheetId").asText(mapping.sheetId());
                var sourceSheetName = cell.path("sheetName").asText(sheetName);
                var sourceRow = cell.path("rowNumber").asInt(row.rowNumber());
                var sourceColumn = cell.path("columnNumber").asInt(0);
                var sourceColumnName = cell.path("columnName").asText(mapping.sourceColumn());
                var address = cell.path("cellAddress").asText(sourceColumnName + sourceRow);
                return new DataRepository.Anchor(mapping.fieldCode(),
                        cell.path("bindingId").asText(mapping.detail().path("bindingId").asText(mapping.fieldCode())),
                        cell.path("valuePath").asText(mapping.detail().path("dataPath").asText("/" + mapping.fieldCode())),
                        cell.path("labelPath").asText(mapping.detail().path("labelPath").asText(mapping.fieldName())),
                        cell.path("valueSource").asText(mapping.detail().path("valueSource").asText("INPUT")),
                        row.status(), sourceSheetId, sourceSheetName, sourceRow,
                        sourceColumn, sourceColumnName, address, row.rawValues().path(mapping.sourceColumn()));
            }
            // A structured row has virtual source columns. Do not fabricate a physical
            // A1-style anchor when the template did not provide a cell locator.
            return null;
        }
        int column = columnNumber(mapping.sourceColumn());
        return new DataRepository.Anchor(mapping.fieldCode(),
                mapping.detail().path("bindingId").asText(mapping.fieldCode()),
                mapping.detail().path("dataPath").asText("/" + mapping.fieldCode()),
                mapping.detail().path("labelPath").asText(mapping.fieldName()),
                mapping.detail().path("valueSource").asText("INPUT"), row.status(),
                mapping.sheetId(), sheetName, row.rowNumber(), column,
                mapping.sourceColumn(), mapping.sourceColumn() + row.rowNumber(), row.rawValues().path(mapping.sourceColumn()));
    }

    private TemplateDataImportFacade.FieldDefinition matchField(List<TemplateDataImportFacade.FieldDefinition> fields, String header) {
        var key = normalizeHeader(header);
        if (key.isBlank()) return null;
        return fields.stream().filter(field -> normalizeHeader(field.fieldCode()).equals(key)
                || normalizeHeader(field.displayName()).equals(key)
                || field.aliases().stream().map(this::normalizeHeader).anyMatch(key::equals)).findFirst().orElse(null);
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-（）()：:]+", "");
    }

    private String mappedValue(JsonNode normalized, DataRepository.Mapping mapping) {
        if (normalized == null || !normalized.isObject()) return "";
        var bindingId = mapping.detail().path("bindingId").asText(mapping.fieldCode());
        var valuePath = mapping.detail().path("dataPath").asText("/" + mapping.fieldCode());
        var values = normalized.fields();
        while (values.hasNext()) {
            var wrapper = values.next().getValue();
            if (!mapping.fieldCode().equals(wrapper.path("fieldCode").asText(mapping.fieldCode()))) continue;
            if (!bindingId.equals(wrapper.path("bindingId").asText(bindingId))) continue;
            if (!valuePath.equals(wrapper.path("valuePath").asText(wrapper.path("dataPath").asText(valuePath)))) continue;
            return effectiveFieldValue(wrapper);
        }
        return "";
    }

    private boolean locatorMatches(TemplateDataImportFacade.ImportBinding binding, String sheetId,
                                   String sourceColumn, List<Integer> headerRows, int dataStartRow) {
        if (binding == null || binding.locator() == null || !binding.locator().isObject()) return false;
        var locator = binding.locator();
        var locatorSheet = locator.path("sheetId").asText(locator.path("sheet").asText(""));
        if (!locatorSheet.isBlank() && !locatorSheet.equals(sheetId)) return false;
        var address = firstLocatorText(locator, "logicalInputRange", "valueRange", "address", "range",
                "dataRange", "sourceRange");
        if (address.isBlank()) return false;
        var range = parseRange(address);
        if (range == null) return false;
        var column = columnNumber(sourceColumn);
        if (column < range.startColumn() || column > range.endColumn()) return false;
        return headerRows.stream().anyMatch(row -> row >= range.startRow() && row <= range.endRow())
                || (dataStartRow >= range.startRow() && dataStartRow <= range.endRow());
    }

    private String firstLocatorText(JsonNode node, String... names) {
        for (var name : names) {
            var value = node.path(name).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private List<TemplateDataImportFacade.ImportBinding> applyComponentOverrides(
            List<TemplateDataImportFacade.ImportBinding> bindings,
            List<DataRepository.ComponentOverride> overrides,
            JsonNode contract
    ) {
        if (overrides.isEmpty()) return bindings;
        var overridesByComponent = overrides.stream().collect(java.util.stream.Collectors.toMap(
                DataRepository.ComponentOverride::componentId, item -> item, (left, right) -> right));
        var originalRanges = new HashMap<String, CellRange>();
        if (contract != null) contract.path("components").forEach(component -> {
            var componentId = component.path("componentId").asText("");
            var range = parseRange(component.path("range").asText(""));
            if (!componentId.isBlank() && range != null) originalRanges.put(componentId, range);
        });
        return bindings.stream().map(binding -> {
            var locator = binding.locator();
            var componentId = locator == null ? "" : locator.path("componentId").asText("");
            if (componentId.isBlank()) componentId = binding.parentBindingId();
            if (componentId == null || componentId.isBlank()) componentId = binding.bindingId();
            var override = overridesByComponent.get(componentId);
            if (override == null) return binding;
            var target = parseRange(override.sourceRange());
            var original = originalRanges.get(componentId);
            if (target == null || original == null) return binding;
            var translated = locator != null && locator.isObject()
                    ? (ObjectNode) locator.deepCopy() : objectMapper.createObjectNode();
            translated.put("sheetId", override.sheetId()).put("componentId", componentId)
                    .put("manuallyReanchored", true).put("reanchorReason", override.reason());
            for (var key : List.of("logicalInputRange", "valueRange", "address", "range", "recordRange",
                    "dataRange", "sourceRange", "headerRange", "labelRange", "crossDataRange")) {
                var source = parseRange(translated.path(key).asText(""));
                if (source == null) continue;
                translated.put(key, translateRange(source,
                        target.startRow() - original.startRow(), target.startColumn() - original.startColumn()));
            }
            if (binding.parentBindingId() == null || binding.parentBindingId().isBlank()) {
                translated.put("range", override.sourceRange().toUpperCase(Locale.ROOT));
            }
            return new TemplateDataImportFacade.ImportBinding(
                    binding.bindingId(), binding.fieldCode(), binding.dataPath(), binding.mappingKind(),
                    binding.parentBindingId(), binding.repeatAxis(), binding.recordHeight(), binding.recordWidth(),
                    binding.recordStride(), binding.terminationRule(), translated, binding.required(),
                    binding.identity(), binding.trainingEligible(), binding.valueSource(), binding.valueType(),
                    binding.unit(), binding.labelPath(), binding.trainingRole(), binding.ragEligible());
        }).toList();
    }

    private ImportCompatibilityEvaluator.Result applyCompatibilityOverrides(
            ImportCompatibilityEvaluator.Result evaluated,
            List<DataRepository.ComponentOverride> overrides
    ) {
        if (overrides.isEmpty() || !evaluated.componentMatches().isArray()) return evaluated;
        var ids = overrides.stream().map(DataRepository.ComponentOverride::componentId).collect(java.util.stream.Collectors.toSet());
        var byId = overrides.stream().collect(java.util.stream.Collectors.toMap(
                DataRepository.ComponentOverride::componentId, item -> item, (left, right) -> right));
        var matches = evaluated.componentMatches().deepCopy();
        matches.forEach(match -> {
            if (!ids.contains(match.path("componentId").asText("")) || !match.isObject()) return;
            var override = byId.get(match.path("componentId").asText(""));
            if (!match.path("formulaRoleCompatible").asBoolean(true)) return;
            ((ObjectNode) match).put("status", "COMPATIBLE").put("manuallyReanchored", true)
                    .put("sheetId", override.sheetId()).put("sourceRange", override.sourceRange())
                    .put("resolutionReason", override.reason());
        });
        var statuses = new ArrayList<String>();
        matches.forEach(match -> statuses.add(match.path("status").asText("REVIEW_REQUIRED")));
        var overall = statuses.stream().anyMatch("INCOMPATIBLE"::equals) ? "INCOMPATIBLE"
                : statuses.stream().anyMatch("REVIEW_REQUIRED"::equals) ? "REVIEW_REQUIRED"
                : statuses.stream().anyMatch("COMPATIBLE"::equals) ? "COMPATIBLE" : "EXACT";
        return new ImportCompatibilityEvaluator.Result(overall, matches);
    }

    private String translateRange(CellRange range, int rowOffset, int columnOffset) {
        return columnName(Math.max(1, range.startColumn() + columnOffset))
                + Math.max(1, range.startRow() + rowOffset) + ":"
                + columnName(Math.max(1, range.endColumn() + columnOffset))
                + Math.max(1, range.endRow() + rowOffset);
    }

    private CellRange parseRange(String value) {
        var matcher = java.util.regex.Pattern.compile("^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value.replace("$", "").trim());
        if (!matcher.matches()) return null;
        var startColumn = columnNumber(matcher.group(1));
        var startRow = Integer.parseInt(matcher.group(2));
        var endColumn = columnNumber(matcher.group(3) == null ? matcher.group(1) : matcher.group(3));
        var endRow = Integer.parseInt(matcher.group(4) == null ? matcher.group(2) : matcher.group(4));
        return new CellRange(Math.min(startRow, endRow), Math.max(startRow, endRow),
                Math.min(startColumn, endColumn), Math.max(startColumn, endColumn));
    }

    private List<String> rowAt(List<List<String>> rows, int oneBased) {
        return oneBased > 0 && oneBased <= rows.size() ? rows.get(oneBased - 1) : List.of();
    }

    private List<String> headerAt(List<List<String>> rows, List<Integer> headerRows) {
        var max = headerRows.stream().mapToInt(row -> rowAt(rows, row).size()).max().orElse(0);
        var result = new ArrayList<String>();
        for (int column = 0; column < max; column++) {
            var parts = new ArrayList<String>();
            for (Integer row : headerRows) {
                var values = rowAt(rows, row);
                if (column < values.size() && values.get(column) != null && !values.get(column).isBlank()) {
                    parts.add(values.get(column).trim());
                }
            }
            result.add(String.join(" / ", parts));
        }
        return result;
    }

    private String mappingStatus(String action) {
        return switch (action == null ? "PENDING" : action) {
            case "MAP" -> "CONFIRMED";
            case "IGNORE" -> "IGNORED";
            case "REQUEST_FIELD" -> "REQUESTED";
            default -> "PENDING";
        };
    }

    private String sourceFormat(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) return "XLSX";
        if (lower.endsWith(".xls")) return "XLS";
        if (lower.endsWith(".csv")) return "CSV";
        throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据中心仅支持 XLS、XLSX 或 CSV");
    }

    private String columnName(int value) {
        var result = new StringBuilder();
        while (value > 0) { value--; result.insert(0, (char) ('A' + value % 26)); value /= 26; }
        return result.toString();
    }

    private int columnNumber(String value) {
        int result = 0;
        for (int i = 0; i < value.length(); i++) result = result * 26 + Character.toUpperCase(value.charAt(i)) - 'A' + 1;
        return result;
    }

    private String safeMessage(Throwable error) {
        if (isDataConflict(error)) return "导入数据结构存在冲突，请检查模板区域后重新导入";
        if (error instanceof IllegalArgumentException) return "导入文件或模板配置无效，请检查后重新导入";
        return "文件解析失败，请检查文件内容后重试";
    }

    void markParseFailed(UUID organizationId, UUID importJobId, Throwable error) {
        repository.updateJobStatus(organizationId, importJobId, "FAILED", 0, "FAILED", safeMessage(error));
    }

    private boolean isDataConflict(Throwable error) {
        for (var current = error; current != null; current = current.getCause()) {
            var message = current.getMessage();
            if (current instanceof org.springframework.dao.DataIntegrityViolationException) return true;
            if (message != null && (message.contains("duplicate key") || message.contains("重复键")
                    || message.contains("unique constraint") || message.contains("唯一约束"))) return true;
        }
        return false;
    }

    public record CreateCommand(UUID sourceFileId, UUID templateVersionId,
                                UUID categoryId, boolean duplicateOverride) {
        public CreateCommand(UUID sourceFileId, UUID templateVersionId, boolean duplicateOverride) {
            this(sourceFileId, templateVersionId, null, duplicateOverride);
        }
    }
    public record FieldRequestCommand(String fieldId, String displayName, String valueType,
                                      String uiType, String groupCode, String description) {}
    public record MappingCommand(String sheetId, String sourceColumn, String sourceHeader, String fieldCode, String fieldName,
                                  String action, String valueType, String sourceUnit, String standardUnit, JsonNode detail) {}
    public record Preview(DataRepository.Job job, List<DataRepository.Sheet> sheets, List<DataRepository.Mapping> mappings,
                          List<DataRepository.Row> rows, List<DataRepository.Issue> issues,
                          TemplateContract templateContract, ProjectionSummary projectionSummary,
                          JsonNode compatibilityReport, List<DataRepository.ComponentOverride> componentOverrides) {
        public Preview(DataRepository.Job job, List<DataRepository.Sheet> sheets, List<DataRepository.Mapping> mappings,
                       List<DataRepository.Row> rows, List<DataRepository.Issue> issues) {
            this(job, sheets, mappings, rows, issues, new TemplateContract(0, 0, null, null, List.of(), List.of()),
                    new ProjectionSummary("NOT_COMMITTED", 0, 0, 0), objectMapperPlaceholder(), List.of());
        }
        private static JsonNode objectMapperPlaceholder() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
    }

    private String sourceFingerprint(String sheetId, List<String> header) {
        return sha256(sheetId + "|" + String.join("\u001f", header));
    }

    private String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record CellRange(int startRow, int endRow, int startColumn, int endColumn) {}

    public record TemplateContract(int importContractVersion, int layoutStructureVersion,
                                   String contractHash, JsonNode contract,
                                   List<TemplateDataImportFacade.FieldDefinition> fields,
                                   List<TemplateDataImportFacade.ImportBinding> bindings) {}

    public record ProjectionSummary(UUID datasetId, String status, int recordCount, int longValueCount,
                                    int eligibleRecordCount) {
        public ProjectionSummary(String status, int recordCount, int longValueCount, int eligibleRecordCount) {
            this(null, status, recordCount, longValueCount, eligibleRecordCount);
        }
    }
}
