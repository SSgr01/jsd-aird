package com.jsd.aird.ai.rnd.eligibility;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.jsd.aird.ai.rnd.eligibility.EligibilityContracts.*;

@RestController
@RequestMapping("/api/v1/ai/rnd")
public class EligibilityController {
    private final EligibilityService service;

    public EligibilityController(EligibilityService service) { this.service = service; }

    @GetMapping("/eligibility/summary")
    public ApiResponse<?> summary(@RequestParam UUID targetVersionId, @RequestParam UUID inputSchemeId) {
        return ok(service.summary(targetVersionId, inputSchemeId));
    }

    @GetMapping("/eligibility")
    public ApiResponse<?> page(@RequestParam UUID targetVersionId, @RequestParam UUID inputSchemeId,
                               @RequestParam(required = false) String state,
                               @RequestParam(required = false) String reasonCode,
                               @RequestParam(required = false) String sourceType,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return ok(service.page(targetVersionId, inputSchemeId, state, reasonCode, sourceType, keyword, page, size));
    }

    @GetMapping("/eligibility/{id}")
    public ApiResponse<?> detail(@PathVariable UUID id) { return ok(service.detail(id)); }

    @PostMapping("/targets/{id}/reevaluate")
    public ApiResponse<?> reevaluate(@PathVariable UUID id,
                                     @RequestHeader("Idempotency-Key") String key) {
        return ok(service.reevaluate(id, key));
    }

    @PostMapping("/reviews/{id}/decisions")
    public ApiResponse<?> decide(@PathVariable UUID id, @RequestBody ReviewDecisionCommand body,
                                 @RequestHeader("Idempotency-Key") String key,
                                 @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ok(service.decide(id, body, key, requestId == null ? RequestIdHolder.currentOrUnknown() : requestId));
    }

    private static <T> ApiResponse<T> ok(T data) { return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown()); }
}
