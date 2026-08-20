package com.jsd.aird.iam.infrastructure;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * This application authenticates through the database-backed opaque session
     * filter below. Providing an explicit service prevents Spring Boot from
     * creating and logging an unrelated generated in-memory password.
     */
    @Bean
    UserDetailsService unusedFormAuthenticationUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Local session authentication is enabled");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionAuthenticationFilter sessionFilter,
            PermissionRouteFilter permissionRouteFilter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource,
            @Value("${app.identity.development-mode:false}") boolean developmentMode
    ) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers(request -> developmentMode))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(content -> { })
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/actuator/health").permitAll();
                    if (developmentMode) auth.anyRequest().permitAll();
                    else auth.anyRequest().authenticated();
                })
                .addFilterBefore(sessionFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(permissionRouteFilter, SessionAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    SessionAuthenticationFilter sessionAuthenticationFilter(
            com.jsd.aird.iam.application.IamAuthService auth,
            @Value("${app.security.session-cookie-name:JSD_AIRD_SESSION}") String cookieName
    ) {
        return new SessionAuthenticationFilter(auth, cookieName);
    }

    @Bean
    PermissionRouteFilter permissionRouteFilter(
            com.jsd.aird.iam.api.AuthorizationService authorization,
            ObjectMapper objectMapper,
            @Value("${app.identity.development-mode:false}") boolean developmentMode
    ) {
        return new PermissionRouteFilter(authorization, objectMapper, developmentMode);
    }

    @Bean
    FilterRegistrationBean<Filter> sessionAuthenticationFilterRegistration(SessionAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<Filter>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<Filter> permissionRouteFilterRegistration(PermissionRouteFilter filter) {
        var registration = new FilterRegistrationBean<Filter>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> writeError(objectMapper, request, response, ApiErrorCode.AUTH_REQUIRED);
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, exception) -> writeError(objectMapper, request, response, ApiErrorCode.PERMISSION_DENIED);
    }

    private static void writeError(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                                   ApiErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), ResponseFactory.error(errorCode, RequestIdHolder.currentOrUnknown()));
    }
}
