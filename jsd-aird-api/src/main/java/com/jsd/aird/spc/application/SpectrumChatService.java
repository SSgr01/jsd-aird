package com.jsd.aird.spc.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.spc.application.port.SpectrumRepository;
import com.jsd.aird.spc.application.port.SpectrumPromptPort;
import com.jsd.aird.spc.application.port.SpectrumPromptPort.ConversationMessage;
import com.jsd.aird.spc.application.port.SpectrumPromptPort.SpectrumAnalysisPromptContext;
import com.jsd.aird.spc.application.port.SpectrumVisionClient;
import com.jsd.aird.spc.infrastructure.SpectrumAnalysisEventNotifier;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SpectrumChatService {

    private static final int MAX_CHARTS = 12;
    private static final int MAX_PAGES = 20;
    private static final int MAX_PAGES_PER_CHART = 5;
    private static final int DEFAULT_PDF_RENDER_DPI = 240;
    private static final long SSE_TIMEOUT_MILLIS = 20L * 60L * 1000L;
    private static final ScheduledExecutorService ANSWER_DELTA_FLUSHER = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "spectrum-answer-delta-flusher");
        thread.setDaemon(true);
        return thread;
    });
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern EVIDENCE_LABEL_PATTERN = Pattern.compile(
            "(?i)(?:evidence\\s*ids?|证据\\s*id)\\s*[:：]?\\s*[^，。；;\\n]*");

    private final SpectrumRepository repository;
    private final SpectrumService charts;
    private final ObjectMapper objectMapper;
    private final OpsAsyncFacade async;
    private final SpectrumVisionClient vision;
    private final SpectrumPromptPort prompts;
    private final SpectrumResultValidator resultValidator;
    private final SpectrumResultPresenter resultPresenter;
    private final SpectrumIntentResolver intentResolver;
    private final SpectrumProtocolResolver protocolResolver;
    private final AuditLogFacade audit;
    private final String model;
    private final int pdfRenderDpi;
    private final SpectrumAnalysisEventNotifier eventNotifier;

    public SpectrumChatService(SpectrumRepository repository, SpectrumService charts, ObjectMapper objectMapper,
                               OpsAsyncFacade async, SpectrumVisionClient vision, SpectrumPromptPort prompts,
                               SpectrumResultValidator resultValidator, SpectrumResultPresenter resultPresenter,
                               @org.springframework.beans.factory.annotation.Value("${app.spectrum.model.name:}") String model) {
        this(repository, charts, objectMapper, async, vision, prompts, resultValidator, resultPresenter,
                null, model, new SpectrumIntentResolver(), new SpectrumProtocolResolver(objectMapper));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SpectrumChatService(SpectrumRepository repository, SpectrumService charts, ObjectMapper objectMapper,
                               OpsAsyncFacade async, SpectrumVisionClient vision, SpectrumPromptPort prompts,
                               SpectrumResultValidator resultValidator, SpectrumResultPresenter resultPresenter,
                               AuditLogFacade audit,
                               SpectrumIntentResolver intentResolver,
                               SpectrumProtocolResolver protocolResolver,
                               ObjectProvider<SpectrumAnalysisEventNotifier> eventNotifier,
                               @org.springframework.beans.factory.annotation.Value("${app.spectrum.model.name:}") String model,
                               @org.springframework.beans.factory.annotation.Value("${app.spectrum.pdf-render-dpi:240}") int pdfRenderDpi) {
        this(repository, charts, objectMapper, async, vision, prompts, resultValidator, resultPresenter,
                audit, model, intentResolver, protocolResolver, pdfRenderDpi,
                eventNotifier == null ? null : eventNotifier.getIfAvailable());
    }

    SpectrumChatService(SpectrumRepository repository, SpectrumService charts, ObjectMapper objectMapper,
                        OpsAsyncFacade async, SpectrumVisionClient vision, SpectrumPromptPort prompts,
                        SpectrumResultValidator resultValidator, SpectrumResultPresenter resultPresenter,
                        AuditLogFacade audit, String model, SpectrumIntentResolver intentResolver,
                        SpectrumProtocolResolver protocolResolver) {
        this(repository, charts, objectMapper, async, vision, prompts, resultValidator, resultPresenter,
                audit, model, intentResolver, protocolResolver, DEFAULT_PDF_RENDER_DPI);
    }

    SpectrumChatService(SpectrumRepository repository, SpectrumService charts, ObjectMapper objectMapper,
                        OpsAsyncFacade async, SpectrumVisionClient vision, SpectrumPromptPort prompts,
                        SpectrumResultValidator resultValidator, SpectrumResultPresenter resultPresenter,
                        AuditLogFacade audit, String model, SpectrumIntentResolver intentResolver,
                        SpectrumProtocolResolver protocolResolver, int pdfRenderDpi) {
        this(repository, charts, objectMapper, async, vision, prompts, resultValidator, resultPresenter,
                audit, model, intentResolver, protocolResolver, pdfRenderDpi, null);
    }

    SpectrumChatService(SpectrumRepository repository, SpectrumService charts, ObjectMapper objectMapper,
                        OpsAsyncFacade async, SpectrumVisionClient vision, SpectrumPromptPort prompts,
                        SpectrumResultValidator resultValidator, SpectrumResultPresenter resultPresenter,
                        AuditLogFacade audit, String model, SpectrumIntentResolver intentResolver,
                        SpectrumProtocolResolver protocolResolver, int pdfRenderDpi,
                        SpectrumAnalysisEventNotifier eventNotifier) {
        this.repository = repository;
        this.charts = charts;
        this.objectMapper = objectMapper;
        this.async = async;
        this.vision = vision;
        this.prompts = prompts;
        this.resultValidator = resultValidator;
        this.resultPresenter = resultPresenter;
        this.intentResolver = intentResolver;
        this.protocolResolver = protocolResolver;
        this.audit = audit;
        this.model = model == null ? "" : model;
        this.pdfRenderDpi = Math.min(300, Math.max(150, pdfRenderDpi));
        this.eventNotifier = eventNotifier;
    }

    @Transactional
    public SubmitView submit(ChatCommand command) {
        var actor = ActorContext.required();
        if (command == null || !StringUtils.hasText(command.question()) || command.question().length() > 3000) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "问题不能为空且不能超过 3000 字符");
        }
        if ((command.replaceFromMessageId() == null) != (command.expectedLastMessageId() == null)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "编辑问题时需要同时提供目标消息和会话版本");
        }
        var sessionId = command.sessionId();
        SpectrumRepository.SessionRow existingSession = null;
        SpectrumRepository.AnalysisRow replacementAnalysis = null;
        if (sessionId != null) {
            existingSession = repository.findSession(actor.organizationId(), actor.userId(), sessionId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "图谱对话不存在"));
            repository.lockSessionForUpdate(actor.organizationId(), actor.userId(), sessionId);
            if (command.replaceFromMessageId() != null) {
                var currentMessages = repository.listMessages(actor.organizationId(), sessionId, 5_000);
                var lastMessageId = currentMessages.isEmpty() ? null : currentMessages.getLast().id();
                if (!command.expectedLastMessageId().equals(lastMessageId)) {
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "会话内容已变化，请刷新后重新编辑");
                }
                var targetIndex = -1;
                for (var index = 0; index < currentMessages.size(); index++) {
                    if (command.replaceFromMessageId().equals(currentMessages.get(index).id())) {
                        targetIndex = index;
                        break;
                    }
                }
                if (targetIndex < 0 || !"USER".equals(currentMessages.get(targetIndex).role())) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "只能编辑本会话中已保存的问题");
                }
                var target = currentMessages.get(targetIndex);
                if (target.analysisRunId() == null) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "该问题没有可恢复的图谱范围，无法安全重跑");
                }
                replacementAnalysis = repository.findAnalysis(actor.organizationId(), target.analysisRunId())
                        .orElseThrow(() -> new ApiException(ApiErrorCode.BAD_REQUEST, "原图谱分析范围已不可用"));
                if (!sessionId.equals(replacementAnalysis.sessionId())) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "原图谱分析范围不属于当前会话");
                }

                var descendantRunIds = currentMessages.subList(targetIndex, currentMessages.size()).stream()
                        .map(SpectrumRepository.MessageRow::analysisRunId).filter(java.util.Objects::nonNull)
                        .distinct().toList();
                for (var runId : descendantRunIds) {
                    var run = repository.findAnalysis(actor.organizationId(), runId).orElse(null);
                    if (run != null && !isTerminal(run.status())) {
                        throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "该会话仍有分析任务运行，请完成后再编辑");
                    }
                }
                repository.deleteMessages(actor.organizationId(), sessionId,
                        currentMessages.subList(targetIndex, currentMessages.size()).stream()
                                .map(SpectrumRepository.MessageRow::id).toList());
            }
        } else if (command.replaceFromMessageId() != null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "编辑问题时必须指定原图谱会话");
        }
        var chartIds = replacementAnalysis != null
                ? readUuidList(replacementAnalysis.chartIdsJson())
                : command.chartIds() == null
                    ? existingSession == null ? List.<UUID>of() : readUuidList(existingSession.chartIdsJson())
                    : List.copyOf(command.chartIds());
        if (chartIds.isEmpty() || chartIds.size() > MAX_CHARTS) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    existingSession == null ? "新会话请先选择 1-12 张图谱" : "本会话尚未选择图谱，请先添加 1-12 张图谱");
        }
        if (chartIds.stream().distinct().count() != chartIds.size()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "同一图谱不能重复引用");
        }
        var rows = chartIds.stream().map(id -> charts.requireChart(actor.organizationId(), id)).toList();
        var requestedPageSelections = replacementAnalysis != null
                ? readJson(replacementAnalysis.pageSelectionsJson())
                : command.pageSelections() == null && existingSession != null
                    ? readJson(existingSession.pageSelectionsJson()) : command.pageSelections();
        var pageSelections = normalizedPageSelections(rows, requestedPageSelections);
        if (sessionId == null) {
            sessionId = repository.createSession(actor.organizationId(), actor.userId(), title(command.question()));
            if (audit != null) {
                var detail = objectMapper.createObjectNode().put("objectName", title(command.question()));
                audit.append(actor.organizationId(), actor.userId(), "SPECTRUM_SESSION_CREATED", "SPECTRUM_SESSION", sessionId, detail);
            }
        }
        repository.updateSessionChartContext(actor.organizationId(), actor.userId(), sessionId,
                objectMapper.valueToTree(chartIds).toString(), pageSelections.toString());
        var categoryNames = objectMapper.createArrayNode();
        rows.stream().map(SpectrumRepository.ChartRow::categoryName).distinct().forEach(categoryNames::add);
        var analysisId = UUID.randomUUID();
        var routing = intentResolver.resolve(command.analysisIntent(), command.scenarioTemplate(),
                command.question(), rows);
        var resolvedIntent = routing.resolvedIntent().name();
        var analysis = repository.insertAnalysis(new SpectrumRepository.NewAnalysis(
                analysisId, actor.organizationId(), sessionId,
                "USER_SELECTED".equals(routing.resolution()) ? "INTENT_GUIDED" : "AI_CHAT",
                command.question().trim(), objectMapper.valueToTree(chartIds).toString(), pageSelections.toString(),
                categoryNames.toString(), resolvedIntent, actor.userId(), SpectrumPromptPort.currentVersion(),
                model));
        if (audit != null) {
            var detail = objectMapper.createObjectNode().put("objectName", command.question().trim());
            audit.append(actor.organizationId(), actor.userId(), "SPECTRUM_ANALYSIS_STARTED", "SPECTRUM_ANALYSIS", analysis.id(), detail);
        }
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

    public SseEmitter stream(UUID analysisId, long lastEventId) {
        var actor = ActorContext.required();
        repository.findAnalysisForUser(actor.organizationId(), actor.userId(), analysisId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "图谱分析任务不存在"));
        var emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        var subscription = eventNotifier == null ? null : eventNotifier.subscribe(analysisId);
        CompletableFuture.runAsync(() -> pumpEvents(emitter, actor.organizationId(), analysisId,
                Math.max(0L, lastEventId), subscription));
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
            var referenceChartIds = new ArrayList<String>();
            var explicitSampleRelations = new ArrayList<String>();
            var evidenceSourceTypes = new LinkedHashMap<String, String>();
            var sampleNames = new ArrayList<String>();
            var categoryHints = new ArrayList<String>();
            var chartIndex = 0;
            var routing = intentResolver.resolve("INTENT_GUIDED".equals(analysis.mode())
                            ? analysis.scenarioTemplate() : "AUTO",
                    "COMPETITOR_DECOMPOSITION".equals(analysis.scenarioTemplate())
                            ? analysis.scenarioTemplate() : null,
                    analysis.question(), rows);
            var protocols = protocolResolver.resolve(analysis.question(), rows);
            for (var row : rows) {
                chartIndex++;
                var chartLabel = "图谱" + chartIndex;
                var reference = hasSinglePeakReference(row.metadataJson());
                if (reference) referenceChartIds.add(row.id().toString());
                explicitSampleRelations.addAll(explicitSampleRelations(row.metadataJson()));
                if (StringUtils.hasText(row.sampleName())) sampleNames.add(row.sampleName().trim());
                var category = charts.requireCategory(organizationId, row.categoryId());
                if (StringUtils.hasText(category.analysisHint())) {
                    categoryHints.add(category.name() + "：" + category.analysisHint().trim());
                }
                var titleEvidenceId = metadataEvidenceId(row.id(), "title");
                evidenceSourceTypes.put(titleEvidenceId, "DOCUMENT_METADATA");
                context.append(chartLabel).append("；类别=").append(row.categoryName())
                        .append("；标题=").append(row.title())
                        .append("；样品=").append(nullToUnknown(row.sampleName()))
                        .append("；批号=").append(nullToUnknown(row.batchNo())).append("；测试条件=")
                        .append(nullToUnknown(row.testConditions())).append("；类别元数据=").append(row.metadataJson()).append("\n");
                addMetadataEvidence(evidenceSourceTypes, row, "sampleName", row.sampleName());
                addMetadataEvidence(evidenceSourceTypes, row, "batchNo", row.batchNo());
                addMetadataEvidence(evidenceSourceTypes, row, "testConditions", row.testConditions());
                var pages = pageSelections.getOrDefault(row.id(), List.of(1));
                pageContext.append(chartLabel).append("（").append(row.title()).append("） -> ")
                        .append(pages).append("\n");
                try (var stored = charts.openChart(organizationId, row.id()).file()) {
                    var bytes = stored.stream().readAllBytes();
                    for (var page : pages) {
                        var evidenceId = pageEvidenceId(row.id(), page);
                        evidenceSourceTypes.put(evidenceId, reference
                                ? "OBSERVED_DATA,REFERENCE_SPECTRUM" : "OBSERVED_DATA");
                        images.addAll(render(row, bytes, page));
                    }
                }
            }
            repository.updateAnalysisProgress(organizationId, analysisId, 40, "正在生成分析回答");
            appendEvent(organizationId, analysisId, "status", statusPayload("RUNNING", 40, "正在生成分析回答"));
            var promptContext = new SpectrumAnalysisPromptContext(
                    analysis.question(), context.toString(), pageContext.toString(),
                    routing.requestedIntent().name(), routing.resolvedIntent().name(), routing.resolution(),
                    routing.reasonCode(), protocols, categoryHints.stream().distinct().toList(), evidenceSourceTypes,
                    referenceChartIds.stream().distinct().toList(), explicitSampleRelations.stream().distinct().toList(),
                    sampleNames.stream().distinct().toList(), conversationHistory(organizationId, analysisId));
            var prompt = prompts.build(promptContext);
            var deltaBuffer = new AnswerDeltaBuffer(organizationId, analysisId);
            SpectrumVisionClient.VisionResult response;
            try {
                response = vision.analyze(
                        new SpectrumVisionClient.VisionRequest(prompt, images, routing.resolvedIntent().name()),
                        new SpectrumVisionClient.StreamObserver() {
                            @Override
                            public void onOutputTextDelta(String delta) {
                                deltaBuffer.append(delta);
                            }
                        });
            } finally {
                deltaBuffer.flush();
            }
            repository.updateAnalysisProgress(organizationId, analysisId, 85, "整理分析结果");
            appendEvent(organizationId, analysisId, "status", statusPayload("RUNNING", 85, "整理分析结果"));
            var modelResult = normalizeResult(response.result());
            if ("FAILED".equals(modelResult.path("analysisStatus").asText())) {
                throw new IllegalStateException("视觉模型未返回可显示的分析内容");
            }
            var warnings = objectMapper.createArrayNode();
            ObjectNode internalResult;
            String finalStatus;
            if (modelResult.path("schemaVersion").asInt() >= 4
                    && StringUtils.hasText(modelResult.path("answerMarkdown").asText())) {
                internalResult = modelResult;
                internalResult.put("analysisStatus", "SUCCEEDED");
                finalStatus = "SUCCEEDED";
            } else {
                var validation = resultValidator.validate(modelResult, promptContext);
                internalResult = validation.result();
                validation.warnings().forEach(warnings::add);
                finalStatus = "PARTIAL".equals(internalResult.path("analysisStatus").asText())
                        ? "PARTIAL" : "SUCCEEDED";
            }
            var publicResult = resultPresenter.present(internalResult, analysis.question());
            var citations = citations(rows, pageSelections);
            repository.updateAnalysisFinished(organizationId, analysisId, finalStatus, internalResult.toString(),
                    response.rawResponse().toString(), warnings.toString(), null,
                    response.model(), response.promptVersion());
            repository.insertMessage(new SpectrumRepository.NewMessage(
                    UUID.randomUUID(), organizationId, analysis.sessionId(), analysisId, "ASSISTANT",
                    answer(publicResult), citations.toString(), publicResult.toString(), warnings.toString()));
            repository.touchSession(organizationId, analysis.sessionId(), null);
            appendEvent(organizationId, analysisId, "done", statusPayload(finalStatus, 100,
                    "PARTIAL".equals(finalStatus) ? "分析完成，部分结论因依据不足未列入结果" : "分析完成"));
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
        var publicFailureMessage = "图谱分析未完成，请稍后重试。";
        var warning = objectMapper.createArrayNode().add(publicFailureMessage);
        var internalResult = prompts.emptyResult(publicFailureMessage, publicFailureMessage);
        internalResult.put("analysisStatus", "FAILED");
        internalResult.put("errorMessage", safeMessage(exception));
        var publicResult = resultPresenter.present(internalResult, analysis.question());
        repository.updateAnalysisFinished(organizationId, analysisId, "FAILED", internalResult.toString(), "{}",
                warning.toString(), safeMessage(exception), analysis.model(), analysis.promptVersion());
        repository.insertMessage(new SpectrumRepository.NewMessage(
                UUID.randomUUID(), organizationId, analysis.sessionId(), analysisId, "ASSISTANT",
                publicFailureMessage, "[]", publicResult.toString(), warning.toString()));
        appendEvent(organizationId, analysisId, "error", statusPayload("FAILED", 0, publicFailureMessage));
    }

    public boolean retryable(Exception exception) {
        var message = safeMessage(exception);
        return !(exception instanceof ApiException) && !message.contains("尚未配置")
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

    private JsonNode readJson(String value) {
        try { return objectMapper.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("图谱页面选择数据无效", exception); }
    }

    private List<SpectrumVisionClient.VisionImage> render(SpectrumRepository.ChartRow row, byte[] bytes, int page)
            throws IOException {
        var result = new ArrayList<SpectrumVisionClient.VisionImage>();
        var caption = "下面图片对应图谱“" + row.title() + "”，类别“" + row.categoryName()
                + "”，第 " + page + " 页。";
        if (isPdf(row)) {
            try (var document = Loader.loadPDF(bytes)) {
                var renderer = new PDFRenderer(document);
                BufferedImage image = renderer.renderImageWithDPI(page - 1, pdfRenderDpi, ImageType.RGB);
                try (var output = new ByteArrayOutputStream()) {
                    ImageIO.write(image, "png", output);
                    result.add(new SpectrumVisionClient.VisionImage(row.id(), row.categoryName(), page, caption,
                            SpectrumVisionClient.dataUri("image/png", output.toByteArray())));
                }
            }
        } else if (page == 1) {
            result.add(new SpectrumVisionClient.VisionImage(row.id(), row.categoryName(), 1, caption,
                    SpectrumVisionClient.dataUri(row.contentType(), bytes)));
        }
        return result;
    }

    private ObjectNode normalizeResult(JsonNode source) {
        var result = source != null && source.isObject() ? (ObjectNode) source.deepCopy()
                : prompts.emptyResult("模型没有返回可显示的分析内容。", "模型响应格式不完整");
        if (result.path("schemaVersion").asInt() >= 4) {
            if (!StringUtils.hasText(result.path("answerMarkdown").asText())) {
                result.put("analysisStatus", "FAILED");
                result.put("errorMessage", "视觉模型没有返回可显示的分析内容。");
            } else {
                result.put("schemaVersion", 4);
                if (!"FAILED".equals(result.path("analysisStatus").asText())) {
                    result.put("analysisStatus", "SUCCEEDED");
                }
            }
            return result;
        }
        result.put("schemaVersion", 3);
        if (source == null || !source.isObject() || source.size() == 0) result.put("analysisStatus", "FAILED");
        if (!result.path("claims").isArray()) result.set("claims", objectMapper.createArrayNode());
        if (!result.path("limitations").isArray()) result.set("limitations", objectMapper.createArrayNode());
        if (!result.path("assessment").isObject()) result.set("assessment", objectMapper.createObjectNode());
        normalizeConfidence(result);
        return result;
    }

    private void normalizeConfidence(ObjectNode result) {
        var confidence = result.path("confidence");
        var value = confidence.isTextual() ? confidence.asText("") : "";
        if (confidence.isObject()) {
            for (var key : List.of("level", "value", "overall", "overallConfidence", "rating", "label", "confidence")) {
                if (confidence.path(key).isValueNode() && StringUtils.hasText(confidence.path(key).asText())) {
                    value = confidence.path(key).asText();
                    break;
                }
            }
        } else if (confidence.isNumber()) {
            var score = confidence.asDouble();
            value = score >= 0.75 ? "HIGH" : score >= 0.45 ? "MEDIUM" : "LOW";
        }
        value = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("LOW", "MEDIUM", "HIGH").contains(value)) value = "LOW";
        result.put("confidence", value);
    }

    private String answer(ObjectNode result) {
        var answerMarkdown = result.path("answerMarkdown").asText("");
        if (StringUtils.hasText(answerMarkdown)) return answerMarkdown;
        var answer = result.path("answer");
        for (var field : List.of("confirmed", "notConfirmed", "reasonableInference", "nextStep")) {
            var values = answer.path(field);
            if (values.isArray() && !values.isEmpty()) return values.get(0).asText();
        }
        return result.path("errorMessage").asText("图谱分析结果不完整，请重新分析。");
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

    private String pageEvidenceId(UUID chartId, int page) {
        return "chart:" + chartId + ":page:" + page;
    }

    private String metadataEvidenceId(UUID chartId, String field) {
        return "chart:" + chartId + ":metadata:" + field;
    }

    private void addMetadataEvidence(Map<String, String> evidenceSourceTypes, SpectrumRepository.ChartRow row,
                                     String field, String value) {
        if (StringUtils.hasText(value)) {
            evidenceSourceTypes.put(metadataEvidenceId(row.id(), field), "DOCUMENT_METADATA");
        }
    }

    private AnalysisView analysisView(SpectrumRepository.AnalysisRow row) {
        var stored = parse(row.resultJson(), objectMapper.createObjectNode());
        var publicResult = stored.isObject() ? resultPresenter.present((ObjectNode) stored, row.question()) : stored;
        return new AnalysisView(row.id(), row.sessionId(), row.mode(), row.question(), parse(row.chartIdsJson(), objectMapper.createArrayNode()),
                parse(row.pageSelectionsJson(), objectMapper.createObjectNode()), parse(row.categoriesJson(), objectMapper.createArrayNode()),
                row.scenarioTemplate(), row.status(), row.progress(), row.currentStage(), publicResult,
                parse(row.warningJson(), objectMapper.createArrayNode()),
                StringUtils.hasText(row.errorMessage()) ? "图谱分析未完成，请稍后重试。" : null,
                row.createdAt(), row.startedAt(), row.completedAt());
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

    private void pumpEvents(SseEmitter emitter, UUID organizationId, UUID analysisId, long cursor,
                            SpectrumAnalysisEventNotifier.Subscription subscription) {
        var lastHeartbeat = System.nanoTime();
        try {
            for (int attempt = 0; attempt < 1200; attempt++) {
                var observedVersion = subscription == null ? 0L : subscription.version();
                var events = repository.listAnalysisEvents(organizationId, analysisId, cursor, 100);
                var terminalEventIncluded = false;
                if (!events.isEmpty()) {
                    for (var event : events) {
                        cursor = event.id();
                        sendEvent(emitter, event.eventType(), event.id(), event.payloadJson());
                        terminalEventIncluded |= "done".equals(event.eventType()) || "error".equals(event.eventType());
                    }
                } else if (attempt == 0) {
                    var snapshot = repository.findAnalysis(organizationId, analysisId)
                            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "图谱分析任务不存在"));
                    sendEvent(emitter, "snapshot", cursor, statusPayload(snapshot.status(), snapshot.progress(), snapshot.currentStage()));
                } else if (System.nanoTime() - lastHeartbeat >= TimeUnit.SECONDS.toNanos(15)) {
                    sendEvent(emitter, "heartbeat", cursor, "{\"analysisRunId\":\"" + analysisId + "\"}");
                    lastHeartbeat = System.nanoTime();
                }
                var current = repository.findAnalysis(organizationId, analysisId).orElse(null);
                if (current != null && isTerminal(current.status()) && (events.isEmpty() || terminalEventIncluded)) {
                    if (events.isEmpty()) {
                        sendEvent(emitter, "done", cursor, statusPayload(current.status(), current.progress(), current.currentStage()));
                    }
                    emitter.complete();
                    return;
                }
                if (events.isEmpty()) {
                    if (subscription != null) subscription.awaitChange(observedVersion, Duration.ofMillis(15));
                    else Thread.sleep(15L);
                }
            }
            emitter.complete();
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        } finally {
            if (subscription != null) subscription.close();
        }
    }

    private void sendEvent(SseEmitter emitter, String eventType, long id, String payloadJson) throws IOException {
        emitter.send(SseEmitter.event().id(Long.toString(id)).name(eventType).data(payloadJson));
    }

    private void appendEvent(UUID organizationId, UUID analysisId, String eventType, String payloadJson) {
        repository.appendAnalysisEvent(organizationId, analysisId, eventType, payloadJson);
    }

    private void publishAnswerDelta(UUID organizationId, UUID analysisId, String delta) {
        if (delta == null || delta.isEmpty()) return;
        var payload = objectMapper.createObjectNode().put("delta", delta).toString();
        appendEvent(organizationId, analysisId, "answer_delta", payload);
    }

    private final class AnswerDeltaBuffer {
        private static final int MAX_DELTA_CHARACTERS = 24;
        private static final long MAX_BUFFER_MILLIS = 20L;
        private final UUID organizationId;
        private final UUID analysisId;
        private final StringBuilder pending = new StringBuilder();
        private ScheduledFuture<?> scheduledFlush;

        private AnswerDeltaBuffer(UUID organizationId, UUID analysisId) {
            this.organizationId = organizationId;
            this.analysisId = analysisId;
        }

        private synchronized void append(String delta) {
            if (delta == null || delta.isEmpty()) return;
            pending.append(delta);
            if (pending.length() >= MAX_DELTA_CHARACTERS) {
                flushLocked();
            } else if (scheduledFlush == null) {
                scheduledFlush = ANSWER_DELTA_FLUSHER.schedule(
                        this::flush, MAX_BUFFER_MILLIS, TimeUnit.MILLISECONDS);
            }
        }

        private synchronized void flush() {
            flushLocked();
        }

        private void flushLocked() {
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
            if (pending.isEmpty()) return;
            var delta = pending.toString();
            pending.setLength(0);
            publishAnswerDelta(organizationId, analysisId, delta);
        }
    }

    private List<ConversationMessage> conversationHistory(UUID organizationId, UUID analysisId) {
        var recent = repository.listRecentMessagesBeforeAnalysis(organizationId, analysisId, 8);
        var newestFirst = new ArrayList<ConversationMessage>();
        var remainingCharacters = 8_000;
        for (var message : recent) {
            if (remainingCharacters <= 0) break;
            if (!"USER".equals(message.role()) && !"ASSISTANT".equals(message.role())) continue;
            var content = message.content() == null ? "" : message.content().trim();
            if (content.isEmpty()) continue;
            if (content.length() > remainingCharacters) {
                content = content.substring(0, remainingCharacters) + "…（较早内容已省略）";
            }
            newestFirst.add(new ConversationMessage(message.role(), content));
            remainingCharacters -= content.length();
        }
        java.util.Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    private String statusPayload(String status, int progress, String stage) {
        return objectMapper.createObjectNode().put("status", status).put("progress", progress)
                .put("stage", stage == null ? "" : stage).toString();
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "PARTIAL".equals(status)
                || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    static String sanitizeStreamText(String value) {
        if (!StringUtils.hasText(value)) return "";
        var cleaned = UUID_PATTERN.matcher(value).replaceAll("");
        cleaned = EVIDENCE_LABEL_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("(?i)/page-\\d+", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("[。！？!?][；;]", "。")
                .replaceAll("^[，。；;\\s]+", "")
                .replaceAll(" ?\\n ?", "\n")
                .strip();
        return cleaned;
    }

    static final class FirstJsonStringField {
        private final ObjectMapper mapper;
        private final String marker;
        private final StringBuilder buffer = new StringBuilder();
        private String value;

        FirstJsonStringField(ObjectMapper mapper, String field) {
            this.mapper = mapper;
            this.marker = "\"" + field + "\"";
        }

        boolean append(String delta) {
            if (value != null || delta == null || delta.isEmpty()) return value != null;
            buffer.append(delta);
            var markerIndex = buffer.indexOf(marker);
            if (markerIndex < 0) return false;
            var colon = buffer.indexOf(":", markerIndex + marker.length());
            if (colon < 0) return false;
            var start = colon + 1;
            while (start < buffer.length() && Character.isWhitespace(buffer.charAt(start))) start++;
            if (start >= buffer.length() || buffer.charAt(start) != '"') return false;
            var escaped = false;
            for (var index = start + 1; index < buffer.length(); index++) {
                var current = buffer.charAt(index);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    try {
                        value = mapper.readValue(buffer.substring(start, index + 1), String.class);
                        return true;
                    } catch (Exception exception) {
                        throw new IllegalStateException("图谱回答摘要解析失败", exception);
                    }
                }
            }
            return false;
        }

        String value() {
            return value == null ? "" : value;
        }
    }

    public record ChatCommand(UUID sessionId, String question, List<UUID> chartIds, JsonNode pageSelections,
                              String analysisIntent, String scenarioTemplate, UUID replaceFromMessageId,
                              UUID expectedLastMessageId) {
        public ChatCommand(UUID sessionId, String question, List<UUID> chartIds, JsonNode pageSelections,
                           String analysisIntent, String scenarioTemplate) {
            this(sessionId, question, chartIds, pageSelections, analysisIntent, scenarioTemplate, null, null);
        }
    }

    public record SubmitView(UUID sessionId, UUID messageId, UUID analysisRunId, String status) { }

    public record AnalysisView(UUID id, UUID sessionId, String mode, String question, JsonNode chartIds,
                               JsonNode pageSelections, JsonNode categories, String analysisIntent, String status,
                               int progress, String currentStage, JsonNode result, JsonNode warnings, String errorMessage,
                               java.time.Instant createdAt, java.time.Instant startedAt, java.time.Instant completedAt) { }
}
