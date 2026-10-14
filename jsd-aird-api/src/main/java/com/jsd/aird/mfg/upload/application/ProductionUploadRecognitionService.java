package com.jsd.aird.mfg.upload.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.mfg.ingest.application.DocumentImagePreprocessor;
import com.jsd.aird.mfg.ingest.application.InstanceWorkbookManifest;
import com.jsd.aird.mfg.ingest.application.WorkbookInstanceExtractor;
import com.jsd.aird.mfg.ingest.application.port.InstanceDocumentRecognitionClient;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.OcrMode;
import com.jsd.aird.kb.infrastructure.MineruDocumentParser;
import com.jsd.aird.rnd.application.ExperimentImagePdfConverter;
import com.jsd.aird.mfg.upload.application.port.ProductionUploadRepository;
import com.jsd.aird.mfg.upload.infrastructure.DocxTableWorkbookConverter;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.excel.WorkbookInstanceParser;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.api.TemplateOfficeFacade;
import com.jsd.aird.tpl.api.TemplateOfficeNormalizationFacade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;

import com.jsd.aird.tpl.application.StandardFieldDictionary;

@Service
public class ProductionUploadRecognitionService {

    private final ProductionUploadRepository uploads;
    private final ProductionOrderRepository orders;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final WorkbookInstanceParser parser;
    private final WorkbookInstanceExtractor extractor;
    private final JsonCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;
    private final DocumentImagePreprocessor imagePreprocessor;
    private final InstanceDocumentRecognitionClient documentRecognitionClient;
    private final MineruDocumentParser mineruParser;
    private final FileStorageFacade fileStorage;
    private final TemplateOfficeNormalizationFacade officeNormalization;
    private final TemplateOfficeFacade officeStructure;
    private final ProductionUploadWorkbookBuilder workbookBuilder;
    private final DocxTableWorkbookConverter docxTableConverter;
    private final WordTemplateValueExtractor wordValueExtractor;
    private final ProductionUploadRecognitionReviewAssembler reviewAssembler;
    private final boolean photoEnabled;

    public ProductionUploadRecognitionService(
            ProductionUploadRepository uploads, ProductionOrderRepository orders,
            FileObjectRepository files, ObjectStorage storage, WorkbookInstanceParser parser,
            WorkbookInstanceExtractor extractor, JsonCanonicalizer canonicalizer, ObjectMapper objectMapper,
            DocumentImagePreprocessor imagePreprocessor,
            InstanceDocumentRecognitionClient documentRecognitionClient,
            MineruDocumentParser mineruParser,
            FileStorageFacade fileStorage, TemplateOfficeNormalizationFacade officeNormalization,
            TemplateOfficeFacade officeStructure,
            ProductionUploadWorkbookBuilder workbookBuilder,
            DocxTableWorkbookConverter docxTableConverter,
            WordTemplateValueExtractor wordValueExtractor,
            ProductionUploadRecognitionReviewAssembler reviewAssembler,
            @Value("${app.production-instance-photo.enabled:false}") boolean photoEnabled
    ) {
        this.uploads = uploads;
        this.orders = orders;
        this.files = files;
        this.storage = storage;
        this.parser = parser;
        this.extractor = extractor;
        this.canonicalizer = canonicalizer;
        this.objectMapper = objectMapper;
        this.imagePreprocessor = imagePreprocessor;
        this.documentRecognitionClient = documentRecognitionClient;
        this.mineruParser = mineruParser;
        this.fileStorage = fileStorage;
        this.officeNormalization = officeNormalization;
        this.officeStructure = officeStructure;
        this.workbookBuilder = workbookBuilder;
        this.docxTableConverter = docxTableConverter;
        this.wordValueExtractor = wordValueExtractor;
        this.reviewAssembler = reviewAssembler;
        this.photoEnabled = photoEnabled;
    }

    public JsonNode process(UUID organizationId, UUID uploadId, UUID fileId) {
        return process(organizationId, uploadId, fileId, "XLSX", null);
    }

