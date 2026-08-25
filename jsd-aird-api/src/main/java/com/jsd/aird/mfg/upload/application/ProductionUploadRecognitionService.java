package com.jsd.aird.mfg.upload.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.mfg.ingest.application.DocumentImagePreprocessor;
import com.jsd.aird.mfg.ingest.application.InstanceWorkbookManifest;
import com.jsd.aird.mfg.ingest.application.WorkbookInstanceExtractor;
import com.jsd.aird.mfg.ingest.application.port.InstanceDocumentRecognitionClient;
import com.jsd.aird.mfg.upload.application.port.ProductionUploadRepository;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.excel.WorkbookInstanceParser;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;

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
    private final boolean photoEnabled;

    public ProductionUploadRecognitionService(
            ProductionUploadRepository uploads, ProductionOrderRepository orders,
            FileObjectRepository files, ObjectStorage storage, WorkbookInstanceParser parser,
            WorkbookInstanceExtractor extractor, JsonCanonicalizer canonicalizer, ObjectMapper objectMapper,
            DocumentImagePreprocessor imagePreprocessor,
            InstanceDocumentRecognitionClient documentRecognitionClient,
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
            if (isDocument(file)) return processDocument(uploadId, file);
            return processPhoto(organizationId, uploadId, file, requestedTemplateVersionId);
        }
        if (!"XLSX".equals(normalizedSourceType)) {
            throw new IllegalArgumentException("生产单来源必须是 XLSX 或 PHOTO");
        }
        uploads.updateRecognitionProgress(uploadId, "PARSING", 15, "LOADING_SOURCE");
        if (!file.originalName().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("生产单识别目前只支持 XLSX 文件");
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
                : exact ? "EXACT_MANIFEST" : auto ? "SIMILAR_AUTO" : "USER_REVIEW";

        uploads.updateRecognitionProgress(uploadId, "EXTRACTING", 70, "EXTRACTING_VALUES");
        var result = objectMapper.createObjectNode();
        result.put("sourceType", "XLSX");
        result.put("matchMode", matchMode);
        result.put("requiresTemplateSelection", !auto);
        result.set("templateCandidates", candidates(matches));
        result.set("issues", objectMapper.valueToTree(parsed.issues()));

        UUID selectedVersionId = null;
        double score = 0d;
        if (selected != null && auto) {
            selectedVersionId = selected.versionId();
            score = selected.score();
            var template = orders.findPublishedTemplate(organizationId, selected.versionId()).orElseThrow();
            var extraction = extractor.extract(template.schema(), template.mapping(), parsed.snapshot());
            result.put("templateMatchScore", score);
            result.put("selectedTemplateVersionId", selected.versionId().toString());
            result.set("data", extraction.data());
            result.set("mapping", template.mapping().deepCopy());
            result.set("items", objectMapper.valueToTree(extraction.items()));
        } else {
            result.put("templateMatchScore", 0d);
            result.set("data", objectMapper.createObjectNode());
            result.set("mapping", objectMapper.createArrayNode());
            result.set("items", objectMapper.createArrayNode());
        }

        uploads.replaceRecognitionFields(uploadId, auto ? extractionFields(result) : List.of());
        uploads.updateRecognizedMetadata(uploadId,
                firstValue(result, "PRODUCTION.ORDER_NO"),
                firstValue(result, "PRODUCTION.PRODUCT_NAME"),
                firstValue(result, "PRODUCTION.CATEGORY"),
                firstDate(result, "PRODUCTION.MANUFACTURE_DATE"));
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
        uploads.updateRecognitionProgress(uploadId, "MATCHING_TEMPLATE", 35, "WAITING_TEMPLATE");
        if (requestedTemplateVersionId == null) {
            var result = objectMapper.createObjectNode();
            result.put("sourceType", "PHOTO");
            result.put("matchMode", "USER_REVIEW");
            result.put("templateMatchScore", 0d);
            result.put("requiresTemplateSelection", true);
            result.put("message", "图片识别需要先选择已发布模板");
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
        if (!documentRecognitionClient.isConfigured()) {
            throw new IllegalArgumentException("生产单图片识别模型尚未配置");
        }
        var template = orders.findPublishedTemplate(organizationId, requestedTemplateVersionId)
                .orElseThrow(() -> new IllegalArgumentException("已发布模板不存在或已失效"));
        byte[] content;
        try (var stored = storage.get(file.objectKey())) {
            content = stored.stream().readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("生产单图片读取失败", exception);
        }
        var processed = imagePreprocessor.process(content, imageContentType(file));
        var recognized = documentRecognitionClient.recognize(template.schema(), template.mapping(),
                List.of(new InstanceDocumentRecognitionClient.ImageSource(
                        processed.contentType(), processed.content())));
        var result = objectMapper.createObjectNode();
        result.put("sourceType", "PHOTO");
        result.put("matchMode", "USER_SELECTED_TEMPLATE");
        result.put("templateMatchScore", 1d);
        result.put("requiresTemplateSelection", false);
        result.put("selectedTemplateVersionId", requestedTemplateVersionId.toString());
        result.put("model", recognized.model());
        result.set("templateCandidates", photoCandidates(orders.listPublishedTemplates(organizationId)));
        result.set("data", recognized.data() == null ? objectMapper.createObjectNode() : recognized.data());
        result.set("mapping", template.mapping().deepCopy());
        result.set("items", photoItems(recognized.items()));
        result.set("issues", objectMapper.createArrayNode());
        uploads.replaceRecognitionFields(uploadId, extractionFields(result));
        uploads.updateRecognizedMetadata(uploadId,
                firstValue(result, "PRODUCTION.ORDER_NO"),
                firstValue(result, "PRODUCTION.PRODUCT_NAME"),
                firstValue(result, "PRODUCTION.CATEGORY"),
                firstDate(result, "PRODUCTION.MANUFACTURE_DATE"));
        uploads.updateRecognitionProgress(uploadId, "EXTRACTING", 90, "BUILDING_PREVIEW");
        uploads.completeRecognition(uploadId, requestedTemplateVersionId, "USER_SELECTED_TEMPLATE", 1d,
                objectMapper.createObjectNode().put("sourceType", "PHOTO").put("imageCount", 1), null, result);
        return result;
    }

    private JsonNode processDocument(UUID uploadId,
                                     com.jsd.aird.ops.application.port.FileObjectRepository.FileObject file) {
        if (!isDocument(file)) throw new IllegalArgumentException("生产单文档来源必须是 DOCX 文件");
        uploads.updateRecognitionProgress(uploadId, "EXTRACTING", 70, "BUILDING_PREVIEW");
        var result = objectMapper.createObjectNode();
        result.put("sourceType", "DOCX");
        result.put("matchMode", "USER_REVIEW");
        result.put("templateMatchScore", 0d);
        result.put("requiresTemplateSelection", false);
        result.put("message", "DOCX 生产单已保存，可直接预览");
        result.set("templateCandidates", objectMapper.createArrayNode());
        result.set("data", objectMapper.createObjectNode());
        result.set("mapping", objectMapper.createArrayNode());
        result.set("items", objectMapper.createArrayNode());
        result.set("issues", objectMapper.createArrayNode());
        uploads.replaceRecognitionFields(uploadId, List.of());
        uploads.completeRecognition(uploadId, null, "USER_REVIEW", 0d,
                objectMapper.createObjectNode().put("sourceType", "DOCX"), null, result);
        return result;
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
                    item.path("confidence").asDouble(1d), item.path("reviewStatus").asText("EXTRACTED")));
        }
        return fields;
    }

    private String firstValue(JsonNode result, String fieldCode) {
        for (var item : result.path("items")) {
            if (fieldCode.equals(item.path("fieldCode").asText(""))) {
                var value = item.has("value") ? item.path("value") : item.path("normalizedValue");
                if (value.isValueNode() && !value.isNull()) return value.asText("").trim();
            }
        }
        return "";
    }

    private LocalDate firstDate(JsonNode result, String fieldCode) {
        var value = firstValue(result, fieldCode);
        if (value.isBlank()) return null;
        try { return LocalDate.parse(value); } catch (RuntimeException ignored) { return null; }
    }

    private JsonNode itemValue(JsonNode item) {
        return item.has("value") ? item.path("value") : item.path("normalizedValue");
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
                || name.endsWith(".docx");
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
