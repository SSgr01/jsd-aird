package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.optimization.OptimizationContracts.*;

@RestController
@RequestMapping("/api/v1/ai/rnd")
public class OptimizationController {
    private final OptimizationService service;
    private final FeedbackService feedback;
    public OptimizationController(OptimizationService service,FeedbackService feedback){this.service=service;this.feedback=feedback;}

    @GetMapping("/optimization-baselines")
    public ApiResponse<BaselinePage> baselines(@RequestParam(required=false) String sourceType,
                                               @RequestParam(required=false) String keyword,
                                               @RequestParam(defaultValue="0") int page,
                                               @RequestParam(defaultValue="20") int size){return ok(service.baselines(sourceType,keyword,page,size));}
    @GetMapping("/optimization-context")
    public ApiResponse<JsonNode> context(@RequestParam String baselineType,@RequestParam UUID baselineId,
                                         @RequestParam(required=false) List<UUID> targetId){return ok(service.context(baselineType,baselineId,targetId));}
    @PostMapping("/experiment-optimizations")
    public ResponseEntity<?> submit(@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key){var result=service.submit(body,key);if(List.of("QUEUED","RUNNING").contains(result.executionStatus()))return ResponseEntity.status(202).body(result);return ResponseEntity.ok(service.get(result.runId()));}
    @GetMapping("/experiment-optimizations/{id}") public ApiResponse<OptimizationRun> run(@PathVariable UUID id){return ok(service.get(id));}
    @PostMapping("/research-runs/{id}/experiment-drafts")
    public ApiResponse<DraftCreationResult> drafts(@PathVariable UUID id,@RequestBody DraftCommand command,
                                                    @RequestHeader("Idempotency-Key") String key){return ok(service.createDrafts(id,command,key));}
    @GetMapping("/research-runs/{id}/experiment-links") public ApiResponse<List<DraftLink>> links(@PathVariable UUID id){return ok(service.links(id));}
    @GetMapping("/research-runs/{id}/feedback") public ApiResponse<JsonNode> feedback(@PathVariable UUID id){return ok(feedback.forRun(id));}
    private <T> ApiResponse<T> ok(T value){return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());}
}
