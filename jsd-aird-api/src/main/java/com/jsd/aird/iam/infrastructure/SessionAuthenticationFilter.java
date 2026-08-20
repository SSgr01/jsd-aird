package com.jsd.aird.iam.infrastructure;

import java.io.IOException;
import com.jsd.aird.iam.application.IamAuthService;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTRIBUTE = SessionAuthenticationFilter.class.getName() + ".session";
    private final IamAuthService auth;
    private final String cookieName;

    public SessionAuthenticationFilter(IamAuthService auth, String cookieName) {
        this.auth = auth;
        this.cookieName = cookieName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var token = cookie(request, cookieName);
        var session = auth.session(token);
        try {
            if (session != null && isCurrent(session)) {
                var actor = new Actor(session.organizationId(), session.userId(), session.username());
                ActorContext.set(actor);
                request.setAttribute(SESSION_ATTRIBUTE, session);
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
                auth.touch(session);
            }
            chain.doFilter(request, response);
        } finally {
            ActorContext.clear();
        }
    }

    private boolean isCurrent(IamStore.Session session) {
        var now = java.time.Instant.now();
        var user = auth.user(session.userId());
        return user != null && "ACTIVE".equals(user.status()) && user.authVersion() == session.authVersion()
                && session.expiresAt().isAfter(now) && session.absoluteExpiresAt().isAfter(now);
    }

    private String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}
