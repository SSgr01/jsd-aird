package com.jsd.aird.iam.application;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IamAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IamStore store;
    private final PasswordEncoder passwordEncoder;
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;
    private final boolean developmentMode;

    public IamAuthService(IamStore store, PasswordEncoder passwordEncoder,
                          @Value("${app.security.session-idle-timeout:12h}") Duration idleTimeout,
                          @Value("${app.security.session-absolute-timeout:30d}") Duration absoluteTimeout,
                          @Value("${app.identity.development-mode:false}") boolean developmentMode) {
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.idleTimeout = idleTimeout;
        this.absoluteTimeout = absoluteTimeout;
        this.developmentMode = developmentMode;
    }

    @Transactional
    public LoginResult login(LoginCommand command, String ip, String userAgent) {
        var organization = store.defaultOrganization()
                .orElseThrow(() -> new ApiException(ApiErrorCode.AUTH_INVALID));
        var now = Instant.now();
        if (store.recentIpFailures(ip, now.minus(Duration.ofMinutes(5))) >= 30) {
            throw new ApiException(ApiErrorCode.AUTH_RATE_LIMITED);
        }
        var user = store.user(organization.id(), command.username())
                .orElseThrow(() -> failed(organization.id(), command.username(), ip, null));
        if ("DISABLED".equals(user.status())) throw new ApiException(ApiErrorCode.ACCOUNT_DISABLED);
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) throw new ApiException(ApiErrorCode.ACCOUNT_LOCKED);

        if (user.passwordHash() == null || !passwordEncoder.matches(command.password(), user.passwordHash())) {
            throw failed(organization.id(), command.username(), ip, user);
        }

        store.attempt(organization.id(), user.username(), ip, true);
        store.markLoginSuccess(organization.id(), user.id());
        var token = randomToken();
        var session = store.createSession(organization.id(), user.id(), user.authVersion(), hash(token),
                now.plus(idleTimeout), now.plus(absoluteTimeout), ip, userAgent, command.rememberMe());
        return new LoginResult(token, session.id(), profile(user, organization), command.rememberMe());
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        store.session(token).ifPresent(session -> store.revokeSession(session.id()));
    }

    @Transactional
    public void changePassword(Actor actor, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "新密码至少 6 位");
        }
        var user = store.user(actor.userId()).orElseThrow(() -> new ApiException(ApiErrorCode.AUTH_REQUIRED));
        if (user.passwordHash() == null || !passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new ApiException(ApiErrorCode.AUTH_INVALID, "当前密码错误");
        }
        store.changePassword(actor.organizationId(), actor.userId(), passwordEncoder.encode(newPassword));
        store.revokeUserSessions(actor.organizationId(), actor.userId());
    }

    public IamStore.Session session(String token) {
        return token == null || token.isBlank() ? null : store.session(token).orElse(null);
    }

    public IamStore.User user(UUID userId) {
        return store.user(userId).orElse(null);
    }

    public List<IamStore.SessionSummary> sessions(Actor actor) {
        return store.sessions(actor.organizationId(), actor.userId());
    }

    @Transactional
    public void revokeSession(Actor actor, UUID sessionId) {
        var owned = store.sessions(actor.organizationId(), actor.userId()).stream()
                .anyMatch(session -> session.id().equals(sessionId));
        if (!owned) throw new ApiException(ApiErrorCode.NOT_FOUND, "会话不存在");
        store.revokeSession(sessionId);
    }

    @Transactional
    public void touch(IamStore.Session session) {
        var now = Instant.now();
        if (session.absoluteExpiresAt().isBefore(now)) return;
        store.touchSession(session.id(), now.plus(idleTimeout));
    }

    public MeView me(Actor actor) {
        var user = store.user(actor.userId()).orElseThrow(() -> new ApiException(ApiErrorCode.AUTH_REQUIRED));
        var organization = store.organizationById(actor.organizationId());
        return profile(user, organization);
    }

    public boolean developmentMode() { return developmentMode; }

    private ApiException failed(UUID organizationId, String username, String ip, IamStore.User user) {
        store.attempt(organizationId, username, ip, false);
        if (user != null) {
            var count = store.recentFailures(organizationId, username, Instant.now().minus(Duration.ofMinutes(15)));
            store.markLoginFailure(organizationId, user.id(), count >= 5);
            if (count >= 5) return new ApiException(ApiErrorCode.ACCOUNT_LOCKED);
        }
        return new ApiException(ApiErrorCode.AUTH_INVALID);
    }

    private String randomToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private MeView profile(IamStore.User user, IamStore.Organization organization) {
        var bindings = new LinkedHashMap<String, Binding>();
        if (user.roleId() != null) store.role(organization.id(), user.roleId()).ifPresent(role ->
                store.roleBindings(organization.id(), role.id()).forEach(binding -> bindings.put(binding.permissionCode(), binding)));
        store.userOverrides(organization.id(), user.id()).forEach(binding -> bindings.put(binding.permissionCode(), binding));
        return new MeView(user.id(), organization.id(), organization.name(), user.username(), user.displayName(),
                user.email(), user.departmentName(), user.roleId(), user.roleCode(), user.roleName(), user.status(),
                user.authVersion(), bindings.values().stream().filter(b -> "ALLOW".equals(b.effect())).map(Binding::permissionCode).toList());
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算会话摘要", exception);
        }
    }

    public record LoginCommand(String username, String password, boolean rememberMe) { }
    public record LoginResult(String token, UUID sessionId, MeView profile, boolean rememberMe) { }
    public record MeView(UUID userId, UUID organizationId, String organizationName,
                         String username, String displayName, String email, String departmentName, UUID roleId,
                         String roleCode, String roleName, String status, long authVersion,
                         List<String> permissions) { }
}