    public JsonNode process(UUID organizationId, UUID uploadId, UUID fileId,
                            String sourceType, UUID requestedTemplateVersionId) {
        var normalizedSourceType = sourceType == null || sourceType.isBlank()
                ? "XLSX" : sourceType.trim().toUpperCase(java.util.Locale.ROOT);
        var file = files.find(organizationId, fileId)
                .orElseThrow(() -> new IllegalArgumentException("生产单来源文件不存在"));
        if ("PHOTO".equals(normalizedSourceType)) {
            if (isDocument(file)) return processDocument(organizationId, uploadId, file, requestedTemplateVersionId);
            return processPhoto(organizationId, uploadId, file, requestedTemplateVersionId);
        }
        if (!"XLSX".equals(normalizedSourceType)) {
            throw new IllegalArgumentException("生产单来源必须是 XLSX 或 PHOTO");
        }
        uploads.updateRecognitionProgress(uploadId, "PARSING", 15, "LOADING_SOURCE");
        var originalName = file.originalName() == null ? "" : file.originalName().toLowerCase(java.util.Locale.ROOT);
        if (!originalName.endsWith(".xlsx") && !originalName.endsWith(".xls")) {
            throw new IllegalArgumentException("生产单识别目前只支持 XLSX 或 XLS 文件");
        }
        byte[] content;
        try (var stored = storage.get(file.objectKey())) {
            content = stored.stream().readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("生产单来源文件读取失败", exception);
        }

        uploads.updateRecognitionProgress(uploadId, "PARSING", 30, "PARSING_STRUCTURE");
        var parsed = parser.parseInstance(new ByteArrayInputStream(content));
        uploads.updateRecognitionProgress(uploadId, "MATCHING_TEMPLATE", 50, "MATCHING_TEMPLATE");

        var manifest = InstanceWorkbookManifest.read(content);
        var matches = rankTemplates(organizationId, parsed.snapshot(), manifest);
        var selected = requestedTemplateVersionId == null
                ? (matches.isEmpty() ? null : matches.getFirst())
                : requestedMatch(organizationId, requestedTemplateVersionId, parsed.snapshot());
        var exact = selected != null && selected.exact();
        var userSelected = requestedTemplateVersionId != null;
        var auto = selected != null && (userSelected || exact || selected.score() >= .90d && margin(matches) >= .10d);
        var matchMode = userSelected ? "USER_SELECTED_TEMPLATE"
                : exact ? "EXACT_MANIFEST" : auto ? "SIMILAR_AUTO" : "NO_TEMPLATE";

        uploads.updateRecognitionProgress(uploadId, "EXTRACTING", 70, "EXTRACTING_VALUES");
        var result = objectMapper.createObjectNode();
        result.put("sourceType", "XLSX");
        result.put("matchMode", matchMode);
        // A template improves field extraction, but it is not required to open
        // and edit an uploaded workbook.  When matching is inconclusive, keep
        // the parsed source snapshot and continue as a template-free draft.
        result.put("requiresTemplateSelection", false);
        if (!auto) result.put("message", "未选择模板，已按原文件生成可编辑生产单");
        result.set("templateCandidates", candidates(matches));
        result.set("issues", objectMapper.valueToTree(parsed.issues()));

        // Experiment uploads use the complete template recognition pipeline;
        // the light rule pass below only finds a few explicit label/value pairs.
        // Reuse the complete candidates for production Excel as well, without
        // replacing the production order's own workbook snapshot.
        TemplateOfficeFacade.RecognitionBatch fullRecognition = null;
        if (originalName.endsWith(".xlsx")) {
            try {
                var upload = uploads.find(organizationId, uploadId).orElseThrow();
                fullRecognition = officeStructure.recognizeExperimentWorkbook(
                        organizationId, upload.createdBy(), fileId, fileId).batch();
            } catch (RuntimeException exception) {
                result.put("recognitionWarning", "完整字段识别未完成，已使用规则识别结果："
                        + exception.getMessage());
            }
        }
        result.put("recognitionMode", fullRecognition == null ? "RULE" : "FULL");

        UUID selectedVersionId = null;
        JsonNode metadataMapping = objectMapper.createArrayNode();
        double score = 0d;
        if (selected != null && auto) {
            selectedVersionId = selected.versionId();
            score = selected.score();
            var template = orders.findPublishedTemplate(organizationId, selected.versionId()).orElseThrow();
            metadataMapping = template.mapping();
            var extraction = extractor.extract(template.schema(), template.mapping(), parsed.snapshot());
            result.put("templateMatchScore", score);
            result.put("selectedTemplateVersionId", selected.versionId().toString());
            result.set("data", extraction.data());
            result.set("mapping", template.mapping().deepCopy());
            result.set("items", objectMapper.valueToTree(extraction.items()));
            // The workspace reviews fields with the template center's field manager,
            // so the recognition result carries that contract plus the engine's own
            // candidates for anything the template's mapping does not bind.
            reviewAssembler.attach(result, "XLSX", file.originalName(), parsed.structureSummary(),
                    template.schema(), template.mapping(), extraction.data(), fullRecognition);
            // A template can contain a perfectly valid physical mapping while
            // its field is still carrying an AUTO.* code (for example when the
            // template was imported before standard-field confirmation).  The
            // extractor has already found the value; normalize the semantic
            // code before persisting the upload so the production list columns
            // can consume it.
            normalizeStandardFieldCodes(result, template.mapping());
        } else {
            result.put("templateMatchScore", 0d);
            result.set("data", objectMapper.createObjectNode());
            result.set("mapping", objectMapper.createArrayNode());
            result.set("items", objectMapper.createArrayNode());
            // Free Excel uploads still need a review queue. Reuse the template
            // recognition contract with an empty mapping and persist its field
            // suggestions so confirmation has a durable target.
            reviewAssembler.attach(result, "XLSX", file.originalName(), parsed.structureSummary(),
                    objectMapper.createObjectNode(), objectMapper.createArrayNode(), result.path("data"),
                    fullRecognition);
            var items = (ArrayNode) result.path("items");
            for (var suggestion : result.path("recognitionReview").path("items")) {
                if (!"SCALAR".equals(suggestion.path("kind").asText("SCALAR"))) continue;
                var payload = suggestion.path("payload");
                var dataPath = payload.path("dataPath").asText("");
                if (dataPath.isBlank()) continue;
                var item = objectMapper.createObjectNode();
                item.put("itemKey", suggestion.path("id").asText(UUID.randomUUID().toString()));
                item.put("itemKind", suggestion.path("kind").asText("SCALAR"));
                item.put("fieldCode", payload.path("fieldCode").asText(""));
                item.put("dataPath", dataPath);
                item.put("confidence", suggestion.path("confidence").asDouble(0.5d));
                item.set("sourceLocator", payload.path("locator").deepCopy());
                items.add(item);
            }
        }

        uploads.replaceRecognitionFields(uploadId, extractionFields(result));
        uploads.updateRecognizedMetadata(uploadId,
                firstResultValue(result, metadataMapping, "PRODUCTION.ORDER_NO"),
                firstResultValue(result, metadataMapping, "PRODUCTION.PRODUCT_NAME"),
                firstResultValue(result, metadataMapping, "PRODUCTION.CATEGORY"),
                firstResultDate(result, metadataMapping, "PRODUCTION.MANUFACTURE_DATE"));
        uploads.updateRecognitionProgress(uploadId, "EXTRACTING", 90, "BUILDING_PREVIEW");
        uploads.completeRecognition(uploadId, selectedVersionId, matchMode, score,
                parsed.structureSummary(), parsed.snapshot(), result);
        return result;
    }

