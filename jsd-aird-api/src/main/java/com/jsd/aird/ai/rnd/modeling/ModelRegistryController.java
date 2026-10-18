package com.jsd.aird.ai.rnd.modeling;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.jsd.aird.ai.rnd.modeling.ModelRegistryContracts.*;

@RestController
@RequestMapping("/api/v1/ai/rnd")
public class ModelRegistryController {
    private final ModelRegistryService service;
    public ModelRegistryController(ModelRegistryService service){this.service=service;}
    @GetMapping("/models") public ApiResponse<?> models(@RequestParam(required=false)String status,@RequestParam(required=false)String category,@RequestParam(required=false)String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.models(status,category,keyword,page,size));}
    @GetMapping("/models/{id}") public ApiResponse<?> model(@PathVariable UUID id){return ok(service.model(id));}
    @PostMapping("/models/{id}/pause") public ApiResponse<?> pause(@PathVariable UUID id,@RequestBody ModelActionCommand body,@RequestHeader("Idempotency-Key") String key){return ok(service.pause(id,body,key));}
    @PostMapping("/models/{id}/rollback") public ApiResponse<?> rollback(@PathVariable UUID id,@RequestBody ModelActionCommand body,@RequestHeader("Idempotency-Key") String key){return ok(service.rollback(id,body,key));}
    private static <T> ApiResponse<T> ok(T value){return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());}
}
