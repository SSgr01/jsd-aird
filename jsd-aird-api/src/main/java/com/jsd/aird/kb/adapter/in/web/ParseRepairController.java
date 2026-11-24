package com.jsd.aird.kb.adapter.in.web;

import com.jsd.aird.kb.application.ParseRepairService;
import com.jsd.aird.shared.api.*;
import com.jsd.aird.platform.web.RequestIdHolder;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge/parse-repairs")
public class ParseRepairController {
    private final ParseRepairService service;
    public ParseRepairController(ParseRepairService service) { this.service=service; }
    @PostMapping("/preview") public ApiResponse<?> preview(@RequestBody ParseRepairService.Selection selection) { return success(service.preview(selection)); }
    @PostMapping public ApiResponse<?> create(@RequestBody ParseRepairService.Selection selection) { return success(service.submit(selection)); }
    @GetMapping("/{id}") public ApiResponse<?> get(@PathVariable UUID id) { return success(service.get(id)); }
    @PostMapping("/{id}/adopt") public ApiResponse<?> adopt(@PathVariable UUID id) { return success(service.adopt(id)); }
    private <T> ApiResponse<T> success(T value) { return ResponseFactory.success(value,RequestIdHolder.currentOrUnknown()); }
}
