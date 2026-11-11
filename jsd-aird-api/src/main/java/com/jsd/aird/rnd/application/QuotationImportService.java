package com.jsd.aird.rnd.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.rnd.application.port.QuotationImportRepository;
import com.jsd.aird.rnd.application.port.QuotationRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报价记录导入：请求线程只暂存文件和创建任务，Excel/CSV 解析、客户信息与明细落库在后台
 * 异步任务中完成，与实验本的文件导入链路保持一致。
 */
@Service
public class QuotationImportService {
    private static final String JOB_TYPE = "QUOTATION_IMPORT_PARSE";
    private static final String KEY_PREFIX = "quotation-import:";
    private static final String RETRY_KEY_PREFIX = "quotation-import-retry:";
    private static final Set<TemplateFormat> SUPPORTED_FORMATS =
            Set.of(TemplateFormat.XLSX, TemplateFormat.XLS, TemplateFormat.CSV);

    private final QuotationImportRepository imports;
    private final QuotationRepository quotations;
    private final TemplateRepository templates;
    private final FileStorageFacade files;
    private final ObjectMapper objectMapper;
    private final OpsAsyncFacade asyncJobs;
    private final QuotationWorkbookParser parser = new QuotationWorkbookParser();
    private org.springframework.transaction.support.TransactionTemplate commitTransaction;

    public QuotationImportService(QuotationImportRepository imports, QuotationRepository quotations,
                                  FileStorageFacade files, ObjectMapper objectMapper,
                                  OpsAsyncFacade asyncJobs, TemplateRepository templates) {
        this.imports = imports;
        this.quotations = quotations;
        this.templates = templates;
        this.files = files;
        this.objectMapper = objectMapper;
        this.asyncJobs = asyncJobs;
    }

