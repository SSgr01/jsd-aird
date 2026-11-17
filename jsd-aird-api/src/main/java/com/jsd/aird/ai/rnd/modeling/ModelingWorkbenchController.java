package com.jsd.aird.ai.rnd.modeling;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/rnd/modeling-workbench")
public class ModelingWorkbenchController {
    private final ModelingWorkbenchService service;
    private final WorkbenchModelRegistrationService registration;
    private final ModelingSelectionService selection;

    public ModelingWorkbenchController(ModelingWorkbenchService service, WorkbenchModelRegistrationService registration,ModelingSelectionService selection) { this.service = service; this.registration = registration;this.selection=selection; }

    @GetMapping("/datasets")
    public ApiResponse<?> datasets(@RequestParam(required = false) String keyword) {
        return ok(service.datasets(keyword));
    }

    @GetMapping("/datasets/{id}")
    public ApiResponse<?> dataset(@PathVariable UUID id) { return ok(service.dataset(id)); }

    @GetMapping("/datasets/{id}/batches")
    public ApiResponse<?> batches(@PathVariable UUID id) {return ok(selection.batches(id));}

    @GetMapping("/datasets/{id}/experiments")
    public ApiResponse<?> experiments(@PathVariable UUID id,@RequestParam(required=false) String batch,
        @RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ok(selection.experiments(id,batch,keyword,page,size));
    }

    public record Selection(java.util.List<UUID> experimentIds){}
    @PostMapping("/datasets/{id}/selection")
    public ApiResponse<?> selected(@PathVariable UUID id,@RequestBody Selection command) {return ok(service.selectedDataset(id,command.experimentIds()));}

    @PostMapping("/runs")
    public ApiResponse<?> train(@RequestBody ModelingWorkbenchService.TrainCommand command,
                                @RequestHeader("Idempotency-Key") String key) {
        return ok(service.train(command, key));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<?> run(@PathVariable UUID id) { var run=(com.fasterxml.jackson.databind.node.ObjectNode)service.run(id);
        run.set("registration",registration.registration(com.jsd.aird.shared.security.ActorContext.required().organizationId(),id));
        return ok(run); }

    @PostMapping("/runs/{id}/register")
    public ApiResponse<?> register(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        service.run(id);
        return ok(registration.register(id,key));
    }

    private ApiResponse<?> ok(Object value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }
}
