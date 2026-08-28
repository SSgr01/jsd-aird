package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.Audit;
import com.jsd.aird.rnd.domain.ExperimentModels.Category;
import com.jsd.aird.rnd.domain.ExperimentModels.Detail;
import com.jsd.aird.rnd.domain.ExperimentModels.Summary;
import com.jsd.aird.rnd.domain.ExperimentModels.Version;
import com.jsd.aird.rnd.domain.ExperimentSourceType;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ExperimentService {
    private final ExperimentRepository repository;
    private final ObjectMapper json;
    private final FileStorageFacade files;
    private final ExperimentEditModelNormalizer editModels;

    public ExperimentService(ExperimentRepository repository, ObjectMapper json, FileStorageFacade files,
                             ExperimentEditModelNormalizer editModels) {
        this.repository = repository;
        this.json = json;
        this.files = files;
        this.editModels = editModels;
    }

    public PageResponse<Summary> search(ExperimentRepository.Search query) {
        ExperimentAccessPolicy.requireRead();
        var actor = ActorContext.required();
        var normalized = normalize(query);
        var items = repository.search(actor.organizationId(), normalized);
        var total = repository.count(actor.organizationId(), normalized);
        return new PageResponse<>(items, normalized.page(), normalized.size(), total,
                (total + normalized.size() - 1) / normalized.size());
    }

    public Detail detail(UUID id) {
        ExperimentAccessPolicy.requireRead();
        var actor = ActorContext.required();
        return normalize(repository.detail(actor.organizationId(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "实验不存在")));
    }

    @Transactional
    public Summary create(CreateCommand command) {
        ExperimentAccessPolicy.requireWrite();
        var actor = ActorContext.required();
        if (command == null || command.title() == null || command.title().isBlank()) {
            throw validation("实验名称不能为空");
        }

        var title = command.title().strip();
        var id = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var experimentNo = command.experimentNo() == null || command.experimentNo().isBlank()
                ? "EXP-" + LocalDate.now().getYear() + "-"
                        + id.toString().substring(0, 8).toUpperCase(Locale.ROOT)
                : command.experimentNo().strip();
        var ownerName = defaultText(command.ownerName(), actor.username());
        var sourceType = sourceType(command.sourceType(), true);
        var editModel = command.editModel() == null
                ? editModels.empty(title, versionId)
                : editModels.normalize(object(command.editModel(), "editModel"), versionId);

        var summary = repository.create(new ExperimentRepository.Create(
                id,
                actor.organizationId(),
                experimentNo,
                title,
                command.categoryId(),
                blankToNull(command.categoryName()),
                sourceType,
                ExperimentStatus.DRAFT,
                command.projectId(),
                command.stageId(),
                command.taskId(),
                actor.userId(),
                ownerName,
                command.experimentDate() == null ? LocalDate.now() : command.experimentDate(),
                versionId,
                command.templateVersionId(),
                blankToNull(command.templateSnapshotHash()),
                object(command.templateSnapshot(), "templateSnapshot"),
                editModel,
                actor.userId(),
                actor.username()
        ));
        if (command.sourceFileId() != null) {
            files.activate(command.sourceFileId());
        }
        return summary;
    }

    public Summary copy(UUID id) {
        ExperimentAccessPolicy.requireWrite();
        var actor = ActorContext.required();
        return repository.copy(actor.organizationId(), id, actor.userId(), actor.username());
    }

    public Detail save(UUID id, long revision, DraftCommand command) {
        ExperimentAccessPolicy.requireWrite();
        var actor = ActorContext.required();
        if (command == null || command.experimentNo() == null || command.experimentNo().isBlank()) {
            throw validation("实验编号不能为空");
        }
        if (command.title() == null || command.title().isBlank()) {
            throw validation("实验名称不能为空");
        }
        if (command.ownerName() == null || command.ownerName().isBlank()) {
            throw validation("实验负责人不能为空");
        }
        if (command.experimentDate() == null) {
            throw validation("实验日期不能为空");
        }

        return normalize(repository.saveDraft(
                actor.organizationId(),
                id,
                revision,
                new ExperimentRepository.Draft(
                        command.experimentNo().strip(),
                        command.title().strip(),
                        command.categoryId(),
                        blankToNull(command.categoryName()),
                        command.projectId(),
                        command.stageId(),
                        command.taskId(),
                        command.ownerName().strip(),
                        command.experimentDate(),
                        command.templateVersionId(),
                        blankToNull(command.templateSnapshotHash()),
                        object(command.templateSnapshot(), "templateSnapshot"),
                        editModels.normalize(object(command.editModel(), "editModel"), id)
                ),
                actor.userId(),
                actor.username()
        ));
    }

    public void delete(UUID id, long revision) {
        ExperimentAccessPolicy.requireDelete();
        var actor = ActorContext.required();
        repository.delete(actor.organizationId(), id, revision, actor.userId(), actor.username());
    }

    public Detail transition(UUID id, long revision, ExperimentStatus target, String comment) {
        if (target == null) {
            throw validation("实验目标状态不能为空");
        }
        if (target == ExperimentStatus.COMPLETED || target == ExperimentStatus.RETURNED) {
            ExperimentAccessPolicy.requireReview();
        } else {
            ExperimentAccessPolicy.requireWrite();
        }
        var actor = ActorContext.required();
        return normalize(repository.transition(
                actor.organizationId(), id, revision, target, comment, actor.userId(), actor.username()));
    }

    public Detail revision(UUID id, long revision, String reason) {
        ExperimentAccessPolicy.requireWrite();
        var actor = ActorContext.required();
        return normalize(repository.createRevision(
                actor.organizationId(), id, revision, reason, actor.userId(), actor.username()));
    }

    public Detail rollback(UUID id, long revision, int targetVersion, String reason) {
        ExperimentAccessPolicy.requireWrite();
        var actor = ActorContext.required();
        return normalize(repository.rollback(
                actor.organizationId(), id, revision, targetVersion, reason, actor.userId(), actor.username()));
    }

    public List<Version> versions(UUID id) {
        ExperimentAccessPolicy.requireRead();
        var actor = ActorContext.required();
        return repository.versions(actor.organizationId(), id).stream().map(this::normalize).toList();
    }

    public JsonNode compare(UUID id, int from, int to) {
        ExperimentAccessPolicy.requireRead();
        var actor = ActorContext.required();
        return repository.compare(actor.organizationId(), id, from, to);
    }

    public List<Audit> audits(UUID id) {
        ExperimentAccessPolicy.requireRead();
        var actor = ActorContext.required();
        return repository.audits(actor.organizationId(), id);
    }

    public List<Category> categories(boolean includeInactive) {
        ExperimentAccessPolicy.requireRead();
        var actor = ActorContext.required();
        return repository.categories(actor.organizationId(), includeInactive);
    }

    public Category createCategory(String code, String name, String description) {
        ExperimentAccessPolicy.requireWrite();
        var actor = ActorContext.required();
        return repository.createCategory(
                actor.organizationId(), requiredText(code, "分类编码不能为空"),
                requiredText(name, "分类名称不能为空"), requiredText(description, "分类说明不能为空"),
                actor.userId());
    }

    public Category updateCategory(UUID id, long revision, String name, String description) {
        ExperimentAccessPolicy.requireWrite();
        var actor = ActorContext.required();
        return repository.updateCategory(
                actor.organizationId(), id, revision, requiredText(name, "分类名称不能为空"),
                requiredText(description, "分类说明不能为空"), actor.userId());
    }

    public Category categoryActive(UUID id, long revision, boolean active) {
        ExperimentAccessPolicy.requireWrite();
        var actor = ActorContext.required();
        return repository.setCategoryActive(actor.organizationId(), id, revision, active);
    }

    private ExperimentRepository.Search normalize(ExperimentRepository.Search query) {
        if (query == null) {
            throw validation("实验查询条件不能为空");
        }
        if (query.dateFrom() != null && query.dateTo() != null && query.dateFrom().isAfter(query.dateTo())) {
            throw validation("实验开始日期不能晚于结束日期");
        }
        var page = Math.max(1, query.page());
        var size = Math.min(100, Math.max(1, query.size()));
        return new ExperimentRepository.Search(
                blankToNull(query.keyword()),
                status(query.status()),
                sourceType(query.sourceType(), false),
                query.projectId(),
                query.stageId(),
                query.taskId(),
                query.categoryId(),
                blankToNull(query.ownerName()),
                query.dateFrom(),
                query.dateTo(),
                page,
                size
        );
    }

    private String status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ExperimentStatus.valueOf(value.strip().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw validation("实验状态不合法");
        }
    }

    private String sourceType(String value, boolean defaultManual) {
        if (!defaultManual && (value == null || value.isBlank())) {
            return null;
        }
        try {
            return ExperimentSourceType.fromNullable(value).name();
        } catch (IllegalArgumentException exception) {
            throw validation(exception.getMessage());
        }
    }

    private Detail normalize(Detail detail) {
        return new Detail(detail.summary(), detail.currentVersionId(), detail.templateVersionId(),
                detail.templateSnapshotHash(), detail.templateSnapshot(),
                editModels.normalize(detail.editModel(), detail.currentVersionId()),
                detail.reviews(), detail.attachments());
    }

    private Version normalize(Version version) {
        return new Version(version.id(), version.versionNo(), version.status(), version.templateVersionId(),
                version.snapshotHash(), version.templateSnapshot(),
                editModels.normalize(version.editModel(), version.id()), version.revisionReason(),
                version.submittedAt(), version.publishedAt(), version.createdAt(), version.createdBy());
    }

    private JsonNode object(JsonNode value, String fieldName) {
        if (value == null) {
            return json.createObjectNode();
        }
        if (!value.isObject()) {
            throw validation(fieldName + "必须是JSON对象");
        }
        return value;
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw validation(message);
        }
        return value.strip();
    }

    private static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException validation(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_ERROR, message);
    }

    public record CreateCommand(
            String experimentNo,
            String title,
            UUID categoryId,
            String categoryName,
            String sourceType,
            UUID projectId,
            UUID stageId,
            UUID taskId,
            String ownerName,
            LocalDate experimentDate,
            UUID sourceFileId,
            UUID templateVersionId,
            String templateSnapshotHash,
            JsonNode templateSnapshot,
            JsonNode editModel
    ) {}

    public record DraftCommand(
            String experimentNo,
            String title,
            UUID categoryId,
            String categoryName,
            UUID projectId,
            UUID stageId,
            UUID taskId,
            String ownerName,
            LocalDate experimentDate,
            UUID templateVersionId,
            String templateSnapshotHash,
            JsonNode templateSnapshot,
            JsonNode editModel
    ) {}
}
