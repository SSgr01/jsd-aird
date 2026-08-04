package com.jsd.aird.tpl.application;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.application.port.WorkbookSnapshotStructureParser;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateImportService {

    private final TemplateImportRepository repository;
    private final FileObjectRepository fileRepository;
    private final ObjectStorage objectStorage;
    private final List<OfficeStructureParser> parsers;
    private final ObjectMapper objectMapper;
    private final RecognitionModelClient recognitionModelClient;
    private final RuleBasedRecognitionEngine ruleRecognitionEngine;
    private final WorkbookSnapshotStructureParser snapshotStructureParser;
    private final WorkbookQualityAnalyzer qualityAnalyzer;
    private final JsonCanonicalizer canonicalizer;
    private final TemplateRepository templateRepository;

    public TemplateImportService(
            TemplateImportRepository repository,
            FileObjectRepository fileRepository,
            ObjectStorage objectStorage,
            List<OfficeStructureParser> parsers,
            ObjectMapper objectMapper,
            RecognitionModelClient recognitionModelClient,
            RuleBasedRecognitionEngine ruleRecognitionEngine,
            WorkbookSnapshotStructureParser snapshotStructureParser,
            WorkbookQualityAnalyzer qualityAnalyzer,
            JsonCanonicalizer canonicalizer,
            TemplateRepository templateRepository
    ) {
        this.repository = repository;
        this.fileRepository = fileRepository;
        this.objectStorage = objectStorage;
        this.parsers = List.copyOf(parsers);
        this.objectMapper = objectMapper;
        this.recognitionModelClient = recognitionModelClient;
        this.ruleRecognitionEngine = ruleRecognitionEngine;
        this.snapshotStructureParser = snapshotStructureParser;
        this.qualityAnalyzer = qualityAnalyzer;
        this.canonicalizer = canonicalizer;
        this.templateRepository = templateRepository;
    }

    @Transactional
    public TemplateImportRepository.ImportJobView create(UUID fileId, TemplateFormat format) {
        var actor = ActorContext.required();
        fileRepository.find(actor.organizationId(), fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
        var importJobId = UUID.randomUUID();
        repository.enqueue(new TemplateImportRepository.NewImportJob(
                importJobId,
                UUID.randomUUID(),
                actor.organizationId(),
                fileId,
                format,
                actor.userId(),
                "OFFICE_FILE"
        ));
        return get(importJobId);
    }

    public TemplateImportRepository.ImportJobView get(UUID importJobId) {
        return repository.find(ActorContext.required().organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
    }

    public List<TemplateImportRepository.ImportJobView> list() {
        return repository.list(ActorContext.required().organizationId());
    }

    public List<TemplateImportRepository.RecognitionSuggestionView> listSuggestions(UUID importJobId) {
        var actor = ActorContext.required();
        repository.find(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        return repository.listSuggestions(actor.organizationId(), importJobId);
    }

    @Transactional
    public List<TemplateImportRepository.RecognitionSuggestionView> confirmAll(UUID importJobId) {
        var actor = ActorContext.required();
        repository.find(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        repository.acceptSuggestionsAboveConfidence(actor.organizationId(), importJobId, 0, actor.userId());
        return repository.listSuggestions(actor.organizationId(), importJobId);
    }

    @Transactional
    public TemplateImportRepository.RecognitionSuggestionView decideSuggestion(
            UUID importJobId,
            UUID suggestionId,
            String decision
    ) {
        if (!"ACCEPTED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "建议处理结果只能是 ACCEPTED 或 REJECTED");
        }
        var actor = ActorContext.required();
        return repository.decideSuggestion(
                        actor.organizationId(), importJobId, suggestionId, decision, actor.userId()
                )
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "识别建议不存在"));
    }

    public JsonNode process(UUID importJobId, UUID organizationId, UUID fileId, TemplateFormat format) {
        repository.updateProgress(importJobId, 15, "LOADING_FILE");
        var file = fileRepository.find(organizationId, fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
        var parser = parsers.stream()
                .filter(candidate -> candidate.format() == format)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No parser for " + format));
        try (var stored = objectStorage.get(file.objectKey())) {
            repository.updateProgress(importJobId, 35, "READING_STRUCTURE");
            var parsed = parser.parse(stored.stream());
            return recognizeParsed(
                    importJobId, organizationId, format, file.originalName(), parsed,
                    "WORKBOOK", null, null
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Import parsing failed", exception);
        }
    }

    public JsonNode processSnapshot(UUID importJobId, UUID organizationId, UUID fileId) {
        return processSnapshot(importJobId, organizationId, fileId, "WORKBOOK", null, null, null);
    }

    public JsonNode processSnapshot(
            UUID importJobId,
            UUID organizationId,
            UUID fileId,
            String scope,
            String sheetId,
            String address,
            JsonNode snapshotFragment
    ) {
        repository.updateProgress(importJobId, 15, "LOADING_FILE");
        var file = fileRepository.find(organizationId, fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
        try (var stored = objectStorage.get(file.objectKey())) {
            repository.updateProgress(importJobId, 35, "READING_STRUCTURE");
            OfficeStructureParser.ParseResult parsed;
            if (snapshotFragment != null && snapshotFragment.isObject()
                    && snapshotFragment.path("operations").isArray()) {
                var snapshot = objectMapper.readTree(stored.stream());
                if (snapshotFragment.path("operations").size() > 200
                        || !validFragmentScope(snapshotFragment, scope, sheetId, address)
                        || !qualityAnalyzer.apply(snapshot, snapshotFragment)) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "增量识别的工作簿片段已失效，请保存后重试");
                }
                parsed = snapshotStructureParser.parse(new ByteArrayInputStream(
                        objectMapper.writeValueAsBytes(snapshot)
                ));
            } else {
                parsed = snapshotStructureParser.parse(stored.stream());
            }
            return recognizeParsed(
                    importJobId, organizationId, TemplateFormat.XLSX, file.originalName(), parsed,
                    scope, sheetId, address
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Snapshot recognition failed", exception);
        }
    }

    private JsonNode recognizeParsed(
            UUID importJobId,
            UUID organizationId,
            TemplateFormat format,
            String sourceFileName,
            OfficeStructureParser.ParseResult parsed,
            String scope,
            String requestedSheetId,
            String requestedAddress
    ) {
        var issues = new java.util.ArrayList<>(parsed.issues());
        var workingParsed = parsed;
        var structureVersion = workingParsed.structureSummary().path("structureVersion").asInt();
        if (format == TemplateFormat.XLSX && structureVersion != 6) {
            throw new IllegalStateException("Excel 识别仅支持 structureVersion 6");
        }
        var qualityAnalysis = format == TemplateFormat.XLSX
                ? qualityAnalyzer.analyze(
                        workingParsed.structureSummary(), workingParsed.initialEditorSnapshot(),
                        mappedLocations(importJobId, organizationId), false
                )
                : new WorkbookQualityAnalyzer.Analysis(
                        workingParsed.initialEditorSnapshot(), List.of(), false
                );
        var beforeSnapshotHash = canonicalizer.hash(workingParsed.initialEditorSnapshot());
        var modelRegions = modelRegions(
                workingParsed.structureSummary(), format, scope, requestedSheetId, requestedAddress
        );
        var recognitionRunId = repository.startRecognitionRun(
                importJobId, scope, structureVersion,
                workingParsed.initialEditorSnapshot().path("snapshotFormatVersion").asInt(3),
                modelRegions.size()
        );
        repository.updateProgress(importJobId, 48, "RECOGNIZING_FIELDS");
        var ruleBatch = ruleRecognitionEngine.recognize(format, sourceFileName, workingParsed.structureSummary());
        var suggestionCount = ruleBatch.suggestions().size();
        var modelStatus = "NOT_CONFIGURED";
        var modelQualityIssues = new java.util.ArrayList<RecognitionModelClient.QualityIssueSuggestion>();
        var reviewedQualityKeys = new java.util.HashSet<String>();
        if (recognitionModelClient.isConfigured() && format == TemplateFormat.XLSX) {
            repository.updateProgress(importJobId, 65, "RECOGNIZING_WORKBOOK_SEMANTICS");
            var modelSuggestions = new java.util.ArrayList<RecognitionModelClient.ModelSuggestion>();
            var failedCalls = 0;
            var processedRegions = 0;
            for (var region : modelRegions) {
                try {
                    var batch = recognitionModelClient.recognize(new RecognitionModelClient.RecognitionRequest(
                            importJobId, recognitionRunId, format, sourceFileName,
                            region.path("regionId").asText(), format == TemplateFormat.XLSX
                                    ? globalContext(workingParsed.structureSummary(), scope,
                                    requestedSheetId, requestedAddress)
                                    : workingParsed.structureSummary()
                    ));
                    for (var callTrace : batch.callTraces()) {
                        repository.saveRecognitionCall(recognitionRunId, callTrace);
                    }
                    for (var qualityIssue : batch.qualityIssues()) {
                        modelQualityIssues.add(withCall(
                                qualityIssue, batch.callTrace() == null ? null : batch.callTrace().callId()
                        ));
                    }
                    for (var suggestion : batch.suggestions()) {
                        var payload = suggestion.payload().deepCopy();
                        var confidence = suggestion.confidence();
                        if (payload instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                            object.put("analysisScope", "WORKBOOK_GLOBAL");
                            if (batch.callTrace() != null) object.put(
                                    "recognitionCallId", batch.callTrace().callId().toString()
                            );
                        }
                        modelSuggestions.add(new RecognitionModelClient.ModelSuggestion(
                                suggestion.suggestionType(), payload, confidence, suggestion.evidence()
                        ));
                    }
                } catch (RecognitionModelClient.RecognitionCallException exception) {
                    failedCalls++;
                    for (var callTrace : exception.traces()) {
                        repository.saveRecognitionCall(recognitionRunId, callTrace);
                    }
                } catch (Exception exception) {
                    failedCalls++;
                    var now = java.time.Instant.now();
                    repository.saveRecognitionCall(recognitionRunId, new RecognitionModelClient.CallTrace(
                            UUID.randomUUID(), region.path("regionId").asText(), 1,
                            "openai-compatible", "global-semantic-model", "template-global-semantic-v1",
                            "FAILED", null, now, now, 0, 0, 0, 0,
                            objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                            ruleBatch.requestHash(), null, exception.getClass().getSimpleName(),
                            "完整工作簿语义识别未执行，请检查模型配置与响应协议"
                    ));
                }
                processedRegions++;
                repository.updateProgress(importJobId,
                        65 + (int) Math.floor(18d * processedRegions / Math.max(1, modelRegions.size())),
                        "RECOGNIZING_WORKBOOK_SEMANTICS");
            }
            var aggregate = new RecognitionModelClient.RecognitionBatch(
                    List.copyOf(modelSuggestions), List.copyOf(modelQualityIssues),
                    "openai-compatible", "global-semantic-model", "template-global-semantic-v1",
                    ruleBatch.requestHash(), ruleBatch.responseHash()
            );
            repository.replaceModelSuggestions(importJobId, recognitionRunId, aggregate);
            suggestionCount += modelSuggestions.size();
            modelStatus = failedCalls == 0 ? "COMPLETED"
                    : modelSuggestions.isEmpty() ? "FAILED" : "PARTIAL";
            repository.completeRecognitionRun(recognitionRunId, modelStatus);
            if (failedCalls > 0) {
                issues.add(new OfficeStructureParser.ParseIssue(
                        "WARNING", "MODEL_RECOGNITION_FAILED",
                        "完整工作簿语义识别暂时失败，请检查模型协议或稍后重新识别。",
                        objectMapper.createObjectNode()
                ));
            }
        } else {
            repository.completeRecognitionRun(recognitionRunId, "COMPLETED");
            if (!recognitionModelClient.isConfigured()) {
                issues.add(new OfficeStructureParser.ParseIssue(
                        "INFO", "MODEL_NOT_CONFIGURED",
                        "未配置智能识别服务，本次仅使用物理事实解析。",
                        objectMapper.createObjectNode()
                ));
            } else {
                modelStatus = "NOT_APPLICABLE";
            }
        }
        var qualityResolution = resolveQualityIssues(
                qualityAnalysis, modelQualityIssues, reviewedQualityKeys, "WORKBOOK".equals(scope)
        );
        if (qualityResolution.changed()) {
            workingParsed = reparsePatched(qualityResolution.snapshot(), workingParsed);
            ruleBatch = ruleRecognitionEngine.recognize(format, sourceFileName, workingParsed.structureSummary());
        }
        repository.replaceRuleSuggestions(importJobId, recognitionRunId, ruleBatch);
        repository.replaceQualityIssues(
                importJobId, recognitionRunId, aggregateQualityIssues(qualityResolution.issues()), beforeSnapshotHash,
                canonicalizer.hash(qualityResolution.snapshot())
        );
        var completed = new OfficeStructureParser.ParseResult(
                workingParsed.structureSummary(), qualityResolution.snapshot(), List.copyOf(issues)
        );
        repository.updateProgress(importJobId, 86, "BUILDING_DRAFT");
        repository.complete(importJobId, completed);
        var result = objectMapper.createObjectNode();
        result.set("initialEditorSnapshot", qualityResolution.snapshot());
        result.put("modelStatus", modelStatus);
        result.put("suggestionCount", suggestionCount);
        result.put("qualityIssueCount", qualityResolution.issues().size());
        result.put("autoFixedCount", qualityResolution.issues().stream()
                .filter(issue -> "AUTO_APPLIED".equals(issue.status())).count());
        return result;
    }

    private RecognitionModelClient.QualityIssueSuggestion withCall(
            RecognitionModelClient.QualityIssueSuggestion issue, UUID callId
    ) {
        return new RecognitionModelClient.QualityIssueSuggestion(
                issue.issueType(), issue.severity(), issue.sheetId(), issue.sheetName(), issue.address(),
                issue.title(), issue.description(), issue.businessImpact(), issue.confidence(),
                issue.autoFixable(), issue.suggestedPatch(), issue.inversePatch(), issue.evidence(),
                issue.status(), issue.regionId(), callId
        );
    }

    private QualityResolution resolveQualityIssues(
            WorkbookQualityAnalyzer.Analysis ruleAnalysis,
            List<RecognitionModelClient.QualityIssueSuggestion> modelIssues,
            java.util.Set<String> reviewedQualityKeys,
            boolean allowPhysicalAutoFix
    ) {
        var snapshot = ruleAnalysis.snapshot().deepCopy();
        var result = new java.util.LinkedHashMap<String, RecognitionModelClient.QualityIssueSuggestion>();
        for (var issue : ruleAnalysis.issues()) {
            var matchingModel = modelIssues.stream()
                    .filter(model -> qualityKey(model).equals(qualityKey(issue)))
                    .filter(model -> model.confidence() >= 0.92).findFirst().orElse(null);
            var autoApply = allowPhysicalAutoFix && reviewedQualityKeys.contains(qualityKey(issue))
                    && matchingModel != null && issue.autoFixable()
                    && issue.confidence() >= 0.92 && qualityAnalyzer.apply(snapshot, issue.suggestedPatch());
            var resolved = new RecognitionModelClient.QualityIssueSuggestion(
                    issue.issueType(), issue.severity(), issue.sheetId(), issue.sheetName(), issue.address(),
                    issue.title(), issue.description(), issue.businessImpact(),
                    Math.max(issue.confidence(), matchingModel == null ? 0 : matchingModel.confidence()),
                    issue.autoFixable(), issue.suggestedPatch(), issue.inversePatch(), issue.evidence(),
                    autoApply ? "AUTO_APPLIED" : issue.status(), issue.regionId(),
                    matchingModel == null ? issue.recognitionCallId() : matchingModel.recognitionCallId()
            );
            result.put(qualityKey(resolved), resolved);
        }
        for (var issue : modelIssues) result.putIfAbsent(qualityKey(issue), issue);
        return new QualityResolution(snapshot, List.copyOf(result.values()),
                result.values().stream().anyMatch(issue -> "AUTO_APPLIED".equals(issue.status())));
    }

    private String qualityKey(RecognitionModelClient.QualityIssueSuggestion issue) {
        return issue.sheetId() + "|" + rootBlock(issue) + "|" + customerIssueCategory(issue.issueType());
    }

    private List<RecognitionModelClient.QualityIssueSuggestion> aggregateQualityIssues(
            List<RecognitionModelClient.QualityIssueSuggestion> issues
    ) {
        var grouped = new java.util.LinkedHashMap<String, java.util.List<RecognitionModelClient.QualityIssueSuggestion>>();
        for (var issue : issues) grouped.computeIfAbsent(qualityKey(issue), ignored -> new java.util.ArrayList<>())
                .add(issue);
        var result = new java.util.ArrayList<RecognitionModelClient.QualityIssueSuggestion>();
        for (var entry : grouped.entrySet()) {
            var group = entry.getValue();
            var first = group.getFirst();
            var severity = group.stream().map(RecognitionModelClient.QualityIssueSuggestion::severity)
                    .max(java.util.Comparator.comparingInt(this::severityRank)).orElse(first.severity());
            var title = group.size() == 1 ? first.title() : first.title() + "（" + group.size() + "处）";
            var description = group.size() == 1 ? first.description()
                    : "同一业务区域有 " + group.size() + " 处同类情况，已合并为一项建议核对。";
            var evidence = objectMapper.createArrayNode();
            group.stream().limit(20).forEach(issue -> evidence.add(objectMapper.createObjectNode()
                    .put("address", issue.address()).put("title", issue.title())));
            result.add(new RecognitionModelClient.QualityIssueSuggestion(
                    customerIssueCategory(first.issueType()), severity, first.sheetId(), first.sheetName(),
                    first.address(), title, description, first.businessImpact(),
                    group.stream().mapToDouble(RecognitionModelClient.QualityIssueSuggestion::confidence)
                            .max().orElse(first.confidence()),
                    group.size() == 1 && first.autoFixable(), first.suggestedPatch(), first.inversePatch(),
                    evidence, first.status(), rootBlock(first), first.recognitionCallId()
            ));
        }
        return List.copyOf(result);
    }

    private int severityRank(String severity) {
        return "BLOCKER".equals(severity) ? 3 : "WARNING".equals(severity) ? 2 : 1;
    }

    private String rootBlock(RecognitionModelClient.QualityIssueSuggestion issue) {
        return issue.regionId() == null || issue.regionId().isBlank() ? "sheet-root" : issue.regionId();
    }

    private String customerIssueCategory(String issueType) {
        return switch (issueType) {
            case "MIXED_CELL_ROLES", "FIELD_RELATION_UNCLEAR" -> "FIELD_RELATION_UNCLEAR";
            case "HIERARCHY_MISMATCH", "BUSINESS_BLOCK_UNCLEAR" -> "BUSINESS_BLOCK_UNCLEAR";
            case "RECORD_ORIENTATION_MISMATCH", "TABLE_STRUCTURE_UNCLEAR" -> "TABLE_STRUCTURE_UNCLEAR";
            case "EDITABILITY_UNCLEAR" -> "EDITABILITY_UNCLEAR";
            case "VISUAL_PHYSICAL_MISMATCH", "LAYOUT_INCONSISTENT" -> "LAYOUT_INCONSISTENT";
            case "DUPLICATE_MEANING" -> "DUPLICATE_MEANING";
            default -> "OTHER";
        };
    }

    private java.util.Set<String> mappedLocations(UUID importJobId, UUID organizationId) {
        var versionId = repository.findGeneratedVersionId(importJobId).orElse(null);
        if (versionId == null) return java.util.Set.of();
        var workspace = templateRepository.findWorkspace(organizationId, versionId).orElse(null);
        if (workspace == null) return java.util.Set.of();
        var result = new java.util.HashSet<String>();
        for (var binding : workspace.mapping()) {
            var locator = binding.path("locator");
            var sheetId = locator.path("sheetId").asText(locator.path("sheetName").asText(""));
            for (var key : List.of("labelAddress", "labelRange", "address", "range", "logicalInputRange")) {
                addRangeLocations(result, sheetId, locator.path(key).asText(""));
            }
        }
        return java.util.Set.copyOf(result);
    }

    private void addRangeLocations(java.util.Set<String> target, String sheetId, String address) {
        var match = java.util.regex.Pattern.compile(
                "^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$"
        ).matcher(address.toUpperCase(java.util.Locale.ROOT));
        if (!match.matches()) return;
        var startColumn = columnIndex(match.group(1));
        var startRow = Integer.parseInt(match.group(2));
        var endColumn = match.group(3) == null ? startColumn : columnIndex(match.group(3));
        var endRow = match.group(4) == null ? startRow : Integer.parseInt(match.group(4));
        for (var row = Math.min(startRow, endRow); row <= Math.max(startRow, endRow); row++) {
            for (var column = Math.min(startColumn, endColumn); column <= Math.max(startColumn, endColumn); column++) {
                target.add(sheetId + "|" + columnName(column) + row);
            }
        }
    }

    private int columnIndex(String letters) {
        var result = 0;
        for (var letter : letters.toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
    }

    private String columnName(int column) {
        var value = column;
        var result = new StringBuilder();
        while (value > 0) {
            value--;
            result.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return result.toString();
    }

    private OfficeStructureParser.ParseResult reparsePatched(
            JsonNode snapshot, OfficeStructureParser.ParseResult original
    ) {
        try {
            var reparsed = snapshotStructureParser.parse(
                    new ByteArrayInputStream(objectMapper.writeValueAsBytes(snapshot))
            );
            if (reparsed.structureSummary() instanceof com.fasterxml.jackson.databind.node.ObjectNode summary) {
                for (var key : List.of("dataValidations", "namedRanges")) {
                    if (original.structureSummary().has(key)) {
                        summary.set(key, original.structureSummary().path(key).deepCopy());
                    }
                }
            }
            return new OfficeStructureParser.ParseResult(
                    reparsed.structureSummary(), snapshot, original.issues()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("自动规范后无法重新解析工作簿", exception);
        }
    }

    private record QualityResolution(
            JsonNode snapshot,
            List<RecognitionModelClient.QualityIssueSuggestion> issues,
            boolean changed
    ) {
    }

    private List<JsonNode> modelRegions(
            JsonNode structure, TemplateFormat format, String scope, String requestedSheetId, String requestedAddress
    ) {
        if (format != TemplateFormat.XLSX) {
            return List.of(objectMapper.createObjectNode().put("regionId", "document"));
        }
        return List.of(objectMapper.createObjectNode()
                .put("regionId", "workbook-global")
                .put("scope", scope)
                .put("requestedSheetId", requestedSheetId == null ? "" : requestedSheetId)
                .put("requestedAddress", requestedAddress == null ? "" : requestedAddress));
    }

    private boolean rangesOverlap(String first, String second) {
        var a = parseRange(first);
        var b = parseRange(second);
        return a != null && b != null && a[0] <= b[2] && a[2] >= b[0]
                && a[1] <= b[3] && a[3] >= b[1];
    }

    private boolean validFragmentScope(
            JsonNode fragment, String scope, String requestedSheetId, String requestedAddress
    ) {
        for (var operation : fragment.path("operations")) {
            if (!"SET_CELL".equals(operation.path("op").asText())) return false;
            if ("REGION".equals(scope) && (!requestedSheetId.equals(operation.path("sheetId").asText())
                    || !rangesOverlap(requestedAddress, operation.path("address").asText()))) return false;
        }
        return true;
    }

    private int[] parseRange(String address) {
        if (address == null) return null;
        var match = java.util.regex.Pattern.compile(
                "^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$"
        ).matcher(address.toUpperCase(java.util.Locale.ROOT));
        if (!match.matches()) return null;
        var firstColumn = columnIndex(match.group(1));
        var firstRow = Integer.parseInt(match.group(2));
        var lastColumn = match.group(3) == null ? firstColumn : columnIndex(match.group(3));
        var lastRow = match.group(4) == null ? firstRow : Integer.parseInt(match.group(4));
        return new int[]{Math.min(firstRow, lastRow), Math.min(firstColumn, lastColumn),
                Math.max(firstRow, lastRow), Math.max(firstColumn, lastColumn)};
    }

    private JsonNode globalContext(
            JsonNode structure, String scope, String requestedSheetId, String requestedAddress
    ) {
        var context = objectMapper.createObjectNode()
                .put("structureVersion", structure.path("structureVersion").asInt())
                .put("parserVersion", structure.path("parserVersion").asText())
                .put("sourceKind", structure.path("sourceKind").asText("XLSX"))
                .put("requestedScope", scope)
                .put("requestedSheetId", requestedSheetId == null ? "" : requestedSheetId)
                .put("requestedAddress", requestedAddress == null ? "" : requestedAddress);
        var sheets = objectMapper.createArrayNode();
        for (var sheet : structure.path("sheets")) {
            var compact = objectMapper.createObjectNode()
                    .put("id", sheet.path("id").asText())
                    .put("name", sheet.path("name").asText())
                    .put("hidden", sheet.path("hidden").asBoolean(false))
                    .put("usedRange", sheet.path("usedRange").asText());
            for (var key : List.of("semanticCells", "layoutSpans", "borderSegments", "mergedRanges",
                    "rowProfiles", "columnProfiles", "dataValidationRules")) {
                compact.set(key, sheet.path(key).deepCopy());
            }
            sheets.add(compact);
        }
        context.set("sheets", sheets);
        context.set("structureHints", structure.path("structureHints").deepCopy());
        context.set("namedRanges", structure.path("namedRanges").deepCopy());
        return context;
    }

}
