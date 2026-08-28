package com.jsd.aird.kb.adapter.in.web;

import java.io.IOException;
import java.util.UUID;

import com.jsd.aird.kb.application.KnowledgeResultAssetService;
import com.jsd.aird.platform.web.HttpContentDisposition;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge/assets")
public class KnowledgeResultAssetController {

    private final KnowledgeResultAssetService service;

    public KnowledgeResultAssetController(KnowledgeResultAssetService service) {
        this.service = service;
    }

    @GetMapping("/{assetId}/content")
    public void content(@PathVariable UUID assetId, HttpServletResponse response) throws IOException {
        var actor = com.jsd.aird.shared.security.ActorContext.required();
        var file = service.open(actor.organizationId(), assetId);
        response.setContentType(file.contentType());
        response.setContentLengthLong(file.size());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                HttpContentDisposition.inline(file.originalName()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
        try (file) {
            file.stream().transferTo(response.getOutputStream());
        } catch (Exception exception) {
            throw new IOException("知识图片读取失败", exception);
        }
    }
}
