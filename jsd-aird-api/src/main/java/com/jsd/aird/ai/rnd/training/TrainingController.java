package com.jsd.aird.ai.rnd.training;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.jsd.aird.ai.rnd.training.TrainingContracts.*;

@RestController
@RequestMapping("/api/v1/ai/rnd")
public class TrainingController {
    private final TrainingService service;
    public TrainingController(TrainingService service){this.service=service;}
    @GetMapping("/training-settings") public ApiResponse<?> settings(){return ok(service.settings());}
    @PutMapping("/training-settings") public ApiResponse<?> updateSettings(@RequestBody UpdateTrainingSettings body,@RequestHeader("Idempotency-Key") String key){return ok(service.updateSettings(body,key));}
    @PostMapping("/training-jobs/evaluate") public ApiResponse<?> evaluate(@RequestHeader("Idempotency-Key") String key){return ok(service.evaluateAll(key));}
    @GetMapping("/training-jobs") public ApiResponse<?> jobs(@RequestParam(required=false)String status,@RequestParam(required=false)String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.jobs(status,keyword,page,size));}
    @GetMapping("/training-jobs/{id}") public ApiResponse<?> job(@PathVariable UUID id){return ok(service.job(id));}
    @PostMapping("/training-jobs/{id}/retry") public ApiResponse<?> retry(@PathVariable UUID id,@RequestBody RetryCommand body,@RequestHeader("Idempotency-Key") String key){return ok(service.retry(id,body,key));}
    @PostMapping("/training-jobs/{id}/cancel") public ApiResponse<?> cancel(@PathVariable UUID id,@RequestBody CancelCommand body,@RequestHeader("Idempotency-Key") String key){return ok(service.cancel(id,body,key));}
    @GetMapping("/snapshots/{id}") public ApiResponse<?> snapshot(@PathVariable UUID id){return ok(service.snapshot(id));}
    @GetMapping("/snapshots/{id}/items") public ApiResponse<?> snapshotItems(@PathVariable UUID id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.snapshotItems(id,page,size));}
    @GetMapping("/models") public ApiResponse<?> models(@RequestParam(required=false)String status,@RequestParam(required=false)String category,@RequestParam(required=false)String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.models(status,category,keyword,page,size));}
    @GetMapping("/models/{id}") public ApiResponse<?> model(@PathVariable UUID id){return ok(service.model(id));}
    @GetMapping("/models/{id}/comparison") public ApiResponse<?> comparison(@PathVariable UUID id){return ok(service.comparison(id));}
    @PostMapping("/models/{id}/activate") public ApiResponse<?> activate(@PathVariable UUID id,@RequestBody ModelActionCommand body,@RequestHeader("Idempotency-Key") String key){return ok(service.activate(id,body,key));}
    @PostMapping("/models/{id}/pause") public ApiResponse<?> pause(@PathVariable UUID id,@RequestBody ModelActionCommand body,@RequestHeader("Idempotency-Key") String key){return ok(service.pause(id,body,key));}
    @PostMapping("/models/{id}/rollback") public ApiResponse<?> rollback(@PathVariable UUID id,@RequestBody ModelActionCommand body,@RequestHeader("Idempotency-Key") String key){return ok(service.rollback(id,body,key));}
    private static <T> ApiResponse<T> ok(T value){return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());}
}