    @Autowired
    void configureTransactions(org.springframework.transaction.PlatformTransactionManager manager) {
        commitTransaction = new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    @Transactional
    public Accepted importFile(Command command) {
        var actor = ActorContext.required();
        var normalized = normalize(command);
        if (templates.findTemplates(actor.organizationId(), null, TemplateFormat.XLSX,
                TemplateStatus.PUBLISHED).stream()
                .noneMatch(item -> item.versionId().equals(normalized.templateVersionId()))) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "已发布的 Excel 识别模板不存在或已失效");
        }
        var existing = imports.findCompletedBySourceHash(actor.organizationId(), normalized.sha256());
        if (existing.isPresent()) {
            return new Accepted(existing.get().id(), existing.get().quotationId(), "COMPLETED");
        }
        var jobId = UUID.randomUUID();
        imports.create(jobId, actor.organizationId(), normalized.fileId(), normalized.fileName(),
                normalized.sha256(), normalized.format().name(), actor.userId(),
                normalized.templateVersionId(), normalized.projectId(), normalized.projectName(),
                normalized.stageId(), normalized.stageName(), normalized.taskId(),
                normalized.taskName(), normalized.visibility());
        enqueue(jobId, normalized, actor, KEY_PREFIX + jobId);
        return new Accepted(jobId, null, "PARSING");
    }

    public Accepted retry(UUID id) {
        var actor = ActorContext.required();
        var job = imports.find(actor.organizationId(), actor.userId(), actor.role(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "上传记录不存在"));
        if ("PARSING".equals(job.status())) {
            if (imports.cancel(actor.organizationId(), actor.userId(), actor.role(), id) != 1) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "解析任务状态已变化，请刷新后重试");
            }
            asyncJobs.cancelByIdempotencyKeyPrefix(actor.organizationId(), KEY_PREFIX + id);
            asyncJobs.cancelByIdempotencyKeyPrefix(actor.organizationId(), RETRY_KEY_PREFIX + id + ":");
        } else if (!Set.of("COMPLETED", "FAILED", "CANCELLED").contains(job.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "当前上传记录不可重试");
        }
        if (imports.markRetrying(actor.organizationId(), id) != 1) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "上传记录状态已变化，请刷新后重试");
        }
        var command = normalize(new Command(job.sourceFileId(), job.sourceFileName(),
                job.sourceSha256(), parseFormat(job.sourceFormat()), job.templateVersionId(),
                job.projectId(), job.projectName(), job.stageId(), job.stageName(),
                job.taskId(), job.taskName(), job.visibility()));
        enqueue(job.id(), command, actor, RETRY_KEY_PREFIX + job.id() + ":" + UUID.randomUUID());
        return new Accepted(job.id(), null, "PARSING");
    }

    /** 后台任务入口：解析文件并生成报价记录与明细。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UUID processQueued(UUID jobId, Command command, Actor actor, String asyncIdempotencyKey) {
        updateProgress(actor.organizationId(), asyncIdempotencyKey, 5, "PREPARING");
        try {
            updateProgress(actor.organizationId(), asyncIdempotencyKey, 20, "LOADING_FILE");
            QuotationWorkbookParser.ParsedQuotation parsed;
            try (var stored = files.open(actor.organizationId(), command.fileId())) {
                var bytes = com.jsd.aird.shared.resource.BoundedStreams.readBytes(
                        stored.stream(), 200L * 1024 * 1024);
                updateProgress(actor.organizationId(), asyncIdempotencyKey, 45, "PARSING_WORKBOOK");
                parsed = parser.parse(command.format(), command.fileName(), bytes);
            }
            if (!imports.isParsing(actor.organizationId(), jobId)) return null;
            updateProgress(actor.organizationId(), asyncIdempotencyKey, 75, "BUILDING_RECORD");
            var draft = toDraft(command, parsed);
            updateProgress(actor.organizationId(), asyncIdempotencyKey, 95, "PERSISTING_RESULT");
            java.util.function.Supplier<UUID> commit = () -> {
                asyncJobs.requireExecution(
                        com.jsd.aird.ops.application.port.AsyncExecutionScope.current(), false);
                if (!imports.isParsing(actor.organizationId(), jobId)) return null;
                var previousQuotationId = imports.find(actor.organizationId(), actor.userId(),
                        actor.role(), jobId).map(QuotationImportRepository.Job::quotationId).orElse(null);
                var quotationId = quotations.create(actor.organizationId(), actor.userId(), draft);
                imports.complete(jobId, quotationId);
                if (previousQuotationId != null) {
                    quotations.retireReplaced(actor.organizationId(), previousQuotationId);
                }
                return quotationId;
            };
            return commitTransaction == null ? commit.get() : commitTransaction.execute(status -> commit.get());
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("报价文件解析失败: " + exception.getMessage(), exception);
        }
    }

    @Transactional
    public void failQueued(UUID jobId, String errorMessage) {
        asyncJobs.requireExecution(com.jsd.aird.ops.application.port.AsyncExecutionScope.current(), true);
        imports.fail(jobId, errorMessage);
    }

    public List<QuotationImportRepository.Job> list() {
        var actor = ActorContext.required();
        return imports.list(actor.organizationId(), actor.userId(), actor.role()).stream()
                .map(job -> enrichProgress(actor.organizationId(), job))
                .toList();
    }

    public QuotationImportRepository.Job get(UUID id) {
        var actor = ActorContext.required();
        var job = imports.find(actor.organizationId(), actor.userId(), actor.role(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "上传记录不存在"));
        return enrichProgress(actor.organizationId(), job);
    }

    @Transactional
    public void delete(UUID id) {
        var actor = ActorContext.required();
        imports.find(actor.organizationId(), actor.userId(), actor.role(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "上传记录不存在"));
        var parsing = imports.cancel(actor.organizationId(), actor.userId(), actor.role(), id) == 1;
        asyncJobs.cancelByIdempotencyKeyPrefix(actor.organizationId(), KEY_PREFIX + id);
        asyncJobs.cancelByIdempotencyKeyPrefix(actor.organizationId(), RETRY_KEY_PREFIX + id + ":");
        if (parsing) return;
        if (imports.delete(actor.organizationId(), actor.userId(), actor.role(), id) == 0) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "上传记录不存在或状态已变化，请刷新后重试");
        }
    }

    private QuotationRepository.Draft toDraft(Command command, QuotationWorkbookParser.ParsedQuotation parsed) {
        var customer = parsed.customer();
        var contacts = parsed.contacts().stream()
                .map(contact -> new QuotationRepository.ContactDraft(contact.position(),
                        contact.sourceColumn(), contact.name(), contact.title(), contact.phone()))
                .toList();
        var items = parsed.items().stream()
                .map(item -> new QuotationRepository.ItemDraft(item.lineNo(), item.sourceRowNo(),
                        item.productName(), item.unitPrice(), item.specification(),
                        item.priceOptions().stream().map(option -> new QuotationRepository.PriceOptionDraft(
                                option.position(), option.unitPrice(), option.specification())).toList(),
                        item.quoteDate(), item.customerCode(), item.note()))
                .toList();
        return new QuotationRepository.Draft(quotationNo(), customer.customerName(),
                customer.relatedCompany(), customer.contactName(), customer.contactTitle(),
                customer.phone(), customer.fax(), customer.email(), customer.addressLine1(),
                customer.addressLine2(), customer.website(), customer.note(), command.fileId(),
                command.fileName(), command.format().name(), contacts, items,
                command.templateVersionId(), command.projectId(), command.projectName(),
                command.stageId(), command.stageName(), command.taskId(), command.taskName(),
                command.visibility());
    }

    private String quotationNo() {
        return "BJ-" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT);
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
        payload.set("command", objectMapper.valueToTree(command));
        asyncJobs.enqueue(actor.organizationId(), JOB_TYPE, payload, idempotencyKey, 50);
    }

    private Command normalize(Command command) {
        if (command == null || command.fileId() == null || command.fileName() == null
                || command.fileName().isBlank() || command.sha256() == null
                || command.sha256().isBlank() || command.format() == null
                || command.templateVersionId() == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "报价文件信息不完整");
        }
        if (!SUPPORTED_FORMATS.contains(command.format())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "报价记录仅支持 XLSX / XLS / CSV 文件");
        }
        if (!Set.of("ALL", "RND", "PROJECT").contains(command.visibility())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "权限类型不合法");
        }
        if ("PROJECT".equals(command.visibility()) && command.projectId() == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "项目组可见时必须关联项目");
        }
        return new Command(command.fileId(), command.fileName().strip(),
                command.sha256().strip(), command.format(), command.templateVersionId(),
                command.projectId(), command.projectName(), command.stageId(),
                command.stageName(), command.taskId(), command.taskName(), command.visibility());
    }

    private TemplateFormat parseFormat(String value) {
        try {
            return TemplateFormat.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的报价文件格式");
        }
    }

    private void updateProgress(UUID organizationId, String asyncIdempotencyKey, int progress, String stage) {
        asyncJobs.updateProgress(organizationId, asyncIdempotencyKey, progress, stage);
    }

    private void putUuid(ObjectNode target, String field, UUID value) {
        if (value != null) target.put(field, value.toString());
    }

    private QuotationImportRepository.Job enrichProgress(UUID organizationId, QuotationImportRepository.Job job) {
        if ("COMPLETED".equals(job.status())) return withProgress(job, 100, "COMPLETED");
        var asyncJob = latestAsyncJob(organizationId, job.id());
        if ("PARSING".equals(job.status())
                && asyncJob.map(item -> "FAILED".equals(item.status())).orElse(false)) {
            var error = asyncJob.map(OpsAsyncFacade.AsyncJobView::lastError)
                    .filter(item -> item != null && !item.isBlank())
                    .orElse("后台解析任务失败");
            return withStatus(job, "FAILED", error, 0, "FAILED");
        }
        return withProgress(job, asyncJob.map(OpsAsyncFacade.AsyncJobView::progress).orElse(0),
                asyncJob.map(OpsAsyncFacade.AsyncJobView::currentStage).orElse(null));
    }

    private Optional<OpsAsyncFacade.AsyncJobView> latestAsyncJob(UUID organizationId, UUID jobId) {
        var retry = asyncJobs.findLatestJob(organizationId, RETRY_KEY_PREFIX + jobId + ":");
        return retry.isPresent() ? retry : asyncJobs.findJob(organizationId, KEY_PREFIX + jobId);
    }

    private QuotationImportRepository.Job withProgress(QuotationImportRepository.Job job, int progress,
                                                       String currentStage) {
        return withStatus(job, job.status(), job.errorMessage(), progress, currentStage);
    }

    private QuotationImportRepository.Job withStatus(QuotationImportRepository.Job job, String status,
                                                     String errorMessage, int progress,
                                                     String currentStage) {
        return new QuotationImportRepository.Job(job.id(), job.sourceFileId(), job.sourceFileName(),
                job.sourceSha256(), job.sourceFormat(), status, job.quotationId(), job.quotationNo(),
                job.customerName(), job.itemCount(), errorMessage, job.createdAt(), progress, currentStage,
                job.templateVersionId(), job.projectId(), job.projectName(), job.stageId(),
                job.stageName(), job.taskId(), job.taskName(), job.visibility());
    }

    public record Command(UUID fileId, String fileName, String sha256, TemplateFormat format,
                          UUID templateVersionId, UUID projectId, String projectName,
                          UUID stageId, String stageName, UUID taskId, String taskName,
                          String visibility) { }

    public record Accepted(UUID jobId, UUID quotationId, String status) { }
}
