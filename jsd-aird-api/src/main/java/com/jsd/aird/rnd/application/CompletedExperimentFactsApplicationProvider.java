package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.rnd.api.CompletedExperimentFactsProvider;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;

@Service
public class CompletedExperimentFactsApplicationProvider implements CompletedExperimentFactsProvider {

    private final ExperimentRepository repository;
    private final ExperimentEditModelNormalizer editModels;
    private final AuthorizationService authorization;
    private final ObjectMapper json;

    public CompletedExperimentFactsApplicationProvider(ExperimentRepository repository,
                                                        ExperimentEditModelNormalizer editModels,
                                                        AuthorizationService authorization,
                                                        ObjectMapper json) {
        this.repository = repository;
        this.editModels = editModels;
        this.authorization = authorization;
        this.json = json;
    }

    @Override
    public CompletedExperimentFactsPage query(CompletedExperimentFactsQuery query) {
        var actor = ActorContext.required();
        var request = query == null ? CompletedExperimentFactsQuery.all(1, 100) : query;
        var scope = authorization.resolveScope(new PermissionCheck(
                actor.organizationId(), actor.userId(), "experiment.view", "EXPERIMENT", null, "READ"));
        if (!scope.allowed()) {
            throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "当前用户没有实验查看权限");
        }
        var search = new ExperimentRepository.CompletedFactsSearch(
                request.experimentIds(), request.projectId(), request.categoryId(), request.page(), request.size(),
                new ExperimentRepository.DataScopeFilter(scope.scopeType(), actor.userId(), scope.targetIds()));
        var rows = repository.completedFacts(actor.organizationId(), search);
        var total = repository.countCompletedFacts(actor.organizationId(), search);
        var items = rows.stream().map(row -> {
            var model = editModels.normalize(row.editModel(), row.experimentVersionId());
            return new CompletedExperimentFacts(
                    row.experimentId(), row.experimentVersionId(), row.experimentNo(), row.title(), row.sourceType(),
                    row.projectId(), row.stageId(), row.taskId(), row.categoryId(), row.categoryName(), row.experimentDate(),
                    copy(model.path("sourceGroups"), true), copy(model.path("sourceContexts"), true),
                    copy(model.path("formulaItems"), true), copy(model.path("processSteps"), true),
                    copy(model.path("testResults"), true), copy(model.path("dynamicValues"), false));
        }).toList();
        return new CompletedExperimentFactsPage(items, request.page(), request.size(), total,
                (total + request.size() - 1) / request.size());
    }

    private com.fasterxml.jackson.databind.JsonNode copy(com.fasterxml.jackson.databind.JsonNode value, boolean array) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return array ? json.createArrayNode() : json.createObjectNode();
        }
        return value.deepCopy();
    }
}
