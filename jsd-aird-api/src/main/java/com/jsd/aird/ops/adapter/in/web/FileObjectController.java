package com.jsd.aird.ops.adapter.in.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.jsd.aird.ops.application.FileObjectService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.platform.web.TiffPreview;
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
@RequestMapping("/api/v1/files")
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

    /** Serve a browser-friendly representation while keeping /content as the original download. */
    @GetMapping("/{fileId}/preview")
    public void preview(@PathVariable UUID fileId, HttpServletResponse response) throws IOException {
        var file = service.download(fileId);
        if (!TiffPreview.isTiff(file.originalName(), file.contentType())) {
            response.setContentType(file.contentType());
            response.setContentLengthLong(file.size());
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.inline()
                            .filename(file.originalName(), StandardCharsets.UTF_8)
                            .build().toString()
            );
            try (var stored = file.storedObject()) {
                stored.stream().transferTo(response.getOutputStream());
            } catch (Exception exception) {
                throw new IOException("Unable to stream preview object", exception);
            }
            return;
        }

        byte[] png;
        try (var stored = file.storedObject()) {
            png = TiffPreview.toPng(stored.stream());
        } catch (Exception exception) {
            throw new IOException("Unable to convert TIFF preview", exception);
        }
        response.setContentType("image/png");
        response.setContentLengthLong(png.length);
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline()
                        .filename(TiffPreview.previewName(file.originalName()), StandardCharsets.UTF_8)
                        .build().toString()
        );
        response.getOutputStream().write(png);
    }
}