    private JsonNode processPhoto(UUID organizationId, UUID uploadId,
                                  com.jsd.aird.ops.application.port.FileObjectRepository.FileObject file,
                                  UUID requestedTemplateVersionId) {
        if (!photoEnabled) throw new IllegalArgumentException("生产单图片识别功能尚未启用");
        if (!isImage(file)) throw new IllegalArgumentException("生产单图片来源必须是图片文件");
        uploads.updateRecognitionProgress(uploadId, "MATCHING_TEMPLATE", 35, "MATCHING_TEMPLATE");
        if (requestedTemplateVersionId == null) {
            var result = objectMapper.createObjectNode();
            result.put("sourceType", "PHOTO");
            result.put("matchMode", "USER_REVIEW");
            result.put("templateMatchScore", 0d);
            result.put("requiresTemplateSelection", false);
            result.put("message", "未选择模板，已保存原图并可直接预览；选择模板后可继续识别字段");
            result.set("templateCandidates", photoCandidates(orders.listPublishedTemplates(organizationId)));
            result.set("data", objectMapper.createObjectNode());
            result.set("mapping", objectMapper.createArrayNode());
            result.set("items", objectMapper.createArrayNode());
            result.set("issues", objectMapper.createArrayNode());
            uploads.replaceRecognitionFields(uploadId, List.of());
            uploads.completeRecognition(uploadId, null, "USER_REVIEW", 0d,
                    objectMapper.createObjectNode().put("sourceType", "PHOTO"), null, result);
            return result;
        }

        uploads.updateRecognitionProgress(uploadId, "EXTRACTING", 70, "RECOGNIZING_IMAGE_VALUES");
        if (!mineruParser.isConfigured() || !documentRecognitionClient.isConfigured()) {
            throw new IllegalArgumentException("生产单图片识别所需的 MinerU 或字段提取模型尚未配置");
        }
        var template = orders.findPublishedTemplate(organizationId, requestedTemplateVersionId)
                .orElseThrow(() -> new IllegalArgumentException("已发布模板不存在或已失效"));
        byte[] content;
        try (var stored = storage.get(file.objectKey())) {
            content = stored.stream().readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("生产单图片读取失败", exception);
        }
        DocumentParser.ParsedDocument parsed;
        try {
            var pdf = ExperimentImagePdfConverter.convert(content);
            var pdfName = file.originalName().replaceFirst("(?i)\\.[^.]+$", "") + ".pdf";
            parsed = mineruParser.parse(new ByteArrayInputStream(pdf), pdfName,
                    new DocumentParser.ParseContext(organizationId, null, null,
                            "application/pdf", pdf.length, OcrMode.ON, false));
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("生产单图片无法转换为 MinerU 可识别的 PDF", exception);
        }
        var recognizedText = parsed.blocks().stream()
                .map(DocumentParser.TextBlock::content)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        if (recognizedText.isBlank()) throw new IllegalArgumentException("MinerU 未识别到生产单内容");
        var recognized = documentRecognitionClient.recognizeText(
                template.schema(), template.mapping(), recognizedText);
        var result = objectMapper.createObjectNode();
        result.put("sourceType", "PHOTO");
        result.put("matchMode", "USER_SELECTED_TEMPLATE");
        result.put("templateMatchScore", 1d);
        result.put("requiresTemplateSelection", false);
        result.put("selectedTemplateVersionId", requestedTemplateVersionId.toString());
        result.put("model", recognized.model());
        result.put("ocrProvider", "MINERU");
        result.put("ocrParserVersion", parsed.parserVersion());
        result.set("templateCandidates", photoCandidates(orders.listPublishedTemplates(organizationId)));
        result.set("data", recognized.data() == null ? objectMapper.createObjectNode() : recognized.data());
        result.set("mapping", template.mapping().deepCopy());
        result.set("items", photoItems(recognized.items()));
        result.set("issues", objectMapper.createArrayNode());
        // Image models may return the template-local field name instead of the
        // standard production code. Resolve it before the summary fields are
        // copied to the production-upload record.
        normalizeStandardFieldCodes(result, template.mapping());
        reviewAssembler.attach(result, "PHOTO", "", null,
                template.schema(), template.mapping(), result.path("data"));
        uploads.replaceRecognitionFields(uploadId, extractionFields(result));
        uploads.updateRecognizedMetadata(uploadId,
                firstResultValue(result, template.mapping(), "PRODUCTION.ORDER_NO"),
                firstResultValue(result, template.mapping(), "PRODUCTION.PRODUCT_NAME"),
                firstResultValue(result, template.mapping(), "PRODUCTION.CATEGORY"),
                firstResultDate(result, template.mapping(), "PRODUCTION.MANUFACTURE_DATE"));
        uploads.updateRecognitionProgress(uploadId, "EXTRACTING", 90, "BUILDING_PREVIEW");
        // Photo recognition only yields values; the workbook snapshot is what makes
        // the record editable and exportable, so it is generated right away.  A
        // failure must not fail the upload: the photo preview stays available and
        // the workspace can retry the conversion on demand.
        var workbook = buildPhotoWorkbook(organizationId, requestedTemplateVersionId, result);
        var summary = objectMapper.createObjectNode().put("sourceType", "PHOTO").put("imageCount", 1);
        if (workbook != null) summary.put("workbookGenerated", true);
        uploads.completeRecognition(uploadId, requestedTemplateVersionId, "USER_SELECTED_TEMPLATE", 1d,
                summary, workbook, result);
        return result;
    }

