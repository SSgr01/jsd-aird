package com.jsd.aird.shared.web;

import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ImportSourceDownload {
    private ImportSourceDownload() {}

    public static void write(FileStorageFacade files, UUID fileId, HttpServletResponse response) throws IOException {
        try (var file = files.open(ActorContext.required().organizationId(), fileId)) {
            response.setContentType(file.contentType());
            response.setContentLengthLong(file.size());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(file.originalName(), StandardCharsets.UTF_8).build().toString());
            file.stream().transferTo(response.getOutputStream());
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Unable to stream import source", exception);
        }
    }
}
