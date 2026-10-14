package com.jsd.aird.ai.rnd.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.research.FormulaDesignContracts.*;

@RestController
@RequestMapping("/api/v1/ai/rnd")
public class FormulaDesignController {
    private final FormulaDesignService service;
    public FormulaDesignController(FormulaDesignService service){this.service=service;}
    @GetMapping("/formula-design-context") public ApiResponse<JsonNode> context(@RequestParam(required=false) List<UUID> targetId){return ok(service.context(targetId));}
    @PostMapping("/formula-designs") public ResponseEntity<?> submit(@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key){
        var accepted=service.submit(body,key);
        if (accepted.executionStatus()==ExecutionStatus.QUEUED || accepted.executionStatus()==ExecutionStatus.RUNNING) return ResponseEntity.status(202).body(accepted);
        return ResponseEntity.ok(service.get(accepted.runId()));
    }
    @GetMapping("/research-runs") public ApiResponse<ResearchPage> page(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String status){return ok(service.page(page,size,status));}
    @GetMapping("/research-runs/{id}") public ApiResponse<ResearchRun> run(@PathVariable UUID id){return ok(service.get(id));}
    private <T> ApiResponse<T> ok(T value){return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());}
}
