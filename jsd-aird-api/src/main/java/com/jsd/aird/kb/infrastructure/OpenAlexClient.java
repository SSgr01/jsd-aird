package com.jsd.aird.kb.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.ScholarlyLiteratureFacade.Unavailable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** One credential, bounded caches and one free-budget gate shared by all callers. */
@Component
public class OpenAlexClient {
    private final ObjectMapper mapper;
    private final String key;
    private final URI api;
    private final URI content;
    private final HttpClient http;
    private final Clock clock;
    private long nextRequest;
    private long nextSemanticRequest;
    private long blockedUntil;
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private record Cached(JsonNode value, long expires) { }

    @Autowired
    public OpenAlexClient(ObjectMapper mapper,
            @Value("${app.scholarly.openalex.api-key:}") String key) {
        this(mapper, key, URI.create("https://api.openalex.org"), URI.create("https://content.openalex.org"),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    OpenAlexClient(ObjectMapper mapper, String key, URI api, URI content, HttpClient http, Clock clock) {
        this.mapper = mapper; this.key = key == null ? "" : key.strip();
        this.api = api; this.content = content; this.http = http; this.clock = clock;
    }

    public synchronized JsonNode get(String path, Map<String, String> params, String cost, Duration ttl) {
        var uri = uri(api, path, params);
        var cached = cache.get(uri.toString());
        if (cached != null && cached.expires() > clock.millis()) return cached.value().deepCopy();
        var data = json(request(uri, cost, 4 * 1024 * 1024));
        var effectiveTtl = data.path("results").isArray() && data.path("results").isEmpty()
            ? Duration.ofHours(1) : ttl;
        cache.put(uri.toString(), new Cached(data.deepCopy(), clock.millis() + effectiveTtl.toMillis()));
        while (cache.size() > 256) cache.remove(cache.keySet().iterator().next());
        return data;
    }

    public synchronized byte[] pdf(String workId) {
        if (!workId.matches("W[1-9][0-9]{0,20}")) throw new IllegalArgumentException("Invalid work ID");
        return request(uri(content, "/works/" + workId + ".pdf", Map.of()), "content", 10 * 1024 * 1024);
    }

    private byte[] request(URI uri, String cost, int maxBytes) {
        if (key.isBlank()) throw new Unavailable("OPENALEX_NOT_CONFIGURED", 0);
        return send(uri, maxBytes, cost);
    }

    private void requireFreeBudget(String cost) {
        if (!"singleton".equals(cost)) {
            if (blockedUntil > clock.millis())
                throw new Unavailable("OPENALEX_FREE_BUDGET_EXHAUSTED", secondsUntil(blockedUntil));
            // Recheck the account's FREE balance before every metered call. Prepaid balance is never used.
            var quota = json(send(uri(api, "/rate-limit", Map.of()), 128 * 1024, "singleton")).path("rate_limit");
            var price = quota.path("endpoint_costs_usd").path(cost);
            var remaining = quota.path("daily_remaining_usd");
            if (!price.isNumber() || price.decimalValue().signum() < 0 || !remaining.isNumber())
                throw new Unavailable("OPENALEX_QUOTA_UNAVAILABLE", 60);
            if (remaining.decimalValue().compareTo(price.decimalValue()) < 0) {
                blockedUntil = clock.millis() + Math.max(1, quota.path("resets_in_seconds").asLong(3600)) * 1000;
                throw new Unavailable("OPENALEX_FREE_BUDGET_EXHAUSTED", secondsUntil(blockedUntil));
            }
        }
    }

    private byte[] send(URI uri, int maxBytes, String cost) {
        for (int attempt = 0; attempt < 3; attempt++) {
            // A retry is another potentially billable request, so check the free budget again.
            requireFreeBudget(cost);
            pause(Math.max(0, Math.max(nextRequest, "semantic".equals(cost) ? nextSemanticRequest : 0) - clock.millis()));
            nextRequest = clock.millis() + 200;
            if ("semantic".equals(cost)) nextSemanticRequest = clock.millis() + 1000;
            try {
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + key).header("User-Agent", "JSD-AIRD-Research/1.0")
                    .GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    int status = response.statusCode();
                    if (status == 200) {
                        long size = response.headers().firstValueAsLong("Content-Length").orElse(0);
                        if (size > maxBytes) throw new Unavailable("OPENALEX_RESPONSE_TOO_LARGE", 0);
                        var bytes = body.readNBytes(maxBytes + 1);
                        if (bytes.length > maxBytes) throw new Unavailable("OPENALEX_RESPONSE_TOO_LARGE", 0);
                        return bytes;
                    }
                    if (status == 404) throw new Unavailable("OPENALEX_NOT_FOUND", 0);
                    if (status == 401 || status == 403) throw new Unavailable("OPENALEX_CREDENTIAL_REJECTED", 0);
                    if (status >= 300 && status < 400) throw new Unavailable("OPENALEX_REDIRECT_REJECTED", 0);
                    if (status == 400 || status == 422) throw new Unavailable("OPENALEX_QUERY_REJECTED", 0);
                    long delay = retryAfter(response.headers().firstValue("Retry-After").orElse(""), 1L << attempt);
                    if (status == 429) {
                        var remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse("");
                        if (remaining.matches("0(?:\\.0+)?")) {
                            delay = retryAfter(response.headers().firstValue("X-RateLimit-Reset").orElse(""), 3600);
                            blockedUntil = clock.millis() + delay * 1000;
                            throw new Unavailable("OPENALEX_FREE_BUDGET_EXHAUSTED", delay);
                        }
                        // Never silently spend a second download after an unknown first outcome.
                        if ("content".equals(cost))
                            throw new Unavailable("OPENALEX_RATE_LIMITED", delay);
                        if (delay > 2 || attempt == 2) throw new Unavailable("OPENALEX_RATE_LIMITED", delay);
                    } else if (status < 500 || attempt == 2) {
                        throw new Unavailable("OPENALEX_UNAVAILABLE", 60);
                    }
                    if ("content".equals(cost)) throw new Unavailable("OPENALEX_UNAVAILABLE", 60);
                    pause(delay * 1000);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new Unavailable("OPENALEX_REQUEST_INTERRUPTED", 0);
            } catch (IOException error) {
                // Request URLs, credentials and upstream error bodies must not escape this adapter.
                throw new Unavailable("OPENALEX_REQUEST_OUTCOME_UNKNOWN", 0);
            }
        }
        throw new Unavailable("OPENALEX_UNAVAILABLE", 60);
    }

    private JsonNode json(byte[] bytes) {
        try {
            var value = mapper.readTree(bytes);
            if (value == null || !value.isObject()) throw new Unavailable("OPENALEX_INVALID_RESPONSE", 0);
            return value;
        }
        catch (IOException error) { throw new Unavailable("OPENALEX_INVALID_RESPONSE", 0); }
    }

    private static URI uri(URI origin, String path, Map<String, String> params) {
        var builder = UriComponentsBuilder.fromUri(origin).path(path);
        params.forEach(builder::queryParam);
        return builder.build().encode().toUri();
    }

    private long secondsUntil(long until) { return Math.max(1, (until - clock.millis() + 999) / 1000); }
    private long retryAfter(String raw, long fallback) {
        try {
            long value = Long.parseLong(raw);
            if (value > 1000000000L) value -= clock.instant().getEpochSecond();
            return Math.min(86400, Math.max(1, value));
        } catch (NumberFormatException error) {
            try {
                long until = java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
                return Math.min(86400, secondsUntil(until));
            } catch (java.time.format.DateTimeParseException ignored) { return fallback; }
        }
    }
    private static void pause(long millis) {
        if (millis <= 0) return;
        try { Thread.sleep(millis); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new Unavailable("OPENALEX_REQUEST_INTERRUPTED", 0);
        }
    }
}
