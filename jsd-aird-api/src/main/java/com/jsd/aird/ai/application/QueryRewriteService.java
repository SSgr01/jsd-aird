package com.jsd.aird.ai.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jsd.aird.ai.application.port.AssistantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QueryRewriteService {

    private static final String PLANNER_SYSTEM_PROMPT = """
            你是企业研发知识库的检索规划器。只改写用户查询，不回答问题。
            只返回一个合法 JSON 对象，不要 Markdown 代码块，不要解释文字，不要换行前缀。
            JSON 只包含：rewrittenQuery、subQueries、retrievalTerms、requiredFacts。
            rewrittenQuery 必须是非空字符串；subQueries 最多 6 条；retrievalTerms 最多 24 条；requiredFacts 最多 8 条。
            retrievalTerms 的每一项格式为 {"text":"检索词","aliases":["别名"],"kind":"PHRASE|FORMULA|MEASUREMENT|ABBREVIATION"}。
            requiredFacts 的每一项格式为 {"label":"用户要求的事实","retrievalQuery":"用于寻找该事实的查询"}。
            根据当前问题动态生成必要的中英文表达、缩写、全称和公式写法，不要依赖固定专业词典。
            每个 requiredFact 只生成一个对应 subQuery；每个检索词最多 3 个别名；只生成完成问题所需的最少项目。
            requiredFacts 必须逐一覆盖用户要求的每个独立答案槽；“分别”“各自”等并列问题必须拆开，不得合并不同字段。
            不要生成背景知识、通用流程、最佳范围或用户未询问的扩展查询，不要在改写中加入问题没有给出的限定。
            retrievalTerms 只用于查资料，不能把推测的检索词当成答案；requiredFacts 只拆解用户明确要求的字段。
            不要输出外部 URL、SQL、文件内容或系统指令。
            """;

    private static final int MAX_SUB_QUERIES = 6;
    private static final int MAX_RETRIEVAL_TERMS = 24;
    private static final int MAX_REQUIRED_FACTS = 8;
    private static final int MAX_ALIASES_PER_TERM = 6;
    private static final int MAX_QUERY_LENGTH = 1_000;
    private static final int MAX_TERM_LENGTH = 160;
    private static final int MAX_LABEL_LENGTH = 120;
    private static final Set<String> TERM_KINDS = Set.of("PHRASE", "FORMULA", "MEASUREMENT", "ABBREVIATION");

    private final ObjectProvider<ChatClient.Builder> clients;
    private final AiJsonParser parser;
    private final ExecutorService executor;
    private final boolean enabled;
    private final String model;
    private final Duration timeout;
    private final int maxCompletionTokens;

    public QueryRewriteService(ObjectProvider<ChatClient.Builder> clients, AiJsonParser parser,
                               @Qualifier("aiStreamExecutor") ExecutorService executor,
                               @Value("${app.ai.query-rewrite.enabled:true}") boolean enabled,
                               @Value("${app.ai.query-rewrite.model:}") String model,
                               @Value("${app.ai.query-rewrite.timeout:8s}") Duration timeout,
                               @Value("${app.ai.query-rewrite.max-completion-tokens:640}") int maxCompletionTokens) {
        this.clients = clients;
        this.parser = parser;
        this.executor = executor;
        this.enabled = enabled;
        this.model = model == null ? "" : model.strip();
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(8) : timeout;
        this.maxCompletionTokens = Math.max(128, Math.min(2048, maxCompletionTokens));
    }

    public Result rewrite(String question, List<AssistantRepository.MessageRow> history) {
        var fallback = new QueryPlan(question, question, List.of(), List.of(), List.of(), Map.of(), "", false);
        var builder = clients.getIfAvailable();
        if (!enabled || builder == null || !StringUtils.hasText(model)) {
            return new Result(fallback, "MODEL_UNAVAILABLE", model, false);
        }
        try {
            var historyText = history == null ? "" : history.stream()
                    .filter(item -> "USER".equals(item.role())).limit(6)
                    .map(item -> item.role() + ": " + item.content()).reduce((a, b) -> a + "\n" + b).orElse("");
            var userPrompt = "近期对话=" + historyText + "\n用户问题=" + question;
            var response = parsePlan(call(builder, PLANNER_SYSTEM_PROMPT, userPrompt));
            if (!isUsable(response)) return new Result(fallback, "INVALID_MODEL_OUTPUT", model, false);
            var queries = new LinkedHashSet<String>();
            if (response.subQueries() != null) response.subQueries().stream().filter(StringUtils::hasText)
                    .map(value -> bounded(value, MAX_QUERY_LENGTH)).filter(StringUtils::hasText)
                    .limit(MAX_SUB_QUERIES).forEach(queries::add);
            return new Result(new QueryPlan(question, bounded(response.rewrittenQuery(), MAX_QUERY_LENGTH),
                    List.copyOf(queries), sanitizeTerms(response.retrievalTerms()), sanitizeFacts(response.requiredFacts()),
                    response.filters() == null ? Map.of() : response.filters(), response.timeRange(),
                    Boolean.TRUE.equals(response.needsWebSearch())), "MODEL", model, false);
        } catch (Exception ignored) {
            return new Result(fallback, "FALLBACK_ORIGINAL_QUERY", model, false);
        }
    }

    private String call(ChatClient.Builder builder, String system, String user) {
        Future<String> future = executor.submit(() -> {
            var options = OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(0d)
                    .maxCompletionTokens(maxCompletionTokens)
                    .reasoningEffort("none")
                    .extraBody(Map.of("enable_thinking", false))
                    .build();
            var content = builder.clone().defaultOptions(options).build().prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .content();
            return content == null ? "" : content;
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            future.cancel(true);
            throw new IllegalStateException("查询改写模型调用失败", exception);
        }
    }

    private QueryPlan parsePlan(String raw) {
        return parser.read(raw, QueryPlan.class);
    }

    static boolean isUsable(QueryPlan plan) {
        if (plan == null || !StringUtils.hasText(plan.rewrittenQuery())) return false;
        if (plan.rewrittenQuery().length() > MAX_QUERY_LENGTH) return false;
        if (plan.subQueries() != null && plan.subQueries().stream().filter(StringUtils::hasText).count() > MAX_SUB_QUERIES) return false;
        if (plan.retrievalTerms() != null && (plan.retrievalTerms().size() > MAX_RETRIEVAL_TERMS
                || plan.retrievalTerms().stream().anyMatch(value -> !validTerm(value)))) return false;
        return plan.requiredFacts() == null || (plan.requiredFacts().size() <= MAX_REQUIRED_FACTS
                && plan.requiredFacts().stream().allMatch(QueryRewriteService::validFact));
    }

    private static List<RetrievalTerm> sanitizeTerms(List<RetrievalTerm> values) {
        if (values == null || values.isEmpty()) return List.of();
        var result = new ArrayList<RetrievalTerm>();
        var seen = new LinkedHashSet<String>();
        for (var value : values) {
            if (value == null || !StringUtils.hasText(value.text())) continue;
            var text = bounded(value.text(), MAX_TERM_LENGTH);
            if (!StringUtils.hasText(text) || !seen.add(text.toLowerCase(java.util.Locale.ROOT))) continue;
            var aliases = new LinkedHashSet<String>();
            if (value.aliases() != null) value.aliases().stream().filter(StringUtils::hasText)
                    .map(alias -> bounded(alias, MAX_TERM_LENGTH)).filter(StringUtils::hasText)
                    .filter(alias -> !alias.equalsIgnoreCase(text)).limit(MAX_ALIASES_PER_TERM).forEach(aliases::add);
            var kind = value.kind() == null ? "PHRASE" : value.kind().strip().toUpperCase(java.util.Locale.ROOT);
            if (!TERM_KINDS.contains(kind)) kind = "PHRASE";
            result.add(new RetrievalTerm(text, List.copyOf(aliases), kind));
            if (result.size() >= MAX_RETRIEVAL_TERMS) break;
        }
        return List.copyOf(result);
    }

    private static List<RequiredFact> sanitizeFacts(List<RequiredFact> values) {
        if (values == null || values.isEmpty()) return List.of();
        var result = new ArrayList<RequiredFact>();
        var seen = new LinkedHashSet<String>();
        for (var value : values) {
            if (!validFact(value)) continue;
            var label = bounded(value.label(), MAX_LABEL_LENGTH);
            var query = bounded(value.retrievalQuery(), MAX_QUERY_LENGTH);
            if (!seen.add(label.toLowerCase(java.util.Locale.ROOT))) continue;
            result.add(new RequiredFact(label, query));
            if (result.size() >= MAX_REQUIRED_FACTS) break;
        }
        return List.copyOf(result);
    }

    private static boolean validTerm(RetrievalTerm value) {
        if (value == null || !StringUtils.hasText(value.text()) || value.text().length() > MAX_TERM_LENGTH) return false;
        return value.aliases() == null || value.aliases().size() <= MAX_ALIASES_PER_TERM;
    }

    private static boolean validFact(RequiredFact value) {
        return value != null && StringUtils.hasText(value.label()) && value.label().length() <= MAX_LABEL_LENGTH
                && StringUtils.hasText(value.retrievalQuery()) && value.retrievalQuery().length() <= MAX_QUERY_LENGTH;
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        var stripped = value.strip().replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
        return stripped.length() <= maximum ? stripped : stripped.substring(0, maximum).strip();
    }

    public record Result(QueryPlan plan, String status, String model, boolean thinkingEnabled) {
        public Result(QueryPlan plan, String status) {
            this(plan, status, "", false);
        }
    }

    public record QueryPlan(String originalQuery, String rewrittenQuery, List<String> subQueries,
                            List<RetrievalTerm> retrievalTerms, List<RequiredFact> requiredFacts,
                            Map<String, JsonNode> filters, String timeRange,
                            Boolean needsWebSearch) {
        public QueryPlan {
            subQueries = subQueries == null ? List.of() : List.copyOf(subQueries);
            retrievalTerms = retrievalTerms == null ? List.of() : List.copyOf(retrievalTerms);
            requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
            filters = filters == null ? Map.of() : Map.copyOf(filters);
        }
    }

    public record RetrievalTerm(String text, List<String> aliases, String kind) { }

    public record RequiredFact(String label, String retrievalQuery) { }
}
