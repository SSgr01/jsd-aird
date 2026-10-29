package com.jsd.aird.ops.adapter.in.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.jsd.aird.ops.application.FileObjectService;
import com.jsd.aird.ops.application.port.TemplateOfficeNormalizationFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.platform.web.TiffPreview;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final long MAX_DOC_PREVIEW_BYTES = 50L * 1024 * 1024;

    private final FileObjectService service;
    private final TemplateOfficeNormalizationFacade officeNormalization;

    @Autowired
    public FileObjectController(FileObjectService service, TemplateOfficeNormalizationFacade officeNormalization) {
        this.service = service;
        this.officeNormalization = officeNormalization;
    }

    /** Kept for controller tests that exercise upload handling only. */
    public FileObjectController(FileObjectService service) {
        this(service, null);
    }

    @PostMapping("/staged")
    public ApiResponse<FileObjectService.StagedFile> stage(
            @RequestPart MultipartFile file,
            @RequestParam(defaultValue = "SNAPSHOT") String kind
    ) {
        var input = openUpload(file);
        return ResponseFactory.success(
                service.stage(
                        file.getOriginalFilename(),
                        file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                        kind,
                        input
                ),
                RequestIdHolder.currentOrUnknown()
        );
    }

    private java.io.InputStream openUpload(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "上传文件无法读取，可能已被安全软件隔离，请检查文件安全状态后重试");
        }
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
        if (isLegacyWord(file.originalName())) {
            if (file.size() > MAX_DOC_PREVIEW_BYTES) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST,
                        "DOC 文件超过 50 MB，暂不支持在线预览，请下载原文件查看");
            }
            if (officeNormalization == null) {
                throw new IOException("Legacy Word preview conversion is unavailable");
            }
            byte[] source;
            try (var stored = file.storedObject()) {
                source = com.jsd.aird.shared.resource.BoundedStreams.readBytes(stored.stream(),MAX_DOC_PREVIEW_BYTES);
            } catch (Exception exception) {
                throw new IOException("Unable to read legacy Word preview source", exception);
            }
            var normalized = officeNormalization.normalizeOfficeFileForPreview(
                    file.originalName(), file.contentType(), source);
            response.setContentType(normalized.normalizedContentType());
            response.setContentLengthLong(normalized.content().length);
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.inline()
                            .filename(normalized.normalizedName(), StandardCharsets.UTF_8)
                            .build().toString()
            );
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.getOutputStream().write(normalized.content());
            return;
        }
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

    private boolean isLegacyWord(String fileName) {
        return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".doc");
    }
}
