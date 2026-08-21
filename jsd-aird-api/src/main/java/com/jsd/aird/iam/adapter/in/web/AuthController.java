package com.jsd.aird.iam.adapter.in.web;

import java.time.Duration;
import java.util.List;

import com.jsd.aird.iam.application.IamAuthService;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final IamAuthService auth;
    private final String cookieName;
    private final boolean secureCookie;
    private final String sameSite;

    public AuthController(IamAuthService auth,
                          @Value("${app.security.session-cookie-name:JSD_AIRD_SESSION}") String cookieName,
                          @Value("${app.security.cookie-secure:false}") boolean secureCookie,
                          @Value("${app.security.cookie-same-site:Lax}") String sameSite) {
        this.auth = auth;
        this.cookieName = cookieName;
        this.secureCookie = secureCookie;
        this.sameSite = sameSite;
    }

    @GetMapping("/csrf")
    public ApiResponse<CsrfView> csrf(CsrfToken token) {
        return ResponseFactory.success(new CsrfView(token == null ? null : token.getToken()), RequestIdHolder.currentOrUnknown());
    }

    @PostMapping("/login")
    public ApiResponse<IamAuthService.MeView> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest servletRequest,
                                                     HttpServletResponse response) {
        var result = auth.login(new IamAuthService.LoginCommand(request.username(), request.password(), request.rememberMe()),
                clientIp(servletRequest), servletRequest.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.token(), result.rememberMe()).toString());
        return ResponseFactory.success(result.profile(), RequestIdHolder.currentOrUnknown());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(cookie(request, cookieName));
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie().toString());
        return ResponseFactory.success(null, RequestIdHolder.currentOrUnknown());
    }

    @GetMapping("/me")
    public ApiResponse<IamAuthService.MeView> me() {
        return ResponseFactory.success(auth.me(ActorContext.required()), RequestIdHolder.currentOrUnknown());
    }

    @PostMapping("/password/change")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        auth.changePassword(ActorContext.required(), request.currentPassword(), request.newPassword());
        return ResponseFactory.success(null, RequestIdHolder.currentOrUnknown());
    }

    @GetMapping("/sessions")
    public ApiResponse<List<IamStore.SessionSummary>> sessions() {
        var actor = ActorContext.required();
        return ResponseFactory.success(auth.sessions(actor), RequestIdHolder.currentOrUnknown());
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> revokeSession(@PathVariable java.util.UUID sessionId) {
        auth.revokeSession(ActorContext.required(), sessionId);
        return ResponseFactory.success(null, RequestIdHolder.currentOrUnknown());
    }

    private ResponseCookie cookie(String token, boolean rememberMe) {
        var builder = ResponseCookie.from(cookieName, token).httpOnly(true).secure(secureCookie).sameSite(sameSite).path("/");
        if (rememberMe) builder.maxAge(Duration.ofDays(30));
        return builder.build();
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(cookieName, "").httpOnly(true).secure(secureCookie).sameSite(sameSite).path("/").maxAge(Duration.ZERO).build();
    }

    private String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }

    public record CsrfView(String token) { }

    /**
     * Login validates presence only. Password policy belongs to password
     * creation/change flows; applying it here would turn a bad password (or a
     * legacy short password) into a misleading request-validation response.
     */
    public record LoginRequest(@NotBlank String username,
                               @NotBlank String password, boolean rememberMe) { }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 6, max = 200) String newPassword) { }
}
