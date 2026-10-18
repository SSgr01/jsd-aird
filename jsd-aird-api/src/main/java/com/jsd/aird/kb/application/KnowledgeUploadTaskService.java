package com.jsd.aird.kb.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.application.port.KnowledgeUploadTaskRepository;
import com.jsd.aird.kb.application.port.KnowledgeUploadTaskRepository.UploadTaskRow;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeUploadTaskService {

    private static final Set<String> CLIENT_STATUSES = Set.of(
            "STAGING", "CHECKING", "SUBMITTING", "QUEUED", "DUPLICATE", "FAILED");

    private final KnowledgeUploadTaskRepository tasks;
    private final KnowledgeRepository knowledge;

    public KnowledgeUploadTaskService(KnowledgeUploadTaskRepository tasks, KnowledgeRepository knowledge) {
        this.tasks = tasks;
        this.knowledge = knowledge;
    }

    @Transactional
    public UploadTaskView create(CreateCommand command) {
        var actor = ActorContext.required();
        var fileName = command.fileName() == null ? "" : command.fileName().trim();
        if (!StringUtils.hasText(fileName) || fileName.length() > 500 || command.size() < 0) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件名或文件大小无效");
        }
        return view(tasks.create(UUID.randomUUID(), actor.organizationId(), actor.userId(), fileName, command.size(),
                command.uploadContext() == null ? JsonNodeFactory.instance.objectNode() : command.uploadContext()));
    }

    @Transactional
    public List<UploadTaskView> list(int requestedLimit) {
        var actor = ActorContext.required();
        var limit = Math.min(100, Math.max(1, requestedLimit));
        return tasks.list(actor.organizationId(), actor.userId(), limit).stream().map(this::view).toList();
    }

    @Transactional
    public UploadTaskView update(UUID id, UpdateCommand command) {
        var actor = ActorContext.required();
        if (command.status() == null || !CLIENT_STATUSES.contains(command.status())
                || command.progress() < 0 || command.progress() > 100) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "上传任务状态或进度无效");
        }
        if (command.message() != null && command.message().length() > 2000) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "上传任务提示过长");
        }
        if (command.uploadContext() != null && command.uploadContext().toString().length() > 16000) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "上传配置过长");
        }
        if ("QUEUED".equals(command.status())
                && (command.documentId() == null || command.documentVersionId() == null)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "解析任务必须关联知识文档版本");
        }
        if (command.documentVersionId() != null && command.documentId() == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "知识文件版本必须关联知识文档");
        }
        if (command.documentId() != null) {
            var document = knowledge.findDocument(actor.organizationId(), command.documentId())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
            if (command.documentVersionId() != null && knowledge.listVersions(actor.organizationId(), document.id()).stream()
                    .noneMatch(version -> version.id().equals(command.documentVersionId()))) {
                throw new ApiException(ApiErrorCode.NOT_FOUND, "知识文件版本不存在");
            }
        }
        var row = tasks.update(actor.organizationId(), actor.userId(), id, command.status(), command.progress(),
                        command.sourceFileId(), command.uploadContext(), command.documentId(),
                        command.documentVersionId(), command.message())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "上传记录不存在"));
        return view(row);
    }

    @Transactional
    public void delete(UUID id) {
        var actor = ActorContext.required();
        if (!tasks.delete(actor.organizationId(), actor.userId(), id)) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "上传记录不存在");
        }
    }

    private UploadTaskView view(UploadTaskRow row) {
        return new UploadTaskView(row.id(), row.fileName(), row.size(), row.status(), row.progress(),
                row.sourceFileId(), row.uploadContext(), row.documentId(), row.documentVersionId(),
                row.message(), row.createdAt(), row.updatedAt());
    }

    public record CreateCommand(String fileName, long size, JsonNode uploadContext) { }
    public record UpdateCommand(String status, int progress, UUID sourceFileId, JsonNode uploadContext,
                                UUID documentId, UUID documentVersionId, String message) { }
    public record UploadTaskView(UUID id, String fileName, long size, String status, int progress,
                                 UUID sourceFileId, JsonNode uploadContext, UUID documentId,
                                 UUID documentVersionId, String message,
                                 java.time.Instant createdAt, java.time.Instant updatedAt) { }
}