    /**
     * Writes the values recognized from the photo into the selected template's
     * workbook snapshot.  Returns {@code null} when that is not possible, and
     * records why so the workspace can offer a retry instead of a silent gap.
     */
    private JsonNode buildPhotoWorkbook(UUID organizationId, UUID templateVersionId, ObjectNode result) {
        try {
            var workbook = workbookBuilder.build(organizationId, templateVersionId, result.path("data"));
            result.put("workbookStatus", "GENERATED");
            return workbook;
        } catch (Exception exception) {
            result.put("workbookStatus", "FAILED");
            result.put("workbookMessage",
                    "生成 Excel 工作簿失败，可在工作区点击“转成 Excel”重试：" + exception.getMessage());
            return null;
        }
    }

    private JsonNode processDocument(UUID organizationId, UUID uploadId,
                                     com.jsd.aird.ops.application.port.FileObjectRepository.FileObject file,
                                     UUID requestedTemplateVersionId) {
        if (!isDocument(file)) throw new IllegalArgumentException("生产单文档来源必须是 DOCX 或 DOC 文件");
        uploads.updateRecognitionProgress(uploadId, "EXTRACTING", 70, "BUILDING_PREVIEW");
        var result = objectMapper.createObjectNode();
        result.put("sourceType", "DOCX");
        var artifact = resolveWordArtifact(organizationId, uploadId, file, result);
        // A selected template turns the document into an Excel order: the values are
        // read out of the Word structure through the template's own field labels and
        // written into the template workbook, which puts the record on the same
        // edit/save/export path as an uploaded spreadsheet.
        var fromTemplate = buildTemplateWorkbook(organizationId, requestedTemplateVersionId, artifact, result);
        if (fromTemplate != null) {
            result.put("matchMode", "USER_SELECTED_TEMPLATE");
            result.put("templateMatchScore", 1d);
            result.put("requiresTemplateSelection", false);
            result.put("selectedTemplateVersionId", requestedTemplateVersionId.toString());
            result.put("message", "已按所选模板把 Word 解析结果填入 Excel，可直接在页面内修改");
            result.set("templateCandidates", photoCandidates(orders.listPublishedTemplates(organizationId)));
            result.set("data", fromTemplate.data());
            result.set("mapping", fromTemplate.mapping());
            result.set("items", objectMapper.createArrayNode());
            result.set("issues", objectMapper.createArrayNode());
            uploads.replaceRecognitionFields(uploadId, List.of());
            uploads.updateRecognizedMetadata(uploadId,
                    firstMappedValue(fromTemplate.data(), fromTemplate.mapping(), "PRODUCTION.ORDER_NO"),
                    firstMappedValue(fromTemplate.data(), fromTemplate.mapping(), "PRODUCTION.PRODUCT_NAME"),
                    firstMappedValue(fromTemplate.data(), fromTemplate.mapping(), "PRODUCTION.CATEGORY"),
                    firstMappedDate(fromTemplate.data(), fromTemplate.mapping(), "PRODUCTION.MANUFACTURE_DATE"));
            uploads.completeRecognition(uploadId, requestedTemplateVersionId, "USER_SELECTED_TEMPLATE", 1d,
                    fromTemplate.summary(), fromTemplate.snapshot(), result);
            return result;
        }
        // A Word form is edited in the sheet workspace: its table becomes the
        // editable workbook, which puts Word production orders on the same
        // save/publish/export path as an uploaded spreadsheet.  Documents without
        // a table keep the rich-text document path.
        var worksheet = convertTablesToWorksheet(artifact, result);
        var editable = artifact == null || worksheet != null
                ? null : parseEditableWordDocument(artifact, result);
        result.put("matchMode", "USER_REVIEW");
        result.put("templateMatchScore", 0d);
        result.put("requiresTemplateSelection", false);
        result.put("message", worksheet != null
                ? "已从 Word 表格生成可编辑的工作簿，可直接在页面内修改"
                : editable == null
                        ? "DOCX 生产单已保存，可直接预览原件"
                        : "已生成可编辑的 Word 文档，可直接在页面内修改");
        // Offering the published templates keeps the workspace's template picker
        // usable after the fact: choosing one re-runs recognition, which then reads
        // the document through the template and fills the template workbook.
        result.set("templateCandidates", photoCandidates(orders.listPublishedTemplates(organizationId)));
        result.set("data", objectMapper.createObjectNode());
        result.set("mapping", objectMapper.createArrayNode());
        result.set("items", objectMapper.createArrayNode());
        result.set("issues", objectMapper.createArrayNode());
        uploads.replaceRecognitionFields(uploadId, List.of());
        uploads.completeRecognition(uploadId, null, "USER_REVIEW", 0d,
                worksheet != null ? worksheet.summary()
                        : editable == null
                                ? objectMapper.createObjectNode().put("sourceType", "DOCX")
                                : editable.structureSummary(),
                worksheet != null ? worksheet.snapshot()
                        : editable == null ? null : editable.editorSnapshot(),
                result);
        return result;
    }

