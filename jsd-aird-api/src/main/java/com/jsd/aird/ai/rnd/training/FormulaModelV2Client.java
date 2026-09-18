package com.jsd.aird.ai.rnd.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

@Component
public class FormulaModelV2Client {
    private final ObjectMapper json;
    private final HttpClient client;
    private final String baseUrl;
    private final String token;

    public FormulaModelV2Client(ObjectMapper json,
                                @Value("${app.ai.formula-model.base-url:}") String baseUrl,
                                @Value("${app.ai.formula-model.token:}") String token) {
        this.json = json;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.token = token == null ? "" : token.strip();
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    public JsonNode validate(JsonNode request) { return post("/internal/v2/snapshots/validate", request, Duration.ofMinutes(15)); }
    public JsonNode folds(JsonNode request) { return post("/internal/v2/snapshots/validation-folds", request, Duration.ofMinutes(15)); }
    public JsonNode train(JsonNode request) { return post("/internal/v2/models/train", request, Duration.ofHours(4)); }
    public JsonNode score(JsonNode request) { return post("/internal/v2/models/score", request, Duration.ofMinutes(2)); }
    public JsonNode recommend(JsonNode request) { return post("/internal/v2/models/recommend", request, Duration.ofMinutes(5)); }

    private JsonNode post(String path, JsonNode payload, Duration timeout) {
        if (baseUrl.isBlank()) throw new ApiException(ApiErrorCode.COMPUTE_UNAVAILABLE, "formula-model.v2计算服务地址未配置");
        try {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Request-Id", payload.path("requestId").asText("unknown"));
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            var response = client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            var body = json.readTree(response.body());
            if (response.statusCode() / 100 == 2) return body;
            var message = body.path("message").asText("formula-model.v2契约校验失败");
            var code = body.path("code").asText();
            if (response.statusCode() >= 500) throw new ComputeTransportException(message);
            throw new ApiException(SetCodes.ARTIFACT.contains(code) ? ApiErrorCode.AI_MODEL_ARTIFACT_INVALID
                    : ApiErrorCode.INVALID_SCHEMA, message, body.path("detail"));
        } catch (HttpTimeoutException exception) {
            throw new ApiException(ApiErrorCode.TRAINING_TIMEOUT, "formula-model.v2训练超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ComputeTransportException("formula-model.v2请求被中断", exception);
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ComputeTransportException("formula-model.v2计算服务不可用", exception);
        }
    }

    public static final class ComputeTransportException extends RuntimeException {
        public ComputeTransportException(String message) { super(message); }
        public ComputeTransportException(String message, Throwable cause) { super(message, cause); }
    }
    private static final class SetCodes {
        private static final java.util.Set<String> ARTIFACT = java.util.Set.of("HASH_MISMATCH","INVALID_SNAPSHOT","UNSUPPORTED_CONTRACT");
    }
}
