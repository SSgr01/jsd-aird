package com.jsd.aird.ops.adapter.in.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.jsd.aird.ops.application.FileObjectService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v2/files")
public class FileObjectController {

    private final FileObjectService service;

    public FileObjectController(FileObjectService service) {
        this.service = service;
    }

    @PostMapping("/staged")
    public ApiResponse<FileObjectService.StagedFile> stage(
            @RequestPart MultipartFile file,
            @RequestParam(defaultValue = "SNAPSHOT") String kind
    ) throws IOException {
        return ResponseFactory.success(
                service.stage(
                        file.getOriginalFilename(),
                        file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                        kind,
                        file.getInputStream()
                ),
                RequestIdHolder.currentOrUnknown()
        );
    }

    @GetMapping("/{fileId}/content")
    public void download(@PathVariable UUID fileId, HttpServletResponse response) throws IOException {
        var file = service.download(fileId);
        response.setContentType(file.contentType());
        response.setContentLengthLong(file.size());
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename(file.originalName(), StandardCharsets.UTF_8)
                        .build().toString()
        );
        try (var stored = file.storedObject()) {
            stored.stream().transferTo(response.getOutputStream());
        } catch (Exception exception) {
            throw new IOException("Unable to stream object", exception);
        }
    }
}
