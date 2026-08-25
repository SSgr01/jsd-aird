package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
import com.jsd.aird.rnd.application.port.ExperimentImportRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.Summary;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class ExperimentImportService {
    private final ExperimentImportRepository imports;
    private final ExperimentService experiments;
    private final FileStorageFacade files;
    private final List<OfficeStructureParser> parsers;
    private final List<MediaExtractionProvider> mediaProviders;
    private final ObjectMapper objectMapper;

    public ExperimentImportService(ExperimentImportRepository imports, ExperimentService experiments,
                                   FileStorageFacade files, List<OfficeStructureParser> parsers,
                                   List<MediaExtractionProvider> mediaProviders,
                                   ObjectMapper objectMapper) {
        this.imports = imports;
        this.experiments = experiments;
        this.files = files;
        this.parsers = List.copyOf(parsers);
        this.mediaProviders = List.copyOf(mediaProviders);
        this.objectMapper = objectMapper;
    }

    public Summary importFile(Command command) {
        ExperimentAccessPolicy.requireWrite();
        var actor = ActorContext.required();
        var jobId = UUID.randomUUID();
        var visibility = command.visibility() == null || command.visibility().isBlank() ? "ALL" : command.visibility();
        if (!java.util.Set.of("ALL", "QUALITY", "PROJECT").contains(visibility)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "权限类型不合法");
        }
        imports.create(jobId, actor.organizationId(), command.fileId(), command.fileName(),
                command.sha256(), command.format().name(), command.categoryName(), command.projectId(),
                command.stageId(), command.taskId(), visibility, actor.userId());
        try {
            try (var stored = files.open(actor.organizationId(), command.fileId())) {
                var parser = parsers.stream().filter(item -> item.format() == command.format()).findFirst().orElse(null);
                var parsed = parser == null
                        ? parseSource(command, stored.stream().readAllBytes())
                        : parser.parse(stored.stream());
                var editModel = objectMapper.createObjectNode();
                editModel.put("title", command.fileName());
                editModel.put("purpose", "");
                editModel.put("plan", "");
                editModel.put("documentFormat", command.format() == TemplateFormat.XLSX ? "excel" : "word");
                editModel.set("documentSnapshot", parsed.initialEditorSnapshot());
                editModel.put("sourceFileId", command.fileId().toString());
                editModel.put("sourceFileName", command.fileName());
                editModel.put("sourceFileSha256", command.sha256());
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
                var summary = experiments.create(new ExperimentService.CreateCommand(
                        null, command.fileName(), command.categoryId(), command.categoryName(),
                        command.format() == TemplateFormat.XLSX || command.format() == TemplateFormat.XLS || command.format() == TemplateFormat.CSV
                                ? "EXCEL_IMPORT" : "OCR_IMPORT",
                        command.projectId(), command.stageId(), command.taskId(), actor.username(),
                        command.experimentDate(), command.fileId(), null, command.sha256(),
                        parsed.initialEditorSnapshot(), editModel));
                imports.complete(jobId, summary.id(), parsed.initialEditorSnapshot());
                return summary;
            }
        } catch (Exception exception) {
            imports.fail(jobId, exception.getMessage());
            if (exception instanceof ApiException apiException) throw apiException;
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,
                    "实验文件解析失败: " + exception.getMessage());
        }
    }

    public List<ExperimentImportRepository.Job> list() {
        ExperimentAccessPolicy.requireRead();
        return imports.list(ActorContext.required().organizationId());
    }

    @Transactional
    public void delete(UUID id) {
        ExperimentAccessPolicy.requireWrite();
        var actor = ActorContext.required();
        if (imports.delete(actor.organizationId(), id) == 0) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "上传记录不存在或仍在解析中，暂不能删除");
        }
    }

    public record Command(UUID fileId, String fileName, String sha256, TemplateFormat format,
                          UUID categoryId, String categoryName, UUID projectId, UUID stageId,
                          UUID taskId, LocalDate experimentDate, String visibility) {}

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
                .filter(item -> item.isConfigured() && item.supports(command.fileName(), contentType(command.format())))
                .findFirst()
                .orElse(null);
        if (provider == null) return genericParse(command.format(), command.fileName(), bytes);
        try {
            var extracted = provider.extract(new ByteArrayInputStream(bytes), command.fileName(),
                    new MediaExtractionProvider.ExtractionContext(command.fileId(), contentType(command.format()), bytes.length, null));
            return ocrSnapshot(command.fileName(), extracted);
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

    private String contentType(TemplateFormat format) {
        return format == TemplateFormat.PDF ? "application/pdf" : "image/*";
    }
}
