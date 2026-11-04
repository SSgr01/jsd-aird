package com.jsd.aird.kb.adapter.in.web;

import com.jsd.aird.kb.application.BibliographyService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge")
public class BibliographyController {
    private final BibliographyService service;
    public BibliographyController(BibliographyService service) {this.service=service;}
    @GetMapping("/documents/{doc}/versions/{version}/bibliography")
    public ApiResponse<?> get(@PathVariable UUID doc,@PathVariable UUID version,@RequestParam(required=false) Integer revision) {
        return ok(service.get(doc,version,revision));
    }
    @PutMapping("/documents/{doc}/versions/{version}/bibliography")
    public ApiResponse<?> confirm(@PathVariable UUID doc,@PathVariable UUID version,@RequestBody BibliographyService.Confirmation command) {
        return ok(service.confirm(doc,version,command));
    }
    @GetMapping("/documents/{doc}/versions/{version}/vector-status")
    public ApiResponse<?> vectors(@PathVariable UUID doc,@PathVariable UUID version) {return ok(service.vectorStatus(doc,version));}
    @PostMapping("/bibliography-tasks/preview")
    public ApiResponse<?> preview(@RequestBody BibliographyService.Selection command) {return ok(service.preview(command));}
    @PostMapping("/bibliography-tasks")
    public ApiResponse<?> create(@RequestBody BibliographyService.Selection command) {return ok(java.util.Map.of("id",service.create(command)));}
    @GetMapping("/bibliography-tasks")
    public ApiResponse<?> tasks() {return ok(service.tasks());}
    @GetMapping("/bibliography-tasks/{id}")
    public ApiResponse<?> results(@PathVariable UUID id,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="100") int limit) {
        return ok(service.results(id,cursor,limit));
    }
    @PostMapping("/bibliography-tasks/{id}/items/{item}/retry")
    public ApiResponse<?> retry(@PathVariable UUID id,@PathVariable UUID item) {service.retry(id,item);return ok(null);}
    private <T> ApiResponse<T> ok(T data) {return ResponseFactory.success(data,RequestIdHolder.currentOrUnknown());}
}
