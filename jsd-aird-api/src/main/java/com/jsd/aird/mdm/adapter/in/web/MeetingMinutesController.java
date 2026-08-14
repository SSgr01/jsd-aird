package com.jsd.aird.mdm.adapter.in.web;

import com.jsd.aird.mdm.application.service.MeetingMinutesService;
import com.jsd.aird.mdm.domain.model.MeetingMinutes;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/meetings")
@Tag(name = "项目-会议纪要", description = "项目下的会议纪要管理（按项目维度）")
public class MeetingMinutesController {

    private final MeetingMinutesService service;

    public MeetingMinutesController(MeetingMinutesService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "分页查询会议纪要", description = "按项目 ID 分页查询会议纪要")
    ApiResponse<PageResponse<MeetingMinutes>> list(@Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ok(service.listByProject(projectId, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询单条会议纪要", description = "按 ID 获取会议纪要详情")
    ApiResponse<MeetingMinutes> get(@Parameter(description = "会议纪要 ID") @PathVariable UUID id) {
        return ok(service.get(id));
    }

    @PostMapping
    @Operation(summary = "新增会议纪要", description = "为指定项目新增一条会议纪要")
    ApiResponse<UUID> create(@Parameter(description = "项目 ID") @PathVariable(required = false) UUID projectId,
                             @Valid @RequestBody MeetingMinutesRequest r) {
        var resolvedProjectId = r.projectId() != null ? r.projectId() : projectId;
        if (resolvedProjectId == null) {
            throw new com.jsd.aird.shared.error.ApiException(
                    com.jsd.aird.shared.error.ApiErrorCode.BAD_REQUEST, "缺少项目 ID");
        }
        var now = Instant.now();
        var domain = new MeetingMinutes(null, resolvedProjectId, r.title(), r.attendees(),
                r.summary(), r.occurredAt() == null ? now : r.occurredAt(), false, 0, now, now);
        return ok(service.create(domain));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新会议纪要", description = "更新会议纪要内容（需携带版本号）")
    ApiResponse<Void> update(@Parameter(description = "会议纪要 ID") @PathVariable UUID id,
                             @Valid @RequestBody MeetingMinutesRequest r) {
        var now = Instant.now();
        var domain = new MeetingMinutes(id, null, r.title(), r.attendees(),
                r.summary(), r.occurredAt() == null ? now : r.occurredAt(), false,
                r.version() == null ? 0 : r.version(), null, now);
        service.update(id, domain);
        return ok(null);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除会议纪要", description = "按 ID 删除会议纪要（需携带版本号）")
    ApiResponse<Void> delete(@Parameter(description = "会议纪要 ID") @PathVariable UUID id,
                             @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ok(null);
    }

    @PostMapping("/{id}/archive-to-kb")
    @Operation(summary = "归档到知识库", description = "将会议纪要标记为已归档到知识库")
    ApiResponse<Void> archiveToKb(@Parameter(description = "会议纪要 ID") @PathVariable UUID id) {
        service.archiveToKb(id);
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record MeetingMinutesRequest(@NotBlank @Size(max = 200) String title,
                                        @Size(max = 200) List<@NotBlank @Size(max = 50) String> attendees,
                                        @Size(max = 5000) String summary,
                                        Instant occurredAt,
                                        UUID projectId,
                                        @PositiveOrZero Long version) {
    }
}