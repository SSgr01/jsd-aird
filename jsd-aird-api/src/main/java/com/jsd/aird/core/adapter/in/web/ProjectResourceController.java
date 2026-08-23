package com.jsd.aird.core.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.core.api.ProjectResourceFacade;
import com.jsd.aird.core.api.ProjectResourceFacade.ProjectRelationTarget;
import com.jsd.aird.core.api.ProjectResourceFacade.ReferenceQuery;
import com.jsd.aird.core.api.ProjectResourceFacade.ResourceType;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ProjectResourceController {

    private final ProjectResourceFacade service;
    private final AuthorizationService authorization;

    public ProjectResourceController(ProjectResourceFacade service, AuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping("/project-resource-links")
    public ApiResponse<List<ProjectResourceFacade.RelatedProjectView>> links(
            @RequestParam ResourceType resourceType, @RequestParam UUID resourceId) {
        return success(service.links(ActorContext.required(), resourceType, resourceId));
    }

    @PutMapping("/project-resource-links/{resourceType}/{resourceId}")
    public ApiResponse<List<ProjectResourceFacade.RelatedProjectView>> replace(
            @PathVariable ResourceType resourceType, @PathVariable UUID resourceId,
            @Valid @RequestBody LinkRequest request) {
        var actor = ActorContext.required();
        var sourcePermission = resourceType == ResourceType.KNOWLEDGE_DOCUMENT ? "knowledge.update" : "data.update";
        var sourceResource = resourceType == ResourceType.KNOWLEDGE_DOCUMENT ? "KNOWLEDGE" : "DATA";
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), sourcePermission,
                sourceResource, resourceId, "WRITE"));
        return success(service.replaceLinks(actor, resourceType, resourceId, targets(request.targets())));
    }

    @PostMapping("/project-references")
    public ApiResponse<List<ProjectResourceFacade.ReferenceView>> addReferences(
            @Valid @RequestBody AddReferenceRequest request) {
        return success(service.addReferences(ActorContext.required(), request.resourceType(), request.resourceId(),
                request.summary(), targets(request.targets())));
    }

    @GetMapping("/projects/{projectId}/references")
    public ApiResponse<PageResponse<ProjectResourceFacade.ReferenceView>> references(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sourceModule,
            @RequestParam(required = false) UUID stageId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) UUID addedBy,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(service.references(ActorContext.required(), projectId,
                new ReferenceQuery(keyword, sourceModule, stageId, taskId, addedBy, status, page, size)));
    }

    @DeleteMapping("/project-references/{referenceId}")
    public ApiResponse<Void> remove(@PathVariable UUID referenceId) {
        service.removeReference(ActorContext.required(), referenceId);
        return success(null);
    }

    @PostMapping("/project-references/{referenceId}/restore")
    public ApiResponse<ProjectResourceFacade.ReferenceView> restore(@PathVariable UUID referenceId) {
        return success(service.restoreReference(ActorContext.required(), referenceId));
    }

    private List<ProjectRelationTarget> targets(List<TargetRequest> targets) {
        return targets.stream().map(item -> new ProjectRelationTarget(item.projectId(), item.stageId(), item.taskId())).toList();
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record LinkRequest(@NotNull List<@Valid TargetRequest> targets) { }
    public record TargetRequest(@NotNull UUID projectId, UUID stageId, UUID taskId) { }
    public record AddReferenceRequest(@NotNull ResourceType resourceType, @NotNull UUID resourceId,
                                      @Size(max = 2000) String summary,
                                      @NotEmpty List<@Valid TargetRequest> targets) { }
}
