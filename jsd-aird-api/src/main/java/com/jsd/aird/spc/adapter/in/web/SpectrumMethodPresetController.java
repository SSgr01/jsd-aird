package com.jsd.aird.spc.adapter.in.web;

import com.jsd.aird.spc.application.SpectrumMethodPresetService;
import com.jsd.aird.shared.api.*;
import com.jsd.aird.platform.web.RequestIdHolder;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/spc/method-presets")
public class SpectrumMethodPresetController {
    private final SpectrumMethodPresetService service;
    public SpectrumMethodPresetController(SpectrumMethodPresetService service) { this.service = service; }
    @GetMapping public ApiResponse<SpectrumMethodPresetService.Catalog> list() { return success(service.list()); }
    @PostMapping public ApiResponse<SpectrumMethodPresetService.Preset> create(@RequestBody SpectrumMethodPresetService.Command command) { return success(service.create(command)); }
    @PutMapping("/{id}") public ApiResponse<SpectrumMethodPresetService.Preset> update(@PathVariable UUID id, @RequestBody SpectrumMethodPresetService.Command command) { return success(service.update(id, command)); }
    private <T> ApiResponse<T> success(T value) { return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown()); }
}
