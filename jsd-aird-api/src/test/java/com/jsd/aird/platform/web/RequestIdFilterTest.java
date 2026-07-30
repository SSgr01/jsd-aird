package com.jsd.aird.platform.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void preservesIncomingRequestIdAndClearsContext() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdHolder.HEADER_NAME, "request-123");
        var response = new MockHttpServletResponse();
        var observedRequestId = new AtomicReference<String>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                observedRequestId.set(RequestIdHolder.currentOrUnknown()));

        assertThat(observedRequestId).hasValue("request-123");
        assertThat(response.getHeader(RequestIdHolder.HEADER_NAME)).isEqualTo("request-123");
        assertThat(RequestIdHolder.current()).isEmpty();
    }

    @Test
    void generatesRequestIdWhenHeaderIsMissing() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
        });

        assertThat(response.getHeader(RequestIdHolder.HEADER_NAME)).isNotBlank();
        assertThat(RequestIdHolder.current()).isEmpty();
    }
}

