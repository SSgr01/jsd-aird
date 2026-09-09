package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
import com.jsd.aird.kb.domain.OrderedDocumentProjector;
import com.jsd.aird.rnd.application.port.ExperimentImportRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.Summary;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.api.TemplateOfficeNormalizationFacade;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ExperimentImportService {
    private final ExperimentImportRepository imports;
    private final ExperimentService experiments;
    private final FileStorageFacade files;
    private final List<OfficeStructureParser> parsers;
    private final List<MediaExtractionProvider> mediaProviders;
    private final ObjectMapper objectMapper;
    private final OpsAsyncFacade asyncJobs;
    private final TemplateOfficeNormalizationFacade fileNormalizer;
    private final OrderedDocumentProjector documentProjector;

    public ExperimentImportService(ExperimentImportRepository imports, ExperimentService experiments,
                                   FileStorageFacade files, List<OfficeStructureParser> parsers,
                                   List<MediaExtractionProvider> mediaProviders,
                                   ObjectMapper objectMapper, OpsAsyncFacade asyncJobs,
                                   TemplateOfficeNormalizationFacade fileNormalizer) {
        this.imports = imports;
        this.experiments = experiments;
        this.files = files;
        this.parsers = List.copyOf(parsers);
        this.mediaProviders = List.copyOf(mediaProviders);
        this.objectMapper = objectMapper;
        this.asyncJobs = asyncJobs;
        this.fileNormalizer = fileNormalizer;
        this.documentProjector = new OrderedDocumentProjector();
    }

    @Transactional
    public Accepted importFile(Command command) {
        var actor = ActorContext.required();
        var normalized = normalize(command);
        var jobId = UUID.randomUUID();
        imports.create(jobId, actor.organizationId(), normalized.fileId(), normalized.fileName(),
                normalized.sha256(), normalized.format().name(), normalized.categoryName(), normalized.projectId(),
                normalized.stageId(), normalized.taskId(), normalized.visibility(), actor.userId());
        enqueue(jobId, normalized, actor, "experiment-import:" + jobId);
        return new Accepted(jobId, "PARSING");
    }

    public Accepted retry(UUID id) {
        var actor = ActorContext.required();
        var job = imports.find(actor.organizationId(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "上传记录不存在"));
        if ("PARSING".equals(job.status())) {
            // A retry of an in-flight import must first stop the old worker.
            // These updates intentionally run outside one surrounding
            // transaction so the worker observes CANCELLED before the row is
            // reset to PARSING for the new attempt.
            if (imports.cancel(actor.organizationId(), id) != 1) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "解析任务状态已变化，请刷新后重试");
            }
            asyncJobs.cancelByIdempotencyKeyPrefix(actor.organizationId(), "experiment-import:" + id);
            asyncJobs.cancelByIdempotencyKeyPrefix(actor.organizationId(), "experiment-import-retry:" + id + ":");
        }
        if (!Set.of("PARSING", "FAILED", "CANCELLED", "COMPLETED").contains(job.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "当前上传记录不可重试");
        }
        if (imports.markRetrying(actor.organizationId(), id) != 1) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "上传记录状态已变化，请刷新后重试");
        }
        final TemplateFormat format = parseFormat(job.sourceFormat());
        var command = normalize(new Command(job.sourceFileId(), job.sourceFileName(), job.sourceSha256(), format,
                null, job.categoryName(), job.projectId(), job.stageId(), job.taskId(), LocalDate.now(), job.visibility()));
        enqueue(job.id(), command, actor, "experiment-import-retry:" + job.id() + ":" + UUID.randomUUID());
        return new Accepted(job.id(), "PARSING");
    }

    /** Parse a staged source using the shared Office/OCR pipeline. */
    public OfficeStructureParser.ParseResult parseSourceFile(
            UUID organizationId, UUID fileId, String fileName, String sha256, TemplateFormat format) {
        if (organizationId == null || fileId == null || format == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件解析参数不完整");
        }
        try (var stored = files.open(organizationId, fileId)) {
            var bytes = stored.stream().readAllBytes();
            var command = new Command(fileId, fileName, sha256, format,
                    null, null, null, null, null, LocalDate.now(), "ALL");
            var parser = parsers.stream().filter(item -> item.format() == format).findFirst().orElse(null);
            var parseBytes = normalizedOfficeBytes(fileName, stored.contentType(), format, bytes);
            return parser == null
                    ? parseSource(command, parseBytes)
                    : parser.parse(new ByteArrayInputStream(parseBytes));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "上传文件不可读取");
        }
    }

    /** Runs in the worker process. The request thread only creates/enqueues the job. */
    @Transactional(rollbackFor = Exception.class)
    public Summary processQueued(UUID jobId, Command command, Actor actor) {
        return processQueuedInternal(jobId, command, actor, null);
    }

    /** Worker entry point that also carries the generic async job key for progress updates. */
    @Transactional(rollbackFor = Exception.class)
    public Summary processQueued(UUID jobId, Command command, Actor actor, String asyncIdempotencyKey) {
        return processQueuedInternal(jobId, command, actor, asyncIdempotencyKey);
    }

    private Summary processQueuedInternal(UUID jobId, Command command, Actor actor, String asyncIdempotencyKey) {
        updateProgress(actor.organizationId(), asyncIdempotencyKey, 5, "PREPARING");
        try {
            updateProgress(actor.organizationId(), asyncIdempotencyKey, 15, "LOADING_FILE");
            try (var stored = files.open(actor.organizationId(), command.fileId())) {
                updateProgress(actor.organizationId(), asyncIdempotencyKey, 25, "READING_STRUCTURE");
                var bytes = stored.stream().readAllBytes();
                var parseBytes = normalizedOfficeBytes(command.fileName(), stored.contentType(), command.format(), bytes);
                var parser = parsers.stream().filter(item -> item.format() == command.format()).findFirst().orElse(null);
                var parsed = parser == null
                        ? parseSource(command, parseBytes)
                        : parser.parse(new ByteArrayInputStream(parseBytes));
                if (parsed == null || parsed.initialEditorSnapshot() == null
                        || !parsed.initialEditorSnapshot().isObject()) {
                    throw new ApiException(ApiErrorCode.VALIDATION_ERROR,
                            "实验文件解析未生成有效的实验工作区");
                }
                // Deleting a parsing row is a cancellation request. Check
                // again after the potentially expensive OCR/Office parse before
                // creating the experiment aggregate.
                if (!imports.isParsing(actor.organizationId(), jobId)) return null;
                updateProgress(actor.organizationId(), asyncIdempotencyKey, 70, "PARSING_COMPLETED");
                var editModel = objectMapper.createObjectNode();
                editModel.put("title", command.fileName());
                editModel.put("purpose", "");
                editModel.put("plan", "");
                // Images are OCR-ed through the shared template/knowledge media
                // provider and materialized as an Excel workbook for review.
                editModel.put("documentFormat", command.format() == TemplateFormat.XLSX
                        || command.format() == TemplateFormat.IMAGE ? "excel" : "word");
                editModel.set("documentSnapshot", parsed.initialEditorSnapshot());
                editModel.put("sourceFileId", command.fileId().toString());
                editModel.put("sourceFileName", command.fileName());
                editModel.put("sourceFileSha256", command.sha256());
                if (command.format() == TemplateFormat.IMAGE && parsed.structureSummary() != null) {
                    editModel.set("ocrResult", parsed.structureSummary());
                }
                editModel.set("dynamicValues", objectMapper.createObjectNode());
                editModel.set("formulaItems", objectMapper.createArrayNode());
                editModel.set("processSteps", objectMapper.createArrayNode());
                editModel.set("testResults", objectMapper.createArrayNode());
                editModel.set("events", objectMapper.createArrayNode());
                var conclusion = objectMapper.createObjectNode();
                conclusion.put("resultStatus", "");
                conclusion.put("mainConclusion", "");
                conclusion.put("failureCategory", "");
                editModel.set("conclusion", conclusion);
                if (!imports.isParsing(actor.organizationId(), jobId)) return null;
                updateProgress(actor.organizationId(), asyncIdempotencyKey, 85, "BUILDING_EXPERIMENT");
                var summary = experiments.create(new ExperimentService.CreateCommand(
                        null, command.fileName(), command.categoryId(), command.categoryName(),
                        command.format() == TemplateFormat.XLSX || command.format() == TemplateFormat.XLS || command.format() == TemplateFormat.CSV
                                ? "EXCEL_IMPORT" : "OCR_IMPORT",
                        command.projectId(), command.stageId(), command.taskId(), actor.username(),
                        command.experimentDate(), command.fileId(), null, command.sha256(),
                        parsed.initialEditorSnapshot(), editModel));
                updateProgress(actor.organizationId(), asyncIdempotencyKey, 95, "PERSISTING_RESULT");
                imports.complete(jobId, summary.id(), parsed.initialEditorSnapshot());
                return summary;
            }
        } catch (Exception exception) {
            if (exception instanceof ApiException apiException) throw apiException;
            if (exception instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("实验文件解析失败: " + exception.getMessage(), exception);
        }
    }

    @Transactional
    public void failQueued(UUID jobId, String errorMessage) {
        imports.fail(jobId, errorMessage);
    }

    private void updateProgress(UUID organizationId, String asyncIdempotencyKey, int progress, String stage) {
        asyncJobs.updateProgress(organizationId, asyncIdempotencyKey, progress, stage);
    }

    private void enqueue(UUID jobId, Command command, Actor actor, String idempotencyKey) {
        var payload = objectMapper.createObjectNode();
        payload.put("jobId", jobId.toString());
        payload.put("organizationId", actor.organizationId().toString());
        payload.put("actorId", actor.userId().toString());
        payload.put("actorName", actor.username());
        payload.put("actorRole", actor.role() == null ? "ADMIN" : actor.role());
        payload.put("asyncIdempotencyKey", idempotencyKey);
        putUuid(payload, "fileId", command.fileId());
        payload.put("fileName", command.fileName());
        payload.put("sha256", command.sha256());
        payload.put("format", command.format().name());
        putUuid(payload, "categoryId", command.categoryId());
        payload.put("categoryName", command.categoryName() == null ? "" : command.categoryName());
        putUuid(payload, "projectId", command.projectId());
        putUuid(payload, "stageId", command.stageId());
        putUuid(payload, "taskId", command.taskId());
        payload.put("experimentDate", (command.experimentDate() == null ? LocalDate.now() : command.experimentDate()).toString());
        payload.put("visibility", command.visibility());
        asyncJobs.enqueue(actor.organizationId(), "EXPERIMENT_IMPORT_PARSE", payload, idempotencyKey, 50);
    }

    private Command normalize(Command command) {
        if (command == null || command.fileId() == null || command.fileName() == null || command.fileName().isBlank()
                || command.sha256() == null || command.sha256().isBlank() || command.format() == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "实验文件信息不完整");
        }
        var visibility = command.visibility() == null || command.visibility().isBlank() ? "ALL" : command.visibility();
        if (!java.util.Set.of("ALL", "QUALITY", "PROJECT").contains(visibility)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "权限类型不合法");
        }
        return new Command(command.fileId(), command.fileName().strip(), command.sha256().strip(), command.format(),
                command.categoryId(), command.categoryName(), command.projectId(), command.stageId(), command.taskId(),
                command.experimentDate(), visibility);
    }

    private TemplateFormat parseFormat(String value) {
        try {
            return TemplateFormat.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的实验文件格式");
        }
    }

    private byte[] normalizedOfficeBytes(String fileName, String contentType, TemplateFormat format, byte[] bytes) {
        if (format != TemplateFormat.DOCX || fileName == null
                || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".doc")) return bytes;
        return fileNormalizer.normalizeOfficeFile(fileName,
                contentType == null || contentType.isBlank() ? "application/msword" : contentType,
                bytes).content();
    }

    private void putUuid(ObjectNode target, String field, UUID value) {
        if (value != null) target.put(field, value.toString());
    }

    public List<ExperimentImportRepository.Job> list() {
        var actor = ActorContext.required();
        return imports.list(actor.organizationId()).stream()
                .map(job -> enrichProgress(actor.organizationId(), job))
                .toList();
    }

    private ExperimentImportRepository.Job enrichProgress(UUID organizationId, ExperimentImportRepository.Job job) {
        if ("COMPLETED".equals(job.status())) {
            return withProgress(job, 100, "COMPLETED");
        }
        var asyncJob = latestAsyncJob(organizationId, job.id());
        // Older workers could mark the generic async row FAILED without
        // reaching ExperimentImportJobHandler, leaving the import row in
        // PARSING forever. Project that terminal worker state into the read
        // model so the UI can show a failure and offer retry immediately.
        if ("PARSING".equals(job.status())
                && asyncJob.map(item -> "FAILED".equals(item.status())).orElse(false)) {
            var error = asyncJob.map(OpsAsyncFacade.AsyncJobView::lastError)
                    .filter(item -> item != null && !item.isBlank())
                    .orElse("后台解析任务失败");
            return withStatus(job, "FAILED", error, 0, "FAILED");
        }
        var progress = asyncJob.map(OpsAsyncFacade.AsyncJobView::progress).orElse(0);
        var stage = asyncJob.map(OpsAsyncFacade.AsyncJobView::currentStage).orElse(null);
        return withProgress(job, progress, stage);
    }

    private Optional<OpsAsyncFacade.AsyncJobView> latestAsyncJob(UUID organizationId, UUID importId) {
        var retry = asyncJobs.findLatestJob(
                organizationId, "experiment-import-retry:" + importId + ":");
        return retry.isPresent()
                ? retry
                : asyncJobs.findJob(organizationId, "experiment-import:" + importId);
    }

    private ExperimentImportRepository.Job withProgress(
            ExperimentImportRepository.Job job, int progress, String currentStage
    ) {
        return withStatus(job, job.status(), job.errorMessage(), progress, currentStage);
    }

    private ExperimentImportRepository.Job withStatus(
            ExperimentImportRepository.Job job, String status, String errorMessage,
            int progress, String currentStage
    ) {
        return new ExperimentImportRepository.Job(
                job.id(), job.sourceFileId(), job.sourceFileName(), job.sourceSha256(), job.sourceFormat(),
                status, job.experimentId(), errorMessage, job.categoryName(), job.projectId(),
                job.projectName(), job.stageId(), job.stageName(), job.taskId(), job.taskName(), job.visibility(),
                job.createdAt(), progress, currentStage);
    }

    @Transactional
    public void delete(UUID id) {
        var actor = ActorContext.required();
        // Mark the upload row first so a concurrent retry cannot enqueue a new
        // task after we cancel the queue. Then cancel the initial parse and all
        // retry tasks for this upload before removing the record.
        var parsing = imports.cancel(actor.organizationId(), id) == 1;
        asyncJobs.cancelByIdempotencyKeyPrefix(actor.organizationId(), "experiment-import:" + id);
        asyncJobs.cancelByIdempotencyKeyPrefix(actor.organizationId(), "experiment-import-retry:" + id + ":");
        if (parsing) return;
        if (imports.delete(actor.organizationId(), id) == 0) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "上传记录不存在或状态已变化，请刷新后重试");
        }
    }

    public record Command(UUID fileId, String fileName, String sha256, TemplateFormat format,
                          UUID categoryId, String categoryName, UUID projectId, UUID stageId,
                          UUID taskId, LocalDate experimentDate, String visibility) {}

    public record Accepted(UUID jobId, String status) {}

    /**
     * Non-OOXML sources still need a traceable draft even when no Office parser is configured.
     * The original file remains the source attachment; the generated snapshot is deliberately
     * conservative so reviewers can correct OCR/legacy content in the workspace.
     */
    private OfficeStructureParser.ParseResult genericParse(TemplateFormat format, String fileName, byte[] bytes) {
        var summary = objectMapper.createObjectNode()
                .put("format", format.name())
                .put("parserVersion", "experiment-source-generic-v1")
                .put("sourceFileName", fileName)
                .put("reviewRequired", format == TemplateFormat.PDF || format == TemplateFormat.IMAGE);
        var snapshot = objectMapper.createObjectNode().put("id", UUID.randomUUID().toString());
        if (format == TemplateFormat.XLS || format == TemplateFormat.CSV) {
            snapshot.put("snapshotFormatVersion", 3).put("name", fileName);
            snapshot.putArray("sheetOrder").add("sheet-1");
            var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("name", "Sheet1")
                    .put("rowCount", 200).put("columnCount", 26);
            var cellData = objectMapper.createObjectNode();
            if (format == TemplateFormat.CSV) {
                var text = new String(bytes, StandardCharsets.UTF_8);
                var rows = text.split("\\R", -1);
                for (int rowIndex = 0; rowIndex < Math.min(rows.length, 200); rowIndex++) {
                    var cells = objectMapper.createObjectNode();
                    var columns = rows[rowIndex].split(",", -1);
                    for (int columnIndex = 0; columnIndex < Math.min(columns.length, 26); columnIndex++) {
                        cells.set(String.valueOf(columnIndex), objectMapper.createObjectNode().put("v", columns[columnIndex].trim()));
                    }
                    if (!cells.isEmpty()) cellData.set(String.valueOf(rowIndex), cells);
                }
            }
            sheet.set("cellData", cellData);
            snapshot.set("sheets", objectMapper.createObjectNode().set("sheet-1", sheet));
        } else if (format == TemplateFormat.IMAGE) {
            var imageSummary = objectMapper.createObjectNode()
                    .put("format", "OCR_IMPORT")
                    .put("outputFormat", "XLSX")
                    .put("sourceFormat", "IMAGE")
                    .put("parserVersion", "experiment-image-ocr-fallback-v1")
                    .put("sourceFileName", fileName)
                    .put("reviewRequired", true)
                    .put("ocrStatus", "REVIEW_REQUIRED")
                    .put("message", "OCR 服务尚未配置，已创建可编辑的 Excel 草稿");
            return new OfficeStructureParser.ParseResult(imageSummary,
                    ocrWorkbookSnapshot(fileName, contentType(format, fileName), bytes,
                            List.of(new DocumentParser.TextBlock(
                                    null, "warning", "OCR 服务尚未配置，请先配置模板中心 OCR 后重新上传或手工录入。"))), List.of());
        } else {
            snapshot.put("snapshotFormatVersion", 5).put("editorMode", "UNIVER_DOCS").put("title", fileName);
            var body = objectMapper.createObjectNode().put("dataStream", "\\r\\n");
            body.putArray("textRuns");
            body.putArray("paragraphs").addObject().put("startIndex", 0);
            body.putArray("customRanges");
            snapshot.set("body", body);
            snapshot.set("documentStyle", objectMapper.createObjectNode()
                    .set("pageSize", objectMapper.createObjectNode().put("width", 595).put("height", 842)));
            if (format == TemplateFormat.PDF || format == TemplateFormat.IMAGE) {
                summary.put("ocrStatus", "REVIEW_REQUIRED");
                summary.put("sourcePreviewBase64", Base64.getEncoder().encodeToString(bytes));
            }
        }
        return new OfficeStructureParser.ParseResult(summary, snapshot, List.of());
    }

    private OfficeStructureParser.ParseResult parseSource(Command command, byte[] bytes) {
        if (command.format() != TemplateFormat.PDF && command.format() != TemplateFormat.IMAGE) {
            return genericParse(command.format(), command.fileName(), bytes);
        }
        var provider = mediaProviders.stream()
                .filter(item -> item.isConfigured() && item.supports(command.fileName(), contentType(command.format(), command.fileName())))
                .findFirst()
                .orElse(null);
        if (provider == null) return genericParse(command.format(), command.fileName(), bytes);
        try {
            var extracted = provider.extract(new ByteArrayInputStream(bytes), command.fileName(),
                    new MediaExtractionProvider.ExtractionContext(command.fileId(), contentType(command.format(), command.fileName()), bytes.length, null));
            return command.format() == TemplateFormat.IMAGE
                    ? ocrWorkbookResult(command.fileName(), contentType(command.format(), command.fileName()), bytes, extracted)
                    : ocrSnapshot(command.fileName(), extracted);
        } catch (RuntimeException ignored) {
            // A reviewable source draft is safer than losing the original file when OCR is unavailable.
            return genericParse(command.format(), command.fileName(), bytes);
        }
    }

    private OfficeStructureParser.ParseResult ocrSnapshot(String fileName, DocumentParser.ParsedDocument extracted) {
        var summary = objectMapper.createObjectNode()
                .put("format", "OCR_IMPORT")
                .put("parserVersion", extracted.parserVersion() == null ? "ocr" : extracted.parserVersion())
                .put("sourceFileName", fileName)
                .put("reviewRequired", true)
                .put("ocrStatus", "REVIEW_REQUIRED")
                .put("recognizedBlockCount", extracted.blocks().size());
        var blocks = summary.putArray("textBlocks");
        extracted.blocks().forEach(block -> {
            var node = blocks.addObject()
                    .put("pageNo", block.pageNo() == null ? 0 : block.pageNo())
                    .put("section", block.section() == null ? "" : block.section())
                    .put("content", block.content() == null ? "" : block.content());
            if (block.confidence() != null) node.put("confidence", block.confidence());
        });
        var text = extracted.blocks().stream().map(DocumentParser.TextBlock::content)
                .filter(java.util.Objects::nonNull).filter(item -> !item.isBlank()).reduce((left, right) -> left + "\\r\\n" + right).orElse("");
        var snapshot = objectMapper.createObjectNode().put("id", UUID.randomUUID().toString())
                .put("snapshotFormatVersion", 5).put("editorMode", "UNIVER_DOCS").put("title", fileName);
        var body = objectMapper.createObjectNode().put("dataStream", text + "\\r\\n");
        body.putArray("textRuns");
        body.putArray("paragraphs").addObject().put("startIndex", 0);
        body.putArray("customRanges");
        snapshot.set("body", body);
        snapshot.set("documentStyle", objectMapper.createObjectNode().set("pageSize", objectMapper.createObjectNode().put("width", 595).put("height", 842)));
        return new OfficeStructureParser.ParseResult(summary, snapshot, List.of());
    }

    /**
     * Materializes the shared template-center OCR blocks into a workbook so an
     * image upload opens in the same Excel editor as a normal experiment.
     * Table rows retain their cell boundaries when the OCR provider supplies
     * the converter's table-cell attributes; ordinary text is kept on a
     * separate 识别文本 sheet. The original source remains available through
     * the experiment's source-file download instead of being duplicated here.
     */
    private OfficeStructureParser.ParseResult ocrWorkbookResult(
            String fileName, String sourceContentType, byte[] sourceBytes,
            DocumentParser.ParsedDocument extracted) {
        var projection = documentProjector.project(extracted.blocks());
        var summary = objectMapper.createObjectNode()
                .put("format", "OCR_IMPORT")
                .put("outputFormat", "XLSX")
                .put("sourceFormat", "IMAGE")
                .put("parserVersion", extracted.parserVersion() == null ? "ocr" : extracted.parserVersion())
                .put("sourceFileName", fileName)
                .put("reviewRequired", true)
                .put("ocrStatus", "REVIEW_REQUIRED")
                .put("recognizedBlockCount", extracted.blocks().size())
                .put("structureScore", projection.structureScore());
        summary.set("structureIssues", objectMapper.valueToTree(projection.issues()));
        var blocks = summary.putArray("textBlocks");
        extracted.blocks().forEach(block -> {
            var node = blocks.addObject()
                    .put("pageNo", block.pageNo() == null ? 0 : block.pageNo())
                    .put("section", block.section() == null ? "" : block.section())
                    .put("content", block.content() == null ? "" : block.content());
            if (block.confidence() != null) node.put("confidence", block.confidence());
            if (block.attributes() != null && !block.attributes().isEmpty()) {
                node.set("attributes", objectMapper.valueToTree(block.attributes()));
            }
        });
        return new OfficeStructureParser.ParseResult(summary,
                ocrWorkbookSnapshot(fileName, projection), List.of());
    }

    ObjectNode ocrWorkbookSnapshot(String fileName, List<DocumentParser.TextBlock> blocks) {
        return ocrWorkbookSnapshot(fileName, documentProjector.project(blocks));
    }

    /**
     * Creates the workbook used by image OCR imports. The OCR text/table
     * sheets are editable. The original image is retained by file storage and
     * downloaded from the experiment workspace when the user needs it.
     */
    ObjectNode ocrWorkbookSnapshot(String fileName, String sourceContentType, byte[] sourceBytes,
                                   List<DocumentParser.TextBlock> blocks) {
        return ocrWorkbookSnapshot(fileName, documentProjector.project(blocks));
    }

    private ObjectNode ocrWorkbookSnapshot(String fileName, OrderedDocumentProjector.Projection projection) {
        var snapshot = objectMapper.createObjectNode()
                .put("id", UUID.randomUUID().toString())
                .put("snapshotFormatVersion", 3)
                .put("name", fileName + " · OCR结果")
                .put("appVersion", "univer-0.25.1");
        var styles = objectMapper.createObjectNode();
        var sheetId = "sheet-ocr-result";
        snapshot.putArray("sheetOrder").add(sheetId);
        snapshot.putObject("sheets").set(sheetId, workbookOrderedSheet(sheetId, projection, styles));
        snapshot.set("styles", styles);
        return snapshot;
    }

    private ObjectNode workbookOrderedSheet(String id, OrderedDocumentProjector.Projection projection,
                                             ObjectNode styles) {
        ensureOcrStyles(styles);
        var cellData = objectMapper.createObjectNode();
        var mergeData = objectMapper.createArrayNode();
        var rowData = objectMapper.createObjectNode();
        var columnWidths = new ArrayList<Integer>();
        var currentRow = 0;
        var maximumColumns = projection.sections().stream()
                .filter(OrderedDocumentProjector.Table.class::isInstance)
                .map(OrderedDocumentProjector.Table.class::cast)
                .mapToInt(OrderedDocumentProjector.Table::columnCount).max().orElse(8);
        for (var section : projection.sections()) {
            if (section instanceof OrderedDocumentProjector.Paragraph paragraph) {
                var text = paragraph.text();
                var cell = objectMapper.createObjectNode().put("v", text)
                        .put("s", paragraph.type() != null && paragraph.type().startsWith("heading")
                                ? "ocr-header" : "ocr-text");
                cellData.withObject("/" + currentRow).set("0", cell);
                if (maximumColumns > 1) mergeData.addObject().put("startRow", currentRow).put("endRow", currentRow)
                        .put("startColumn", 0).put("endColumn", maximumColumns - 1);
                var lines = Math.max(1, (text.length() + 79) / 80);
                rowData.set(String.valueOf(currentRow), objectMapper.createObjectNode().put("h", Math.min(120, 28 * lines)));
                currentRow++;
                continue;
            }
            var table = (OrderedDocumentProjector.Table) section;
            var tableStart = currentRow;
            // Materialize the complete logical rectangle first. Empty and merge-covered cells
            // need styles too; otherwise Univer renders broken borders around sparse OCR rows.
            for (var logicalRow = 0; logicalRow < table.rowCount(); logicalRow++) {
                var targetRow = cellData.withObject("/" + (tableStart + logicalRow));
                for (var logicalColumn = 0; logicalColumn < table.columnCount(); logicalColumn++) {
                    targetRow.set(String.valueOf(logicalColumn), objectMapper.createObjectNode()
                            .put("v", "").put("s", "ocr-table"));
                }
            }
            for (var placement : table.cells()) {
                var row = tableStart + placement.row();
                cellData.withObject("/" + row).set(String.valueOf(placement.column()), objectMapper.createObjectNode()
                        .put("v", placement.text()).put("s", placement.header() ? "ocr-header" : "ocr-table"));
                if (placement.rowSpan() > 1 || placement.columnSpan() > 1) {
                    mergeData.addObject().put("startRow", row).put("endRow", row + placement.rowSpan() - 1)
                            .put("startColumn", placement.column())
                            .put("endColumn", placement.column() + placement.columnSpan() - 1);
                }
                while (columnWidths.size() < placement.column() + placement.columnSpan()) columnWidths.add(34);
                var pixelWidth = geometryNumber(placement.geometry(), "pixelRight")
                        - geometryNumber(placement.geometry(), "pixelLeft");
                var width = pixelWidth > 0 ? Math.min(220, Math.max(34,
                        (int) Math.round(pixelWidth * .72 / placement.columnSpan())))
                        : Math.min(180, Math.max(76, 20 + placement.text().length() * 13 / placement.columnSpan()));
                for (var offset = 0; offset < placement.columnSpan(); offset++) {
                    columnWidths.set(placement.column() + offset,
                            Math.max(columnWidths.get(placement.column() + offset), width));
                }
                var pixelHeight = geometryNumber(placement.geometry(), "pixelBottom")
                        - geometryNumber(placement.geometry(), "pixelTop");
                if (pixelHeight > 0) {
                    var targetHeight = Math.min(120, Math.max(22, (int) Math.round(pixelHeight * .72)));
                    var existingHeight = rowData.path(String.valueOf(row)).path("h").asInt(0);
                    if (targetHeight > existingHeight) rowData.set(String.valueOf(row),
                            objectMapper.createObjectNode().put("h", targetHeight));
                }
            }
            for (var abnormal : table.abnormalRows()) {
                rowData.withObject("/" + (tableStart + abnormal)).put("ocrReviewRequired", 1);
            }
            currentRow += table.rowCount();
        }
        var sheet = objectMapper.createObjectNode().put("id", id).put("name", "识别结果")
                .put("rowCount", Math.max(200, currentRow + 20))
                .put("columnCount", Math.max(12, maximumColumns + 3))
                .put("defaultRowHeight", 30).put("defaultColumnWidth", 76)
                .put("showGridlines", 0).put("detectedColumnCount", maximumColumns)
                .put("ocrStructureScore", projection.structureScore());
        sheet.set("cellData", cellData);
        sheet.set("mergeData", mergeData);
        sheet.set("rowData", rowData);
        var columnData = objectMapper.createObjectNode();
        for (var column = 0; column < columnWidths.size(); column++) {
            columnData.set(String.valueOf(column), objectMapper.createObjectNode().put("w", columnWidths.get(column)));
        }
        sheet.set("columnData", columnData);
        sheet.set("freeze", objectMapper.createObjectNode().put("startRow", -1).put("startColumn", -1).put("xSplit", 0).put("ySplit", 0));
        sheet.set("rowHeader", objectMapper.createObjectNode().put("width", 46));
        sheet.set("columnHeader", objectMapper.createObjectNode().put("height", 20));
        return sheet;
    }

    private double geometryNumber(Map<String, Object> geometry, String key) {
        if (geometry == null) return 0d;
        var value = geometry.get(key);
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (RuntimeException ignored) { return 0d; }
    }

    private ObjectNode workbookTextSheet(String id, String name, List<List<String>> rows, ObjectNode styles) {
        ensureOcrStyles(styles);
        var sheet = objectMapper.createObjectNode()
                .put("id", id)
                .put("name", name)
                .put("rowCount", Math.max(200, rows.size() + 20))
                .put("columnCount", Math.max(8, rows.stream().mapToInt(List::size).max().orElse(1)))
                .put("defaultRowHeight", 26)
                .put("defaultColumnWidth", 100)
                .put("showGridlines", 1);
        var cellData = objectMapper.createObjectNode();
        for (var rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            var rowData = objectMapper.createObjectNode();
            var row = rows.get(rowIndex);
            for (var columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                rowData.set(String.valueOf(columnIndex), objectMapper.createObjectNode()
                        .put("v", row.get(columnIndex))
                        .put("s", rowIndex == 0 ? "ocr-header" : "ocr-text"));
            }
            cellData.set(String.valueOf(rowIndex), rowData);
        }
        sheet.set("cellData", cellData);
        sheet.set("mergeData", objectMapper.createArrayNode());
        sheet.set("rowData", objectMapper.createObjectNode());
        var columnData = objectMapper.createObjectNode();
        columnData.set("0", objectMapper.createObjectNode().put("w", 70));
        columnData.set("1", objectMapper.createObjectNode().put("w", 140));
        columnData.set("2", objectMapper.createObjectNode().put("w", 560));
        columnData.set("3", objectMapper.createObjectNode().put("w", 90));
        sheet.set("columnData", columnData);
        return sheet;
    }

    private ObjectNode workbookTableSheet(String id, String name, List<DocumentParser.TextBlock> rows,
                                           ObjectNode styles) {
        ensureOcrStyles(styles);
        var placements = new ArrayList<OcrCellPlacement>();
        var occupiedUntilRow = new ArrayList<Integer>();
        var columnWidths = new ArrayList<Integer>();
        var maxColumn = 0;
        for (var rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            var block = rows.get(rowIndex);
            var cells = tableCellModels(block.attributes());
            if (cells.isEmpty()) cells = List.of(new OcrTableCell(block.content() == null ? "" : block.content(), 1, 1, false));
            var column = 0;
            for (var cell : cells) {
                var columnSpan = Math.max(1, cell.columnSpan());
                var rowSpan = Math.max(1, cell.rowSpan());
                while (!freeColumnRange(occupiedUntilRow, column, columnSpan, rowIndex)) column++;
                while (occupiedUntilRow.size() < column + columnSpan) occupiedUntilRow.add(-1);
                for (var offset = 0; offset < columnSpan; offset++) {
                    occupiedUntilRow.set(column + offset, Math.max(occupiedUntilRow.get(column + offset), rowIndex + rowSpan - 1));
                    while (columnWidths.size() <= column + offset) columnWidths.add(88);
                    var estimated = Math.min(240, Math.max(88, 22 + cell.text().length() * 14 / columnSpan));
                    columnWidths.set(column + offset, Math.max(columnWidths.get(column + offset), estimated));
                }
                placements.add(new OcrCellPlacement(rowIndex, column, cell));
                column += columnSpan;
                maxColumn = Math.max(maxColumn, column);
            }
        }

        var sheet = objectMapper.createObjectNode()
                .put("id", id).put("name", name)
                .put("rowCount", Math.max(200, rows.size() + 20))
                .put("columnCount", Math.max(12, maxColumn + 5))
                .put("defaultRowHeight", 30).put("defaultColumnWidth", 88)
                .put("showGridlines", 0).put("detectedColumnCount", maxColumn);
        var cellData = objectMapper.createObjectNode();
        var mergeData = objectMapper.createArrayNode();
        var rowData = objectMapper.createObjectNode();
        for (var placement : placements) {
            var cell = placement.cell();
            var row = cellData.withObject("/" + placement.row());
            row.set(String.valueOf(placement.column()), objectMapper.createObjectNode()
                    .put("v", cell.text())
                    .put("s", cell.header() ? "ocr-header" : "ocr-table"));
            if (cell.rowSpan() > 1 || cell.columnSpan() > 1) {
                mergeData.add(objectMapper.createObjectNode()
                        .put("startRow", placement.row())
                        .put("endRow", placement.row() + cell.rowSpan() - 1)
                        .put("startColumn", placement.column())
                        .put("endColumn", placement.column() + cell.columnSpan() - 1));
            }
            var visualLength = Math.max(1, cell.text().length() / Math.max(1, cell.columnSpan()));
            if (visualLength > 25) {
                var height = Math.min(100, 30 + (visualLength / 25) * 20);
                var current = rowData.path(String.valueOf(placement.row())).path("h").asInt(0);
                if (height > current) rowData.set(String.valueOf(placement.row()), objectMapper.createObjectNode().put("h", height));
            }
        }
        var columnData = objectMapper.createObjectNode();
        for (var column = 0; column < columnWidths.size(); column++) {
            columnData.set(String.valueOf(column), objectMapper.createObjectNode().put("w", columnWidths.get(column)));
        }
        sheet.set("cellData", cellData);
        sheet.set("mergeData", mergeData);
        sheet.set("rowData", rowData);
        sheet.set("columnData", columnData);
        sheet.set("freeze", objectMapper.createObjectNode().put("startRow", -1).put("startColumn", -1).put("xSplit", 0).put("ySplit", 0));
        sheet.set("rowHeader", objectMapper.createObjectNode().put("width", 46));
        sheet.set("columnHeader", objectMapper.createObjectNode().put("height", 20));
        return sheet;
    }

    private boolean freeColumnRange(List<Integer> occupiedUntilRow, int start, int span, int row) {
        for (var column = start; column < start + span; column++) {
            if (column < occupiedUntilRow.size() && occupiedUntilRow.get(column) >= row) return false;
        }
        return true;
    }

    private int tableRowOrder(DocumentParser.TextBlock block) {
        if (block.attributes() == null) return Integer.MAX_VALUE;
        for (var key : List.of("tableRowNo", "tableRowIndex", "logicalRowNo")) {
            var value = block.attributes().get(key);
            if (value instanceof Number number) return number.intValue();
            if (value != null) {
                try { return Integer.parseInt(String.valueOf(value)); }
                catch (NumberFormatException ignored) { }
            }
        }
        return Integer.MAX_VALUE;
    }

    private void ensureOcrStyles(ObjectNode styles) {
        if (styles.has("ocr-table")) return;
        var border = objectMapper.createObjectNode();
        for (var side : List.of("t", "r", "b", "l")) {
            ObjectNode sideBorder = objectMapper.createObjectNode();
            sideBorder.put("s", 1);
            ObjectNode color = objectMapper.createObjectNode();
            color.put("rgb", "#7F8FA6");
            sideBorder.set("cl", color);
            border.set(side, sideBorder);
        }
        styles.set("ocr-table", objectMapper.createObjectNode()
                .put("ff", "Microsoft YaHei").put("fs", 11).put("ht", 2).put("vt", 2).put("tb", 3)
                .set("bd", border.deepCopy()));
        var headerStyle = objectMapper.createObjectNode()
                .put("ff", "Microsoft YaHei").put("fs", 11).put("bl", 1)
                .put("ht", 2).put("vt", 2).put("tb", 3);
        headerStyle.set("bg", objectMapper.createObjectNode().put("rgb", "#EAF2FF"));
        headerStyle.set("bd", border.deepCopy());
        styles.set("ocr-header", headerStyle);
        styles.set("ocr-text", objectMapper.createObjectNode()
                .put("ff", "Microsoft YaHei").put("fs", 11).put("vt", 2).put("tb", 3));
    }

    private String stringAttribute(Map<String, Object> attributes, String key) {
        if (attributes == null) return "";
        var value = attributes.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private List<OcrTableCell> tableCellModels(Map<String, Object> attributes) {
        if (attributes == null || !(attributes.get("cells") instanceof List<?> source)) return List.of();
        var result = new ArrayList<OcrTableCell>();
        for (var value : source) {
            if (value instanceof Map<?, ?> cell) {
                result.add(new OcrTableCell(
                        cell.get("text") == null ? "" : String.valueOf(cell.get("text")),
                        positiveInt(cell.get("rowSpan")), positiveInt(cell.get("columnSpan")),
                        Boolean.parseBoolean(String.valueOf(cell.containsKey("header") ? cell.get("header") : false))));
            } else {
                result.add(new OcrTableCell(value == null ? "" : String.valueOf(value), 1, 1, false));
            }
        }
        return List.copyOf(result);
    }

    private int positiveInt(Object value) {
        if (value instanceof Number number) return Math.max(1, number.intValue());
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(value)));
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    private record OcrTableCell(String text, int rowSpan, int columnSpan, boolean header) { }
    private record OcrCellPlacement(int row, int column, OcrTableCell cell) { }

    private String contentType(TemplateFormat format, String fileName) {
        if (format == TemplateFormat.PDF) return "application/pdf";
        if (format != TemplateFormat.IMAGE) return "application/octet-stream";
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".tif") || name.endsWith(".tiff")) return "image/tiff";
        return "image/png";
    }
}
