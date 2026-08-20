package com.jsd.aird.ops.application;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.ConversationTitleGenerator;
import com.jsd.aird.ops.application.port.ConversationTitleTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Generates both AI Q&A and spectrum chat titles inside the existing worker. */
@Component
public class ConversationTitleJobHandler implements AsyncJobHandler {

    public static final String JOB_TYPE = "AI_GENERATE_CONVERSATION_TITLE";
    private static final Logger log = LoggerFactory.getLogger(ConversationTitleJobHandler.class);

    private final List<ConversationTitleTarget> targets;
    private final ConversationTitleGenerator generator;
    private final AuditLogFacade audit;
    private final ObjectMapper objectMapper;

    public ConversationTitleJobHandler(List<ConversationTitleTarget> targets,
                                       ConversationTitleGenerator generator,
                                       AuditLogFacade audit,
                                       ObjectMapper objectMapper) {
        this.targets = List.copyOf(targets);
        this.generator = generator;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return JOB_TYPE.equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        var organizationId = uuid(payload, "organizationId");
        var conversationId = uuid(payload, "conversationId");
        var targetType = payload.path("conversationType").asText("");
        var target = targets.stream().filter(item -> item.targetType().equals(targetType)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported conversation type: " + targetType));
        var record = target.find(organizationId, conversationId);
        if (record.isEmpty()) {
            return result("SKIPPED", targetType, conversationId, "会话已删除");
        }
        var current = record.get();
        if ("USER".equalsIgnoreCase(current.titleSource()) || !StringUtils.hasText(current.firstQuestion())) {
            appendAudit(current, "AI_CONVERSATION_TITLE_SKIPPED", objectMapper.createObjectNode()
                    .put("reason", "USER".equalsIgnoreCase(current.titleSource()) ? "USER_TITLE" : "EMPTY_QUESTION"));
            return result("SKIPPED", targetType, conversationId, "用户标题或问题为空");
        }

        appendAudit(current, "AI_CONVERSATION_TITLE_STARTED", objectMapper.createObjectNode()
                .put("promptVersion", "conversation-title-v1"));
        var generated = generator.generate(current.firstQuestion());
        var updated = target.updateAutomaticTitle(organizationId, conversationId, generated.title());
        var action = updated && "MODEL".equals(generated.outcome())
                ? "AI_CONVERSATION_TITLE_SUCCEEDED" : "AI_CONVERSATION_TITLE_FALLBACK";
        appendAudit(current, action, objectMapper.createObjectNode()
                .put("title", generated.title())
                .put("outcome", generated.outcome())
                .put("model", generated.model())
                .put("promptVersion", generated.promptVersion())
                .put("updated", updated)
                .put("fallbackReason", generated.fallbackReason() == null ? "" : generated.fallbackReason()));
        return result(generated.outcome(), targetType, conversationId, generated.title());
    }

    private JsonNode result(String outcome, String type, UUID conversationId, String detail) {
        return objectMapper.createObjectNode().put("outcome", outcome).put("conversationType", type)
                .put("conversationId", conversationId.toString()).put("detail", detail);
    }

    private UUID uuid(JsonNode payload, String field) {
        var value = payload.path(field).asText("");
        try {
            return UUID.fromString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("标题任务缺少有效的 " + field, exception);
        }
    }

    private void appendAudit(ConversationTitleTarget.Target target, String action, JsonNode detail) {
        try {
            audit.append(target.organizationId(), target.actorId(), action, "AI_CONVERSATION",
                    target.conversationId(), detail);
        } catch (Exception exception) {
            log.warn("Conversation title audit failed for {}", target.conversationId(), exception);
        }
    }
}
