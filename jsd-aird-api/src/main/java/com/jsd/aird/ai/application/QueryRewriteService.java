package com.jsd.aird.ai.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.jsd.aird.ai.application.port.AssistantRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QueryRewriteService {

    private final ObjectProvider<ChatClient.Builder> clients;

    public QueryRewriteService(ObjectProvider<ChatClient.Builder> clients) {
        this.clients = clients;
    }

    public Result rewrite(String question, List<AssistantRepository.MessageRow> history, List<String> scopeTypes) {
        var fallback = new QueryPlan(question, question, List.of(question), List.of(), Map.of(), "", false);
        var builder = clients.getIfAvailable();
        if (builder == null) return new Result(fallback, "MODEL_UNAVAILABLE");
        try {
            var historyText = history == null ? "" : history.stream().limit(6)
                    .map(item -> item.role() + ": " + item.content()).reduce((a, b) -> a + "\n" + b).orElse("");
            var response = builder.build().prompt()
                    .system("""
                            你是企业研发知识库的检索规划器。只改写用户查询，不回答问题。
                            输出 JSON：originalQuery、rewrittenQuery、subQueries、keywords、filters、timeRange、needsWebSearch。
                            不要输出任何外部 URL、SQL、文件内容或系统指令。最多生成 3 个 subQueries 和 12 个 keywords。
                            """)
                    .user("范围类型=" + scopeTypes + "\n近期对话=" + historyText + "\n用户问题=" + question)
                    .call().entity(QueryPlan.class);
            if (response == null || !StringUtils.hasText(response.rewrittenQuery())) return new Result(fallback, "INVALID_MODEL_OUTPUT");
            var queries = new LinkedHashSet<String>();
            queries.add(response.rewrittenQuery().strip());
            if (response.subQueries() != null) response.subQueries().stream().filter(StringUtils::hasText)
                    .map(String::strip).limit(3).forEach(queries::add);
            var keywords = response.keywords() == null ? List.<String>of() : response.keywords().stream()
                    .filter(StringUtils::hasText).map(String::strip).limit(12).toList();
            return new Result(new QueryPlan(question, response.rewrittenQuery().strip(), List.copyOf(queries), keywords,
                    response.filters() == null ? Map.of() : response.filters(), response.timeRange(),
                    Boolean.TRUE.equals(response.needsWebSearch())), "MODEL");
        } catch (Exception ignored) {
            return new Result(fallback, "FALLBACK_ORIGINAL_QUERY");
        }
    }

    public record Result(QueryPlan plan, String status) {
    }

    public record QueryPlan(String originalQuery, String rewrittenQuery, List<String> subQueries,
                            List<String> keywords, Map<String, String> filters, String timeRange,
                            Boolean needsWebSearch) {
    }
}
