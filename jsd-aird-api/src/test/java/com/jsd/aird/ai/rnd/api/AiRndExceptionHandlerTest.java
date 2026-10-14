package com.jsd.aird.ai.rnd.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AiRndExceptionHandlerTest {

    @AfterEach
    void clearRequestId() {
        RequestIdHolder.clear();
    }

    @Test
    void exposesTheFrozenStructuredFailureEnvelope() {
        RequestIdHolder.set("r02-request-1");
        var response = new AiRndExceptionHandler().handleApiException(new ApiException(
                ApiErrorCode.IDEMPOTENCY_CONFLICT, "幂等键冲突", Map.of("operation", "CREATE_TARGET")));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("幂等键冲突");
        assertThat(response.getBody().requestId()).isEqualTo("r02-request-1");
        assertThat(response.getBody().detail()).isEqualTo(Map.of("operation", "CREATE_TARGET"));
    }
}
