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

    @GetMapping("/optimization-datasets") public ApiResponse<JsonNode> historyDatasets(@RequestParam(required=false) String keyword){return ok(service.historyDatasets(keyword));}
    @GetMapping("/optimization-datasets/{id}") public ApiResponse<JsonNode> historyDataset(@PathVariable UUID id){return ok(service.historyDataset(id));}
    @PostMapping("/experiment-optimizations")
    public ResponseEntity<?> submit(@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key){var result=service.submit(body,key);if(List.of("QUEUED","RUNNING").contains(result.executionStatus()))return ResponseEntity.status(202).body(result);return ResponseEntity.ok(service.get(result.runId()));}
    @GetMapping("/experiment-optimizations/{id}") public ApiResponse<OptimizationRun> run(@PathVariable UUID id){return ok(service.get(id));}
    @GetMapping("/research-runs/{id}/observations") public ApiResponse<JsonNode> observations(@PathVariable UUID id){return ok(feedback.observations(id));}
    @PutMapping("/research-runs/{id}/observations") public ApiResponse<JsonNode> saveObservations(@PathVariable UUID id,@RequestBody ObservationCommand command){return ok(feedback.saveObservations(id,command));}
    @PostMapping("/research-runs/{id}/next-round") public ResponseEntity<?> nextRound(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key){var accepted=service.continueHistorical(id,key);return ResponseEntity.accepted().body(accepted);}
    private <T> ApiResponse<T> ok(T value){return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());}
}