    /**
     * Fills the Excel generated from a selected template with the values read out of
     * the Word document.  Returns {@code null} when there is no template, the
     * document could not be parsed, or no field label matched, so the caller keeps
     * its document-based behaviour and an upload never fails over this step.
     */
    private TemplateWorkbook buildTemplateWorkbook(UUID organizationId, UUID templateVersionId,
                                                   WordArtifact artifact, ObjectNode result) {
        if (templateVersionId == null || artifact == null) return null;
        try {
            var template = orders.findPublishedTemplate(organizationId, templateVersionId).orElse(null);
            if (template == null || !"XLSX".equalsIgnoreCase(template.format())) {
                result.put("workbookStatus", "SKIPPED");
                result.put("workbookMessage", "所选模板不是 Excel 模板，本文档按原格式处理");
                return null;
            }
            var structure = parseWordStructure(artifact);
            var documentIr = structure.path("documentIR");
            var templateSnapshot = workbookBuilder.templateSnapshot(organizationId, templateVersionId);
            var data = wordValueExtractor.extract(template.mapping(), templateSnapshot, documentIr);
            if (data.isEmpty()) {
                result.put("workbookStatus", "EMPTY");
                result.put("workbookMessage", "未能在 Word 文档中匹配到模板字段，本文档按原格式处理");
                return null;
            }
            var filled = workbookBuilder.fill(templateSnapshot, template.mapping(), data);
            reviewAssembler.attach(result, "DOCX", artifact.name(), structure,
                    template.schema(), template.mapping(), data);
            result.put("workbookStatus", "GENERATED");
            result.put("workbookMessage", "已按模板把 " + data.size() + " 个字段从 Word 写入 Excel");
            return new TemplateWorkbook(filled, data, template.mapping().deepCopy(),
                    objectMapper.createObjectNode()
                            .put("sourceType", "DOCX").put("workbookGenerated", true)
                            .put("matchedFieldCount", data.size()));
        } catch (Exception exception) {
            result.put("workbookStatus", "FAILED");
            result.put("workbookMessage", "按模板生成 Excel 失败，本文档按原格式处理：" + exception.getMessage());
            return null;
        }
    }

