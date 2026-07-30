package com.jsd.aird.shared.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void createsSuccessResponseWithCurrentRequestId() {
        var response = ResponseFactory.success("ok", "request-123");

        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.data()).isEqualTo("ok");
        assertThat(response.traceId()).isEqualTo("request-123");
        assertThat(response.timestamp()).isNotNull();
    }
}
