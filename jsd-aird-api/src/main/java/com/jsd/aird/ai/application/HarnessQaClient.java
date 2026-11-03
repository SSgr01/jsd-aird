package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Executes fixed generation in pi; Java remains the canonical conversation/message authority. */
@Component
public class HarnessQaClient {
    public record Chunk(String text, int inputTokens, int outputTokens, boolean reset) {
        public Chunk(String text, int inputTokens, int outputTokens) { this(text, inputTokens, outputTokens, false); }
    }

    private final AgentDelegation delegation;
    private final ObjectMapper mapper;
    private final String controlUrl;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HarnessQaClient(AgentDelegation delegation, ObjectMapper mapper,
            @Value("${app.ai.harness.control-url:http://127.0.0.1:8093}") String controlUrl,
            @Value("${app.ai.harness.qa-enabled:${app.ai.harness.enabled:false}}") boolean enabled) {
        this.delegation = delegation; this.mapper = mapper;
        this.controlUrl = controlUrl.replaceAll("/$", ""); this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    public Flux<Chunk> stream(Actor actor, UUID conversationId, UUID userMessageId, String question,
            String instructions, String prompt, Collection<String> allowedRefs, JsonNode candidates,
            Collection<UUID> knowledgeCategories, Collection<UUID> dataCategories, boolean webSearch) {
        // Issue while the request's identity is available; every downstream tool/gate rechecks it.
        var ticket = delegation.issue(actor);
        var body = mapper.createObjectNode().put("canonical_conversation_id", conversationId.toString())
            .put("canonical_user_message_id", userMessageId.toString()).put("question", question)
            .put("instructions", instructions).put("prompt", prompt);
        body.set("allowed_refs", mapper.valueToTree(allowedRefs)); body.set("candidates", candidates);
        var scope = body.putObject("retrieval_scope").put("web_search_enabled", webSearch);
        scope.set("knowledge_category_ids", mapper.valueToTree(knowledgeCategories));
        scope.set("data_category_ids", mapper.valueToTree(dataCategories));
        return Flux.<Chunk>create(sink -> {
            try {
                var created = exchange("POST", "/prepared-qa", body, ticket, "ordinary-qa:" + userMessageId);
                var id = UUID.fromString(created.path("id").asText());
                long cursor = 0;
                var answer = new StringBuilder();
                for (int attempt = 0; attempt < 3 && !sink.isCancelled(); attempt++) {
                    try {
                        var request = HttpRequest.newBuilder(URI.create(controlUrl + "/internal/v1/harness/runs/" + id + "/events"))
                            .timeout(Duration.ofMinutes(20)).header("X-Jsd-Agent-Ticket", ticket)
                            .header("Last-Event-ID", Long.toString(cursor)).GET().build();
                        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                        try (var input = response.body()) {
                            if (response.statusCode() != 200) throw unavailable();
                            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                                String line;
                                var data = new StringBuilder();
                                while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                                    if (line.startsWith("data:")) data.append(line.substring(5).stripLeading());
                                    else if (line.isEmpty() && !data.isEmpty()) {
                                        var event = mapper.readTree(data.toString()); data.setLength(0);
                                        long seq = event.path("seq").asLong();
                                        if (seq <= cursor) continue;
                                        var payload = event.path("payload");
                                        switch (event.path("type").asText()) {
                                            case "message.started" -> { answer.setLength(0); sink.next(new Chunk("", 0, 0, true)); }
                                            case "message.delta" -> {
                                                var text = payload.path("delta").asText(); answer.append(text);
                                                sink.next(new Chunk(text, 0, 0));
                                            }
                                            case "message.completed" -> sink.next(new Chunk("",
                                                payload.path("usage").path("input").asInt(), payload.path("usage").path("output").asInt()));
                                            case "run.succeeded" -> {
                                                var text = payload.path("text").asText();
                                                if (!text.contentEquals(answer)) sink.next(new Chunk(text, 0, 0, true));
                                                sink.complete(); return;
                                            }
                                            case "run.failed", "run.cancelled" -> throw unavailable();
                                            default -> { }
                                        }
                                        cursor = seq;
                                    }
                                }
                            }
                        }
                    } catch (java.io.IOException error) {
                        if (attempt == 2) throw error;
                    }
                    var snapshot = exchange("GET", "/runs/" + id, null, ticket, null);
                    if ("SUCCEEDED".equals(snapshot.path("status").asText())) {
                        var text = snapshot.path("result").asText();
                        if (!text.contentEquals(answer)) sink.next(new Chunk(text, 0, 0, true));
                        sink.complete(); return;
                    }
                    if (java.util.Set.of("FAILED", "CANCELLED").contains(snapshot.path("status").asText())) throw unavailable();
                }
                if (!sink.isCancelled()) throw unavailable();
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                sink.error(error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private JsonNode exchange(String method, String path, ObjectNode body, String ticket, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(controlUrl + "/internal/v1/harness" + path))
            .timeout(Duration.ofSeconds(30)).header("X-Jsd-Agent-Ticket", ticket).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) throw unavailable();
        return mapper.readTree(response.body());
    }

    private ApiException unavailable() {
        return new ApiException(ApiErrorCode.AI_PROVIDER_UNAVAILABLE, "问答执行服务暂不可用，请稍后重试");
    }
}
