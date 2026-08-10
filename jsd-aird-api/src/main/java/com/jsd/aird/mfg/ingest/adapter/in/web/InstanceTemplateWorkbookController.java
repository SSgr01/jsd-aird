package com.jsd.aird.mfg.ingest.adapter.in.web;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.jsd.aird.mfg.ingest.application.TemplateInstanceWorkbookService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/template-versions")
public class InstanceTemplateWorkbookController {

    private final TemplateInstanceWorkbookService service;

    public InstanceTemplateWorkbookController(TemplateInstanceWorkbookService service) {
        this.service = service;
    }

    @GetMapping("/{versionId}/instance-xlsx")
    public ResponseEntity<byte[]> download(@PathVariable UUID versionId) {
        var file = service.download(versionId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }
}
