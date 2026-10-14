package com.jsd.aird.ai.rnd.facts;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/rnd")
public class UnifiedFactController {
    private final UnifiedFactService service;
    public UnifiedFactController(UnifiedFactService service) { this.service = service; }

    @GetMapping("/samples") public ApiResponse<?> samples(@RequestParam(required=false) String status,
            @RequestParam(required=false) String sourceType, @RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ok(service.samples(status, sourceType, keyword, page, size));
    }
    @GetMapping("/samples/{id}") public ApiResponse<?> sample(@PathVariable UUID id) { return ok(service.sample(id)); }
    @PostMapping("/facts/reconcile") public ApiResponse<?> reconcile() { return ok(service.reconcileCurrentOrganization()); }
    private static <T> ApiResponse<T> ok(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }
}
