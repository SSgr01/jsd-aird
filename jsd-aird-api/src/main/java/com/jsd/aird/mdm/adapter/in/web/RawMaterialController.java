package com.jsd.aird.mdm.adapter.in.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mdm.application.service.RawMaterialService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/raw-materials")
public class RawMaterialController {
    private final RawMaterialService service;
    public RawMaterialController(RawMaterialService service){this.service=service;}
    @GetMapping public ApiResponse<?> list(@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="30") int limit){return ok(service.list(query,offset,limit));}
    @GetMapping("/{id}") public ApiResponse<?> profile(@PathVariable UUID id,@RequestParam(required=false) UUID versionId){return ok(service.profile(id,versionId));}
    @PutMapping("/{id}") public ApiResponse<?> save(@PathVariable UUID id,@RequestBody ObjectNode body,@RequestHeader("Idempotency-Key") String key){return ok(service.save(id,body,key));}
    @GetMapping("/sources/{id}") public ApiResponse<?> source(@PathVariable UUID id){return ok(service.source(id));}
    @PostMapping("/sources") public ApiResponse<?> source(@RequestBody ObjectNode body){return ok(service.saveSource(body));}
    private ApiResponse<?> ok(Object value){return ResponseFactory.success(value,RequestIdHolder.currentOrUnknown());}
}
