package com.jsd.aird.ai.application;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.ai.application.port.AssistantRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Metadata projection for the HTTP facade; session memory and compaction belong to dsh. */
@Service
public class ConversationMemoryService {

    private final AssistantRepository repository;

    public ConversationMemoryService(AssistantRepository repository) {
        this.repository = repository;
    }

    public AssistantRepository.ConversationMeta get(UUID organizationId, UUID conversationId) {
        return repository.conversation(organizationId, conversationId);
    }

    public List<AssistantRepository.ConversationMeta> list(UUID organizationId, int limit) {
        return repository.listConversations(organizationId, limit);
    }

    public void rename(UUID organizationId, UUID conversationId, String title) {
        if (!StringUtils.hasText(title) || title.length() > 80) throw new IllegalArgumentException("标题不能为空且不超过 80 字符");
        repository.updateTitle(organizationId, conversationId, title.strip(), "USER");
    }

    public void delete(UUID organizationId, UUID conversationId) {
        repository.renameOrDelete(organizationId, conversationId, null, true);
    }
}
