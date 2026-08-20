package com.jsd.aird.tpl.adapter.in.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.tpl.application.TemplateFileNormalizationService;
import com.jsd.aird.tpl.application.TemplateFileNormalizationService.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/template-files")
public class TemplateFileController {

    private final FileStorageFacade storage;
    private final TemplateFileNormalizationService normalizer;

    public TemplateFileController(FileStorageFacade storage, TemplateFileNormalizationService normalizer) {
        this.storage = storage;
        this.normalizer = normalizer;
    }

    @PostMapping("/staged")
    public ApiResponse<NormalizedFile> stage(@RequestPart MultipartFile file) throws IOException {
        byte[] source = file.getBytes();
        var original = storage.stageFile(file.getOriginalFilename(), file.getContentType(), "TEMPLATE_SOURCE",
                new ByteArrayInputStream(source));
        // Keep the original object staged before conversion. A normalization
        // failure must not create an import job, while the original remains
        // available for diagnostics and retry after the staged-file cleanup job.
        Result result = normalizer.normalize(file.getOriginalFilename(), file.getContentType(), source);
        var normalized = result.normalizedFormat().equals(result.originalFormat())
                ? original
                : storage.stageFile(result.normalizedName(), result.normalizedContentType(), "TEMPLATE_NORMALIZED",
                new ByteArrayInputStream(result.normalizedBytes()));
        return ResponseFactory.success(new NormalizedFile(
                original.fileId(), normalized.fileId(), result.originalName(), result.normalizedName(),
                result.originalFormat(), result.normalizedFormat(), result.normalizationStatus(), result.normalizationMessage(),
                original.sha256(), normalized.sha256()
        ), RequestIdHolder.currentOrUnknown());
    }

    public record NormalizedFile(
            java.util.UUID originalFileId,
            java.util.UUID normalizedFileId,
            String originalName,
            String normalizedName,
            String originalFormat,
            String normalizedFormat,
            String normalizationStatus,
            String normalizationMessage,
            String originalSha256,
            String normalizedSha256
    ) { }
}
