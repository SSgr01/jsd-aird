package com.jsd.aird.tpl.application;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.jsd.aird.tpl.application.port.TemplateVisualRenderer;
import com.jsd.aird.tpl.application.port.WorkbookSnapshotStructureParser;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Service
public class TemplateImportService {

    private static final Logger log = LoggerFactory.getLogger(TemplateImportService.class);

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
    private final TemplateVisualRenderer visualRenderer;
    private final ModelSemanticViewBuilder semanticViewBuilder;
    private final RecognitionCoverageValidator coverageValidator;
    private final CanonicalMatrixCompiler matrixCompiler;
    private final StructureProposalResolver structureProposalResolver;

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
            TemplateRepository templateRepository,
            TemplateVisualRenderer visualRenderer
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
        this.visualRenderer = visualRenderer;
        this.semanticViewBuilder = new ModelSemanticViewBuilder(objectMapper);
        this.coverageValidator = new RecognitionCoverageValidator(objectMapper);
        this.matrixCompiler = new CanonicalMatrixCompiler(objectMapper);
        this.structureProposalResolver = new StructureProposalResolver(objectMapper);
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

    public JsonNode renderContext(UUID importJobId) {
        var job = get(importJobId);
        var context = objectMapper.createObjectNode()
                .put("importJobId", importJobId.toString())
                .put("sourceFileId", job.sourceFileId().toString())
                .put("ready", job.result() != null
                        && job.result().path("initialEditorSnapshot").isObject())
                .put("visualStatus", job.result() == null
                        ? "NOT_STARTED"
                        : job.result().path("visualRender").path("status").asText("JSON_FALLBACK"));
        if (job.result() != null && job.result().path("visualRender").isObject()) {
            context.set("visualRender", job.result().path("visualRender").deepCopy());
        }
        if (job.result() != null && job.result().path("initialEditorSnapshot").isObject()) {
            context.set("snapshot", job.result().path("initialEditorSnapshot").deepCopy());
        } else {
            context.set("snapshot", objectMapper.createObjectNode());
        }
        return context;
    }

