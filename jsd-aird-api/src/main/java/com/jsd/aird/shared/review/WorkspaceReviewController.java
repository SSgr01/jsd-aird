package com.jsd.aird.shared.review;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import java.util.UUID;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/v1/workspace-reviews/{type}/{id}")
public class WorkspaceReviewController {
    private final WorkspaceReviewService service;

    public WorkspaceReviewController(WorkspaceReviewService service) { this.service = service; }

    @GetMapping
    public ApiResponse<WorkspaceReviewService.State> get(@PathVariable String type, @PathVariable UUID id) {
        return ok(service.get(type, id));
    }

    @GetMapping("/history")
    public ApiResponse<List<WorkspaceReviewService.Event>> history(@PathVariable String type, @PathVariable UUID id) {
        return ok(service.history(type, id));
    }

    @PostMapping("/submit")
    public ApiResponse<WorkspaceReviewService.State> submit(@PathVariable String type, @PathVariable UUID id) {
        return ok(service.submit(type, id));
    }

    @PostMapping("/approve")
    public ApiResponse<WorkspaceReviewService.State> approve(@PathVariable String type, @PathVariable UUID id) {
        return ok(service.approve(type, id));
    }

    public record RejectRequest(@NotBlank String reason) { }

    @PostMapping("/reject")
    public ApiResponse<WorkspaceReviewService.State> reject(@PathVariable String type, @PathVariable UUID id,
                                                             @Valid @RequestBody RejectRequest request) {
        return ok(service.reject(type, id, request.reason()));
    }

    private static <T> ApiResponse<T> ok(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }
}
