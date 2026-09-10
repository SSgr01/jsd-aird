package com.jsd.aird.ai.formula.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.*;
import com.jsd.aird.ai.formula.application.port.FormulaModelComputeClient;
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
import java.util.Set;

@Component
public class PythonFormulaModelClient implements FormulaModelComputeClient {
    private static final Set<String> MODEL_ARTIFACT_ERRORS = Set.of(
            "HASH_MISMATCH", "UNSUPPORTED_CONTRACT", "MODEL_NOT_READY", "INVALID_SNAPSHOT");
    private final ObjectMapper json;
    private final HttpClient client;
    private final String baseUrl;
    private final String token;

    public PythonFormulaModelClient(ObjectMapper json,
                                    @Value("${app.ai.formula-model.base-url:}") String baseUrl,
                                    @Value("${app.ai.formula-model.token:}") String token) {
        this.json = json;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.token = token == null ? "" : token.strip();
        // Uvicorn serves this private compute contract over HTTP/1.1. Leaving the
        // JDK client on its HTTP/2-preferred default sends an h2c upgrade on
        // request bodies, which Uvicorn rejects before FastAPI can validate JSON.
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public SnapshotValidationResponse validate(ValidateSnapshotRequest request) {
        return post("/internal/v1/snapshots/validate", request, SnapshotValidationResponse.class,
                Duration.ofMinutes(15));
    }

    @Override
    public GenerateValidationFoldsResponse generateValidationFolds(GenerateValidationFoldsRequest request) {
        return post("/internal/v1/snapshots/validation-folds", request, GenerateValidationFoldsResponse.class,
                Duration.ofMinutes(15));
    }

    @Override
    public TrainResponse train(TrainRequest request) {
        return post("/internal/v1/models/train", request, TrainResponse.class, Duration.ofHours(4));
    }

    @Override
    public ScoreResponse score(ScoreRequest request) {
        return post("/internal/v1/models/score", request, ScoreResponse.class, Duration.ofSeconds(60));
    }

    @Override
    public RecommendResponse recommend(RecommendRequest request) {
        return post("/internal/v1/models/recommend", request, RecommendResponse.class, Duration.ofSeconds(60));
    }

    @Override
    public JsonNode health() {
        ensureConfigured();
        try {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/health/ready"))
                    .timeout(Duration.ofSeconds(10));
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            var response = client.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) throw unavailable("计算服务健康检查失败");
            return json.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("计算服务健康检查被中断");
        } catch (IOException exception) {
            throw unavailable("计算服务不可访问");
        }
    }

    private <T> T post(String path, Object payload, Class<T> responseType, Duration timeout) {
        ensureConfigured();
        byte[] body;
        try {
            body = json.writeValueAsBytes(payload);
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "计算请求无法序列化");
        }
        for (var attempt = 0; attempt < 3; attempt++) {
            try {
                var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                        .header("Content-Type", "application/json");
                if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
                var response = client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 == 2) return decode(response.body(), responseType);
                if (response.statusCode() >= 500) {
                    if (attempt < 2) continue;
                    throw unavailable("配方计算服务连续返回服务端错误");
                }
                throw contractError(response.statusCode(), response.body());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ApiException(ApiErrorCode.AI_MODEL_TIMEOUT, "配方计算请求被中断");
            } catch (HttpTimeoutException exception) {
                if (attempt < 2) continue;
                throw new ApiException(ApiErrorCode.AI_MODEL_TIMEOUT, "配方计算服务响应超时");
            } catch (IOException exception) {
                if (attempt < 2) continue;
                throw unavailable("配方计算服务请求失败");
            }
        }
        throw unavailable("配方计算服务请求失败");
    }

    private <T> T decode(byte[] body, Class<T> responseType) {
        try {
            return json.readValue(body, responseType);
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.AI_MODEL_ARTIFACT_INVALID, "配方计算响应不符合共享契约");
        }
    }

    private void ensureConfigured() {
        if (baseUrl.isBlank()) {
            throw new ApiException(ApiErrorCode.AI_MODEL_NOT_CONFIGURED, "配方计算服务地址未配置");
        }
    }

    private ApiException contractError(int status, byte[] body) {
        try {
            var response = json.readTree(body);
            var message = response.path("message").asText("配方计算契约校验失败");
            var code = response.path("code").asText("");
            if (MODEL_ARTIFACT_ERRORS.contains(code)) {
                return new ApiException(ApiErrorCode.AI_MODEL_ARTIFACT_INVALID, message);
            }
            return new ApiException(status == 401 || status == 403
                    ? ApiErrorCode.AI_MODEL_AUTH_FAILED : ApiErrorCode.INVALID_SCHEMA, message);
        } catch (Exception ignored) {
            return new ApiException(ApiErrorCode.INVALID_SCHEMA, "配方计算契约校验失败");
        }
    }

    private ApiException unavailable(String message) {
        return new ApiException(ApiErrorCode.AI_PROVIDER_UNAVAILABLE, message);
    }
}
