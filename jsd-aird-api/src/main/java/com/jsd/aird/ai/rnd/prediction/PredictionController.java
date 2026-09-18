package com.jsd.aird.ai.rnd.prediction;

import com.jsd.aird.ai.rnd.api.AiRndContracts.PredictionRequest;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.prediction.PredictionContracts.*;

@RestController
@RequestMapping("/api/v1/ai/rnd")
public class PredictionController {
    private final PredictionService service;
    public PredictionController(PredictionService service){this.service=service;}

    @GetMapping("/prediction-context")
    public ApiResponse<?> context(@RequestParam(required=false) List<UUID> targetId){return ok(service.context(targetId));}
    @GetMapping("/targets/{id}/quality-policies")
    public ApiResponse<?> qualityPolicies(@PathVariable UUID id){return ok(service.qualityPolicies(id));}
    @PostMapping("/targets/{id}/quality-policies")
    public ApiResponse<?> createQualityPolicy(@PathVariable UUID id,@RequestBody QualityPolicyCommand body,
                                               @RequestHeader("Idempotency-Key") String key){return ok(service.createQualityPolicy(id,body,key));}
    @PostMapping("/quality-policies/{id}/publish")
    public ApiResponse<?> publishQualityPolicy(@PathVariable UUID id,@RequestBody PublishPolicyCommand body,
                                                @RequestHeader("Idempotency-Key") String key){return ok(service.publishQualityPolicy(id,body,key));}
    @PostMapping("/predictions")
    public ResponseEntity<?> predict(@RequestBody PredictionRequest body,@RequestHeader("Idempotency-Key")String key){var result=service.predict(body,key);return ResponseEntity.status(result.status()).body(result.body());}
    @GetMapping("/prediction-records/{id}")
    public ResponseEntity<?> record(@PathVariable UUID id){var result=service.record(id);return ResponseEntity.status(result.status()).body(result.body());}
    private <T> ApiResponse<T> ok(T value){return ResponseFactory.success(value,RequestIdHolder.currentOrUnknown());}
}
