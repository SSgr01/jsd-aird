package com.jsd.aird.ai.adapter.in.web;

import com.jsd.aird.ai.application.ExpertCatalogService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agents/experts")
public class ExpertController {
    private final ExpertCatalogService experts;
    public ExpertController(ExpertCatalogService experts) { this.experts = experts; }

    @GetMapping
    public Object list() {
        return ResponseFactory.success(experts.list(), RequestIdHolder.currentOrUnknown());
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable String id) {
        return ResponseFactory.success(experts.resolve(id, false), RequestIdHolder.currentOrUnknown());
    }
}
