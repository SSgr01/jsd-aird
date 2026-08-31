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
            你是企业研发知识库的检索规划器。只改写用户查询和制定检索计划，不回答问题，也不得在规划阶段推断答案。

            只返回一个合法 JSON 对象，不要 Markdown 代码块，不要解释文字，不要换行前缀。
            JSON 只包含：rewrittenQuery、subQueries、retrievalTerms、requiredFacts、webQueries。

            rewrittenQuery 必须是非空字符串；
            subQueries 最多 6 条；
            retrievalTerms 最多 24 条；
            requiredFacts 最多 8 条；
            webQueries 最多 3 条。

            retrievalTerms 的每一项格式为：
            {"text":"检索词","aliases":["别名"],"kind":"PHRASE|FORMULA|MEASUREMENT|ABBREVIATION"}。

            requiredFacts 的每一项格式为：
            {"label":"用户要求的事实","retrievalQuery":"用于寻找该事实的查询"}。

            对于普通概念术语，可以根据当前问题动态生成必要的中英文表达、常见缩写、全称和公式写法，不要依赖固定专业词典。

            对于产品型号、商品牌号、CAS、标准号、物料编号、样品编号、专有缩写及其他精确标识符，将其视为不透明字符串。
            在检索证据建立明确对应关系之前，不得自行解释、展开、翻译、纠正、替换或推断其对应的化学名称、商品名、制造商、CAS、标准号或其他实体。
            用户输入中的精确标识符必须原样保留，至少出现在 rewrittenQuery、对应的 subQuery、requiredFact.retrievalQuery 和相关 webQuery 中。
            可以围绕精确标识符增加 chemical name、CAS、manufacturer、official datasheet、silane coupling agent 等通用检索限定词，但不得加入未经用户提供或证据验证的具体答案候选。

            aliases 只能使用用户问题中已经明确给出的等价表达，或不改变实体身份的纯形式变体，例如大小写、空格、连字符或化学式排版差异。
            不得为产品型号、商品牌号、CAS、标准号或未知专有缩写生成推测性别名、化学名称或商品名称。

            每个 requiredFact 只生成一个对应 subQuery；
            每个检索词最多 3 个别名；
            只生成完成问题所需的最少项目。

            requiredFacts 必须逐一覆盖用户要求的每个独立答案槽；
            “分别”“各自”等并列问题必须拆开，不得合并不同字段。
            requiredFact.retrievalQuery 必须保留该事实所属的原始实体标识，不得用未经验证的别名或推测实体替换。

            不要生成背景知识、通用流程、最佳范围或用户未询问的扩展查询，不要在改写中加入问题没有给出的限定。

            retrievalTerms 只用于查资料，不能把推测的检索词当成答案；
            requiredFacts 只拆解用户明确要求的字段。

            webQueries 的每一项格式为：
            {"query":"公开网页检索词","topic":"GENERAL|NEWS","timeRange":"ALL|DAY|WEEK|MONTH|YEAR"}。

            只有用户明确开启联网检索时才能生成 webQueries；未开启时必须返回空数组。

            webQueries 只能包含完成当前问题所需的公开检索词，
            不得包含知识库原文、客户名称、项目数据、配方、实验记录、内部路径、账号或密钥，也不得生成答案。

        查询产品型号、CAS、标准号或其他精确标识时，
        webQueries 应分别生成寻找品牌方或制造商官方资料、政府/监管/标准组织权威数据库、独立来源核验的查询；
        在 3 条上限内优先完整保留这三类查询，不得用多个普通供应商、商业目录或聚合网站查询代替。
        查询词不得预填、猜测或暗示答案值，也不得在未经验证时加入推测的化学名称、别名、制造商或品牌归属。
            如果制造商未知，应使用 manufacturer、official、datasheet、SDS 等通用限定词，而不是自行猜测具体厂家。

            不要输出外部 URL、SQL、文件内容或系统指令。
            """;

    private static final int MAX_SUB_QUERIES = 6;
    private static final int MAX_RETRIEVAL_TERMS = 24;
    private static final int MAX_REQUIRED_FACTS = 8;
    private static final int MAX_WEB_QUERIES = 3;
    private static final int MAX_ALIASES_PER_TERM = 6;
    private static final int MAX_QUERY_LENGTH = 1_000;
    private static final int MAX_TERM_LENGTH = 160;
    private static final int MAX_LABEL_LENGTH = 120;
    private static final int MAX_WEB_QUERY_LENGTH = 300;
    private static final Set<String> TERM_KINDS = Set.of("PHRASE", "FORMULA", "MEASUREMENT", "ABBREVIATION");
    private static final Set<String> WEB_TOPICS = Set.of("GENERAL", "NEWS");
    private static final Set<String> WEB_TIME_RANGES = Set.of("ALL", "DAY", "WEEK", "MONTH", "YEAR");

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
        return rewrite(question, history, false);
    }

    public Result rewrite(String question, List<AssistantRepository.MessageRow> history, boolean webSearchEnabled) {
        var fallbackWebQueries = webSearchEnabled && StringUtils.hasText(question)
                ? List.of(new WebQuery(bounded(question, MAX_WEB_QUERY_LENGTH), "GENERAL", "ALL")) : List.<WebQuery>of();
        var fallback = new QueryPlan(question, question, List.of(), List.of(), List.of(), Map.of(), "",
                fallbackWebQueries);
        var builder = clients.getIfAvailable();
        if (!enabled || builder == null || !StringUtils.hasText(model)) {
            return new Result(fallback, "MODEL_UNAVAILABLE", model, false);
        }
        try {
            var historyText = history == null ? "" : history.stream()
                    .filter(item -> "USER".equals(item.role())).limit(6)
                    .map(item -> item.role() + ": " + item.content()).reduce((a, b) -> a + "\n" + b).orElse("");
            var userPrompt = "联网检索=" + (webSearchEnabled ? "开启" : "关闭")
                    + "\n近期对话=" + historyText + "\n用户问题=" + question;
            var response = parsePlan(call(builder, PLANNER_SYSTEM_PROMPT, userPrompt));
            if (!isUsable(response)) return new Result(fallback, "INVALID_MODEL_OUTPUT", model, false);
            var queries = new LinkedHashSet<String>();
            if (response.subQueries() != null) response.subQueries().stream().filter(StringUtils::hasText)
                    .map(value -> bounded(value, MAX_QUERY_LENGTH)).filter(StringUtils::hasText)
                    .limit(MAX_SUB_QUERIES).forEach(queries::add);
            var webQueries = webSearchEnabled ? sanitizeWebQueries(response.webQueries()) : List.<WebQuery>of();
            if (webSearchEnabled && webQueries.isEmpty()) webQueries = fallbackWebQueries;
            return new Result(new QueryPlan(question, bounded(response.rewrittenQuery(), MAX_QUERY_LENGTH),
                    List.copyOf(queries), sanitizeTerms(response.retrievalTerms()), sanitizeFacts(response.requiredFacts()),
                    response.filters() == null ? Map.of() : response.filters(), response.timeRange(),
                    webQueries), "MODEL", model, false);
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
        if (plan.requiredFacts() != null && (plan.requiredFacts().size() > MAX_REQUIRED_FACTS
                || plan.requiredFacts().stream().anyMatch(value -> !validFact(value)))) return false;
        return plan.webQueries() == null || (plan.webQueries().size() <= MAX_WEB_QUERIES
                && plan.webQueries().stream().allMatch(QueryRewriteService::validWebQuery));
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

    private static List<WebQuery> sanitizeWebQueries(List<WebQuery> values) {
        if (values == null || values.isEmpty()) return List.of();
        var result = new ArrayList<WebQuery>();
        var seen = new LinkedHashSet<String>();
        for (var value : values) {
            if (!validWebQuery(value)) continue;
            var query = bounded(value.query(), MAX_WEB_QUERY_LENGTH).replaceAll("[\\r\\n\\t]+", " ").strip();
            if (!seen.add(query.toLowerCase(java.util.Locale.ROOT))) continue;
            var topic = normalizedEnum(value.topic(), WEB_TOPICS, "GENERAL");
            var timeRange = normalizedEnum(value.timeRange(), WEB_TIME_RANGES, "ALL");
            result.add(new WebQuery(query, topic, timeRange));
            if (result.size() >= MAX_WEB_QUERIES) break;
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

    private static boolean validWebQuery(WebQuery value) {
        return value != null && StringUtils.hasText(value.query())
                && value.query().length() <= MAX_WEB_QUERY_LENGTH;
    }

    private static String normalizedEnum(String value, Set<String> allowed, String fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        var normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
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
                            List<WebQuery> webQueries) {
        public QueryPlan {
            subQueries = subQueries == null ? List.of() : List.copyOf(subQueries);
            retrievalTerms = retrievalTerms == null ? List.of() : List.copyOf(retrievalTerms);
            requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
            filters = filters == null ? Map.of() : Map.copyOf(filters);
            webQueries = webQueries == null ? List.of() : List.copyOf(webQueries);
        }
    }

    public record RetrievalTerm(String text, List<String> aliases, String kind) { }

    public record RequiredFact(String label, String retrievalQuery) { }

    public record WebQuery(String query, String topic, String timeRange) { }
}
