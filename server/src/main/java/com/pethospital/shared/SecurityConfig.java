package com.pethospital.shared;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.AdminPermissionEvaluator;
import com.pethospital.identity.BackendPermissionCatalog;
import com.pethospital.identity.SessionJwtValidator;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
@Configuration
public class SecurityConfig {
    private static final String[] WEB_PATHS = {"/api/v1/staff/**", "/api/v1/admin/**"};
    private static final String[] MINI_PATHS = {
        "/api/v1/doctors",
        "/api/v1/doctors/**",
        "/api/v1/health/**",
        "/api/v1/pets/**",
        "/api/v1/appointments/**",
        "/api/v1/ai/**"
    };

    @Bean
    SecurityFilterChain filterChain(
        HttpSecurity http,
        ObjectMapper mapper,
        JwtAuthenticationConverter authenticationConverter,
        AdminPermissionEvaluator permissions
    ) throws Exception {
        return http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                "/api/v1/auth/user/register",
                "/api/v1/auth/user/login",
                "/api/v1/auth/wechat/login",
                "/api/v1/auth/web/login",
                "/api/v1/me",
                "/api/v1/me/consents",
                "/api/v1/files/**",
                "/api/v1/appointments",
                "/api/v1/appointments/*/cancel",
                "/api/v1/appointments/*/change-requests",
                "/api/v1/change-requests/*/accept",
                "/api/v1/change-requests/*/reject",
                "/api/v1/change-requests/*/withdraw",
                "/api/v1/notices/**",
                "/api/v1/admin/**",
                "/api/v1/staff/**"
            ))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/hospital",
                    "/api/v1/policies/current",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**"
                ).permitAll()
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/auth/user/register",
                    "/api/v1/auth/user/login",
                    "/api/v1/auth/wechat/login",
                    "/api/v1/auth/web/login"
                ).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/users", "/api/v1/admin/users/*")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.ADMIN_USERS_VIEW))
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/users")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.ADMIN_USERS_MANAGE))
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/admin/users/*/disable",
                    "/api/v1/admin/users/*/enable",
                    "/api/v1/admin/users/*/reset-password"
                ).access((authentication, context) -> permissions.decide(
                    authentication.get(), BackendPermissionCatalog.ADMIN_USERS_MANAGE))
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/audit-logs")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.AUDIT_READ))
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/pets/*/health-logs")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.AUDIT_READ))
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/pets")
                    .hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/pets/*")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.ADMIN_PETS_CORRECT))
                .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/pets/*")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.ADMIN_PETS_CORRECT))
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/ai/call-logs")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.AI_CALL_LOG_READ))
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/doctors")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.ADMIN_DOCTORS_VIEW))
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/doctors")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.ADMIN_DOCTORS_MANAGE))
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/admin/doctors/*/disable",
                    "/api/v1/admin/doctors/*/enable"
                ).access((authentication, context) -> permissions.decide(
                    authentication.get(), BackendPermissionCatalog.ADMIN_DOCTORS_MANAGE))
                .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/doctors/*")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.ADMIN_DOCTORS_MANAGE))
                .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/users/*")
                    .access((authentication, context) -> permissions.decide(
                        authentication.get(), BackendPermissionCatalog.ADMIN_USERS_MANAGE))
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/admin/users/*/roles",
                    "/api/v1/admin/roles/**",
                    "/api/v1/admin/menus/**"
                ).access((authentication, context) -> permissions.decide(
                    authentication.get(), BackendPermissionCatalog.ADMIN_RBAC_VIEW))
                .requestMatchers(
                    "/api/v1/admin/users/*/roles",
                    "/api/v1/admin/roles/**",
                    "/api/v1/admin/menus/**"
                ).access((authentication, context) -> permissions.decide(
                    authentication.get(), BackendPermissionCatalog.ADMIN_RBAC_MANAGE))
                .requestMatchers(HttpMethod.GET, "/api/v1/me/menus").hasAuthority("AUD_WEB")
                .requestMatchers(HttpMethod.POST, "/api/v1/staff/health/guides").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/staff/health/guides/*/publish",
                    "/api/v1/staff/health/guides/*/offline"
                ).hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/staff/health/guides/*").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/staff/health/standards").hasRole("DOCTOR")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/staff/health/standards/*").hasRole("DOCTOR")
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/staff/health/standards/*/publish",
                    "/api/v1/staff/health/standards/*/offline"
                ).hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/staff/triage-rules").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/staff/triage-rules/*/submit-review").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/staff/triage-rules/*/review").hasRole("DOCTOR")
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/staff/triage-rules/*/enable",
                    "/api/v1/staff/triage-rules/*/disable"
                ).hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/staff/triage-rules/test")
                .hasAnyRole("ADMIN", "DOCTOR")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/staff/triage-rules/*").hasRole("ADMIN")
                .requestMatchers("/api/v1/staff/doctor-profile").hasRole("DOCTOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/change-requests/*/accept",
                    "/api/v1/change-requests/*/reject", "/api/v1/change-requests/*/withdraw")
                    .hasAnyAuthority("AUD_MINI", "AUD_WEB")
                .requestMatchers(HttpMethod.GET, "/api/v1/medical-records/*", "/api/v1/medical-records/*/versions")
                    .hasAnyAuthority("AUD_MINI", "AUD_WEB")
                .requestMatchers(WEB_PATHS).hasAuthority("AUD_WEB")
                .requestMatchers(MINI_PATHS).hasAuthority("AUD_MINI")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter))
                .authenticationEntryPoint((request, response, exception) ->
                    writeAuthenticationError(response, mapper, exception))
            )
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    writeAuthenticationError(response, mapper, exception))
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.setHeader("Cache-Control", "no-store");
                    mapper.writeValue(
                        response.getOutputStream(),
                        ApiResponse.error("FORBIDDEN", "无权访问", null)
                    );
                })
            )
            .build();
    }

    private static void writeAuthenticationError(
        HttpServletResponse response,
        ObjectMapper mapper,
        AuthenticationException exception
    ) throws IOException {
        boolean expired = containsExpiredError(exception);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(
            response.getOutputStream(),
            ApiResponse.error(
                expired ? "TOKEN_EXPIRED" : "UNAUTHENTICATED",
                expired ? "登录已过期，请重新登录" : "请先登录",
                null
            )
        );
    }

    private static boolean containsExpiredError(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof JwtValidationException validationException
                && validationException.getErrors().stream()
                    .anyMatch(error -> SessionJwtValidator.EXPIRED_ERROR_CODE.equals(error.getErrorCode()))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

