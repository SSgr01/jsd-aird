package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.rnd.domain.ExperimentSourceType;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ExperimentDraftApplicationFacade implements ExperimentDraftFacade {
    private final ExperimentRepository repository;
    private final ExperimentEditModelNormalizer editModels;
    private final ObjectMapper json;
    private final AuthorizationService authorization;

    public ExperimentDraftApplicationFacade(ExperimentRepository repository,
                                            ExperimentEditModelNormalizer editModels,
                                            ObjectMapper json,
                                            AuthorizationService authorization) {
        this.repository = repository;
        this.editModels = editModels;
        this.json = json;
        this.authorization = authorization;
    }

    @Override
    public CategoryRef requireActiveCategory(UUID organizationId, UUID categoryId) {
        if (categoryId == null) throw validation("必须选择实验分类");
        return repository.categories(organizationId, false).stream()
                .filter(item -> item.id().equals(categoryId))
                .map(item -> new CategoryRef(item.id(), item.code(), item.name()))
                .findFirst().orElseThrow(() -> validation("目标实验分类不存在或已停用"));
    }

    @Override
    @Transactional
    public ImportedDraft createImportedDraft(ImportedDraftCommand command) {
        if (command == null || command.organizationId() == null || command.actorId() == null) {
            throw validation("创建导入实验草稿缺少组织或操作人");
        }
        if (command.title() == null || command.title().isBlank()) {
            throw validation("实验名称不能为空");
        }
        var category = requireActiveCategory(command.organizationId(), command.categoryId());
        var id = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var editModel = editModels.normalize(
                command.editModel() == null ? json.createObjectNode() : command.editModel(), versionId);
        var sourceType = ExperimentSourceType.fromNullable(command.sourceType()).name();
        var result = repository.create(new ExperimentRepository.Create(
                id, command.organizationId(), null, command.title().strip(), category.id(), category.name(),
                sourceType, ExperimentStatus.DRAFT, command.projectId(), command.stageId(), command.taskId(),
                null, blankToNull(command.ownerName()), command.experimentDate(), versionId,
                command.templateVersionId(), blankToNull(command.templateSnapshotHash()),
                command.templateSnapshot() == null ? json.createObjectNode() : command.templateSnapshot(),
                editModel, command.actorId(), blankToNull(command.actorName())
        ));
        return new ImportedDraft(result.id(), versionId, result.experimentNo(), result.title());
    }

    @Override
    @Transactional
    public ImportedDraft createResearchDraft(ResearchDraftCommand command) {
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "experiment.create",
                "EXPERIMENT", null, "CREATE"));
        if (command == null || command.title() == null || command.title().isBlank()) {
            throw validation("实验名称不能为空");
        }
        if (command.plannedExperimentDate() == null) throw validation("必须填写计划实验日期");
        if (command.templateVersionId() == null || command.templateSnapshotHash() == null
                || command.templateSnapshotHash().isBlank() || command.templateSnapshot() == null
                || !command.templateSnapshot().isObject() || command.templateSnapshot().isEmpty()) {
            throw new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED,
                    "AI候选必须使用已发布实验模板创建草稿");
        }
        var category = requireActiveCategory(actor.organizationId(), command.categoryId());
        var id = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var editModel = editModels.normalize(
                command.editModel() == null ? json.createObjectNode() : command.editModel(), versionId);
        var result = repository.create(new ExperimentRepository.Create(
                id, actor.organizationId(), null, command.title().strip(), category.id(), category.name(),
                ExperimentSourceType.MANUAL.name(), ExperimentStatus.DRAFT, command.projectId(), command.stageId(),
                command.taskId(), actor.userId(), blankToNull(command.ownerName()) == null
                ? actor.username() : command.ownerName().strip(), command.plannedExperimentDate(), versionId,
                command.templateVersionId(), command.templateSnapshotHash().strip(),
                command.templateSnapshot().deepCopy(), editModel, actor.userId(), actor.username()
        ));
        return new ImportedDraft(result.id(), versionId, result.experimentNo(), result.title());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException validation(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_ERROR, message);
    }
}
