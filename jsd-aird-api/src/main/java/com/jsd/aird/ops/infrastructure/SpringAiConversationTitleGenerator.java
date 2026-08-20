package com.jsd.aird.ops.infrastructure;

import com.jsd.aird.ops.application.port.ConversationTitleGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Small, deterministic title prompt with a first-question fallback. */
@Component
public class SpringAiConversationTitleGenerator implements ConversationTitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConversationTitleGenerator.class);
    private final ObjectProvider<ChatClient.Builder> clients;
    private final boolean enabled;
    private final int maxLength;
    private final String model;
    private final String promptVersion;

    public SpringAiConversationTitleGenerator(
            ObjectProvider<ChatClient.Builder> clients,
            @Value("${app.ai.conversation-title.enabled:true}") boolean enabled,
            @Value("${app.ai.conversation-title.max-length:30}") int maxLength,
            @Value("${app.ai.conversation-title.prompt-version:conversation-title-v1}") String promptVersion,
            @Value("${app.model.model:}") String model) {
        this.clients = clients;
        this.enabled = enabled;
        this.maxLength = Math.min(80, Math.max(8, maxLength));
        this.model = model == null ? "" : model;
        this.promptVersion = promptVersion;
    }

    @Override
    public GeneratedTitle generate(String question) {
        var fallback = fallback(question);
        if (!enabled) return new GeneratedTitle(fallback, "FALLBACK", model, promptVersion, "TITLE_GENERATION_DISABLED");
        var builder = clients.getIfAvailable();
        if (builder == null) return new GeneratedTitle(fallback, "FALLBACK", model, promptVersion, "MODEL_NOT_CONFIGURED");
        try {
            var content = builder.build().prompt()
                    .system("""
                            你是研发工作台的会话标题生成器。
                            仅根据用户首个问题生成一个简短中文标题，保留材料、测试类型或分析动作等核心关键词。
                            只输出标题正文，不要引号、编号、前缀、解释、Markdown 或换行；不要补充问题中没有的信息。
                            标题不超过 30 个汉字。
                            """)
                    .user(question.strip())
                    .call()
                    .content();
            var title = sanitize(content);
            if (StringUtils.hasText(title)) return new GeneratedTitle(title, "MODEL", model, promptVersion, null);
            return new GeneratedTitle(fallback, "FALLBACK", model, promptVersion, "EMPTY_MODEL_OUTPUT");
        } catch (Exception exception) {
            log.warn("Conversation title model call failed, using first-question fallback", exception);
            return new GeneratedTitle(fallback, "FALLBACK", model, promptVersion, exception.getClass().getSimpleName());
        }
    }

    private String fallback(String question) {
        var value = question == null ? "新对话" : question.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (!StringUtils.hasText(value)) return "新对话";
        return value.substring(0, Math.min(maxLength, value.length())).strip();
    }

    private String sanitize(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        var value = raw.replaceAll("```[a-zA-Z]*", "")
                .replaceAll("[\\r\\n\\t\\\"'“”‘’]+", " ")
                .replaceFirst("^(标题|title)\\s*[:：-]\\s*", "")
                .strip();
        if (value.length() > maxLength) value = value.substring(0, maxLength).strip();
        return value;
    }
}
