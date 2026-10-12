package com.jsd.aird.ai.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.jsd.aird.ai.application.port.AssistantRepository;
import com.fasterxml.jackson.databind.JsonNode;

final class AssistantConversationContext {

    private static final int MAX_MESSAGES = 10;
    private static final int MAX_MESSAGE_CHARS = 1_400;
    private static final int MAX_CONTEXT_CHARS = 7_000;

    private AssistantConversationContext() { }

    static String format(List<AssistantRepository.MessageRow> history) {
        if (history == null || history.isEmpty()) return "无历史对话";
        var relevant = history.stream().filter(AssistantConversationContext::isRelevant)
                .toList();
        var start = Math.max(0, relevant.size() - MAX_MESSAGES);
        var result = new StringBuilder();
        for (var index = start; index < relevant.size(); index++) {
            var item = relevant.get(index);
            var role = switch (item.role()) {
                case "USER" -> "用户";
                case "ASSISTANT" -> "助手";
                default -> "会话摘要";
            };
            appendBounded(result, role + "：" + truncate(item.content(), MAX_MESSAGE_CHARS));
            var citations = citationLabels(item.citations());
            if (!citations.isEmpty()) appendBounded(result,
                    "历史引用来源（仅供解析指代，回答前必须重新检索并校验当前授权）：" + String.join("；", citations));
        }
        return result.isEmpty() ? "无历史对话" : result.toString();
    }

    private static boolean isRelevant(AssistantRepository.MessageRow row) {
        return row != null && ("SUMMARY".equals(row.role()) || "USER".equals(row.role()) || "ASSISTANT".equals(row.role()));
    }

    private static List<String> citationLabels(JsonNode citations) {
        if (citations == null || !citations.isArray()) return List.of();
        var result = new LinkedHashSet<String>();
        for (var citation : citations) {
            var title = text(citation, "title");
            var originalName = text(citation, "originalName");
            var siteName = text(citation, "siteName");
            var sourceType = text(citation, "sourceType");
            var label = !title.isBlank() ? title : !originalName.isBlank() ? originalName : siteName;
            if (!label.isBlank()) result.add(label + (originalName.isBlank() || originalName.equals(label)
                    ? "" : "（" + originalName + "）") + (sourceType.isBlank() ? "" : "［" + sourceType + "］"));
            if (result.size() >= 5) break;
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) return "";
        return node.path(field).asText("").strip();
    }

    private static String truncate(String value, int limit) {
        if (value == null) return "";
        var normalized = value.strip();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }

    private static void appendBounded(StringBuilder builder, String value) {
        if (builder.length() >= MAX_CONTEXT_CHARS) return;
        if (!builder.isEmpty()) builder.append('\n');
        var remaining = MAX_CONTEXT_CHARS - builder.length();
        builder.append(value, 0, Math.min(value.length(), remaining));
    }
}
