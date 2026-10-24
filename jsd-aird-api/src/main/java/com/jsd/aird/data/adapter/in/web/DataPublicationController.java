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
    @PostMapping("/publication/confirm") public ApiResponse<?> confirm(@PathVariable UUID id,@Valid @RequestBody VersionRequest request) { return ok(service.confirm(id,request.revision())); }
    @GetMapping("/publication/reviewers") public ApiResponse<?> reviewers(@PathVariable UUID id) { return ok(service.reviewers(id)); }
    @PostMapping("/publication/submit") public ApiResponse<?> submit(@PathVariable UUID id, @Valid @RequestBody SubmitRequest request) { return ok(service.submit(id, request.reviewerId(),request.revision())); }
    @PostMapping("/publication/withdraw") public ApiResponse<?> withdraw(@PathVariable UUID id,@Valid @RequestBody VersionRequest request) { return ok(service.withdraw(id,request.revision())); }
    @PostMapping("/publication/approve") public ApiResponse<?> review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        return ok(service.review(id, request.snapshotId(), request.approved(), request.comment(),request.revision()));
    }
    @PostMapping("/publication/revise") public ApiResponse<?> revise(@PathVariable UUID id,@Valid @RequestBody VersionRequest request) { return ok(service.revise(id,request.revision())); }
    @PostMapping("/publication/publish") public ApiResponse<?> publish(@PathVariable UUID id,@Valid @RequestBody VersionRequest request) { return ok(service.publish(id,request.revision())); }
    @PostMapping("/publication/disable") public ApiResponse<?> disable(@PathVariable UUID id,@Valid @RequestBody VersionRequest request) { return ok(service.setPublishedEnabled(id, false,request.revision())); }
    @PostMapping("/publication/restore") public ApiResponse<?> restore(@PathVariable UUID id,@Valid @RequestBody VersionRequest request) { return ok(service.setPublishedEnabled(id, true,request.revision())); }
    @GetMapping("/records") public ApiResponse<?> records(@PathVariable UUID id) { return ok(service.records(id)); }
    @PutMapping("/records/{recordId}/governance") public ApiResponse<?> govern(@PathVariable UUID id, @PathVariable UUID recordId,
            @Valid @RequestBody GovernanceRequest request) {
        service.govern(id, recordId, request.name(), request.status(), request.reason(),request.revision()); return ok(null);
    }
    @GetMapping("/records/export") public ResponseEntity<org.springframework.core.io.Resource> export(@PathVariable UUID id,jakarta.servlet.http.HttpServletRequest request) {
        var prepared=service.prepareExport(id);
        request.setAttribute(com.jsd.aird.platform.web.CapacityFilter.OWNED_DOWNLOAD,prepared);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=records.csv").contentLength(prepared.size()).body(new org.springframework.core.io.FileSystemResource(prepared.path()));
    }
    private ApiResponse<?> ok(Object result) { return ResponseFactory.success(result, RequestIdHolder.currentOrUnknown()); }
    public record VersionRequest(@NotBlank String revision) {}
    public record ReviewRequest(@NotBlank String revision,@NotNull UUID snapshotId, boolean approved, String comment) {}
    public record SubmitRequest(@NotBlank String revision,@NotNull UUID reviewerId) {}
    public record GovernanceRequest(@NotBlank String revision,@NotBlank String name, @NotBlank String status, String reason) {}
}
