package com.jsd.aird.tpl.adapter.in.web;

import java.io.IOException;
import java.util.UUID;

import com.jsd.aird.ops.application.FileObjectService;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.application.TemplateWorkspaceService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/template-versions")
public class TemplateSnapshotController {
    private final TemplateWorkspaceService templates;
    private final FileObjectService files;

    public TemplateSnapshotController(TemplateWorkspaceService templates, FileObjectService files) {
        this.templates = templates;
        this.files = files;
    }

    @GetMapping("/{versionId}/snapshot")
    public void download(@PathVariable UUID versionId, HttpServletResponse response) throws IOException {
        var workspace = templates.get(versionId);
        if (workspace.snapshotFileId() == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "模板快照不存在");
        }
        var file = files.download(workspace.snapshotFileId());
        response.setContentType(file.contentType());
        response.setContentLengthLong(file.size());
        try (var stored = file.storedObject()) {
            stored.stream().transferTo(response.getOutputStream());
        } catch (Exception exception) {
            throw new IOException("Unable to stream template snapshot", exception);
        }
    }
}
