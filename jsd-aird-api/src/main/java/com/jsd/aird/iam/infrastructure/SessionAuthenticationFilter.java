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

public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTRIBUTE = SessionAuthenticationFilter.class.getName() + ".session";
    public static final String REPLACED_ATTRIBUTE = SessionAuthenticationFilter.class.getName() + ".replaced";
    private final IamAuthService auth;
    private final String cookieName;

    public SessionAuthenticationFilter(IamAuthService auth, String cookieName) {
        this.auth = auth;
        this.cookieName = cookieName;
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
            if (auth.isCurrent(session)) {
                var user = auth.user(session.userId());
                var actor = new Actor(session.organizationId(), session.userId(), session.username(),
                        user == null || user.roleCode() == null ? "USER" : user.roleCode());
                ActorContext.set(actor);
                request.setAttribute(SESSION_ATTRIBUTE, session);
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
            } else if (auth.wasReplaced(token)) {
                request.setAttribute(REPLACED_ATTRIBUTE, true);
            }
            if (request.getRequestURI().startsWith("/api/v1/assistant/qa")) {
                RequestTimingHolder.put("sessionAuthMs", elapsedMs(started));
            }
            chain.doFilter(request, response);
        } finally {
            ActorContext.clear();
        }
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
