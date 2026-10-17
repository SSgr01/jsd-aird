package com.jsd.aird.ai.rnd.catalog;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.jsd.aird.ai.rnd.catalog.CatalogContracts.ImportCommand;

@RestController
@RequestMapping("/api/v1/ai/rnd/test-metric-catalog")
public class TestMetricCatalogController {
    private final TestSemanticsCatalogService service;

    public TestMetricCatalogController(TestSemanticsCatalogService service) {
        this.service = service;
    }

    @GetMapping("/imports")
    public ApiResponse<?> imports(@RequestParam(defaultValue = "10") int limit) {
        return ok(service.latestImports(limit));
    }

    @PostMapping("/imports")
    public ApiResponse<?> importFile(@RequestBody ImportCommand command) {
        return ok(service.importFile(command == null ? null : command.fileId()));
    }

    @GetMapping("/imports/{id}/indicators")
    public ApiResponse<?> indicators(@PathVariable UUID id,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "100") int size) {
        return ok(service.proposals(id, keyword, null, page, size));
    }

    private ApiResponse<?> ok(Object value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }
}
