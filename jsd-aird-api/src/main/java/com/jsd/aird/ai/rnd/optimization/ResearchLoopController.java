package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.rnd.api.ResearchExperimentFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/rnd/research-loop")
public class ResearchLoopController {
    private final ResearchLoopService loops;
    private final ResearchExperimentFacade experiments;
    private final ObjectMapper json;
    public ResearchLoopController(ResearchLoopService loops,ResearchExperimentFacade experiments,ObjectMapper json){this.loops=loops;this.experiments=experiments;this.json=json;}
    @GetMapping public ApiResponse<?> context(@RequestParam(required=false) UUID runId,@RequestParam(required=false) UUID projectId) {
        var args=json.createObjectNode();if(runId!=null)args.put("run_id",runId.toString());if(projectId!=null)args.put("project_id",projectId.toString());return ok(loops.context(args));
    }
    @PostMapping("/drafts") public ApiResponse<?> draft(@RequestBody ObjectNode args,@RequestHeader("Idempotency-Key") String key) {
        // Browser operations use the same durable identity and receipt as assistant operations.
        args.put("operation_key",com.jsd.aird.shared.business.BusinessViews.hash(key));return ok(loops.createDraft(args));
    }
    @GetMapping("/draft-receipts/{key}") public ApiResponse<?> receipt(@PathVariable String key){return ok(loops.draftReceipt(com.jsd.aird.shared.business.BusinessViews.hash(key)));}
    @PutMapping("/measurements") public ApiResponse<?> measurements(@RequestBody ObjectNode args,@RequestHeader("Idempotency-Key") String key){args.put("operation_key",com.jsd.aird.shared.business.BusinessViews.hash(key));return ok(experiments.saveMeasurements(args));}
    private ApiResponse<?> ok(Object value){return ResponseFactory.success(value,RequestIdHolder.currentOrUnknown());}
}
