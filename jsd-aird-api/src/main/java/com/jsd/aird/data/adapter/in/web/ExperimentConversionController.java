package com.jsd.aird.data.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.data.application.ExperimentConversionService;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/data/experiment-conversions")
public class ExperimentConversionController {
    private final ExperimentConversionService service;
    public ExperimentConversionController(ExperimentConversionService service){this.service=service;}
    public record Create(@NotNull UUID batchId,@NotNull UUID sourceFileId,@NotNull UUID templateVersionId,@NotNull UUID categoryId){}
    @PostMapping public ApiResponse<JsonNode> create(@Valid @RequestBody Create request){
        return success(service.create(request.batchId(),request.sourceFileId(),request.templateVersionId(),request.categoryId()));
    }
    @GetMapping public ApiResponse<?> list(@RequestParam(required=false) UUID batchId){return success(service.list(batchId));}
    @GetMapping("/for-import/{id}") public ApiResponse<?> forImport(@PathVariable UUID id){return success(service.forImport(id));}
    @GetMapping("/{id}/source") public void source(@PathVariable UUID id,HttpServletResponse response) throws Exception {
        try(var file=service.original(id)){
            response.setContentType(file.contentType());response.setContentLengthLong(file.size());
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(file.originalName(),StandardCharsets.UTF_8).build().toString());
            file.stream().transferTo(response.getOutputStream());
        }
    }
    private <T> ApiResponse<T> success(T value){return ResponseFactory.success(value,com.jsd.aird.platform.web.RequestIdHolder.currentOrUnknown());}
}