    /**
     * Returns the safe, structured Word projection produced during import.  The
     * projection intentionally excludes raw OOXML and is suitable for the web
     * structure tree, compatibility panel, and field-location workflow.
     */
    public JsonNode documentStructure(UUID importJobId) {
        var job = get(importJobId);
        if (job.format() != TemplateFormat.DOCX) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "只有 Word 导入任务提供文档结构");
        }
        var documentIr = job.structureSummary() == null
                ? null : job.structureSummary().path("documentIR");
        if (documentIr == null || !documentIr.isObject()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Word 文档结构尚未生成");
        }
        return documentIr.deepCopy();
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

    public List<TemplateImportRepository.RecognitionCallView> listRecognitionCalls(UUID importJobId) {
        var actor = ActorContext.required();
        repository.find(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        return repository.listRecognitionCalls(actor.organizationId(), importJobId);
    }

    @Transactional
    public void delete(UUID importJobId) {
        var actor = ActorContext.required();
        var job = repository.find(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        if (repository.findGeneratedVersionId(importJobId).isPresent()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "该识别记录已经生成模板，不能删除模板来源记录");
        }
        if (!"PARSED".equals(job.status()) && !"FAILED".equals(job.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "识别尚未结束，暂时不能删除记录");
        }
        if (repository.delete(actor.organizationId(), importJobId) == 0) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "识别记录不存在");
        }
    }

    @Transactional
    public List<TemplateImportRepository.RecognitionSuggestionView> confirmAll(UUID importJobId) {
        var actor = ActorContext.required();
        var job = repository.find(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        var result = job.result();
        var recognitionStatus = result == null ? "REVIEW_REQUIRED"
                : result.path("recognitionStatus").asText("REVIEW_REQUIRED");
        var canonicalStatus = result == null ? "PROVISIONAL"
                : result.path("canonicalStatus").asText("PROVISIONAL");
        var readiness = result == null ? "NOT_READY"
                : result.path("publicationReadiness").asText("NOT_READY");
        if (!"COMPLETE".equals(recognitionStatus)
                || !"CONFIRMED".equals(canonicalStatus)
                || !"READY".equals(readiness)) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID,
                    "识别结果仍需人工结构确认，不能批量确认全部候选");
        }
        // Do not use the repository's broad confidence update here. A complete
        // recognition run may still contain diagnostic, physical-only or
        // protocol-recovery rows; only candidates that satisfy the same policy
        // as the compiler may cross the confirmation boundary.
        for (var suggestion : repository.listSuggestions(actor.organizationId(), importJobId)) {
            if (!"PENDING".equals(suggestion.decision())
                    || !RecognitionCandidatePolicy.isFormallyConfirmable(suggestion.payload())) continue;
            repository.decideSuggestion(actor.organizationId(), job.recognitionRunId(), suggestion.id(),
                    "ACCEPTED", actor.userId());
        }
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
        var job = repository.find(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        if (job.recognitionRunId() == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "识别运行尚未创建");
        }
        var suggestion = repository.listSuggestions(actor.organizationId(), importJobId).stream()
                .filter(item -> suggestionId.equals(item.id())).findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "识别建议不存在"));
        if (RecognitionCandidatePolicy.isStructural(suggestion.payload())
                || suggestion.payload().path("resolutionGroupId").asText("").length() > 0
                || suggestion.payload().path("structureConflict").asBoolean(false)) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID,
                    "结构候选必须通过结构冲突组选择，不能单独确认");
        }
        return repository.decideSuggestion(
                        actor.organizationId(), job.recognitionRunId(), suggestionId, decision, actor.userId()
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
        MDC.put("importJobId", importJobId.toString());
        try {
            return recognizeParsedInternal(importJobId, organizationId, format, sourceFileName, parsed,
                    scope, requestedSheetId, requestedAddress);
        } finally {
            MDC.remove("recognitionCallId");
            MDC.remove("recognitionRunId");
            MDC.remove("importJobId");
        }
    }

    private JsonNode recognizeParsedInternal(
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
        log.info("workbook_parsed importJobId={} format={} scope={} sheets={} semanticCells={} formulas={} mergedRanges={}",
                importJobId, format, scope, workingParsed.structureSummary().path("sheets").size(),
                semanticCellCount(workingParsed.structureSummary()),
                workingParsed.structureSummary().path("formulas").size(),
                workingParsed.structureSummary().path("mergedRanges").size());
        var recognitionRunId = repository.startRecognitionRun(
                importJobId, scope, structureVersion,
                workingParsed.initialEditorSnapshot().path("snapshotFormatVersion").asInt(3),
                modelRegions.size()
        );
        repository.updateRecognitionRunSnapshot(
                recognitionRunId, beforeSnapshotHash, "INITIAL_RECOGNITION");
        repository.saveRenderSnapshot(importJobId, workingParsed.initialEditorSnapshot());
        repository.updateProgress(importJobId, 48, "RECOGNIZING_FIELDS");
        repository.updateProgress(importJobId, 52, "RENDERING_OPTIONAL_VISUAL");
        var visualRender = format == TemplateFormat.XLSX
                ? visualRenderer.render(importJobId)
                : TemplateVisualRenderer.RenderResult.unavailable(
                        "NOT_APPLICABLE", "当前格式不执行 Excel 视觉渲染"
                );
        var visualInput = visualRender.rendered() ? visualRender.modelNode(objectMapper) : null;
        var ruleBatch = ruleRecognitionEngine.recognize(format, sourceFileName, workingParsed.structureSummary());
        var suggestionCount = ruleBatch.suggestions().size();
        var modelStatus = "NOT_CONFIGURED";
        var recognitionStatus = "REVIEW_REQUIRED";
        var recognitionCoverage = coverageValidator.physicalReport(
                workingParsed.structureSummary(), "模型语义识别尚未完成"
        );
        var modelQualityIssues = new java.util.ArrayList<RecognitionModelClient.QualityIssueSuggestion>();
        var modelCallCount = 0;
        var succeededCalls = 0;
        var failedCalls = 0;
        var truncatedCalls = 0;
        MDC.put("recognitionRunId", recognitionRunId.toString());
        log.info("recognition_run_started runId={} importJobId={} format={} scope={} sheets={}",
                recognitionRunId, importJobId, format, scope,
                workingParsed.structureSummary().path("sheets").size());
        if (recognitionModelClient.isConfigured() && format == TemplateFormat.XLSX) {
            repository.updateProgress(importJobId, 65, "DISCOVERING_STRUCTURE_REGIONS");
            var staged = recognizeInStages(importJobId, recognitionRunId, format, sourceFileName,
                    workingParsed.structureSummary(), scope, requestedSheetId, requestedAddress, visualInput,
                    "STRUCTURE_DISCOVERY", "REGION_FIELDS");
            modelCallCount += staged.callCount();
            succeededCalls += staged.succeededCalls();
            failedCalls += staged.failedCalls();
            truncatedCalls += staged.truncatedCalls();
            modelQualityIssues.addAll(staged.batch().qualityIssues());
            repository.replaceModelSuggestions(importJobId, recognitionRunId,
                    batchByOrigin(staged.batch(), "MODEL"));
            repository.replacePhysicalSuggestions(importJobId, recognitionRunId,
                    batchByOrigin(staged.batch(), "PHYSICAL"));
            suggestionCount += staged.batch().suggestions().size();
            recognitionStatus = staged.recognitionStatus();
            recognitionCoverage = staged.coverageReport();
            var hasRecoveryDiagnostics = modelQualityIssues.stream().anyMatch(issue ->
                    issue.evidence() != null && issue.evidence().isArray()
                            && issue.evidence().findValue("internalRecovery") != null);
            modelStatus = truncatedCalls > 0 ? "TRUNCATED"
                    : failedCalls == 0 && !hasRecoveryDiagnostics ? "COMPLETED"
                    : failedCalls == 0 ? "PARTIAL"
                    : staged.batch().suggestions().isEmpty() && ruleBatch.suggestions().isEmpty() ? "FAILED" : "PARTIAL";
            if (!"COMPLETE".equals(recognitionStatus) && "COMPLETED".equals(modelStatus)) {
                modelStatus = "PARTIAL";
            }
            repository.completeRecognitionRun(recognitionRunId,
                    "TRUNCATED".equals(modelStatus) ? "PARTIAL" : modelStatus);
            if (failedCalls > 0) {
                issues.add(new OfficeStructureParser.ParseIssue(
                        "WARNING", "MODEL_RECOGNITION_FAILED",
                        "完整工作簿语义识别暂时失败，请检查模型协议或稍后重新识别。",
                        objectMapper.createObjectNode()
                ));
            }
        } else {
            repository.completeRecognitionRun(recognitionRunId, "COMPLETED");
            recognitionStatus = "REVIEW_REQUIRED";
            recognitionCoverage = coverageValidator.physicalReport(
                    workingParsed.structureSummary(), "未配置模型服务，物理结构未完成业务语义确认"
            );
            var physicalBatch = physicalStructureBatch(workingParsed.structureSummary());
            repository.replacePhysicalSuggestions(importJobId, recognitionRunId, physicalBatch);
            suggestionCount += physicalBatch.suggestions().size();
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
                qualityAnalysis, modelQualityIssues, "WORKBOOK".equals(scope)
        );
        if (qualityResolution.changed()) {
            workingParsed = reparsePatched(qualityResolution.snapshot(), workingParsed);
            var repairedSnapshotHash = canonicalizer.hash(workingParsed.initialEditorSnapshot());
            repository.updateRecognitionRunSnapshot(
                    recognitionRunId, repairedSnapshotHash, "RECOGNIZED_AFTER_REPAIR");
            repository.saveRenderSnapshot(importJobId, workingParsed.initialEditorSnapshot());
            repository.updateProgress(importJobId, 52, "RERENDERING_AFTER_REPAIR");
            visualRender = format == TemplateFormat.XLSX
                    ? visualRenderer.render(importJobId)
                    : TemplateVisualRenderer.RenderResult.unavailable(
                            "NOT_APPLICABLE", "当前格式不执行 Excel 视觉渲染"
                    );
            visualInput = visualRender.rendered() ? visualRender.modelNode(objectMapper) : null;
            ruleBatch = ruleRecognitionEngine.recognize(format, sourceFileName, workingParsed.structureSummary());
            // A quality patch can change merges, labels or table boundaries. Reuse the
            // same audit run but replace model candidates with results based on the
            // final physical snapshot; stale pre-repair candidates must not survive.
            modelQualityIssues.clear();
            if (recognitionModelClient.isConfigured() && format == TemplateFormat.XLSX
                    && !modelRegions.isEmpty()) {
                // An automatic physical patch invalidates the previous model
                // proposal. Do not silently run a second recognition pipeline
                // inside the same audit run; the user can explicitly start a
                // fresh run against the patched snapshot.
                repository.replaceModelSuggestions(importJobId, recognitionRunId,
                        new RecognitionModelClient.RecognitionBatch(List.of(), List.of(), "", "", "", "", null));
                var physicalBatch = physicalStructureBatch(workingParsed.structureSummary());
                repository.replacePhysicalSuggestions(importJobId, recognitionRunId, physicalBatch);
                suggestionCount = ruleBatch.suggestions().size() + physicalBatch.suggestions().size();
                modelStatus = "PARTIAL";
                recognitionStatus = "REVIEW_REQUIRED";
                recognitionCoverage = coverageValidator.physicalReport(
                        workingParsed.structureSummary(), "结构已自动修复；请基于新快照重新发起识别"
                );
            } else {
                var physicalBatch = physicalStructureBatch(workingParsed.structureSummary());
                repository.replacePhysicalSuggestions(importJobId, recognitionRunId, physicalBatch);
                suggestionCount = ruleBatch.suggestions().size() + physicalBatch.suggestions().size();
            }
            var repairedQualityAnalysis = qualityAnalyzer.analyze(
                    workingParsed.structureSummary(), workingParsed.initialEditorSnapshot(),
                    mappedLocations(importJobId, organizationId), false
            );
            qualityResolution = resolveQualityIssues(repairedQualityAnalysis, modelQualityIssues, false);
        }
        if (!"COMPLETE".equals(recognitionStatus)
                && issues.stream().noneMatch(issue -> "RECOGNITION_INCOMPLETE".equals(issue.code()))) {
            issues.add(new OfficeStructureParser.ParseIssue(
                    "WARNING", "RECOGNITION_INCOMPLETE",
                    "物理结构已解析，但语义识别覆盖不完整；结果只能作为待确认候选。",
                    recognitionCoverage.deepCopy()
            ));
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
        result.set("visualRender", visualRender.auditNode(objectMapper));
        result.put("modelStatus", modelStatus);
        result.put("recognitionStatus", recognitionStatus);
        result.put("reviewResolutionStatus", "COMPLETE".equals(recognitionStatus) ? "NOT_REQUIRED" : "OPEN");
        result.put("resolutionSource", "COMPLETE".equals(recognitionStatus) ? "AUTO" : "");
        result.put("canonicalStatus", "COMPLETE".equals(recognitionStatus) ? "CONFIRMED" : "PROVISIONAL");
        result.put("publicationReadiness", "COMPLETE".equals(recognitionStatus) ? "READY" : "NOT_READY");
        result.set("recognitionCoverage", recognitionCoverage);
        result.put("suggestionCount", suggestionCount);
        result.put("qualityIssueCount", qualityResolution.issues().size());
        result.put("autoFixedCount", qualityResolution.issues().stream()
                .filter(issue -> "AUTO_APPLIED".equals(issue.status())).count());
        repository.saveImportResult(importJobId, result);
        if ("PARTIAL".equals(modelStatus) || "FAILED".equals(modelStatus) || "TRUNCATED".equals(modelStatus)) {
            log.warn("recognition_run_partial runId={} importJobId={} status={} modelCalls={} succeededCalls={} failedCalls={} modelSuggestions={} ruleSuggestions={} qualityIssues={}",
                    recognitionRunId, importJobId, modelStatus, modelCallCount, succeededCalls, failedCalls,
                    Math.max(0, suggestionCount - ruleBatch.suggestions().size()),
                    ruleBatch.suggestions().size(), qualityResolution.issues().size());
        }
        log.info("recognition_run_finished runId={} importJobId={} status={} callCount={} succeededCalls={} failedCalls={} suggestionCount={} qualityIssueCount={}",
                recognitionRunId, importJobId, modelStatus, modelCallCount, succeededCalls, failedCalls,
                suggestionCount, qualityResolution.issues().size());
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

    private StagedModelResult recognizeInStages(
            UUID importJobId, UUID recognitionRunId, TemplateFormat format, String sourceFileName,
            JsonNode structure, String scope, String requestedSheetId, String requestedAddress,
            JsonNode visualInput, String structurePhase, String regionPhase
    ) {
        var accumulator = new ModelStageAccumulator();
        RecognitionModelClient.RecognitionBatch global = null;
        var globalSucceeded = false;
        var globalFailed = false;
        try {
            global = recognitionModelClient.recognize(new RecognitionModelClient.RecognitionRequest(
                    importJobId, recognitionRunId, format, sourceFileName, "workbook-structure",
                    globalContext(structure, scope, requestedSheetId, requestedAddress), visualInput, structurePhase
            ));
            globalSucceeded = true;
            collectStage(accumulator, recognitionRunId, global, true, List.of(), null);
        } catch (RecognitionModelClient.RecognitionCallException exception) {
            globalFailed = true;
            collectFailure(accumulator, recognitionRunId, importJobId, "workbook-structure", structurePhase,
                    exception, "结构原语发现调用失败");
        } catch (Exception exception) {
            globalFailed = true;
            collectFailure(accumulator, recognitionRunId, importJobId, "workbook-structure", structurePhase,
                    exception, "结构原语发现未执行，请检查模型配置与响应协议");
        }

        var semanticModel = global == null ? null : global.suggestions().stream()
                .filter(item -> "SEMANTIC_MODEL".equals(item.suggestionType()))
                .findFirst().map(RecognitionModelClient.ModelSuggestion::payload).orElse(null);
        var regions = physicalCanonicalRegions(structure, global);
        var conflictGroups = new HashSet<String>();
        for (var region : regions) {
            var groupId = region.path("resolutionGroupId").asText("");
            if (!groupId.isBlank() && region.path("structureConflict").asBoolean(false)
                    && conflictGroups.add(groupId)) {
                accumulator.qualityIssues.add(new RecognitionModelClient.QualityIssueSuggestion(
                        "STRUCTURE_CANDIDATE_CONFLICT", "WARNING", region.path("sheetId").asText(""), "",
                        region.path("range").asText(""), "结构候选存在冲突",
                        "物理结构提议与模型结构提议无法自动确认，必须选择一个结构方案。",
                        "该区域不会自动发布。", 0.95, false, null, null,
                        objectMapper.createObjectNode().put("resolutionGroupId", groupId)
                                .set("modelAlternatives", region.path("modelAlternatives").deepCopy()),
                         "DETECTED", groupId, null));
            }
        }
        var canonicalRegions = regions.stream()
                .filter(region -> "CONFIRMED".equals(region.path("canonicalStatus").asText())
                        && "CONFIRMED".equals(region.path("structureStatus").asText()))
                .toList();
        repository.updateRecognitionRunRegionCount(recognitionRunId, canonicalRegions.size());

        // All region semantics are sent in one batch. Geometry is immutable
        // context; the semantic model cannot create a new table or alter a
        // resolved range.
        if (!canonicalRegions.isEmpty() && globalSucceeded) {
            try {
                var batchContext = semanticViewBuilder.build(structure, scope, requestedSheetId, requestedAddress);
                var semanticRegions = batchContext.putArray("semanticRegions");
                for (var region : canonicalRegions) semanticRegions.add(semanticRegionContext(region));
                var batch = recognitionModelClient.recognize(new RecognitionModelClient.RecognitionRequest(
                        importJobId, recognitionRunId, format, sourceFileName, "workbook-regions",
                        batchContext, visualInput, regionPhase
                ));
                collectStage(accumulator, recognitionRunId, batch, false, canonicalRegions, structure);
                for (var region : canonicalRegions) accumulator.regionStates.put(regionKey(region), "SUCCEEDED");
                repository.updateProgress(importJobId, 84, "RECOGNIZING_REGION_FIELDS");
            } catch (RecognitionModelClient.RecognitionCallException exception) {
                for (var region : canonicalRegions) accumulator.regionStates.put(regionKey(region), "FAILED");
                collectFailure(accumulator, recognitionRunId, importJobId, "workbook-regions", regionPhase,
                        exception, "批量区域语义识别调用失败");
            } catch (Exception exception) {
                for (var region : canonicalRegions) accumulator.regionStates.put(regionKey(region), "FAILED");
                collectFailure(accumulator, recognitionRunId, importJobId, "workbook-regions", regionPhase,
                        exception, "批量区域语义识别未执行，请检查模型配置与响应协议");
            }
        } else {
            for (var region : canonicalRegions) accumulator.regionStates.put(regionKey(region), "NOT_SCHEDULED");
        }
        var coverage = coverageValidator.assess(
                structure, canonicalRegions, accumulator.regionStates, accumulator.suggestions,
                globalSucceeded, globalFailed
        );
        var structureDiagnostics = accumulator.qualityIssues.stream().anyMatch(issue ->
                issue.issueType().startsWith("INVALID_STRUCTURE")
                        || issue.issueType().startsWith("STRUCTURE_")
                        || "SEMANTIC_REGION_NOT_CANONICAL".equals(issue.issueType()));
        if (!"COMPLETE".equals(coverage.status()) || structureDiagnostics) {
            markSemanticSuggestionsForReview(accumulator, coverage.status());
        }
        // Keep the physical structure visible for review even when the model
        // omits the region or the region call fails. This is not considered a
        // semantic success by the coverage assessment above.
        addPhysicalStructureSuggestions(accumulator, regions, structure);
        var last = accumulator.lastBatch;
        var batch = new RecognitionModelClient.RecognitionBatch(
                List.copyOf(accumulator.suggestions), List.copyOf(accumulator.qualityIssues),
                last == null ? "openai-compatible" : last.provider(),
                last == null ? "global-semantic-model" : last.model(),
                last == null ? "template-global-semantic-v3" : last.promptVersion(),
                last == null ? "" : last.requestHash(), last == null ? "" : last.responseHash()
        );
        var stagedStatus = structureDiagnostics ? "REVIEW_REQUIRED" : coverage.status();
        return new StagedModelResult(batch, accumulator.callCount, accumulator.succeededCalls,
                accumulator.failedCalls, accumulator.truncatedCalls,
                coverage.report(), stagedStatus);
    }

    private ObjectNode semanticRegionContext(JsonNode region) {
        var result = objectMapper.createObjectNode()
                .put("regionId", region.path("blockId").asText(region.path("temporaryId").asText(
                        region.path("candidateId").asText())))
                .put("sheetId", region.path("sheetId").asText())
                .put("range", region.path("range").asText())
                .put("type", region.path("type").asText("UNKNOWN"))
                .put("businessName", region.path("businessName").asText(""))
                .put("canonicalStatus", region.path("canonicalStatus").asText("PROVISIONAL"))
                .put("recordAxis", region.path("structure").path("recordAxis").asText("UNKNOWN"));
        result.set("structure", region.path("structure").deepCopy());
        result.set("resolution", region.path("resolution").deepCopy());
        for (var key : List.of("candidateOnly", "physicalStructureOnly", "reviewRequired",
                "structureConflict", "resolutionGroupId", "pendingReason", "structureStatus")) {
            if (region.has(key)) result.set(key, region.path(key).deepCopy());
        }
        result.put("runtimeColumnHeader", "MATRIX".equals(region.path("type").asText())
                && region.path("structure").path("columnHeaderRange").isTextual());
        return result;
    }

    private void markSemanticSuggestionsForReview(ModelStageAccumulator accumulator, String status) {
        for (int index = 0; index < accumulator.suggestions.size(); index++) {
            var suggestion = accumulator.suggestions.get(index);
            var payload = suggestion.payload().deepCopy();
            if (!(payload instanceof ObjectNode object)
                    || "PHYSICAL_STRUCTURE".equals(object.path("recognitionOrigin").asText(""))) continue;
            object.put("reviewRequired", true)
                    .put("recognitionCoverageStatus", status);
            if (object.path("pendingReason").asText("").isBlank()) {
                object.put("pendingReason", "RECOGNITION_COVERAGE_INCOMPLETE");
            }
            accumulator.suggestions.set(index, new RecognitionModelClient.ModelSuggestion(
                    suggestion.suggestionType(), object, suggestion.confidence(), suggestion.evidence()));
        }
    }

    private List<JsonNode> physicalCanonicalRegions(
            JsonNode structure, RecognitionModelClient.RecognitionBatch global
    ) {
        var semanticModel = global == null ? null : global.suggestions().stream()
                .filter(item -> "SEMANTIC_MODEL".equals(item.suggestionType()))
                .findFirst().map(RecognitionModelClient.ModelSuggestion::payload).orElse(null);
        var resolved = structureProposalResolver.resolve(
                structure, coverageValidator.physicalRegions(structure), semanticModel);
        var result = new ArrayList<JsonNode>();
        for (var region : resolved.path("regions")) {
            var copy = region.deepCopy();
            if (copy instanceof ObjectNode object) {
                var type = object.path("type").asText("UNKNOWN");
                var sheetId = object.path("sheetId").asText("");
                var range = object.path("range").asText("");
                object.put("blockId", RecognitionIdentity.blockId(sheetId, range, type, ""))
                        .put("temporaryId", "structure-" + RecognitionIdentity.shortHash(
                                sheetId + "|" + range + "|" + type, 12))
                        .put("businessName", object.path("businessName").asText(
                                "MATRIX".equals(type) ? "交叉表区域"
                                        : "FORM_REGION".equals(type) ? "基本信息区域" : "重复记录区域"))
                        .put("structureSource", object.path("source").asText("STRUCTURE_PROPOSAL"));
                if (object.path("structure").isObject()) object.with("structure").put("range", range);
            }
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private void mergeAssessedGeometry(ObjectNode canonical, JsonNode assessment) {
        var structure = canonical.with("structure");
        for (var key : List.of("rowHeaderRange", "columnHeaderRange", "crossDataRange", "recordAxis")) {
            var value = assessment.path(key).asText("");
            if (!value.isBlank() && !"UNKNOWN".equals(value)) structure.put(key, value);
        }
    }

    private List<JsonNode> mergeRecognitionRegions(
            List<JsonNode> physicalRegions, JsonNode semanticModel
    ) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (var region : physicalRegions) result.put(regionKey(region), region);
        if (semanticModel != null) {
            for (var region : fieldRegions(semanticModel)) {
                var overlapsPhysical = physicalRegions.stream().anyMatch(physical ->
                        rangesOverlap(physical.path("range").asText(""), region.path("range").asText("")));
                if (overlapsPhysical && !isStaticStructureType(region.path("type").asText(""))) continue;
                result.putIfAbsent(regionKey(region), region);
            }
        }
        return List.copyOf(result.values());
    }

    private String regionKey(JsonNode region) {
        return region.path("sheetId").asText("") + "|"
                + region.path("range").asText("") + "|"
                + region.path("type").asText(region.path("blockType").asText(""));
    }

    private void addPhysicalStructureSuggestions(
            ModelStageAccumulator accumulator, List<JsonNode> physicalRegions, JsonNode structure
    ) {
        for (var region : physicalRegions) {
            var key = regionKey(region);
            var alreadyHasRegion = accumulator.suggestions.stream()
                    .filter(item -> ("FORM_REGION".equals(region.path("type").asText(""))
                            ? "SCALAR_FIELD".equals(item.suggestionType())
                            : isTableSuggestion(item)))
                    .anyMatch(item -> key.equals(regionKeyFromPayload(item.payload(), region)));
            if (alreadyHasRegion && !region.path("structureConflict").asBoolean(false)) continue;
            var payload = physicalStructurePayload(region, structure);
            if (payload == null) continue;
            var suggestionType = "FORM_REGION".equals(region.path("type").asText(""))
                    ? "SCALAR_FIELD" : "TABLE_REGION";
            accumulator.suggestions.add(new RecognitionModelClient.ModelSuggestion(
                    suggestionType, payload,
                    physicalConfidence(region),
                    objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                            .put("source", "PHYSICAL_STRUCTURE_PRIMITIVE")
                            .put("reviewRequired", true)
                            .put("reason", "模型未返回完整的区域语义结构"))
            ));
            if (region.path("structureConflict").asBoolean(false)) {
                var alternatives = region.path("modelAlternatives");
                if (alternatives.isArray()) for (var proposal : alternatives) {
                    var proposedType = proposal.path("type").asText("");
                    var proposedRange = proposal.path("range").asText("");
                    if (proposedType.isBlank() || proposedRange.isBlank()
                            || !Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE", "FORM_REGION").contains(proposedType)) continue;
                    var alternative = objectMapper.createObjectNode()
                            .put("kind", proposedType).put("tableKind", proposedType)
                            .put("blockType", proposedType).put("fieldName", "模型建议结构")
                            .put("candidateRef", proposal.path("proposalId").asText())
                            .put("resolutionGroupId", region.path("resolutionGroupId").asText())
                            .put("structureConflict", true).put("structureStatus", "CONFLICT")
                            .put("candidateOnly", true).put("reviewRequired", true)
                            .put("pendingReason", "STRUCTURE_CONFLICT")
                            .put("recognitionOrigin", "MODEL_STRUCTURE_ASSESSMENT")
                             .put("alternativeRole", "MODEL");
                    var details = proposal.path("proposal").isObject() ? proposal.path("proposal") : proposal;
                    for (var detailKey : List.of("cornerRange", "rowHeaderRange", "columnHeaderRange", "crossDataRange", "recordAxis")) {
                        var value = details.path(detailKey).asText("");
                        if (!value.isBlank()) alternative.put(detailKey, value);
                    }
                    alternative.put("canonicalStatus", "PROVISIONAL")
                            .put("structureStatus", "CONFLICT")
                            .put("publishable", false);
                    alternative.set("locator", objectMapper.createObjectNode()
                            .put("sheetId", region.path("sheetId").asText())
                            .put("range", proposedRange).put("address", proposedRange)
                            .put("locatorType", proposedType.equals("MATRIX") ? "MATRIX_REGION" : "TABLE_REGION"));
                    alternative.set("columns", objectMapper.createArrayNode());
                    accumulator.suggestions.add(new RecognitionModelClient.ModelSuggestion(
                            "TABLE_REGION", alternative, 0.5,
                            objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                                    .put("source", "MODEL_STRUCTURE_PROPOSAL")
                                    .put("verdict", "CONFLICT"))));
                }
            }
        }
    }

    private String regionKeyFromPayload(JsonNode payload, JsonNode region) {
        var payloadType = payload.path("kind").asText(payload.path("tableKind").asText(
                region.path("type").asText("")));
        // FORM_REGION is represented as a scalar suggestion after semantic
        // compilation. Keep it comparable with the physical form candidate.
        if ("FORM_REGION".equals(region.path("type").asText(""))
                && "SCALAR".equals(payloadType)) {
            payloadType = "FORM_REGION";
        }
        return payload.path("locator").path("sheetId").asText(region.path("sheetId").asText("")) + "|"
                + payload.path("locator").path("range").asText(region.path("range").asText("")) + "|"
                + payloadType;
    }

    private double physicalConfidence(JsonNode region) {
        return region.path("confidence").asDouble(0.86);
    }

    private ObjectNode physicalStructurePayload(JsonNode region, JsonNode structure) {
        var type = region.path("type").asText("");
        var sheetId = region.path("sheetId").asText("");
        var range = region.path("range").asText("");
        var details = region.path("structure");
        var structureOrigin = "MODEL".equals(region.path("source").asText())
                ? "MODEL_STRUCTURE_PROPOSAL" : "PHYSICAL_STRUCTURE";
        var resolved = "CONFIRMED".equals(region.path("canonicalStatus").asText())
                && "CONFIRMED".equals(region.path("structureStatus").asText());
        if ("FORM_REGION".equals(type)) {
            var labelRange = details.path("labelRange").asText("");
            var valueRange = details.path("valueRange").asText("");
            if (labelRange.isBlank() || valueRange.isBlank()) return null;
            var relationId = RecognitionIdentity.relationId(sheetId, labelRange, valueRange, "FORM_REGION");
            var fieldId = RecognitionIdentity.fieldId(relationId);
            var payload = objectMapper.createObjectNode()
                    .put("kind", "SCALAR")
                    .put("relationId", relationId)
                    .put("fieldId", fieldId.toString())
                    .put("bindingId", RecognitionIdentity.bindingId(fieldId, "CELL_RANGE", sheetId + "|" + valueRange).toString())
                    .put("blockId", region.path("blockId").asText(""))
                    .put("regionId", region.path("blockId").asText(""))
                    .put("candidateRef", region.path("candidateId").asText(region.path("blockId").asText("")))
                    .put("blockType", "FORM_REGION")
                    .put("blockName", region.path("businessName").asText("基本信息区域"))
                    .put("fieldName", labelText(structure, sheetId, labelRange, "待确认字段"))
                    .put("valueType", "string")
                    .put("role", "FIELD")
                    .put("required", false)
                     .put("editability", "EDITABLE")
                     .put("valueSource", "USER_INPUT")
                     .put("canonicalStatus", region.path("canonicalStatus").asText("PROVISIONAL"))
                     .put("structureStatus", region.path("structureStatus").asText("PROVISIONAL"))
                      .put("recognitionOrigin", structureOrigin);
            if (resolved) {
                payload.put("reviewRequired", false).put("publishable", true);
            } else {
                payload.put("candidateOnly", true).put("reviewRequired", true)
                        .put("physicalStructureOnly", true)
                        .put("pendingReason", "MODEL_RECOGNITION_REQUIRED");
            }
            payload.set("locator", objectMapper.createObjectNode()
                    .put("sheetId", sheetId).put("address", valueRange).put("range", region.path("range").asText(valueRange))
                    .put("labelRange", labelRange).put("valueRange", valueRange).put("labelAddress", labelRange)
                    .put("locatorType", "CELL_RANGE"));
            return payload;
        }
        var headerRange = details.path("headerRange").asText("");
        var dataRange = details.path("dataRange").asText("");
        if (headerRange.isBlank() || dataRange.isBlank()) return null;
        var relationId = RecognitionIdentity.relationId(sheetId, headerRange, dataRange, type);
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var payload = objectMapper.createObjectNode()
                .put("kind", type)
                .put("tableKind", type)
                .put("relationId", relationId)
                .put("fieldId", fieldId.toString())
                 .put("blockId", region.path("blockId").asText(""))
                 .put("regionId", region.path("blockId").asText(""))
                 .put("candidateRef", region.path("candidateId").asText(region.path("blockId").asText("")))
                .put("blockType", type)
                .put("blockName", region.path("businessName").asText(""))
                .put("fieldName", region.path("businessName").asText(""))
                .put("groupName", region.path("groupName").asText(""))
                .put("valueType", "array")
                .put("role", "REPEAT_REGION")
                 .put("required", false)
                 .put("editability", "EDITABLE")
                 .put("valueSource", "USER_INPUT")
                  .put("resolutionGroupId", region.path("resolutionGroupId").asText(""))
                  .put("structureStatus", region.path("structureStatus").asText("PROVISIONAL"))
                  .put("canonicalStatus", region.path("canonicalStatus").asText("PROVISIONAL"))
                  .put("recognitionOrigin", structureOrigin)
                .put("repeatAxis", details.path("repeatAxis").asText(
                        "COLUMN_TABLE".equals(type) ? "COLUMN" : "ROW"))
                .put("recordHeight", details.path("recordHeight").asInt(1))
                 .put("recordWidth", details.path("recordWidth").asInt(1))
                 .put("recordStride", details.path("recordStride").asInt(1));
        if (resolved) {
            payload.put("reviewRequired", false).put("publishable", true);
        } else {
            payload.put("candidateOnly", true).put("reviewRequired", true)
                    .put("physicalStructureOnly", true)
                    .put("pendingReason", "MODEL_RECOGNITION_REQUIRED");
        }
        var bindingId = RecognitionIdentity.bindingId(fieldId, "TABLE_REGION", sheetId + "|" + range);
        payload.put("bindingId", bindingId.toString());
        var locator = objectMapper.createObjectNode()
                .put("sheetId", sheetId)
                .put("address", range)
                .put("range", range)
                .put("headerRange", headerRange)
                .put("dataRange", dataRange)
                .put("logicalInputRange", dataRange)
                .put("locatorType", "MATRIX".equals(type) ? "MATRIX_REGION" : "TABLE_REGION");
        payload.set("locator", locator);
        payload.set("columns", objectMapper.createArrayNode());
        if (details.path("terminationRule").isObject()) {
            payload.set("terminationRule", details.path("terminationRule").deepCopy());
        }
        if ("MATRIX".equals(type)) {
            normalizeMatrixRegion(payload, region, structure);
            payload.put("canonicalStatus", region.path("canonicalStatus").asText("PROVISIONAL"))
                    .put("structureStatus", region.path("structureStatus").asText("PROVISIONAL"))
                    .put("canonicalStructureRequired", false)
                    .put("canonicalStructureMayReopen", !resolved)
                   .put("recognitionOrigin", structureOrigin);
            if (resolved) {
                payload.remove("candidateOnly");
                payload.remove("physicalStructureOnly");
                payload.remove("pendingReason");
                payload.put("reviewRequired", false).put("publishable", true);
            } else {
                payload.put("candidateOnly", true).put("reviewRequired", true)
                        .put("physicalStructureOnly", true)
                        .put("pendingReason", "MODEL_RECOGNITION_REQUIRED");
            }
        }
        return payload;
    }

    private RecognitionModelClient.RecognitionBatch physicalStructureBatch(JsonNode structure) {
        var accumulator = new ModelStageAccumulator();
        addPhysicalStructureSuggestions(accumulator, physicalCanonicalRegions(structure, null), structure);
        return new RecognitionModelClient.RecognitionBatch(
                List.copyOf(accumulator.suggestions), List.of(), "physical-structure", "", "", "", ""
        );
    }

    private RecognitionModelClient.RecognitionBatch batchByOrigin(
            RecognitionModelClient.RecognitionBatch batch, String origin
    ) {
        var physical = "PHYSICAL".equals(origin);
        var suggestions = batch.suggestions().stream()
                .filter(item -> physical == "PHYSICAL_STRUCTURE".equals(
                        item.payload().path("recognitionOrigin").asText("")))
                .toList();
        return new RecognitionModelClient.RecognitionBatch(
                suggestions,
                physical ? List.of() : batch.qualityIssues(),
                batch.provider(), batch.model(), batch.promptVersion(), batch.requestHash(), batch.responseHash(),
                batch.callTrace(), batch.callTraces()
        );
    }

    private void collectStage(
            ModelStageAccumulator accumulator, UUID recognitionRunId,
            RecognitionModelClient.RecognitionBatch batch, boolean structureStage, List<JsonNode> canonicalRegions,
            JsonNode physicalStructure
    ) {
        accumulator.lastBatch = batch;
        for (var trace : batch.callTraces()) {
            repository.saveRecognitionCall(recognitionRunId, trace);
            accumulator.callCount++;
            if ("SUCCEEDED".equals(trace.status())) accumulator.succeededCalls++;
            if (trace.responseTruncated() || "MODEL_OUTPUT_TRUNCATED".equals(trace.outcomeCode())) {
                accumulator.truncatedCalls++;
            }
            if (trace.callId() != null) MDC.put("recognitionCallId", trace.callId().toString());
        }
        var callId = batch.callTrace() == null ? null : batch.callTrace().callId();
        for (var issue : batch.qualityIssues()) accumulator.qualityIssues.add(withCall(issue, callId));
        for (var suggestion : batch.suggestions()) {
            if (structureStage && !"SEMANTIC_MODEL".equals(suggestion.suggestionType())) continue;
            if (!structureStage && "SEMANTIC_MODEL".equals(suggestion.suggestionType())) continue;
            var canonicalRegion = structureStage ? null : canonicalRegionFor(suggestion, canonicalRegions);
            if (!structureStage && canonicalRegion == null) {
                accumulator.qualityIssues.add(new RecognitionModelClient.QualityIssueSuggestion(
                        "SEMANTIC_REGION_NOT_CANONICAL", "WARNING", "", "", "",
                        "区域语义未绑定到 Canonical Region",
                        "第二阶段返回的区域标识未对应已确认的 Canonical Region，已保留待复核。",
                        "该区域不会进入正式字段编译。", 0.95, false, null, null,
                        objectMapper.createObjectNode().put("regionId", suggestion.payload().path("regionId")
                                 .asText(suggestion.payload().path("blockId").asText(""))), "DETECTED", "", callId));
                continue;
            }
            if (!structureStage && canonicalRegion != null
                    && "MATRIX".equals(canonicalRegion.path("type").asText())
                    && (!isTableSuggestion(suggestion) || "TABLE_CHILD_FIELD".equals(suggestion.suggestionType()))) {
                // A matrix's row dimensions and measure are physical axes, not
                // independent scalar fields. Keep only the table envelope; the
                // canonical matrix builder emits the axis bindings below.
                continue;
            }
            var payload = suggestion.payload().deepCopy();
            if (payload instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                object.put("analysisScope", structureStage ? "STRUCTURE_DISCOVERY" : "REGION_FIELDS");
                if (callId != null) object.put("recognitionCallId", callId.toString());
                if (!structureStage && canonicalRegion != null) {
                    var candidateId = canonicalRegion.path("candidateId").asText(
                            canonicalRegion.path("blockId").asText(canonicalRegion.path("temporaryId").asText("")));
                    object.put("candidateRef", candidateId);
                    var structuralParent = isStructuralRegionSuggestion(suggestion);
                    if (structuralParent && canonicalRegion.path("resolutionGroupId").isTextual()
                            && !canonicalRegion.path("resolutionGroupId").asText().isBlank()) {
                        object.put("resolutionGroupId", canonicalRegion.path("resolutionGroupId").asText());
                    }
                    if (structuralParent && canonicalRegion.path("structureConflict").asBoolean(false)) {
                        object.put("structureConflict", true)
                                .put("reviewRequired", true)
                                .put("pendingReason", "STRUCTURE_CONFLICT")
                                .put("structureStatus", "CONFLICT");
                    }
                    var confirmed = "CONFIRMED".equals(canonicalRegion.path("canonicalStatus").asText())
                            && "CONFIRMED".equals(canonicalRegion.path("structureStatus").asText("CONFIRMED"));
                    if (confirmed) bindToCanonicalRegion(object, canonicalRegion);
                    if (confirmed && physicalStructure != null
                            && "MATRIX".equals(canonicalRegion.path("type").asText())
                            && isTableSuggestion(suggestion)) {
                        normalizeMatrixRegion(object, canonicalRegion, physicalStructure);
                    }
                }
            }
            var normalizedSuggestion = new RecognitionModelClient.ModelSuggestion(
                    suggestion.suggestionType(), payload, suggestion.confidence(), suggestion.evidence()
            );
            if (duplicateMatrixSuggestion(accumulator, canonicalRegion, suggestion, payload, structureStage)) {
                continue;
            }
            accumulator.suggestions.add(normalizedSuggestion);
        }
    }

    private JsonNode canonicalRegionFor(
            RecognitionModelClient.ModelSuggestion suggestion, List<JsonNode> canonicalRegions
    ) {
        var payload = suggestion.payload();
        var ids = List.of(payload.path("regionId").asText(""), payload.path("blockId").asText(""),
                payload.path("candidateRef").asText(""));
        for (var region : canonicalRegions) {
            var regionId = region.path("blockId").asText(region.path("temporaryId").asText(
                    region.path("candidateId").asText("")));
            if (ids.stream().anyMatch(id -> !id.isBlank() && id.equals(regionId))) return region;
            var locator = payload.path("locator");
            if (region.path("sheetId").asText("").equals(locator.path("sheetId").asText(""))
                    && region.path("range").asText("").equals(locator.path("range").asText(""))) return region;
        }
        return null;
    }

    private boolean duplicateMatrixSuggestion(
            ModelStageAccumulator accumulator, JsonNode canonicalRegion,
            RecognitionModelClient.ModelSuggestion original, JsonNode normalizedPayload,
            boolean structureStage
    ) {
        if (structureStage || canonicalRegion == null
                || !"MATRIX".equals(canonicalRegion.path("type").asText())
                || !isTableSuggestion(original)) {
            return false;
        }
        var relationId = normalizedPayload.path("relationId").asText("");
        if (relationId.isBlank()) return false;
        return accumulator.suggestions.stream()
                .filter(existing -> isTableSuggestion(existing))
                .anyMatch(existing -> relationId.equals(existing.payload().path("relationId").asText("")));
    }

    private boolean isTableSuggestion(RecognitionModelClient.ModelSuggestion suggestion) {
        var type = suggestion.suggestionType();
        var kind = suggestion.payload().path("kind").asText(suggestion.payload().path("tableKind").asText(""));
        return Set.of("ROW_TABLE", "COLUMN_TABLE", "MATRIX", "TABLE_REGION", "TABLE_FIELD")
                .contains(type) || Set.of("ROW_TABLE", "COLUMN_TABLE", "MATRIX").contains(kind);
    }

    private boolean isStructuralRegionSuggestion(RecognitionModelClient.ModelSuggestion suggestion) {
        var origin = suggestion.payload().path("recognitionOrigin").asText("");
        if (!Set.of("PHYSICAL_STRUCTURE", "MODEL_STRUCTURE_ASSESSMENT").contains(origin)) return false;
        if ("CHILD".equals(suggestion.payload().path("suggestionLevel").asText(""))) return false;
        var type = suggestion.suggestionType();
        var kind = suggestion.payload().path("kind")
                .asText(suggestion.payload().path("tableKind").asText(""));
        return Set.of("TABLE_REGION", "MATRIX", "ROW_TABLE", "COLUMN_TABLE").contains(type)
                || Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE").contains(kind);
    }

    /**
     * Stage one owns the structure primitive. If the region model returns a
     * ROW_TABLE for a canonical MATRIX, retain its business evidence but repair
     * the structural envelope from physical facts. This prevents the compiler
     * from inferring A/B row labels as ordinary table columns.
     */
    private void normalizeMatrixRegion(ObjectNode payload, JsonNode canonicalRegion, JsonNode structure) {
        var matrix = canonicalRegion.path("structure");
        var range = canonicalRegion.path("range").asText("");
        var cornerRange = matrix.path("cornerRange").asText("");
        var rowHeaderRange = matrix.path("rowHeaderRange").asText("");
        var columnHeaderRange = matrix.path("columnHeaderRange").asText("");
        var crossDataRange = matrix.path("crossDataRange").asText("");
        var regionBounds = cellBounds(range);
        var corner = cellBounds(cornerRange);
        var rowHeader = cellBounds(rowHeaderRange);
        var columnHeader = cellBounds(columnHeaderRange);
        var crossData = cellBounds(crossDataRange);
        if (regionBounds == null || corner == null || rowHeader == null || columnHeader == null
                || crossData == null || !inside(regionBounds, corner) || !inside(regionBounds, rowHeader)
                || !inside(regionBounds, columnHeader) || !inside(regionBounds, crossData)) return;
        var resolved = "CONFIRMED".equals(canonicalRegion.path("canonicalStatus").asText())
                && "CONFIRMED".equals(canonicalRegion.path("structureStatus").asText());
        var sheetId = canonicalRegion.path("sheetId").asText(payload.path("locator").path("sheetId").asText(""));
        var recordAxis = matrix.path("recordAxis").asText(payload.path("recordAxis").asText("UNKNOWN"));
        if (!Set.of("ROW", "COLUMN", "UNKNOWN").contains(recordAxis)) recordAxis = "UNKNOWN";
        var relationId = RecognitionIdentity.relationId(sheetId, columnHeaderRange, crossDataRange, "MATRIX");
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var bindingId = RecognitionIdentity.bindingId(fieldId, "MATRIX_REGION",
                sheetId + "|" + range);
        var projection = matrixCompiler.recordProjection(corner[3], crossData[0], crossData[2], corner[3],
                crossData[1], crossData[3], recordAxis);
        var slots = "COLUMN".equals(recordAxis)
                ? matrixCompiler.columnSlots(sheetId, canonicalRegion.path("blockId").asText(""), range,
                crossData[0], crossData[2], corner[3], crossData[3])
                : objectMapper.createArrayNode();
        var rowSlots = "ROW".equals(recordAxis)
                ? matrixCompiler.rowSlots(sheetId, canonicalRegion.path("blockId").asText(""), range,
                crossData[1], crossData[3], Math.max(1, crossData[0] - 1), crossData[2])
                : objectMapper.createArrayNode();
        payload.put("kind", "MATRIX").put("relationId", relationId)
                .put("fieldId", fieldId.toString()).put("bindingId", bindingId.toString())
                .put("fieldName", canonicalRegion.path("businessName").asText(payload.path("fieldName").asText("矩阵区域")))
                .put("fieldCode", "AUTO.MATRIX_" + RecognitionIdentity.shortHash(relationId, 12).toUpperCase())
                 .put("valueType", "array").put("role", "REPEAT_REGION")
                 .put("editability", "EDITABLE").put("valueSource", "MIXED")
                 .put("templateStatus", "RUNTIME_INPUT").put("runtimeInputOnly", true)
                  .put("publishable", resolved)
                 .put("canonicalStatus", resolved ? "CONFIRMED" : "PROVISIONAL")
                 .put("canonicalStructureRequired", false)
                 .put("canonicalStructureMayReopen", !resolved)
                  .put("locatorType", "MATRIX_REGION").put("repeatAxis", recordAxis)
                 .put("semanticMode", "CROSS_TAB")
                 .put("recordAxis", recordAxis)
                 .put("recordHeight", projection.path("recordHeight").asInt(1))
                 .put("recordWidth", projection.path("recordWidth").asInt(1))
                 .put("recordStride", projection.path("recordStride").asInt(1))
                 .put("measureHeight", projection.path("measureHeight").asInt(1))
                .put("recordHeightIncludesIdentity", true)
                 .put("rowHeaderRange", rowHeaderRange)
                 .put("columnHeaderRange", columnHeaderRange)
                 .put("crossDataRange", crossDataRange)
                 .put("cornerRange", cornerRange)
                 .put("headerRange", columnHeaderRange)
                 .put("dataRange", crossDataRange)
                .put("matrixStructureSource", "PHYSICAL_FACTS_CANONICAL_REGION")
                .put("structureRecoveryCode", "REGION_MODEL_MATRIX_DOWNGRADE_REPAIRED");
        payload.withArray("columns").removeAll();
        // Empty runtime headers are expected in this template.  A canonical
        // physical matrix must not inherit the generic table candidate badge
        // merely because its protocol payload has columns=[] or a blank axis.
        if (resolved) {
            payload.remove("candidateOnly");
            payload.remove("pendingReason");
            payload.remove("physicalStructureOnly");
            payload.put("reviewRequired", false)
                    .put("structureStatus", "CONFIRMED");
        }
        var locator = payload.with("locator");
        locator.put("sheetId", sheetId)
                 .put("range", range)
                 .put("address", range)
                 .put("headerRange", columnHeaderRange)
                 .put("dataRange", crossDataRange)
                 .put("cornerRange", cornerRange)
                 .put("rowHeaderRange", rowHeaderRange)
                 .put("columnHeaderRange", columnHeaderRange)
                 .put("crossDataRange", crossDataRange)
                 .put("logicalInputRange", crossDataRange)
                 .put("recordRange", range)
                 .put("identityRange", columnHeaderRange)
                 .put("measureRange", crossDataRange)
                .put("columnMemberRole", "COLUMN_MEMBER_INPUT")
                .put("memberMode", "RUNTIME_INPUT");
        var regionId = canonicalRegion.path("blockId").asText(
                canonicalRegion.path("candidateId").asText(""));
        var compiled = matrixCompiler.compile(structure,
                new CanonicalMatrixCompiler.CanonicalMatrixGeometry(
                        sheetId, regionId, range, cornerRange, rowHeaderRange,
                        columnHeaderRange, crossDataRange, recordAxis,
                        resolved ? "CONFIRMED" : "PROVISIONAL"),
                new CanonicalMatrixCompiler.MatrixSemanticAssessment(
                        payload.path("rowDimensions"), payload.path("rowAttributes"),
                        objectMapper.createArrayNode()));
        payload.set("tableModel", compiled.tableModel());
        payload.set("longTableModel", compiled.longTableModel());
        payload.set("recordProjection", compiled.recordProjection());
        payload.set("columnSlots", compiled.columnSlots());
        payload.set("rowSlots", compiled.rowSlots());
        payload.set("matrixModel", compiled.matrixModel());
    }

    private String rowRole(
            JsonNode rowPath, JsonNode structure, String sheetId, int row,
            int startColumn, int endColumn, Set<String> seenRowPaths
    ) {
        var text = rowPath.toString().toLowerCase(java.util.Locale.ROOT);
        if (text.contains("平均") || text.contains("合计") || text.contains("total") || text.contains("average")) {
            return "AGGREGATE";
        }
        var formula = false;
        for (int column = startColumn; column <= endColumn; column++) {
            var cell = cellAt(structure, sheetId, column, row);
            formula |= cell != null && (cell.path("formula").isTextual()
                    || "FORMULA".equals(cell.path("factType").asText()));
        }
        if (formula) return "AGGREGATE";
        if (text.contains("重复") || text.contains("replicate")) return "REPLICATE";
        var signature = rowPath.toString();
        if (signature.replace("\"\"", "").isBlank()) return "UNKNOWN";
        if (!seenRowPaths.add(signature)) return "REPLICATE";
        var siblingPattern = normalizedSiblingPattern(rowPath);
        if (hasOrdinalToken(rowPath) && !seenRowPaths.add("PATTERN|" + siblingPattern)) return "REPLICATE";
        return "TEST_ITEM";
    }

    private String normalizedSiblingPattern(JsonNode rowPath) {
        var normalized = new StringBuilder();
        if (rowPath != null && rowPath.isArray()) {
            for (var value : rowPath) {
                var text = value.asText("").strip().toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("第?[0-9一二三四五六七八九十]+(次|组|号)?$", "")
                        .replaceAll("(?i)(replicate|repeat|sample|测量|重复)[-_ ]*[a-z0-9一二三四五六七八九十]*$", "");
                normalized.append('|').append(text);
            }
        }
        return normalized.toString();
    }

    private boolean hasOrdinalToken(JsonNode rowPath) {
        return rowPath != null && rowPath.toString().toLowerCase(java.util.Locale.ROOT).matches(
                ".*(第?[0-9一二三四五六七八九十]+|replicate|repeat|sample|测量|重复).*"
        );
    }

    private JsonNode cellAt(JsonNode structure, String sheetId, int column, int row) {
        var address = excelAddress(column, row);
        for (var sheet : structure.path("sheets")) {
            if (!sheetId.equals(sheet.path("id").asText(sheet.path("sheetId").asText("")))) continue;
            for (var cell : sheet.path("semanticCells")) {
                if (address.equalsIgnoreCase(cell.path("address").asText(""))) return cell;
                var merged = cellBounds(cell.path("mergedRange").asText(""));
                if (merged != null && merged[0] <= column && merged[1] <= row
                        && merged[2] >= column && merged[3] >= row) return cell;
            }
        }
        return null;
    }

    private String labelText(JsonNode structure, String sheetId, String labelRange, String fallback) {
        var bounds = cellBounds(labelRange);
        if (bounds == null) return fallback;
        var cell = cellAt(structure, sheetId, bounds[0], bounds[1]);
        var value = cell == null ? "" : cell.path("value").asText("").strip();
        if (value.endsWith(":" ) || value.endsWith("：")) {
            value = value.substring(0, value.length() - 1).strip();
        }
        return value.isBlank() ? fallback : value;
    }

    private int[] cellBounds(String range) {
        if (range == null || range.isBlank()) return null;
        var parts = range.toUpperCase(java.util.Locale.ROOT).split(":", 2);
        var start = cellAddress(parts[0]);
        var end = cellAddress(parts.length == 1 ? parts[0] : parts[1]);
        if (start == null || end == null) return null;
        return new int[]{Math.min(start[0], end[0]), Math.min(start[1], end[1]),
                Math.max(start[0], end[0]), Math.max(start[1], end[1])};
    }

    private int[] cellAddress(String value) {
        var matcher = java.util.regex.Pattern.compile("^([A-Z]+)([1-9][0-9]*)$").matcher(value);
        if (!matcher.matches()) return null;
        var column = 0;
        for (var character : matcher.group(1).toCharArray()) column = column * 26 + character - 'A' + 1;
        return new int[]{column, Integer.parseInt(matcher.group(2))};
    }

    private boolean inside(int[] outer, int[] inner) {
        return outer[0] <= inner[0] && outer[1] <= inner[1]
                && outer[2] >= inner[2] && outer[3] >= inner[3];
    }

    private String excelRange(int startColumn, int startRow, int endColumn, int endRow) {
        var start = excelAddress(startColumn, startRow);
        var end = excelAddress(endColumn, endRow);
        return start.equals(end) ? start : start + ":" + end;
    }

    private String excelRange(int[] bounds) {
        return bounds == null ? "" : excelRange(bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    private String excelAddress(int column, int row) {
        var result = new StringBuilder();
        for (var value = Math.max(1, column); value > 0; value = (value - 1) / 26) {
            result.insert(0, (char) ('A' + (value - 1) % 26));
        }
        return result + Integer.toString(Math.max(1, row));
    }

    private List<JsonNode> fieldRegions(JsonNode semanticModel) {
        var blocks = objectMapper.createArrayNode();
        var tables = semanticModel.path("tables");
        for (var block : semanticModel.path("businessBlocks")) {
            var copy = block.deepCopy();
            if (copy instanceof ObjectNode object && tables.isArray()) {
                var blockId = object.path("blockId").asText(object.path("temporaryId").asText(""));
                var blockRange = object.path("range").asText("");
                for (var table : tables) {
                    var tableBlockId = table.path("blockTemporaryId").asText("");
                    var tableRange = table.path("range").asText("");
                    if ((!blockId.isBlank() && blockId.equals(tableBlockId))
                            || (!blockRange.isBlank() && blockRange.equals(tableRange))) {
                        object.set("structure", table.deepCopy());
                        break;
                    }
                }
            }
            blocks.add(copy);
        }
        return fieldRegionsFromBlocks(blocks);
    }

    private List<JsonNode> fieldRegionsFromBlocks(JsonNode blocks) {
        var result = new java.util.ArrayList<JsonNode>();
        var parents = new java.util.HashSet<String>();
        for (var block : blocks) {
            var parent = block.path("parentBlockId").asText(block.path("parentTemporaryId").asText(""));
            if (!parent.isBlank()) parents.add(parent);
        }
        for (var block : blocks) {
            var type = block.path("type").asText("");
            if (isStaticStructureType(type) || "FREE_TEXT".equals(type)) continue;
            var id = block.path("blockId").asText(block.path("temporaryId").asText(""));
            if (parents.contains(id)) continue;
            if (!block.path("sheetId").asText("").isBlank() && !block.path("range").asText("").isBlank()) {
                result.add(block);
            }
        }
        if (result.isEmpty()) {
            for (var block : blocks) {
                var type = block.path("type").asText("");
                if (!isStaticStructureType(type) && !"FREE_TEXT".equals(type)) {
                    result.add(block);
                    break;
                }
            }
        }
        return result;
    }

    private void bindToCanonicalRegion(ObjectNode payload, JsonNode canonicalRegion) {
        var blockId = canonicalRegion.path("blockId").asText(canonicalRegion.path("temporaryId").asText(""));
        payload.put("blockId", blockId)
                .put("regionId", blockId)
                .put("parentBlockId", canonicalRegion.path("parentBlockId").asText(""))
                .put("blockType", canonicalRegion.path("type").asText(""))
                .put("blockName", canonicalRegion.path("businessName").asText(""));
    }

    private void collectFailure(
            ModelStageAccumulator accumulator, UUID recognitionRunId, UUID importJobId,
            String regionId, String phase, RecognitionModelClient.RecognitionCallException exception,
            String message
    ) {
        accumulator.failedCalls++;
        if (exception.traces().isEmpty()) accumulator.callCount++;
        for (var trace : exception.traces()) {
            repository.saveRecognitionCall(recognitionRunId, trace);
            accumulator.callCount++;
            if (trace.responseTruncated() || "MODEL_OUTPUT_TRUNCATED".equals(trace.outcomeCode())) {
                accumulator.truncatedCalls++;
            }
        }
    }

    private void collectFailure(
            ModelStageAccumulator accumulator, UUID recognitionRunId, UUID importJobId,
            String regionId, String phase, Exception exception, String message
    ) {
        accumulator.failedCalls++;
        accumulator.callCount++;
        var now = java.time.Instant.now();
        repository.saveRecognitionCall(recognitionRunId, new RecognitionModelClient.CallTrace(
                UUID.randomUUID(), regionId, 1, "openai-compatible", "global-semantic-model",
                "template-global-semantic-v3", "FAILED", null, now, now, 0, 0, 0, 0,
                objectMapper.createObjectNode(), objectMapper.createObjectNode(), "", null,
                exception.getClass().getSimpleName(), message, "", "MODEL_CALL_FAILED", false,
                phase, null
        ));
    }

    private String regionId(JsonNode block) {
        return block.path("blockId").asText(block.path("temporaryId").asText("unknown-region"));
    }

    private boolean isStaticStructureType(String type) {
        return Set.of("STATIC_REFERENCE", "DOCUMENT_HEADER", "INSTRUCTION_LIST", "NOTE_BLOCK",
                "LOOKUP_TABLE").contains(type);
    }

    private static final class ModelStageAccumulator {
        private final java.util.ArrayList<RecognitionModelClient.ModelSuggestion> suggestions = new java.util.ArrayList<>();
        private final java.util.ArrayList<RecognitionModelClient.QualityIssueSuggestion> qualityIssues = new java.util.ArrayList<>();
        private final Map<String, String> regionStates = new LinkedHashMap<>();
        private RecognitionModelClient.RecognitionBatch lastBatch;
        private int callCount;
        private int succeededCalls;
        private int failedCalls;
        private int truncatedCalls;
    }

    private record StagedModelResult(
            RecognitionModelClient.RecognitionBatch batch,
            int callCount,
            int succeededCalls,
            int failedCalls,
            int truncatedCalls,
            ObjectNode coverageReport,
            String recognitionStatus
    ) {
    }

    private QualityResolution resolveQualityIssues(
            WorkbookQualityAnalyzer.Analysis ruleAnalysis,
            List<RecognitionModelClient.QualityIssueSuggestion> modelIssues,
            boolean allowPhysicalAutoFix
    ) {
        var snapshot = ruleAnalysis.snapshot().deepCopy();
        var result = new java.util.LinkedHashMap<String, RecognitionModelClient.QualityIssueSuggestion>();
        for (var issue : ruleAnalysis.issues()) {
            var matchingModel = modelIssues.stream()
                    .filter(model -> qualityKey(model).equals(qualityKey(issue)))
                    .filter(model -> model.confidence() >= 0.92).findFirst().orElse(null);
            var autoApply = allowPhysicalAutoFix && matchingModel != null && issue.autoFixable()
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
            if (group.stream().anyMatch(issue -> issue.evidence() != null
                    && issue.evidence().findValue("internalRecovery") != null)) {
                evidence.add(objectMapper.createObjectNode().put("internalRecovery", true));
            }
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
        return semanticViewBuilder.build(structure, scope, requestedSheetId, requestedAddress);
    }

    private int semanticCellCount(JsonNode structure) {
        var count = 0;
        for (var sheet : structure.path("sheets")) count += sheet.path("semanticCells").size();
        return count;
    }

}
