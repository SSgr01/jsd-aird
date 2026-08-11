package com.jsd.aird.data.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final Set<String> SUPPORTED_TARGET_DATA_TYPES = Set.of(
            "MATERIAL", "FORMULA", "PROCESS", "EQUIPMENT", "TEST_STANDARD");

    private final DataRepository repository;
    private final FileObjectRepository files;
    private final TemplateDataImportFacade templates;
    private final ObjectMapper objectMapper;
    private final AuditLogFacade auditLog;
    private final OpsAsyncFacade opsAsync;
    private final DataCategoryService categories;
    private final StructuredDataExtractor structuredExtractor;

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
        var targetDataType = normalizeTargetDataType(command.targetDataType());
        var definition = templates.getPublished(actor.organizationId(), command.templateVersionId());
        if (!definition.targetDataType().equals(targetDataType)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "所选模板与数据类型不匹配");
        }
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
        if (categoryId == null && categories != null) {
            var defaultCategory = categories.defaultForTargetType(actor.organizationId(), targetDataType);
            categoryId = defaultCategory == null ? null : defaultCategory.id();
        } else if (categoryId != null && categories != null) {
            var requestedCategoryId = categoryId;
            var category = categories.list().stream().filter(item -> item.id().equals(requestedCategoryId)).findFirst()
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据分类不存在"));
            if (category.targetDataType() != null && !targetDataType.equals(category.targetDataType())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据分类与数据类型不匹配");
            }
        }
        var id = UUID.randomUUID();
        repository.insertJob(new DataRepository.NewJob(
                id, actor.organizationId(), command.sourceFileId(), file.sha256(), file.originalName(), format,
                command.templateVersionId(), targetDataType, categoryId, command.duplicateOverride(), actor.userId()));
        repository.enqueueParse(UUID.randomUUID(), actor.organizationId(), id);
        return get(id);
    }

    public DataRepository.Job get(UUID importJobId) {
        return repository.findJob(ActorContext.required().organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
    }

    public PageResponse<DataRepository.Job> listJobs(String targetDataType, UUID templateVersionId,
                                                      String status, String keyword, int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        return repository.listJobs(actor.organizationId(), targetDataType, templateVersionId, status,
                keyword, safePage, safeSize);
    }

    public List<TemplateDataImportFacade.DataTemplateOption> listTemplates(String targetDataType) {
        return templates.listPublished(ActorContext.required().organizationId(), targetDataType);
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
            var definition = templates.getPublished(organizationId, job.templateVersionId());
            var bindings = templates.getPublishedBindings(organizationId, job.templateVersionId());
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
                var structure = objectMapper.createObjectNode()
                        .put("sheetId", parsedSheet.sheetId())
                        .put("sheetName", parsedSheet.sheetName())
                        .set("rows", objectMapper.valueToTree(parsedSheet.rows()));
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
                            detail.put("mappingKind", binding.mappingKind())
                                    .put("parentBindingId", binding.parentBindingId())
                                    .put("repeatAxis", binding.repeatAxis())
                                    .put("recordHeight", binding.recordHeight())
                                    .put("recordWidth", binding.recordWidth())
                                    .put("recordStride", binding.recordStride())
                                    .put("trainingEligible", binding.trainingEligible())
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
                    rows.add(new DataRepository.Row(UUID.randomUUID(), parsedSheet.sheetId(), rowIndex, raw, raw.deepCopy(),
                            raw.deepCopy(), "STAGED"));
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
        var definition = templates.getPublished(organizationId, job.templateVersionId());
        var mappings = repository.listMappings(organizationId, importJobId);
        var rows = repository.listRows(organizationId, importJobId);
        var issues = new ArrayList<DataRepository.Issue>();
        var mappingByColumn = new HashMap<String, DataRepository.Mapping>();
        for (var mapping : mappings) mappingByColumn.put(mapping.sheetId() + "|" + mapping.sourceColumn(), mapping);
        var mappedSheetsByField = new HashMap<String, Set<String>>();
        for (var mapping : mappings) {
            if (!"MAP".equals(mapping.action()) || mapping.fieldCode() == null || mapping.fieldCode().isBlank()) continue;
            mappedSheetsByField.computeIfAbsent(mapping.fieldCode(), ignored -> new java.util.HashSet<>()).add(mapping.sheetId());
        }
        var normalizedRows = new ArrayList<DataRepository.Row>();
        for (var row : rows) {
            var normalized = objectMapper.createObjectNode();
            var rowBlocked = false;
            var seenPaths = new HashMap<String, DataRepository.Mapping>();
            var entries = row.rawValues().fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                var mapping = mappingByColumn.get(row.sheetId() + "|" + entry.getKey());
                if (mapping == null || "IGNORE".equals(mapping.action())) continue;
                if (!"MAP".equals(mapping.action()) || mapping.fieldCode() == null || mapping.fieldCode().isBlank()) {
                    issues.add(issue(row, mapping, "BLOCKER", "UNMAPPED_FIELD", "字段尚未完成映射"));
                    rowBlocked = true;
                    continue;
                }
                var raw = entry.getValue().asText();
                var normalizedValue = normalize(raw, mapping, row, issues);
                var value = objectMapper.createObjectNode().put("rawValue", raw)
                        .put("rawUnit", mapping.sourceUnit() == null ? "" : mapping.sourceUnit())
                        .put("normalizedValue", normalizedValue)
                        .put("normalizedUnit", mapping.standardUnit() == null ? "" : mapping.standardUnit())
                        .put("dataPath", mapping.detail().path("dataPath").asText("/" + mapping.fieldCode()))
                        .put("trainingEligible", mapping.detail().path("trainingEligible").asBoolean(true));
                var pathKey = mapping.fieldCode() + "|" + mapping.detail().path("dataPath").asText("");
                if (seenPaths.putIfAbsent(pathKey, mapping) != null) {
                    issues.add(issue(row, mapping, "BLOCKER", "DUPLICATE_FIELD_MAPPING", "同一字段被多个源列重复映射"));
                    rowBlocked = true;
                } else {
                    normalized.set(mapping.fieldCode(), value);
                }
            }
            for (var field : definition.fields()) {
                if (!field.required()) continue;
                var mappedSheets = mappedSheetsByField.getOrDefault(field.fieldCode(), Set.of());
                if (mappedSheets.isEmpty()) {
                    issues.add(issue(row, null, "BLOCKER", "REQUIRED_MAPPING_MISSING", "模板必填字段尚未映射：" + field.displayName()));
                    rowBlocked = true;
                } else if (mappedSheets.contains(row.sheetId())
                        && normalized.path(field.fieldCode()).path("normalizedValue").asText("").isBlank()) {
                    var mapping = mappings.stream().filter(item -> row.sheetId().equals(item.sheetId())
                            && field.fieldCode().equals(item.fieldCode())).findFirst().orElse(null);
                    issues.add(issue(row, mapping, "BLOCKER", "REQUIRED_MISSING", "必填字段为空"));
                    rowBlocked = true;
                }
            }
            normalizedRows.add(new DataRepository.Row(row.id(), row.sheetId(), row.rowNumber(), row.rawValues(), normalized,
                    normalized.deepCopy(), rowBlocked ? "BLOCKED" : "VALID", row.sourceMetadata()));
        }
        var blocker = issues.stream().anyMatch(item -> "BLOCKER".equals(item.severity()));
        repository.replaceValidation(organizationId, importJobId, normalizedRows, issues,
                blocker ? "WAITING_MAPPING" : "WAITING_CONFIRM");
    }

    public Preview preview(UUID importJobId) {
        var actor = ActorContext.required();
        var job = requireJob(actor.organizationId(), importJobId);
        var definition = templates.getPublished(actor.organizationId(), job.templateVersionId());
        return new Preview(job, repository.listSheets(actor.organizationId(), importJobId),
                repository.listMappings(actor.organizationId(), importJobId), repository.listRows(actor.organizationId(), importJobId),
                repository.listIssues(actor.organizationId(), importJobId),
                new TemplateContract(definition.fields(), templates.getPublishedBindings(actor.organizationId(), job.templateVersionId())),
                new ProjectionSummary(null, "NOT_COMMITTED", 0, 0, 0));
    }

    public List<DataRepository.Issue> issues(UUID importJobId) {
        var actor = ActorContext.required();
        requireJob(actor.organizationId(), importJobId);
        return repository.listIssues(actor.organizationId(), importJobId);
    }

    @Transactional
    public void resolveIssue(UUID issueId, String status) {
        var actor = ActorContext.required();
        repository.resolveIssue(actor.organizationId(), issueId, actor.userId(), status);
    }

    @Transactional
    public void commit(UUID importJobId) {
        var actor = ActorContext.required();
        var job = repository.findJobForUpdate(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        if ("COMPLETED".equals(job.status())) return;
        if (!Set.of("WAITING_CONFIRM", "COMMITTING").contains(job.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "导入任务当前不可提交：" + job.status());
        }
        var blockers = repository.listIssues(actor.organizationId(), importJobId).stream()
                .anyMatch(item -> "BLOCKER".equals(item.severity()) && !List.of("RESOLVED", "IGNORED").contains(item.status()));
        if (blockers) throw new ApiException(ApiErrorCode.BAD_REQUEST, "仍有阻断异常未处理");
        repository.updateJobStatus(actor.organizationId(), importJobId, "COMMITTING", 90, "COMMITTING", null);
        var rows = repository.listRows(actor.organizationId(), importJobId);
        var mappings = repository.listMappings(actor.organizationId(), importJobId);
        var sheetNames = repository.listSheets(actor.organizationId(), importJobId).stream()
                .collect(java.util.stream.Collectors.toMap(DataRepository.Sheet::sheetId, DataRepository.Sheet::sheetName));
        var committed = rows.stream().filter(row -> !"BLOCKED".equals(row.status())).map(row -> {
            var identity = firstValue(row.normalizedValues(), mappings, row, true);
            var display = firstValue(row.normalizedValues(), mappings, row, false);
            var anchors = mappings.stream().filter(item -> "MAP".equals(item.action()) && item.fieldCode() != null)
                    .map(item -> anchor(item, row, sheetNames.getOrDefault(item.sheetId(), item.sheetId())))
                    .filter(java.util.Objects::nonNull).toList();
            var metadata = row.sourceMetadata() == null ? objectMapper.createObjectNode() : row.sourceMetadata();
            var structuredKey = metadata.path("recordKey").asText("");
            var structuredDisplay = metadata.path("displayName").asText("");
            // Structured projections already computed the logical record key.  It must
            // win over a form-level identity field; otherwise every detail row in a
            // FORM_REGION + ROW_TABLE sheet would be committed as another revision of
            // the same form asset instead of as its own record/asset.
            var assetKey = structuredKey.isBlank()
                    ? (identity == null || identity.isBlank()
                    ? job.targetDataType() + ":" + row.sheetId() + ":" + row.rowNumber() : identity)
                    : structuredKey;
            return new DataRepository.CommittedRow(row.sheetId(), row.rowNumber(), row.rawValues(), row.normalizedValues(),
                    row.correctedValues(), assetKey,
                    display == null || display.isBlank()
                            ? (structuredDisplay.isBlank() ? identity : structuredDisplay) : display, anchors);
        }).toList();
        var result = repository.commit(actor.organizationId(), importJobId, actor.userId(), committed);
        appendCommitAuditAndOutbox(actor.organizationId(), actor.userId(), job, result);
    }

    private void appendCommitAuditAndOutbox(UUID organizationId, UUID actorId, DataRepository.Job job,
                                            DataRepository.CommitResult result) {
        var assets = objectMapper.createArrayNode();
        for (var item : result.assets()) {
            assets.add(objectMapper.createObjectNode()
                    .put("assetId", item.assetId().toString())
                    .put("revisionId", item.revisionId().toString())
                    .put("revisionNo", item.revisionNo())
                    .put("dataHash", item.dataHash()));
        }
        var detail = objectMapper.createObjectNode()
                .put("targetDataType", job.targetDataType())
                .put("templateVersionId", job.templateVersionId().toString())
                .put("sourceFileId", job.sourceFileId().toString())
                .put("sourceFileName", job.sourceFileName())
                .put("sourceSha256", job.sourceSha256())
                .put("assetCount", result.assets().stream().map(DataRepository.CommittedAsset::assetId).distinct().count())
                .put("rowCount", result.rowCount())
                .set("assets", assets);
        auditLog.append(organizationId, actorId, "DATA_IMPORT_COMMITTED", "DATA_IMPORT_JOB", job.id(), detail);

        var eventPayload = objectMapper.createObjectNode()
                .put("organizationId", organizationId.toString())
                .put("actorId", actorId.toString())
                .put("targetDataType", job.targetDataType())
                .put("templateVersionId", job.templateVersionId().toString())
                .put("importJobId", job.id().toString())
                .put("sourceSha256", job.sourceSha256())
                .put("assetCount", result.assets().stream().map(DataRepository.CommittedAsset::assetId).distinct().count())
                .put("rowCount", result.rowCount())
                .set("assets", assets.deepCopy());
        opsAsync.appendOutbox("DATA_IMPORT_JOB", job.id(), "DATA_ASSETS_COMMITTED", eventPayload);
        var revisionSetKey = revisionSetKey(result);
        opsAsync.enqueue(organizationId, "AI_INDEX_DATA_ASSETS", eventPayload,
                "ai-data-index:" + job.id() + ":" + revisionSetKey, 30);
        opsAsync.enqueue(organizationId, "DATA_PROJECT_IMPORT", eventPayload,
                "data-project:" + job.id() + ":" + revisionSetKey, 30);
    }

    private String revisionSetKey(DataRepository.CommitResult result) {
        var revisions = result.assets().stream()
                .map(DataRepository.CommittedAsset::revisionId)
                .map(UUID::toString)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(revisions.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (var item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    public PageResponse<DataRepository.Asset> assets(String targetDataType, UUID categoryId, String status, String keyword,
                                                     int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        return repository.listAssets(actor.organizationId(), targetDataType, categoryId, status,
                targetDataType == null && keyword == null ? null : keyword, safePage, safeSize);
    }

    public DataRepository.AssetDetail asset(UUID id) {
        return repository.findAsset(ActorContext.required().organizationId(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据资产不存在"));
    }

    public List<DataRepository.Revision> revisions(UUID id) {
        return repository.listRevisions(ActorContext.required().organizationId(), id);
    }

    public List<DataRepository.SourceAnchor> sources(UUID id) {
        return repository.listSourceAnchors(ActorContext.required().organizationId(), id);
    }

    private DataRepository.Job requireJob(UUID organizationId, UUID id) {
        return repository.findJob(organizationId, id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
    }


    private DataRepository.Issue issue(DataRepository.Row row, DataRepository.Mapping mapping, String severity,
                                       String type, String message) {
        return new DataRepository.Issue(null, row.sheetId(), mapping == null ? null : mapping.fieldCode(), severity, type,
                row.rowNumber(), mapping == null ? null : mapping.sourceColumn(), mapping == null ? null : mapping.sourceColumn() + row.rowNumber(),
                message, objectMapper.createObjectNode(), "OPEN");
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
                var value = normalized.path(mapping.fieldCode()).path("normalizedValue").asText("");
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
                return new DataRepository.Anchor(mapping.fieldCode(), sourceSheetId, sourceSheetName, sourceRow,
                        sourceColumn, sourceColumnName, address, row.rawValues().path(mapping.sourceColumn()));
            }
            // A structured row has virtual source columns. Do not fabricate a physical
            // A1-style anchor when the template did not provide a cell locator.
            return null;
        }
        int column = columnNumber(mapping.sourceColumn());
        return new DataRepository.Anchor(mapping.fieldCode(), mapping.sheetId(), sheetName, row.rowNumber(), column,
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

    private String normalizeTargetDataType(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TARGET_DATA_TYPES.contains(normalized)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的数据类型：" + value);
        }
        return normalized;
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
        var value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value.substring(0, Math.min(2000, value.length()));
    }

    public record CreateCommand(UUID sourceFileId, UUID templateVersionId, String targetDataType,
                                UUID categoryId, boolean duplicateOverride) {
        public CreateCommand(UUID sourceFileId, UUID templateVersionId, String targetDataType, boolean duplicateOverride) {
            this(sourceFileId, templateVersionId, targetDataType, null, duplicateOverride);
        }
    }
    public record FieldRequestCommand(String fieldId, String displayName, String valueType,
                                      String uiType, String groupCode, String description) {}
    public record MappingCommand(String sheetId, String sourceColumn, String sourceHeader, String fieldCode, String fieldName,
                                  String action, String valueType, String sourceUnit, String standardUnit, JsonNode detail) {}
    public record Preview(DataRepository.Job job, List<DataRepository.Sheet> sheets, List<DataRepository.Mapping> mappings,
                          List<DataRepository.Row> rows, List<DataRepository.Issue> issues,
                          TemplateContract templateContract, ProjectionSummary projectionSummary) {
        public Preview(DataRepository.Job job, List<DataRepository.Sheet> sheets, List<DataRepository.Mapping> mappings,
                       List<DataRepository.Row> rows, List<DataRepository.Issue> issues) {
            this(job, sheets, mappings, rows, issues, new TemplateContract(List.of(), List.of()),
                    new ProjectionSummary("NOT_COMMITTED", 0, 0, 0));
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

    public record TemplateContract(List<TemplateDataImportFacade.FieldDefinition> fields,
                                   List<TemplateDataImportFacade.ImportBinding> bindings) {}

    public record ProjectionSummary(UUID datasetId, String status, int recordCount, int longValueCount,
                                    int eligibleRecordCount) {
        public ProjectionSummary(String status, int recordCount, int longValueCount, int eligibleRecordCount) {
            this(null, status, recordCount, longValueCount, eligibleRecordCount);
        }
    }
}
