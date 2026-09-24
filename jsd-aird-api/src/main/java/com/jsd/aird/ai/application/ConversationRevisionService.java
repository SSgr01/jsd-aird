package com.jsd.aird.ai.application;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.port.AssistantRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationRevisionService {

    private final AssistantRepository repository;
    private final AuditLogFacade audit;
    private final ObjectMapper objectMapper;

    public ConversationRevisionService(AssistantRepository repository, AuditLogFacade audit,
                                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AssistantRepository.ConversationRewriteResult replaceQuestion(
            Actor actor, UUID conversationId, UUID targetMessageId, UUID expectedLastMessageId,
            String replacementQuestion) {
        var result = repository.replaceQuestion(actor.organizationId(), conversationId, targetMessageId,
                expectedLastMessageId, replacementQuestion.strip());
        switch (result.status()) {
            case CONVERSATION_NOT_FOUND, MESSAGE_NOT_FOUND -> throw new ApiException(
                    ApiErrorCode.NOT_FOUND, "待编辑的问题不存在或不属于当前会话");
            case TARGET_NOT_USER -> throw new ApiException(
                    ApiErrorCode.BAD_REQUEST, "只能编辑用户发送的问题");
            case STALE_CONVERSATION -> throw new ApiException(
                    ApiErrorCode.VERSION_CONFLICT, "会话已有新消息，请刷新后重新编辑");
            case UPDATED -> {
                var detail = objectMapper.createObjectNode()
                        .put("targetMessageId", targetMessageId.toString())
                        .put("replacementMessageId", result.replacementMessageId().toString())
                        .put("deletedMessageCount", result.deletedMessageCount())
                        .put("firstQuestionReplaced", result.firstQuestionReplaced())
                        .put("automaticTitleReset", result.automaticTitleReset());
                audit.append(actor.organizationId(), actor.userId(), "AI_CONVERSATION_HISTORY_REWRITTEN",
                        "AI_CONVERSATION", conversationId, detail);
            }
        }
        return result;
    }
}
