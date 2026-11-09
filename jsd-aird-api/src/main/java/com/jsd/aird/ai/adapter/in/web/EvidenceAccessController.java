package com.jsd.aird.ai.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.application.AgentSourceAuthorization;
import com.jsd.aird.ai.application.LiteratureResearchService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.*;

/** Shared source-view gate. No client principal, cached ACL or new retrieval is accepted. */
@RestController
@RequestMapping("/api/v1/assistant/sources")
public class EvidenceAccessController {
    private final AgentSourceAuthorization sources;
    private final LiteratureResearchService research;
    public EvidenceAccessController(AgentSourceAuthorization sources, LiteratureResearchService research) {
        this.sources = sources; this.research = research;
    }
    @PostMapping("/authorize")
    public Object authorize(@RequestBody JsonNode request) {
        research.requireUse();
        sources.authorize(request.path("sources"));
        for (var source : request.path("sources")) {
            if (java.util.Set.of("KB_PUBLICATION", "RUN_UPLOAD", "EXTERNAL_FULLTEXT").contains(source.path("kind").asText()))
                sources.validate(source, "");
        }
        return ResponseFactory.success(java.util.Map.of("allowed", true), RequestIdHolder.currentOrUnknown());
    }
}
