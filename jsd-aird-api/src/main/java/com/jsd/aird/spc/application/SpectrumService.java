package com.jsd.aird.spc.application;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.spc.application.port.SpectrumRepository;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.apache.pdfbox.Loader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SpectrumService {

    private static final List<BuiltInCategory> BUILT_INS = List.of(
            new BuiltInCategory("IR", "红外 IR", "峰位、峰形和测试条件的视觉观察", "峰检测、相似度、批次和成分差异", "[{\"key\":\"peakPositions\",\"label\":\"峰位\"},{\"key\":\"spectralLibraryHitRate\",\"label\":\"谱库命中率\"}]", 10),
            new BuiltInCategory("UV", "紫外 UV", "波长范围、吸收值、浓度和曲线", "吸收峰、曲线叠加和样品对比", "[{\"key\":\"wavelengthRange\",\"label\":\"波长范围\"},{\"key\":\"concentration\",\"label\":\"浓度\"}]", 20),
            new BuiltInCategory("HPLC_GPC", "液相 HPLC/GPC", "保留时间、分子量和色谱曲线", "峰形、分子量、分布和异常提示", "[{\"key\":\"methodFile\",\"label\":\"方法文件\"},{\"key\":\"molecularWeight\",\"label\":\"Mn / Mw\"}]", 30),
            new BuiltInCategory("GC", "气相 GC", "保留时间、峰面积和方法文件", "成分和批次对比", "[{\"key\":\"retentionTimes\",\"label\":\"保留时间\"},{\"key\":\"peakAreas\",\"label\":\"峰面积\"}]", 40),
            new BuiltInCategory("PARTICLE_SIZE", "纳米粒径", "D10、D50、D90、平均粒径、PDI和分布曲线", "分布对比和异常识别", "[{\"key\":\"d10d50d90\",\"label\":\"D10 / D50 / D90\"},{\"key\":\"pdi\",\"label\":\"PDI\"}]", 50),
            new BuiltInCategory("MECHANICAL", "拉伸/力学", "最大力、强度、伸长率、弹性系数和原始曲线", "多次测试汇总和曲线差异", "[{\"key\":\"strength\",\"label\":\"强度\"},{\"key\":\"elongation\",\"label\":\"伸长率\"}]", 60)
    );

    private final SpectrumRepository repository;
    private final FileStorageFacade storage;
    private final ObjectMapper objectMapper;

    public SpectrumService(SpectrumRepository repository, FileStorageFacade storage, ObjectMapper objectMapper) {
        this.repository = repository;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<CategoryView> categories() {
        var actor = ActorContext.required();
        ensureBuiltIns(actor.organizationId(), actor.userId());
        return repository.listCategories(actor.organizationId()).stream().map(this::categoryView).toList();
    }

    @Transactional
    public CategoryView createCategory(CategoryCommand command) {
        var actor = ActorContext.required();
        var code = normalizeCustomCode(command.code(), command.name());
        if (repository.findCategoryByCode(actor.organizationId(), code).isPresent()) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "图谱分类编码已存在");
        }
        return categoryView(repository.createCategory(new SpectrumRepository.NewCategory(
                UUID.randomUUID(), actor.organizationId(), code, required(command.name(), "分类名称不能为空"),
                trim(command.description()), trim(command.analysisHint()), validJsonArray(command.fields()),
                1000, false, actor.userId())));
    }

    @Transactional
    public CategoryView updateCategory(UUID categoryId, CategoryCommand command) {
        var actor = ActorContext.required();
        var category = requireCategory(actor.organizationId(), categoryId);
        if (category.systemCategory()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "内置图谱分类不可修改");
        return categoryView(repository.renameCategory(actor.organizationId(), categoryId,
                required(command.name(), "分类名称不能为空"), trim(command.description()), trim(command.analysisHint()),
                validJsonArray(command.fields())));
    }

    @Transactional
    public void deleteCategory(UUID categoryId) {
        var actor = ActorContext.required();
        var category = requireCategory(actor.organizationId(), categoryId);
        if (category.systemCategory()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "内置图谱分类不可删除");
        if (category.chartCount() > 0) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "分类中仍有图谱，请先移动图谱");
        repository.deleteCategory(actor.organizationId(), categoryId);
    }

    @Transactional
    public ChartView createChart(CreateChartCommand command) {
        var actor = ActorContext.required();
        var category = requireCategory(actor.organizationId(), command.categoryId());
        if (!StringUtils.hasText(command.fileId())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "上传文件不能为空");
        UUID fileId = parseUuid(command.fileId(), "上传文件标识无效");
        try (var file = storage.open(actor.organizationId(), fileId)) {
            var duplicate = repository.findChartByHash(actor.organizationId(), file.sha256());
            if (duplicate.isPresent()) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "相同图谱文件已存在：" + duplicate.get().title());
            }
            var title = StringUtils.hasText(command.title()) ? command.title().trim() : file.originalName();
            var pageCount = pageCount(file.contentType(), file.originalName(), file.stream().readAllBytes());
            var chart = repository.insertChart(new SpectrumRepository.NewChart(
                    UUID.randomUUID(), actor.organizationId(), category.id(), fileId, title, file.originalName(),
                    file.contentType(), file.size(), file.sha256(), trim(command.sampleName()), trim(command.batchNo()),
                    trim(command.testConditions()), validJsonObject(command.metadata()), pageCount, actor.userId()));
            storage.activate(fileId);
            return chartView(chart);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "图谱文件已被其他上传请求创建");
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "图谱文件无法读取");
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "图谱文件无法关闭");
        }
    }

    public PageResponse<ChartView> listCharts(String keyword, UUID categoryId, String status, int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        var rows = repository.listCharts(actor.organizationId(), keyword, categoryId,
                StringUtils.hasText(status) ? status : "READY", safePage, safeSize);
        var total = repository.countCharts(actor.organizationId(), keyword, categoryId,
                StringUtils.hasText(status) ? status : "READY");
        return new PageResponse<>(rows.stream().map(this::chartView).toList(), safePage, safeSize, total,
                (total + safeSize - 1) / safeSize);
    }

    public ChartView chart(UUID chartId) {
        var actor = ActorContext.required();
        return chartView(repository.findChart(actor.organizationId(), chartId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "图谱不存在")));
    }

    @Transactional
    public ChartView updateChart(UUID chartId, UpdateChartCommand command) {
        var actor = ActorContext.required();
        requireChart(actor.organizationId(), chartId);
        repository.updateChart(actor.organizationId(), chartId, required(command.title(), "图谱名称不能为空"),
                trim(command.sampleName()), trim(command.batchNo()), trim(command.testConditions()), validJsonObject(command.metadata()));
        return chart(chartId);
    }

    @Transactional
    public void deleteChart(UUID chartId) {
        var actor = ActorContext.required();
        requireChart(actor.organizationId(), chartId);
        repository.deleteChart(actor.organizationId(), chartId);
    }

    public StoredChartFile openChart(UUID chartId) {
        var actor = ActorContext.required();
        return openChart(actor.organizationId(), chartId);
    }

    /**
     * Worker-side file access must use the job's organization explicitly because
     * there is no HTTP request identity in the asynchronous worker thread.
     */
    public StoredChartFile openChart(UUID organizationId, UUID chartId) {
        var chart = requireChart(organizationId, chartId);
        return new StoredChartFile(chart, storage.open(organizationId, chart.fileObjectId()));
    }

    public List<PageView> pages(UUID chartId) {
        var chart = chart(chartId);
        var pages = new ArrayList<PageView>();
        for (int index = 1; index <= chart.pageCount(); index++) pages.add(new PageView(index, chart.pageCount()));
        return pages;
    }

    public SessionView createSession() {
        var actor = ActorContext.required();
        var id = repository.createSession(actor.organizationId(), actor.userId(), "新的图谱分析对话");
        return new SessionView(id, "新的图谱分析对话", Instant.now(), Instant.now(), List.of());
    }

    public List<SessionView> sessions(int limit) {
        var actor = ActorContext.required();
        return repository.listSessions(actor.organizationId(), actor.userId(), Math.min(100, Math.max(1, limit)))
                .stream().map(item -> new SessionView(item.id(), item.title(), item.createdAt(), item.updatedAt(), List.of())).toList();
    }

    public SessionView session(UUID sessionId) {
        var actor = ActorContext.required();
        if (!repository.sessionExists(actor.organizationId(), actor.userId(), sessionId)) throw new ApiException(ApiErrorCode.NOT_FOUND, "图谱对话不存在");
        var messages = repository.listMessages(actor.organizationId(), sessionId, 200).stream().map(this::messageView).toList();
        var item = repository.listSessions(actor.organizationId(), actor.userId(), 100).stream()
                .filter(candidate -> candidate.id().equals(sessionId)).findFirst()
                .orElse(new SpectrumRepository.SessionRow(sessionId, "图谱分析对话", Instant.now(), Instant.now()));
        return new SessionView(item.id(), item.title(), item.createdAt(), item.updatedAt(), messages);
    }

    @Transactional
    public void renameSession(UUID sessionId, String title) {
        var actor = ActorContext.required();
        if (!StringUtils.hasText(title) || title.strip().length() > 80) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "标题不能为空且不超过 80 字符");
        }
        if (!repository.sessionExists(actor.organizationId(), actor.userId(), sessionId)) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "图谱对话不存在");
        }
        repository.renameSession(actor.organizationId(), actor.userId(), sessionId, title.strip());
    }

    @Transactional
    public void deleteSession(UUID sessionId) {
        var actor = ActorContext.required();
        if (!repository.sessionExists(actor.organizationId(), actor.userId(), sessionId)) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "图谱对话不存在");
        }
        repository.deleteSession(actor.organizationId(), actor.userId(), sessionId);
    }

    public SpectrumRepository.CategoryRow requireCategory(UUID organizationId, UUID categoryId) {
        return repository.findCategory(organizationId, categoryId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "图谱分类不存在"));
    }

    public SpectrumRepository.ChartRow requireChart(UUID organizationId, UUID chartId) {
        return repository.findChart(organizationId, chartId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "图谱不存在"));
    }

    private void ensureBuiltIns(UUID organizationId, UUID userId) {
        for (var item : BUILT_INS) {
            if (repository.findCategoryByCode(organizationId, item.code()).isEmpty()) {
                repository.createCategory(new SpectrumRepository.NewCategory(
                        UUID.randomUUID(), organizationId, item.code(), item.name(), item.description(),
                        item.analysisHint(), item.fieldsJson(), item.sortOrder(), true, userId));
            }
        }
    }

    private CategoryView categoryView(SpectrumRepository.CategoryRow row) {
        return new CategoryView(row.id(), row.code(), row.name(), row.description(), row.analysisHint(),
                parse(row.fieldsJson(), objectMapper.createArrayNode()), row.sortOrder(), row.systemCategory(), row.chartCount());
    }

    private ChartView chartView(SpectrumRepository.ChartRow row) {
        return new ChartView(row.id(), row.categoryId(), row.categoryCode(), row.categoryName(), row.fileObjectId(),
                row.title(), row.originalName(), row.contentType(), row.size(), row.sha256(), row.sampleName(),
                row.batchNo(), row.testConditions(), parse(row.metadataJson(), objectMapper.createObjectNode()),
                row.pageCount(), row.status(), row.createdAt(), row.updatedAt());
    }

    private MessageView messageView(SpectrumRepository.MessageRow row) {
        return new MessageView(row.id(), row.analysisRunId(), row.role(), row.content(),
                parse(row.citationsJson(), objectMapper.createArrayNode()), parse(row.resultJson(), objectMapper.createObjectNode()),
                parse(row.warningJson(), objectMapper.createArrayNode()), row.createdAt());
    }

    private JsonNode parse(String value, JsonNode fallback) {
        try { return StringUtils.hasText(value) ? objectMapper.readTree(value) : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private String validJsonObject(JsonNode value) {
        return value == null || !value.isObject() ? "{}" : value.toString();
    }

    private String validJsonObject(String value) {
        try {
            var node = StringUtils.hasText(value) ? objectMapper.readTree(value) : objectMapper.createObjectNode();
            return validJsonObject(node);
        } catch (Exception exception) { throw new ApiException(ApiErrorCode.BAD_REQUEST, "图谱元数据必须是 JSON 对象"); }
    }

    private String validJsonArray(JsonNode value) {
        return value == null || !value.isArray() ? "[]" : value.toString();
    }

    private String validJsonArray(String value) {
        try {
            var node = StringUtils.hasText(value) ? objectMapper.readTree(value) : objectMapper.createArrayNode();
            return validJsonArray(node);
        } catch (Exception exception) { throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类字段必须是 JSON 数组"); }
    }

    private String trim(String value) { return StringUtils.hasText(value) ? value.trim() : null; }

    private String required(String value, String message) {
        if (!StringUtils.hasText(value)) throw new ApiException(ApiErrorCode.BAD_REQUEST, message);
        return value.trim();
    }

    private String normalizeCustomCode(String code, String name) {
        var source = StringUtils.hasText(code) ? code : name;
        if (!StringUtils.hasText(source)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类编码不能为空");
        var normalized = source.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        if (normalized.isBlank()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类编码无效");
        return normalized.startsWith("CUSTOM_") ? normalized : "CUSTOM_" + normalized;
    }

    private UUID parseUuid(String value, String message) {
        try { return UUID.fromString(value); } catch (Exception exception) { throw new ApiException(ApiErrorCode.BAD_REQUEST, message); }
    }

    private int pageCount(String contentType, String fileName, byte[] bytes) {
        if ((contentType != null && contentType.equalsIgnoreCase("application/pdf"))
                || (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf"))) {
            try (var document = Loader.loadPDF(bytes)) { return Math.max(1, document.getNumberOfPages()); }
            catch (IOException exception) { throw new ApiException(ApiErrorCode.BAD_REQUEST, "PDF 图谱无法读取"); }
        }
        return 1;
    }

    public record CategoryCommand(String code, String name, String description, String analysisHint, JsonNode fields) { }

    public record CreateChartCommand(String fileId, String title, UUID categoryId, String sampleName, String batchNo,
                                     String testConditions, JsonNode metadata) { }

    public record UpdateChartCommand(String title, String sampleName, String batchNo, String testConditions,
                                     JsonNode metadata) { }

    public record CategoryView(UUID id, String code, String name, String description, String analysisHint,
                               JsonNode fields, int sortOrder, boolean systemCategory, long chartCount) { }

    public record ChartView(UUID id, UUID categoryId, String categoryCode, String categoryName, UUID fileObjectId,
                            String title, String originalName, String contentType, long size, String sha256,
                            String sampleName, String batchNo, String testConditions, JsonNode metadata,
                            int pageCount, String status, Instant createdAt, Instant updatedAt) { }

    public record PageView(int pageNo, int pageCount) { }

    public record StoredChartFile(SpectrumRepository.ChartRow chart, FileStorageFacade.StoredFile file) { }

    public record SessionView(UUID id, String title, Instant createdAt, Instant updatedAt, List<MessageView> messages) { }

    public record MessageView(UUID id, UUID analysisRunId, String role, String content, JsonNode citations,
                              JsonNode result, JsonNode warnings, Instant createdAt) { }

    private record BuiltInCategory(String code, String name, String description, String analysisHint,
                                   String fieldsJson, int sortOrder) { }
}
