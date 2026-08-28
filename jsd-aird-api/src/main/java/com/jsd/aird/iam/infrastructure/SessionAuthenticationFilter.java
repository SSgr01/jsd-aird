package com.jsd.aird.iam.infrastructure;

import java.io.IOException;
import com.jsd.aird.iam.application.IamAuthService;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.platform.web.RequestTimingHolder;
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
import java.time.Duration;

public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTRIBUTE = SessionAuthenticationFilter.class.getName() + ".session";
    private final IamAuthService auth;
    private final String cookieName;
    private final Duration touchInterval;

    public SessionAuthenticationFilter(IamAuthService auth, String cookieName) {
        this(auth, cookieName, Duration.ofSeconds(30));
    }

    public SessionAuthenticationFilter(IamAuthService auth, String cookieName, Duration touchInterval) {
        this.auth = auth;
        this.cookieName = cookieName;
        this.touchInterval = touchInterval == null || touchInterval.isNegative()
                ? Duration.ofSeconds(30) : touchInterval;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var started = System.nanoTime();
        var token = cookie(request, cookieName);
        var session = auth.activeSession(token);
        try {
            if (session != null && isCurrent(session)) {
                var user = auth.user(session.userId());
                var actor = new Actor(session.organizationId(), session.userId(), session.username(),
                        user == null || user.roleCode() == null ? "USER" : user.roleCode());
                ActorContext.set(actor);
                request.setAttribute(SESSION_ATTRIBUTE, session);
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
                var now = java.time.Instant.now();
                if (session.lastSeenAt() == null || session.lastSeenAt().plus(touchInterval).isBefore(now)) {
                    auth.touch(session);
                }
            }
            if (request.getRequestURI().startsWith("/api/v1/assistant/qa")) {
                RequestTimingHolder.put("sessionAuthMs", elapsedMs(started));
            }
            chain.doFilter(request, response);
        } finally {
            ActorContext.clear();
        }
    }

    private boolean isCurrent(IamStore.Session session) {
        var now = java.time.Instant.now();
        return session.expiresAt() != null && session.absoluteExpiresAt() != null
                && session.expiresAt().isAfter(now) && session.absoluteExpiresAt().isAfter(now);
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}
