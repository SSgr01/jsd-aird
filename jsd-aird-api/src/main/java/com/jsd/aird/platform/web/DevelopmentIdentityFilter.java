package com.jsd.aird.platform.web;

import java.io.IOException;
import java.util.UUID;

import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DevelopmentIdentityFilter extends OncePerRequestFilter {

    private final boolean developmentMode;

    public DevelopmentIdentityFilter(
            @Value("${app.identity.development-mode:true}") boolean developmentMode
    ) {
        this.developmentMode = developmentMode;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            ActorContext.set(resolveActor(request));
            filterChain.doFilter(request, response);
        } finally {
            ActorContext.clear();
        }
    }

    private Actor resolveActor(HttpServletRequest request) {
        if (!developmentMode) {
            return null;
        }
        var defaults = ActorContext.developmentDefault();
        var organization = parseUuid(request.getHeader(ActorContext.ORGANIZATION_HEADER), defaults.organizationId());
        var user = parseUuid(request.getHeader(ActorContext.USER_HEADER), defaults.userId());
        var username = StringUtils.hasText(request.getHeader(ActorContext.USERNAME_HEADER))
                ? request.getHeader(ActorContext.USERNAME_HEADER).trim()
                : defaults.username();
        return new Actor(organization, user, username);
    }

    private UUID parseUuid(String raw, UUID fallback) {
        return StringUtils.hasText(raw) ? UUID.fromString(raw.trim()) : fallback;
    }
}
