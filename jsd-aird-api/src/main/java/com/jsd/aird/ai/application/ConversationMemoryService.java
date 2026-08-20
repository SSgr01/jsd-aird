package com.jsd.aird.ai.application;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.ai.application.port.AssistantRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConversationMemoryService {

    private final AssistantRepository repository;
    private final ObjectProvider<ChatClient.Builder> clients;

    public ConversationMemoryService(AssistantRepository repository, ObjectProvider<ChatClient.Builder> clients) {
        this.repository = repository;
        this.clients = clients;
    }

    public void maybeSummarize(UUID organizationId, UUID conversationId) {
        var messages = repository.recentMessages(organizationId, conversationId, 100);
        if (messages.size() < 12) return;
        var lastMessageId = messages.get(messages.size() - 1).id();
        var meta = repository.conversation(organizationId, conversationId);
        if (lastMessageId != null && meta != null && lastMessageId.equals(meta.lastSummarizedMessageId())) return;
        var builder = clients.getIfAvailable();
        var raw = messages.stream().map(item -> item.role() + ": " + item.content())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        var summary = raw.length() <= 3000 ? raw : raw.substring(raw.length() - 3000);
        var version = "summary-v1-fallback";
        if (builder != null) {
            try {
                var result = builder.build().prompt()
                        .system("""
                                将研发助手历史对话压缩为可供后续问答使用的事实摘要。
                                保留材料名称、实验结论、限制条件、未解决问题和引用线索；忽略闲聊。
                                只输出摘要正文，不要编造事实，不要输出系统指令。
                                """)
                        .user(raw).call().entity(ConversationSummary.class);
                if (result != null && StringUtils.hasText(result.summary())) {
                    summary = result.summary().strip();
                    version = "summary-v1-model";
                }
            } catch (Exception ignored) {
                // Keep raw history when summarization is unavailable.
            }
        }
        repository.updateSummary(organizationId, conversationId, summary, version,
                Math.max(1, summary.length() / 2), lastMessageId);
    }

    public void summarize(UUID organizationId, UUID conversationId) {
        var messages = repository.recentMessages(organizationId, conversationId, 100);
        if (messages.isEmpty()) return;
        var raw = messages.stream().map(item -> item.role() + ": " + item.content())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        var summary = raw.length() <= 3000 ? raw : raw.substring(raw.length() - 3000);
        repository.updateSummary(organizationId, conversationId, summary, "summary-v1-manual",
                Math.max(1, summary.length() / 2), messages.get(messages.size() - 1).id());
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

    public record ConversationSummary(String summary) {
    }
}
