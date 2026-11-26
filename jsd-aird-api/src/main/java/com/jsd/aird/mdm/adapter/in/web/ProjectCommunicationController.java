package com.jsd.aird.mdm.adapter.in.web;

import com.jsd.aird.mdm.application.service.ProjectCommunicationService;
import com.jsd.aird.mdm.domain.model.ProjectCommunicationRecord;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/communications")
public class ProjectCommunicationController {
    private final ProjectCommunicationService service;

    public ProjectCommunicationController(ProjectCommunicationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<ProjectCommunicationRecord>> list(@PathVariable UUID projectId,
                                                                        @RequestParam(defaultValue = "1") int page,
                                                                        @RequestParam(defaultValue = "20") int size) {
        return ok(service.list(projectId, page, size));
    }

    @PostMapping
    public ApiResponse<UUID> create(@PathVariable UUID projectId, @Valid @RequestBody Request request) {
        return ok(service.create(projectId, request.toRecord(0)));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable UUID projectId, @PathVariable UUID id,
                                    @Valid @RequestBody Request request) {
        if (request.version() == null) throw new com.jsd.aird.shared.error.ApiException(
                com.jsd.aird.shared.error.ApiErrorCode.VALIDATION_ERROR, "必须提交读取时的数据版本");
        service.update(projectId, id, request.toRecord(request.version()));
        return ok(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID projectId, @PathVariable UUID id,
                                    @RequestParam @PositiveOrZero long version) {
        service.delete(projectId, id, version);
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record Request(@NotNull Instant occurredAt,
                          @NotBlank @Size(max = 100) String communicationMethod,
                          @NotBlank @Size(max = 100) String communicator,
                          @NotBlank String content,
                          Map<@NotBlank String, String> customFields,
                          @PositiveOrZero Long version) {
        ProjectCommunicationRecord toRecord(long currentVersion) {
            return new ProjectCommunicationRecord(null, null, occurredAt, communicationMethod.trim(),
                    communicator.trim(), content.trim(), customFields == null ? Map.of() : customFields,
                    currentVersion, null, null);
        }
    }
}
