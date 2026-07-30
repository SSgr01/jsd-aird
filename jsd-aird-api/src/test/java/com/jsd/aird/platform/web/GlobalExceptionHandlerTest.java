package com.jsd.aird.platform.web;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsApiExceptionToConfiguredStatusAndCode() {
        var response = handler.handleApiException(
                new ApiException(ApiErrorCode.BAD_REQUEST, "invalid request")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(ApiErrorCode.BAD_REQUEST.httpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ApiErrorCode.BAD_REQUEST.code());
        assertThat(response.getBody().message()).isEqualTo("invalid request");
    }
}
