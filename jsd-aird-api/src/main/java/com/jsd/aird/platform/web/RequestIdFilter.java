package com.jsd.aird.platform.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var incomingRequestId = request.getHeader(RequestIdHolder.HEADER_NAME);
        var requestId = StringUtils.hasText(incomingRequestId)
                ? incomingRequestId.trim()
                : UUID.randomUUID().toString();

        RequestIdHolder.set(requestId);
        response.setHeader(RequestIdHolder.HEADER_NAME, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestIdHolder.clear();
        }
    }
}

