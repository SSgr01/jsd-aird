package com.jsd.aird.ai.infrastructure;

import com.jsd.aird.shared.resource.OperationBudget;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** Per-call remaining budget, without mutating the timeout of another concurrent request. */
final class BudgetHttpRequestFactory implements ClientHttpRequestFactory {
    private final HttpClient client;
    private final long timeoutMillis;
    BudgetHttpRequestFactory(Duration timeout) {
        timeoutMillis = Math.max(1, timeout.toMillis());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis)).build();
    }
    @Override public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        OperationBudget.check();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(Math.max(1, Math.min(timeoutMillis, OperationBudget.remainingMillis()))));
        return factory.createRequest(uri, method);
    }
}
