package com.jsd.aird.ai.adapter.in.web;

import com.jsd.aird.ai.application.PrivateResearchSourceService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents/private-sources")
public class PrivateResearchSourceController {
    private final PrivateResearchSourceService sources;
    public PrivateResearchSourceController(PrivateResearchSourceService sources) { this.sources = sources; }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) throws Exception {
        try (var file = sources.open(id)) {
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.originalName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "private, no-store")
                .contentType(MediaType.parseMediaType(file.contentType())).body(file.stream().readNBytes(10 * 1024 * 1024));
        }
    }
}
