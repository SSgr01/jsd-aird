package com.jsd.aird.ai.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.ai.application.FileSearchService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public class FileSearchController {

    private final FileSearchService service;

    public FileSearchController(FileSearchService service) {
        this.service = service;
    }

    @PostMapping("/files")
    public ApiResponse<FileSearchService.FileSearchResponse> search(@Valid @RequestBody Request request) {
        var actor = ActorContext.required();
        return ResponseFactory.success(service.search(actor.organizationId(), new FileSearchService.SearchCommand(
                request.query(), request.safeLimit(), request.scopeIds(), request.knowledgeCategoryIds(),
                request.dataCategoryIds())), RequestIdHolder.currentOrUnknown());
    }

    public record Request(@NotBlank @Size(max = 1000) String query, int limit, List<UUID> scopeIds,
                          List<UUID> knowledgeCategoryIds, List<UUID> dataCategoryIds) {
        public int safeLimit() { return limit <= 0 ? 20 : Math.min(50, limit); }
    }
}
