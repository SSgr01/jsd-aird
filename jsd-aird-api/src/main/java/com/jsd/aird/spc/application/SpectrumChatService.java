package com.jsd.aird.spc.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.spc.application.port.SpectrumRepository;
import com.jsd.aird.spc.application.port.SpectrumPromptPort;
import com.jsd.aird.spc.application.port.SpectrumPromptPort.SpectrumAnalysisPromptContext;
import com.jsd.aird.spc.application.port.SpectrumVisionClient;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SpectrumChatService {

    private static final int MAX_CHARTS = 12;
    private static final int MAX_PAGES = 20;
    private static final int MAX_PAGES_PER_CHART = 5;

    private final SpectrumRepository repository;
    private final SpectrumService charts;
    private final ObjectMapper objectMapper;
    private final OpsAsyncFacade async;
    private final SpectrumVisionClient vision;
    private final SpectrumPromptPort prompts;
    private final SpectrumResultValidator resultValidator;
    private final String model;

    public SpectrumChatService(SpectrumRepository repository, SpectrumService charts, ObjectMapper objectMapper,
                               OpsAsyncFacade async, SpectrumVisionClient vision, SpectrumPromptPort prompts,
                               SpectrumResultValidator resultValidator,
                               @org.springframework.beans.factory.annotation.Value("${app.model.model:}") String model) {
        this.repository = repository;
        this.charts = charts;
        this.objectMapper = objectMapper;
        this.async = async;
        this.vision = vision;
        this.prompts = prompts;
        this.resultValidator = resultValidator;
        this.model = model == null ? "" : model;
    }

    @Transactional
    public SubmitView submit(ChatCommand command) {
        var actor = ActorContext.required();
        if (command == null || !StringUtils.hasText(command.question()) || command.question().length() > 3000) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "问题不能为空且不能超过 3000 字符");
        }
        var chartIds = command.chartIds() == null ? List.<UUID>of() : List.copyOf(command.chartIds());
        if (chartIds.isEmpty() || chartIds.size() > MAX_CHARTS) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "请引用 1-12 张图谱");
        }
        if (chartIds.stream().distinct().count() != chartIds.size()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "同一图谱不能重复引用");
        }
        var rows = chartIds.stream().map(id -> charts.requireChart(actor.organizationId(), id)).toList();
        var pageSelections = normalizedPageSelections(rows, command.pageSelections());
        UUID sessionId = command.sessionId();
        if (sessionId == null) sessionId = repository.createSession(actor.organizationId(), actor.userId(), title(command.question()));
        else if (!repository.sessionExists(actor.organizationId(), actor.userId(), sessionId)) throw new ApiException(ApiErrorCode.NOT_FOUND, "图谱对话不存在");
        var categoryNames = objectMapper.createArrayNode();
        rows.stream().map(SpectrumRepository.ChartRow::categoryName).distinct().forEach(categoryNames::add);
        var analysisId = UUID.randomUUID();
        var scenario = StringUtils.hasText(command.scenarioTemplate()) ? command.scenarioTemplate().trim() : null;
        var analysis = repository.insertAnalysis(new SpectrumRepository.NewAnalysis(
                analysisId, actor.organizationId(), sessionId, scenario == null ? "AI_CHAT" : "OPTIONAL_SCENARIO",
                command.question().trim(), objectMapper.valueToTree(chartIds).toString(), pageSelections.toString(),
                categoryNames.toString(), scenario, actor.userId(),
                scenario == null ? SpectrumPromptPort.GENERIC_VERSION : SpectrumPromptPort.COMPETITOR_VERSION,
                model));
        var userMessage = repository.insertMessage(new SpectrumRepository.NewMessage(
                UUID.randomUUID(), actor.organizationId(), sessionId, analysis.id(), "USER", command.question().trim(),
                "[]", "{}", "[]"));
        repository.touchSession(actor.organizationId(), sessionId, null);
        enqueueConversationTitle(actor.organizationId(), sessionId);
        var payload = objectMapper.createObjectNode()
                .put("organizationId", actor.organizationId().toString())
                .put("analysisRunId", analysis.id().toString());
        appendEvent(actor.organizationId(), analysis.id(), "status", statusPayload("QUEUED", 0, "任务已排队"));
        async.enqueue(actor.organizationId(), "SPC_GENERATE_CHART_CHAT", payload, "spc-analysis:" + analysis.id(), 50);
        return new SubmitView(sessionId, userMessage.id(), analysis.id(), analysis.status());
    }

    public AnalysisView analysis(UUID analysisId) {
        var actor = ActorContext.required();
        return analysisView(repository.findAnalysisForUser(actor.organizationId(), actor.userId(), analysisId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "图谱分析任务不存在")));
    }

    public SseEmitter stream(UUID analysisId) {
        var actor = ActorContext.required();
        repository.findAnalysisForUser(actor.organizationId(), actor.userId(), analysisId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "图谱分析任务不存在"));
        var emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> pumpEvents(emitter, actor.organizationId(), analysisId));
        return emitter;
    }

    public void executeInternal(UUID organizationId, UUID analysisId) {
        var analysis = repository.findAnalysis(organizationId, analysisId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "图谱分析任务不存在"));
        repository.updateAnalysisStarted(organizationId, analysisId, "准备图谱图片");
        appendEvent(organizationId, analysisId, "status", statusPayload("RUNNING", 10, "准备图谱图片"));
        try {
            var chartIds = readUuidList(analysis.chartIdsJson());
            var pageSelections = readPageSelections(analysis.pageSelectionsJson());
            var rows = chartIds.stream().map(id -> charts.requireChart(organizationId, id)).toList();
            var images = new ArrayList<SpectrumVisionClient.VisionImage>();
            var context = new StringBuilder();
            var pageContext = new StringBuilder();
            var spectrumTypes = new ArrayList<String>();
            var referenceChartIds = new ArrayList<String>();
            var explicitSampleRelations = new ArrayList<String>();
            var availableEvidenceIds = new ArrayList<String>();
            var sampleNames = new ArrayList<String>();
            for (var row : rows) {
                spectrumTypes.add(row.categoryCode());
                if (hasSinglePeakReference(row.metadataJson())) referenceChartIds.add(row.id().toString());
                explicitSampleRelations.addAll(explicitSampleRelations(row.metadataJson()));
                if (StringUtils.hasText(row.sampleName())) sampleNames.add(row.sampleName().trim());
                context.append("图谱ID=").append(row.id()).append("；类别=").append(row.categoryName())
                        .append("；标题=").append(row.title()).append("；样品=").append(nullToUnknown(row.sampleName()))
                        .append("；批号=").append(nullToUnknown(row.batchNo())).append("；测试条件=")
                        .append(nullToUnknown(row.testConditions())).append("；类别元数据=").append(row.metadataJson()).append("\n");
                var pages = pageSelections.getOrDefault(row.id(), List.of(1));
                pageContext.append(row.id()).append(" -> ").append(pages).append("\n");
                pages.forEach(page -> availableEvidenceIds.add(row.id() + "/page-" + page));
                try (var stored = charts.openChart(organizationId, row.id()).file()) {
                    var bytes = stored.stream().readAllBytes();
                    for (var page : pages) {
                        images.addAll(render(row, bytes, page));
                    }
                }
            }
            repository.updateAnalysisProgress(organizationId, analysisId, 40, "调用视觉模型");
            appendEvent(organizationId, analysisId, "status", statusPayload("RUNNING", 40, "调用视觉模型"));
            var promptContext = new SpectrumAnalysisPromptContext(
                    analysis.question(), context.toString(), pageContext.toString(), analysis.scenarioTemplate(),
                    spectrumTypes.stream().distinct().toList(), !referenceChartIds.isEmpty(),
                    referenceChartIds.stream().distinct().toList(), explicitSampleRelations.stream().distinct().toList(),
                    availableEvidenceIds.stream().distinct().toList(), sampleNames.stream().distinct().toList());
            var prompt = prompts.build(promptContext);
            var response = vision.analyze(new SpectrumVisionClient.VisionRequest(prompt, images, analysis.scenarioTemplate()));
            repository.updateAnalysisProgress(organizationId, analysisId, 85, "保存结构化结果");
            appendEvent(organizationId, analysisId, "status", statusPayload("RUNNING", 85, "保存结构化结果"));
            var result = normalizeResult(response.result());
            if ("FAILED".equals(result.path("analysisStatus").asText())) {
                throw new IllegalStateException(result.path("answerMarkdown").asText("视觉模型未返回有效结果"));
            }
            var validation = resultValidator.validate(result, promptContext);
            result = validation.result();
            var warnings = objectMapper.createArrayNode();
            validation.warnings().forEach(warnings::add);
            var finalStatus = "PARTIAL".equals(result.path("analysisStatus").asText()) ? "PARTIAL" : "SUCCEEDED";
            var citations = citations(rows, pageSelections);
            repository.updateAnalysisFinished(organizationId, analysisId, finalStatus, result.toString(),
                    response.rawResponse().toString(), warnings.toString(), null);
            appendEvent(organizationId, analysisId, "done", statusPayload(finalStatus, 100,
                    "PARTIAL".equals(finalStatus) ? "分析完成，部分内容已按证据边界过滤" : "分析完成"));
            repository.insertMessage(new SpectrumRepository.NewMessage(
                    UUID.randomUUID(), organizationId, analysis.sessionId(), analysisId, "ASSISTANT",
                    answer(result), citations.toString(), result.toString(), warnings.toString()));
            repository.touchSession(organizationId, analysis.sessionId(), null);
        } catch (Exception exception) {
            throw exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
        }
    }

    private void enqueueConversationTitle(UUID organizationId, UUID sessionId) {
        var payload = objectMapper.createObjectNode()
                .put("organizationId", organizationId.toString())
                .put("conversationType", "SPC_CHAT")
                .put("conversationId", sessionId.toString());
        async.enqueue(organizationId, "AI_GENERATE_CONVERSATION_TITLE", payload,
                "conversation-title:SPC_CHAT:" + sessionId, 80);
    }

    public void failInternal(UUID organizationId, UUID analysisId, Exception exception) {
        var analysis = repository.findAnalysis(organizationId, analysisId).orElse(null);
        if (analysis == null) return;
        var failureMessage = "图谱 AI 分析失败：" + safeMessage(exception);
        var warning = objectMapper.createArrayNode().add(failureMessage);
        var result = prompts.emptyResult(failureMessage, failureMessage);
        result.put("analysisStatus", "FAILED");
        result.put("errorMessage", safeMessage(exception));
        result.put("answerMarkdown", failureMessage);
        repository.updateAnalysisFinished(organizationId, analysisId, "FAILED", result.toString(), "{}", warning.toString(), safeMessage(exception));
        appendEvent(organizationId, analysisId, "error", statusPayload("FAILED", 0, failureMessage));
        repository.insertMessage(new SpectrumRepository.NewMessage(
                UUID.randomUUID(), organizationId, analysis.sessionId(), analysisId, "ASSISTANT",
                failureMessage, "[]", result.toString(), warning.toString()));
    }

    public boolean retryable(Exception exception) {
        var message = safeMessage(exception);
        return !(exception instanceof ApiException) && !message.contains("尚未配置") && !message.contains("JSON")
                && !message.contains("无法读取") && !message.contains("不能为空");
    }

    private ObjectNode normalizedPageSelections(List<SpectrumRepository.ChartRow> rows, JsonNode input) {
        var result = objectMapper.createObjectNode();
        var total = 0;
        for (var row : rows) {
            var requested = input == null ? null : input.get(row.id().toString());
            var pages = new ArrayList<Integer>();
            if (requested != null && requested.isArray()) {
                for (var item : requested) {
                    if (item.canConvertToInt()) pages.add(item.asInt());
                }
            }
            if (pages.isEmpty()) pages.add(1);
            pages = new ArrayList<>(pages.stream().distinct().sorted().toList());
            if (pages.size() > MAX_PAGES_PER_CHART) throw new ApiException(ApiErrorCode.BAD_REQUEST, "单个图谱最多选择 5 页");
            for (var page : pages) {
                if (page < 1 || page > row.pageCount()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "图谱页面选择超出范围");
            }
            total += pages.size();
            var pageArray = objectMapper.createArrayNode();
            pages.forEach(pageArray::add);
            result.set(row.id().toString(), pageArray);
        }
        if (total > MAX_PAGES) throw new ApiException(ApiErrorCode.BAD_REQUEST, "单次分析最多选择 20 个页面");
        return result;
    }

    private Map<UUID, List<Integer>> readPageSelections(String value) {
        var result = new LinkedHashMap<UUID, List<Integer>>();
        try {
            var node = objectMapper.readTree(value);
            if (node.isArray() && node.size() > 0) node = node.get(0);
            if (node != null && node.isObject()) node.fields().forEachRemaining(entry -> {
                try {
                    var pages = new ArrayList<Integer>();
                    entry.getValue().forEach(item -> pages.add(item.asInt()));
                    result.put(UUID.fromString(entry.getKey()), pages);
                } catch (Exception ignored) { }
            });
            return result;
        } catch (Exception exception) { throw new IllegalStateException("页面选择数据无效", exception); }
    }

    private List<UUID> readUuidList(String value) {
        try {
            var result = new ArrayList<UUID>();
            objectMapper.readTree(value).forEach(item -> result.add(UUID.fromString(item.asText())));
            return result;
        } catch (Exception exception) { throw new IllegalStateException("图谱引用数据无效", exception); }
    }

    private List<SpectrumVisionClient.VisionImage> render(SpectrumRepository.ChartRow row, byte[] bytes, int page) throws IOException {
        var result = new ArrayList<SpectrumVisionClient.VisionImage>();
        if (isPdf(row)) {
            try (var document = Loader.loadPDF(bytes)) {
                var renderer = new PDFRenderer(document);
                BufferedImage image = renderer.renderImageWithDPI(page - 1, 150, ImageType.RGB);
                try (var output = new ByteArrayOutputStream()) {
                    ImageIO.write(image, "png", output);
                    result.add(new SpectrumVisionClient.VisionImage(row.id(), row.categoryName(), page,
                            SpectrumVisionClient.dataUri("image/png", output.toByteArray())));
                }
            }
        } else if (page == 1) {
            result.add(new SpectrumVisionClient.VisionImage(row.id(), row.categoryName(), 1,
                    SpectrumVisionClient.dataUri(row.contentType(), bytes)));
        }
        return result;
    }

    private ObjectNode normalizeResult(JsonNode source) {
        var result = source != null && source.isObject() ? (ObjectNode) source.deepCopy()
                : prompts.emptyResult("模型没有返回可解析的结构化结果。", "模型响应格式不完整");
        if (source == null || !source.isObject() || source.size() == 0) result.put("analysisStatus", "FAILED");
        if (!result.has("answerMarkdown")) result.put("answerMarkdown", "模型返回的结构化结果缺少分析摘要，请重新分析。");
        if (!result.has("conclusionBoundary")) result.put("conclusionBoundary", "POSSIBLE_INTERPRETATIONS_ONLY_NO_DEFINITIVE_FORMULA");
        return result;
    }

    private String answer(ObjectNode result) {
        return result.path("answerMarkdown").asText("图谱分析结果不完整，请查看已通过证据校验的内容。");
    }

    private boolean hasSinglePeakReference(String metadataJson) {
        var metadata = parse(metadataJson, objectMapper.createObjectNode());
        for (var key : List.of("referenceRole", "referenceType", "sampleRole")) {
            var value = metadata.path(key).asText("").trim().toUpperCase(java.util.Locale.ROOT);
            if (List.of("SINGLE_PEAK", "REFERENCE_SINGLE_PEAK", "SINGLE_PEAK_REFERENCE", "单峰", "单峰参考")
                    .contains(value)) return true;
        }
        return false;
    }

    private List<String> explicitSampleRelations(String metadataJson) {
        var metadata = parse(metadataJson, objectMapper.createObjectNode());
        var result = new ArrayList<String>();
        for (var key : List.of("sampleRelation", "relationType", "derivedFrom", "parentSampleId")) {
            var value = metadata.get(key);
            if (value != null && !value.isNull() && StringUtils.hasText(value.asText())) {
                result.add(key + "=" + value.asText().trim());
            }
        }
        return result;
    }

    private ArrayNode citations(List<SpectrumRepository.ChartRow> rows, Map<UUID, List<Integer>> pages) {
        var result = objectMapper.createArrayNode();
        for (var row : rows) for (var page : pages.getOrDefault(row.id(), List.of(1))) {
            var item = objectMapper.createObjectNode();
            item.put("chartId", row.id().toString()).put("category", row.categoryName()).put("page", page)
                    .put("title", row.title()).put("region", "页面视觉证据");
            result.add(item);
        }
        return result;
    }

    private AnalysisView analysisView(SpectrumRepository.AnalysisRow row) {
        return new AnalysisView(row.id(), row.sessionId(), row.mode(), row.question(), parse(row.chartIdsJson(), objectMapper.createArrayNode()),
                parse(row.pageSelectionsJson(), objectMapper.createObjectNode()), parse(row.categoriesJson(), objectMapper.createArrayNode()),
                row.scenarioTemplate(), row.status(), row.progress(), row.currentStage(), parse(row.resultJson(), objectMapper.createObjectNode()),
                parse(row.warningJson(), objectMapper.createArrayNode()), row.errorMessage(), row.createdAt(), row.startedAt(), row.completedAt());
    }

    private JsonNode parse(String value, JsonNode fallback) {
        try { return StringUtils.hasText(value) ? objectMapper.readTree(value) : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private String title(String question) {
        var value = question == null ? "新的图谱分析对话" : question.trim();
        return value.length() <= 40 ? value : value.substring(0, 40) + "…";
    }

    private boolean isPdf(SpectrumRepository.ChartRow row) {
        return "application/pdf".equalsIgnoreCase(row.contentType()) || row.originalName().toLowerCase().endsWith(".pdf");
    }

    private String nullToUnknown(String value) { return StringUtils.hasText(value) ? value : "未提供"; }

    private String safeMessage(Exception exception) {
        var value = exception == null ? "未知错误" : exception.getMessage();
        return value == null || value.isBlank() ? "未知错误" : value.substring(0, Math.min(500, value.length()));
    }

    private void pumpEvents(SseEmitter emitter, UUID organizationId, UUID analysisId) {
        long cursor = 0L;
        try {
            for (int attempt = 0; attempt < 240; attempt++) {
                var events = repository.listAnalysisEvents(organizationId, analysisId, cursor, 100);
                if (!events.isEmpty()) {
                    for (var event : events) {
                        cursor = event.id();
                        sendEvent(emitter, event.eventType(), event.id(), event.payloadJson());
                    }
                } else if (attempt == 0) {
                    var snapshot = repository.findAnalysis(organizationId, analysisId)
                            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "图谱分析任务不存在"));
                    sendEvent(emitter, "snapshot", 0L, statusPayload(snapshot.status(), snapshot.progress(), snapshot.currentStage()));
                } else if (attempt % 15 == 0) {
                    sendEvent(emitter, "heartbeat", cursor, "{\"analysisRunId\":\"" + analysisId + "\"}");
                }
                var current = repository.findAnalysis(organizationId, analysisId).orElse(null);
                if (current != null && isTerminal(current.status())) {
                    if (events.isEmpty()) {
                        sendEvent(emitter, "done", cursor, statusPayload(current.status(), current.progress(), current.currentStage()));
                    }
                    emitter.complete();
                    return;
                }
                Thread.sleep(500L);
            }
            emitter.complete();
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    private void sendEvent(SseEmitter emitter, String eventType, long id, String payloadJson) throws IOException {
        emitter.send(SseEmitter.event().id(Long.toString(id)).name(eventType).data(payloadJson));
    }

    private void appendEvent(UUID organizationId, UUID analysisId, String eventType, String payloadJson) {
        repository.appendAnalysisEvent(organizationId, analysisId, eventType, payloadJson);
    }

    private String statusPayload(String status, int progress, String stage) {
        return objectMapper.createObjectNode().put("status", status).put("progress", progress)
                .put("stage", stage == null ? "" : stage).toString();
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "PARTIAL".equals(status)
                || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    public record ChatCommand(UUID sessionId, String question, List<UUID> chartIds, JsonNode pageSelections,
                              String scenarioTemplate) { }

    public record SubmitView(UUID sessionId, UUID messageId, UUID analysisRunId, String status) { }

    public record AnalysisView(UUID id, UUID sessionId, String mode, String question, JsonNode chartIds,
                               JsonNode pageSelections, JsonNode categories, String scenarioTemplate, String status,
                               int progress, String currentStage, JsonNode result, JsonNode warnings, String errorMessage,
                               java.time.Instant createdAt, java.time.Instant startedAt, java.time.Instant completedAt) { }
}
