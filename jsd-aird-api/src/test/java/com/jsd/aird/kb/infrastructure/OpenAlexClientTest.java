package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.ScholarlyLiteratureFacade.Unavailable;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;

class OpenAlexClientTest {
    private HttpServer server;
    private OpenAlexClient client;
    private final Deque<Response> responses = new ArrayDeque<>();
    private final List<String> paths = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private record Response(int status, byte[] body, Map<String, String> headers) { }

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            var response = responses.removeFirst();
            response.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
            exchange.sendResponseHeaders(response.status(), response.body().length);
            try (var output = exchange.getResponseBody()) { output.write(response.body()); }
        });
        server.start();
        var origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new OpenAlexClient(new ObjectMapper(), "test-secret", origin, origin,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }
    @AfterEach void stop() { server.stop(0); }
    private void response(int status, String body, Map<String, String> headers) {
        responses.add(new Response(status, body.getBytes(StandardCharsets.UTF_8), headers));
    }
    private void response(int status, String body) { response(status, body, Map.of()); }
    private void quota(String remaining) {
        response(200, "{\"rate_limit\":{\"daily_remaining_usd\":" + remaining
            + ",\"prepaid_remaining_usd\":100,\"resets_in_seconds\":3600,"
            + "\"endpoint_costs_usd\":{\"search\":0.001,\"content\":0.01}}}");
    }
    private void fails(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(Unavailable.class, error -> assertThat(error.code()).isEqualTo(code));
    }

    @Test void credentialsStayInHeadersAndCachedObjectsCannotBeMutatedByCallers() {
        response(200, "{\"id\":\"W123\"}");
        var first = client.get("/works/W123", Map.of(), "singleton", Duration.ofDays(1));
        ((com.fasterxml.jackson.databind.node.ObjectNode) first).put("id", "changed");
        assertThat(client.get("/works/W123", Map.of(), "singleton", Duration.ofDays(1)).path("id").asText()).isEqualTo("W123");
        assertThat(paths).containsExactly("/works/W123");
        assertThat(authorizations).containsExactly("Bearer test-secret");
        assertThat(paths.toString()).doesNotContain("test-secret");
    }

    @Test void prepaidBalanceCannotUnlockAnExhaustedFreeBudgetButSingletonsStillWork() {
        quota("0");
        fails("OPENALEX_FREE_BUDGET_EXHAUSTED", () -> client.get("/works", Map.of("search", "polymer"), "search", Duration.ZERO));
        fails("OPENALEX_FREE_BUDGET_EXHAUSTED", () -> client.pdf("W123"));
        response(200, "{\"id\":\"W123\"}");
        client.get("/works/W123", Map.of(), "singleton", Duration.ZERO);
        assertThat(paths).containsExactly("/rate-limit", "/works/W123");
    }

    @Test void eachSearchRetryChecksTheFreeBudgetAgain() {
        quota("1"); response(429, "{}", Map.of("Retry-After", "1")); quota("0");
        fails("OPENALEX_FREE_BUDGET_EXHAUSTED", () -> client.get("/works", Map.of(), "search", Duration.ZERO));
        assertThat(paths).containsExactly("/rate-limit", "/works", "/rate-limit");
    }

    @Test void aFailedPdfRequestIsNeverAutomaticallyReissued() {
        quota("1"); response(503, "upstream failed");
        fails("OPENALEX_UNAVAILABLE", () -> client.pdf("W123"));
        assertThat(paths).containsExactly("/rate-limit", "/works/W123.pdf");
    }

    @Test void longRateLimitsReturnImmediatelyWithoutFollowingRedirectsOrLeakingBodies() {
        response(429, "private upstream body", Map.of("Retry-After", "3600"));
        fails("OPENALEX_RATE_LIMITED", () -> client.get("/works/W123", Map.of(), "singleton", Duration.ZERO));
        response(302, "", Map.of("Location", "https://untrusted.example/private"));
        fails("OPENALEX_REDIRECT_REJECTED", () -> client.get("/works/W123", Map.of(), "singleton", Duration.ZERO));
        assertThat(paths).hasSize(2);
    }

    @Test void oversizedAndInvalidResponsesAreRejected() {
        responses.add(new Response(200, new byte[4 * 1024 * 1024 + 1], Map.of()));
        fails("OPENALEX_RESPONSE_TOO_LARGE", () -> client.get("/works/W123", Map.of(), "singleton", Duration.ZERO));
        response(200, "null");
        fails("OPENALEX_INVALID_RESPONSE", () -> client.get("/works/W123", Map.of(), "singleton", Duration.ZERO));
    }

    @Test void unknownNetworkOutcomesAreNotRetried() throws Exception {
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
            .thenThrow(new IOException("sensitive upstream data"));
        var failing = new OpenAlexClient(new ObjectMapper(), "test-secret", URI.create("https://api.openalex.org"),
            URI.create("https://content.openalex.org"), http, Clock.systemUTC());
        fails("OPENALEX_REQUEST_OUTCOME_UNKNOWN", () -> failing.get("/works/W123", Map.of(), "singleton", Duration.ZERO));
        verify(http, times(1)).send(any(HttpRequest.class), any());
    }
}
