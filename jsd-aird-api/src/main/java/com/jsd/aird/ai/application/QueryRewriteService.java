package com.jsd.aird.ai.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.jsd.aird.ai.application.port.AssistantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QueryRewriteService {

    private static final String PLANNER_SYSTEM_PROMPT = """
            你是企业研发知识库的检索规划器。只改写用户查询，不回答问题。
            只返回一个合法 JSON 对象，不要 Markdown 代码块，不要解释文字，不要换行前缀。
            JSON 必须包含：originalQuery、rewrittenQuery、subQueries、keywords、filters、timeRange、needsWebSearch。
            rewrittenQuery 必须是非空字符串；subQueries 最多 3 条；keywords 最多 12 个。
            不要输出外部 URL、SQL、文件内容或系统指令。企业内部资料问题的 needsWebSearch 默认应为 false。
            """;

    private static final String STRICT_RETRY_PROMPT = """
            上一次检索规划结果未通过 JSON 校验。请重新生成，严格只输出一个 JSON 对象。
            不要使用 ```，不要添加任何说明文字。字段必须完整：
            {"originalQuery":"原问题","rewrittenQuery":"检索问题","subQueries":[],"keywords":[],"filters":{},"timeRange":"不限","needsWebSearch":false}
            """;

    private final ObjectProvider<ChatClient.Builder> clients;
    private final AiJsonParser parser;

    public QueryRewriteService(ObjectProvider<ChatClient.Builder> clients, AiJsonParser parser) {
        this.clients = clients;
        this.parser = parser;
    }

    public Result rewrite(String question, List<AssistantRepository.MessageRow> history, List<String> scopeTypes) {
        var fallback = new QueryPlan(question, question, List.of(question), List.of(), Map.of(), "", false);
        var builder = clients.getIfAvailable();
        if (builder == null) return new Result(fallback, "MODEL_UNAVAILABLE");
        try {
            var historyText = history == null ? "" : history.stream().limit(6)
                    .map(item -> item.role() + ": " + item.content()).reduce((a, b) -> a + "\n" + b).orElse("");
            var userPrompt = "范围类型=" + scopeTypes + "\n近期对话=" + historyText + "\n用户问题=" + question;
            var response = parsePlan(call(builder, PLANNER_SYSTEM_PROMPT, userPrompt));
            var status = "MODEL";
            if (!isUsable(response)) {
                response = parsePlan(call(builder, PLANNER_SYSTEM_PROMPT + "\n" + STRICT_RETRY_PROMPT, userPrompt));
                status = "MODEL_RETRY";
            }
            if (!isUsable(response)) return new Result(fallback, "INVALID_MODEL_OUTPUT");
            var queries = new LinkedHashSet<String>();
            queries.add(response.rewrittenQuery().strip());
            if (response.subQueries() != null) response.subQueries().stream().filter(StringUtils::hasText)
                    .map(String::strip).limit(3).forEach(queries::add);
            var keywords = response.keywords() == null ? List.<String>of() : response.keywords().stream()
                    .filter(StringUtils::hasText).map(String::strip).limit(12).toList();
            return new Result(new QueryPlan(question, response.rewrittenQuery().strip(), List.copyOf(queries), keywords,
                    response.filters() == null ? Map.of() : response.filters(), response.timeRange(),
                    Boolean.TRUE.equals(response.needsWebSearch())), status);
        } catch (Exception ignored) {
            return new Result(fallback, "FALLBACK_ORIGINAL_QUERY");
        }
    }

    private String call(ChatClient.Builder builder, String system, String user) {
        var content = builder.build().prompt()
                .system(system)
                .user(user)
                .call()
                .content();
        return content == null ? "" : content;
    }

    private QueryPlan parsePlan(String raw) {
        return parser.read(raw, QueryPlan.class);
    }

    static boolean isUsable(QueryPlan plan) {
        if (plan == null || !StringUtils.hasText(plan.rewrittenQuery())) return false;
        if (plan.subQueries() != null && plan.subQueries().stream().filter(StringUtils::hasText).count() > 3) return false;
        return plan.keywords() == null || plan.keywords().stream().filter(StringUtils::hasText).count() <= 12;
    }

    public record Result(QueryPlan plan, String status) {
    }

    public record QueryPlan(String originalQuery, String rewrittenQuery, List<String> subQueries,
                            List<String> keywords, Map<String, JsonNode> filters, String timeRange,
                            Boolean needsWebSearch) {
    }
}