    /** The document structure (documentIR) the template field values are read from. */
    private JsonNode parseWordStructure(WordArtifact artifact) {
        try {
            return officeStructure.parseOffice("DOCX", new ByteArrayInputStream(artifact.content()))
                    .structureSummary();
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private record TemplateWorkbook(JsonNode snapshot, JsonNode data, JsonNode mapping, JsonNode summary) {
    }

    /**
     * Bumped whenever the produced Word layout changes shape (table geometry,
     * borders, nested tables).  The workspace compares this with the value stored
     * on a record and regenerates records that an older layout produced, because
     * the editable document is created during recognition and a restart alone
     * never refreshes it.  Keep {@code WORD_LAYOUT_VERSION} in the web workspace
     * page in sync.
     */
    static final int WORD_LAYOUT_VERSION = 2;

    /**
     * Resolves the DOCX artifact that owns the editable content.  Native .docx
     * uploads are used as-is; legacy .doc binaries are normalized through
     * LibreOffice and staged as a derived file.
     */
    private WordArtifact resolveWordArtifact(
            UUID organizationId, UUID uploadId,
            com.jsd.aird.ops.application.port.FileObjectRepository.FileObject file,
            ObjectNode result) {
        var name = file.originalName() == null ? "" : file.originalName().toLowerCase(Locale.ROOT);
        byte[] source;
        try (var stored = storage.get(file.objectKey())) {
            source = stored.stream().readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("生产单来源文件读取失败", exception);
        }
        var workingFileId = file.id();
        var workingName = file.originalName();
        if (name.endsWith(".doc")) {
            var normalized = normalizeLegacyWord(organizationId, uploadId, file, source, result);
            if (normalized == null) return null;
            workingFileId = normalized.fileId();
            workingName = normalized.name();
            source = normalized.content();
        }
        return new WordArtifact(workingFileId, workingName, source);
    }

    /**
     * Parses the artifact into the document structure and the Univer document
     * snapshot the rich-text workspace opens and the export rebuilds from.
     */
    private EditableWordDocument parseEditableWordDocument(WordArtifact artifact, ObjectNode result) {
        try {
            var parsed = officeStructure.parseOffice("DOCX", new ByteArrayInputStream(artifact.content()));
            var structureSummary = parsed.structureSummary();
            var editorSnapshot = parsed.initialEditorSnapshot();
            var wordDocument = result.putObject("wordDocument");
            wordDocument.put("sourceDocxFileId", artifact.fileId().toString());
            wordDocument.put("workingDocxFileId", artifact.fileId().toString());
            wordDocument.put("workingDocxName", artifact.name() == null ? "" : artifact.name());
            wordDocument.put("structureHash",
                    structureSummary.path("documentIR").path("structureHash").asText(""));
            wordDocument.put("snapshotVersion",
                    editorSnapshot == null ? 0 : editorSnapshot.path("snapshotFormatVersion").asInt(0));
            wordDocument.put("layoutVersion", WORD_LAYOUT_VERSION);
            return new EditableWordDocument(structureSummary, editorSnapshot);
        } catch (Exception exception) {
            result.put("wordStructureStatus", "FAILED");
            result.put("wordStructureMessage",
                    "Word 文档解析失败，暂不支持在线编辑，可下载原件查看：" + exception.getMessage());
            return null;
        }
    }

    /**
     * Turns the document's tables into a workbook snapshot.  Returns {@code null}
     * when the document has no table, or when the conversion failed, so the caller
     * falls back to the rich-text document path and never fails the upload.
     */
    private DocxTableWorkbookConverter.ConvertedWorksheet convertTablesToWorksheet(
            WordArtifact artifact, ObjectNode result) {
        if (artifact == null) return null;
        try {
            var worksheet = docxTableConverter.convert(artifact.content(), artifact.name());
            if (worksheet == null) {
                result.put("docxWorksheetStatus", "NO_TABLE");
                return null;
            }
            result.put("docxWorksheetStatus", "GENERATED");
            result.set("docxWorksheet", worksheet.summary());
            return worksheet;
        } catch (Exception exception) {
            result.put("docxWorksheetStatus", "FAILED");
            result.put("docxWorksheetMessage",
                    "Word 表格转换为工作表失败：" + exception.getMessage());
            return null;
        }
    }

    private record WordArtifact(UUID fileId, String name, byte[] content) {
    }

    /**
     * Legacy .doc uploads keep the original file id (the idempotency key is the
     * original sha256), so the editable copy is normalized into DOCX here and
     * referenced from the recognition result.  Normalization failures degrade
     * gracefully: the upload still completes and offers the original download.
     */
    private NormalizedWordDocument normalizeLegacyWord(
            UUID organizationId, UUID uploadId,
            com.jsd.aird.ops.application.port.FileObjectRepository.FileObject file,
            byte[] source, ObjectNode result) {
        try {
            var normalized = officeNormalization.normalizeOfficeFileForPreview(
                    file.originalName(), file.contentType(), source);
            var upload = uploads.find(organizationId, uploadId).orElseThrow(() ->
                    new IllegalStateException("生产单上传记录不存在"));
            var staged = fileStorage.stageDerived(organizationId, upload.createdBy(),
                    normalized.normalizedName(), normalized.normalizedContentType(), "PRODUCTION_UPLOAD",
                    new ByteArrayInputStream(normalized.content()));
            fileStorage.activate(staged.fileId());
            result.put("normalizedFileId", staged.fileId().toString());
            result.put("normalizedFileName", normalized.normalizedName());
            result.put("normalizationStatus", normalized.status());
            return new NormalizedWordDocument(staged.fileId(), normalized.normalizedName(),
                    normalized.content());
        } catch (Exception exception) {
            result.put("normalizationStatus", "FAILED");
            result.put("normalizationMessage",
                    "旧版 .doc 在线预览转换失败，可下载原件查看：" + exception.getMessage());
            return null;
        }
    }

    private record EditableWordDocument(JsonNode structureSummary, JsonNode editorSnapshot) {
    }

    private record NormalizedWordDocument(UUID fileId, String name, byte[] content) {
    }

    private List<ProductionUploadRepository.RecognitionField> extractionFields(
            com.fasterxml.jackson.databind.node.ObjectNode result) {
        var fields = new ArrayList<ProductionUploadRepository.RecognitionField>();
        for (var item : result.path("items")) {
            fields.add(new ProductionUploadRepository.RecognitionField(
                    UUID.randomUUID(), item.path("itemKey").asText(UUID.randomUUID().toString()),
                    item.path("itemKind").asText("SCALAR"), item.path("bindingId").asText(""),
                    item.path("fieldCode").asText(""), item.path("dataPath").asText(""),
                    item.has("recordIndex") && !item.path("recordIndex").isNull()
                            ? item.path("recordIndex").asInt() : null,
                    itemValue(item), itemValue(item), item.path("sourceLocator"),
                    item.path("confidence").asDouble(1d), uploadReviewStatus(item)));
        }
        for (var item : result.path("recognitionReview").path("items")) {
            var kind = item.path("kind").asText("");
            var payload = item.path("payload");
            var dataPath = payload.path("dataPath").asText("");
            if (dataPath.isBlank()) continue;
            var fieldCode = payload.path("fieldCode").asText("");
            var suggestionId = item.path("id").asText("");
            var bindingId = payload.path("bindingId").asText("");
            // Suggestions share the binding identity with extractor items (both
            // fall back to bindingId), so a binding already covered by a real
            // extraction must not be persisted a second time — the unique
            // (production_upload_id, item_key) constraint would reject it.
            var covered = fields.stream().anyMatch(field -> field.itemKey().equals(suggestionId)
                    || (!bindingId.isBlank() && bindingId.equals(field.bindingId()))
                    || (dataPath.equals(field.dataPath()) && fieldCode.equals(field.fieldCode())));
            if (covered) continue;
            fields.add(new ProductionUploadRepository.RecognitionField(
                    UUID.randomUUID(), item.path("id").asText(UUID.randomUUID().toString()),
                    kind.isBlank() ? "SCALAR" : kind, payload.path("bindingId").asText(""),
                    fieldCode, dataPath, null,
                    null, null, payload.path("locator"),
                    item.path("confidence").asDouble(0.5d), "PENDING"));
        }
        // Final safety net: never emit two rows with the same item_key.
        var seenKeys = new HashSet<String>();
        fields.removeIf(field -> !field.itemKey().isBlank() && !seenKeys.add(field.itemKey()));
        return fields;
    }

    /** Recognition output is never a human confirmation on its own. */
    private String uploadReviewStatus(JsonNode item) {
        return "CONFIRMED".equalsIgnoreCase(item.path("reviewStatus").asText())
                ? "CONFIRMED" : "PENDING";
    }

    private String firstValue(JsonNode result, String fieldCode) {
        for (var item : result.path("items")) {
            if (fieldCode.equals(item.path("fieldCode").asText(""))) {
                var value = itemValue(item);
                if (value != null && value.isValueNode() && !value.isNull()) return value.asText("").trim();
            }
        }
        return "";
    }

    private String firstMappedValue(JsonNode data, JsonNode mapping, String fieldCode) {
        if (data == null || mapping == null || !mapping.isArray()) return "";
        for (var binding : mapping) {
            if (!fieldCode.equals(mappedFieldCode(binding))) continue;
            var dataPath = binding.path("dataPath").asText("");
            if (dataPath.isBlank()) continue;
            var value = data.at(dataPath);
            if (value.isValueNode() && !value.isNull()) {
                var text = value.asText("").trim();
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private String firstResultValue(JsonNode result, JsonNode mapping, String fieldCode) {
        var itemValue = firstValue(result, fieldCode);
        return itemValue.isBlank() ? firstMappedValue(result.path("data"), mapping, fieldCode) : itemValue;
    }

    private LocalDate firstMappedDate(JsonNode data, JsonNode mapping, String fieldCode) {
        if (data == null || mapping == null || !mapping.isArray()) return null;
        for (var binding : mapping) {
            if (!fieldCode.equals(mappedFieldCode(binding))) continue;
            var dataPath = binding.path("dataPath").asText("");
            if (dataPath.isBlank()) continue;
            var date = parseDate(data.at(dataPath));
            if (date != null) return date;
        }
        return null;
    }

    private LocalDate firstResultDate(JsonNode result, JsonNode mapping, String fieldCode) {
        var itemDate = firstDate(result, fieldCode);
        return itemDate == null ? firstMappedDate(result.path("data"), mapping, fieldCode) : itemDate;
    }

    private String mappedFieldCode(JsonNode binding) {
        return standardFieldCode(
                binding.path("fieldCode").asText(""),
                binding.path("standardFieldName").asText(""),
                binding.path("fieldName").asText(""),
                binding.path("name").asText(""),
                binding.path("dataPath").asText("")
        );
    }

    private LocalDate firstDate(JsonNode result, String fieldCode) {
        for (var item : result.path("items")) {
            if (!fieldCode.equals(item.path("fieldCode").asText(""))) continue;
            var value = itemValue(item);
            var date = parseDate(value);
            if (date != null) return date;
        }
        return null;
    }

    /**
     * Make template-center mappings usable by the production-upload summary.
     * The summary columns are backed by the versioned standard field codes,
     * while imported templates may still expose a local field code.  Resolve
     * the code from the binding label/data path without changing the original
     * template mapping stored in the recognition result.
     */
    private void normalizeStandardFieldCodes(JsonNode result, JsonNode mapping) {
        if (!result.path("items").isArray() || !mapping.isArray()) return;
        var bindings = new HashMap<String, JsonNode>();
        mapping.forEach(binding -> {
            var id = binding.path("bindingId").asText("");
            if (!id.isBlank()) bindings.put(id, binding);
        });
        for (var itemNode : result.path("items")) {
            if (!(itemNode instanceof com.fasterxml.jackson.databind.node.ObjectNode item)) continue;
            var binding = bindings.get(item.path("bindingId").asText(""));
            var current = item.path("fieldCode").asText("");
            var resolved = standardFieldCode(
                    current,
                    binding == null ? "" : binding.path("standardFieldName").asText(""),
                    binding == null ? "" : binding.path("fieldName").asText(""),
                    binding == null ? "" : binding.path("name").asText(""),
                    binding == null ? "" : binding.path("dataPath").asText(""),
                    item.path("dataPath").asText("")
            );
            if (!resolved.isBlank()) item.put("fieldCode", resolved);
        }
    }

    private String standardFieldCode(String... candidates) {
        for (var candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            if (candidate.startsWith("PRODUCTION.")) return candidate;
            var matched = StandardFieldDictionary.match(candidate);
            if (matched.isPresent()) return matched.get().fieldCode();
            var compact = candidate
                    .replace("/", "")
                    .replace("_", "")
                    .replace("-", "")
                    .replace(" ", "")
                    .toLowerCase(Locale.ROOT);
            if (compact.endsWith("orderno")) return "PRODUCTION.ORDER_NO";
            if (compact.endsWith("productname")) return "PRODUCTION.PRODUCT_NAME";
            if (compact.endsWith("category")) return "PRODUCTION.CATEGORY";
            if (compact.endsWith("manufacturedate") || compact.endsWith("productiondate")) {
                return "PRODUCTION.MANUFACTURE_DATE";
            }
        }
        return "";
    }

    private LocalDate parseDate(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) {
            // Excel's default 1900 date system (including its historical
            // 1900-leap-year compatibility offset).
            var serial = value.asDouble();
            if (serial >= 1d && serial < 300000d) {
                return LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(serial));
            }
        }
        var text = value.asText("").trim();
        if (text.isBlank()) return null;
        try { return LocalDate.parse(text); } catch (DateTimeParseException ignored) { }
        for (var pattern : List.of("yyyy/M/d", "yyyy/M/d H:mm", "yyyy-MM-dd HH:mm:ss", "yyyy年M月d日")) {
            try {
                TemporalAccessor parsed = DateTimeFormatter.ofPattern(pattern).parse(text);
                return LocalDate.from(parsed);
            } catch (RuntimeException ignored) { }
        }
        try {
            var serial = Double.parseDouble(text);
            if (serial >= 1d && serial < 300000d) {
                return LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(serial));
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }

    private JsonNode itemValue(JsonNode item) {
        if (item.hasNonNull("value")) return item.get("value");
        if (item.hasNonNull("normalizedValue")) return item.get("normalizedValue");
        return null;
    }

    private ArrayNode photoItems(List<InstanceDocumentRecognitionClient.ValueItem> items) {
        var result = objectMapper.createArrayNode();
        if (items == null) return result;
        items.forEach(item -> {
            var value = result.addObject();
            value.put("itemKey", item.itemKey());
            value.put("itemKind", item.itemKind());
            value.put("bindingId", item.bindingId());
            value.put("fieldCode", item.fieldCode());
            value.put("dataPath", item.dataPath());
            if (item.recordKey() == null) value.putNull("recordKey"); else value.put("recordKey", item.recordKey());
            if (item.recordIndex() == null) value.putNull("recordIndex"); else value.put("recordIndex", item.recordIndex());
            value.set("rawValue", item.rawValue() == null ? objectMapper.nullNode() : item.rawValue());
            value.set("normalizedValue", item.normalizedValue() == null ? objectMapper.nullNode() : item.normalizedValue());
            value.set("sourceLocator", item.sourceLocator() == null ? objectMapper.createObjectNode() : item.sourceLocator());
            value.put("confidence", item.confidence());
            value.put("handwritten", item.handwritten());
            value.put("reviewStatus", item.handwritten() || item.confidence() < .90d ? "NEEDS_REVIEW" : "EXTRACTED");
        });
        return result;
    }

    private boolean isImage(com.jsd.aird.ops.application.port.FileObjectRepository.FileObject file) {
        var contentType = file.contentType() == null ? "" : file.contentType().toLowerCase(java.util.Locale.ROOT);
        var name = file.originalName() == null ? "" : file.originalName().toLowerCase(java.util.Locale.ROOT);
        return contentType.startsWith("image/") || name.matches(".*\\.(png|jpe?g|gif|webp|bmp|tiff?)$");
    }

    private boolean isDocument(com.jsd.aird.ops.application.port.FileObjectRepository.FileObject file) {
        var contentType = file.contentType() == null ? "" : file.contentType().toLowerCase(java.util.Locale.ROOT);
        var name = file.originalName() == null ? "" : file.originalName().toLowerCase(java.util.Locale.ROOT);
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType)
                || name.endsWith(".docx")
                || "application/msword".equals(contentType)
                || name.endsWith(".doc");
    }

    private String imageContentType(com.jsd.aird.ops.application.port.FileObjectRepository.FileObject file) {
        var contentType = file.contentType() == null ? "" : file.contentType().toLowerCase(java.util.Locale.ROOT);
        if (contentType.startsWith("image/")) return contentType;
        var name = file.originalName() == null ? "" : file.originalName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    public void fail(UUID uploadId, String message) {
        uploads.failRecognition(uploadId, message);
    }

    private List<Match> rankTemplates(UUID organizationId, JsonNode snapshot, InstanceWorkbookManifest manifest) {
        var result = new ArrayList<Match>();
        for (var candidate : orders.listPublishedTemplates(organizationId)) {
            var template = orders.findPublishedTemplate(organizationId, candidate.versionId()).orElse(null);
            if (template == null || !"XLSX".equals(template.format())) continue;
            var exact = manifest != null && candidate.versionId().equals(manifest.templateVersionId())
                    && canonicalizer.hash(template.schema()).equals(manifest.schemaHash())
                    && canonicalizer.hash(template.mapping()).equals(manifest.mappingHash());
            var score = exact ? 1d : similarity(template.mapping(), snapshot);
            result.add(new Match(candidate.versionId(), candidate.templateCode(), candidate.name(), score, exact));
        }
        result.sort(Comparator.comparing(Match::exact).thenComparingDouble(Match::score).reversed());
        return result.stream().limit(3).toList();
    }

    private Match requestedMatch(UUID organizationId, UUID versionId, JsonNode snapshot) {
        var candidate = orders.listPublishedTemplates(organizationId).stream()
                .filter(item -> versionId.equals(item.versionId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("已发布模板不存在或已失效"));
        var template = orders.findPublishedTemplate(organizationId, versionId)
                .orElseThrow(() -> new IllegalArgumentException("已发布模板不存在或已失效"));
        return new Match(candidate.versionId(), candidate.templateCode(), candidate.name(),
                similarity(template.mapping(), snapshot), false);
    }

    private double similarity(JsonNode mapping, JsonNode snapshot) {
        var expectedSheets = new HashSet<String>();
        var expectedLabels = new HashSet<String>();
        mapping.forEach(binding -> {
            add(expectedSheets, binding.path("locator").path("sheetName").asText());
            add(expectedLabels, binding.path("fieldName").asText());
            add(expectedLabels, binding.path("label").asText());
        });
        var actualSheets = new HashSet<String>();
        var actualLabels = new HashSet<String>();
        snapshot.path("sheets").fields().forEachRemaining(entry -> {
            var sheet = entry.getValue();
            add(actualSheets, sheet.path("name").asText());
            sheet.path("cellData").forEach(row -> row.forEach(cell -> {
                var value = cell.path("v");
                if (value.isTextual() && value.asText().length() <= 100) add(actualLabels, value.asText());
            }));
        });
        return rounded(jaccard(expectedSheets, actualSheets) * .35d
                + jaccard(expectedLabels, actualLabels) * .65d);
    }

    private void add(Set<String> target, String value) {
        var normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() >= 2) target.add(normalized);
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) return 1d;
        var intersection = new HashSet<>(left); intersection.retainAll(right);
        var union = new HashSet<>(left); union.addAll(right);
        return union.isEmpty() ? 0d : (double) intersection.size() / union.size();
    }

    private double margin(List<Match> matches) {
        return matches.isEmpty() ? 0d : matches.getFirst().score()
                - (matches.size() > 1 ? matches.get(1).score() : 0d);
    }

    private double rounded(double value) { return Math.round(value * 10000d) / 10000d; }

    private ArrayNode candidates(List<Match> matches) {
        var result = objectMapper.createArrayNode();
        matches.forEach(item -> result.addObject()
                .put("templateVersionId", item.versionId().toString())
                .put("templateCode", item.code()).put("templateName", item.name())
                .put("score", item.score()).put("exact", item.exact()));
        return result;
    }

    private ArrayNode photoCandidates(List<ProductionOrderRepository.TemplateCandidate> matches) {
        var result = objectMapper.createArrayNode();
        matches.forEach(item -> result.addObject()
                .put("templateVersionId", item.versionId().toString())
                .put("templateCode", item.templateCode())
                .put("templateName", item.name())
                .put("score", 0d)
                .put("exact", false));
        return result;
    }

    private record Match(UUID versionId, String code, String name, double score, boolean exact) {}
}
