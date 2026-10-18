package com.jsd.aird.data.adapter.in.web;

import com.jsd.aird.data.application.DataPublicationService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/data/sources/{id}")
public class DataPublicationController {
    private final DataPublicationService service;
    public DataPublicationController(DataPublicationService service) { this.service = service; }
    @GetMapping("/publication") public ApiResponse<?> get(@PathVariable UUID id) { return ok(service.get(id)); }
    @GetMapping("/publication/snapshots/{snapshotId}") public ApiResponse<?> snapshot(@PathVariable UUID id, @PathVariable UUID snapshotId) {
        return ok(service.snapshot(id, snapshotId));
    }
    @PostMapping("/publication/confirm") public ApiResponse<?> confirm(@PathVariable UUID id) { return ok(service.confirm(id)); }
    @GetMapping("/publication/reviewers") public ApiResponse<?> reviewers(@PathVariable UUID id) { return ok(service.reviewers(id)); }
    @PostMapping("/publication/submit") public ApiResponse<?> submit(@PathVariable UUID id, @Valid @RequestBody SubmitRequest request) { return ok(service.submit(id, request.reviewerId())); }
    @PostMapping("/publication/withdraw") public ApiResponse<?> withdraw(@PathVariable UUID id) { return ok(service.withdraw(id)); }
    @PostMapping("/publication/approve") public ApiResponse<?> review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        return ok(service.review(id, request.snapshotId(), request.approved(), request.comment()));
    }
    @PostMapping("/publication/revise") public ApiResponse<?> revise(@PathVariable UUID id) { return ok(service.revise(id)); }
    @PostMapping("/publication/publish") public ApiResponse<?> publish(@PathVariable UUID id) { return ok(service.publish(id)); }
    @PostMapping("/publication/disable") public ApiResponse<?> disable(@PathVariable UUID id) { return ok(service.setPublishedEnabled(id, false)); }
    @PostMapping("/publication/restore") public ApiResponse<?> restore(@PathVariable UUID id) { return ok(service.setPublishedEnabled(id, true)); }
    @GetMapping("/records") public ApiResponse<?> records(@PathVariable UUID id) { return ok(service.records(id)); }
    @PutMapping("/records/{recordId}/governance") public ApiResponse<?> govern(@PathVariable UUID id, @PathVariable UUID recordId,
            @Valid @RequestBody GovernanceRequest request) {
        service.govern(id, recordId, request.name(), request.status(), request.reason()); return ok(null);
    }
    @GetMapping("/records/export") public ResponseEntity<byte[]> export(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=records.csv").body(service.export(id));
    }
    private ApiResponse<?> ok(Object result) { return ResponseFactory.success(result, RequestIdHolder.currentOrUnknown()); }
    public record ReviewRequest(@NotNull UUID snapshotId, boolean approved, String comment) {}
    public record SubmitRequest(@NotNull UUID reviewerId) {}
    public record GovernanceRequest(@NotBlank String name, @NotBlank String status, String reason) {}
}
