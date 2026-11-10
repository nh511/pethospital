package com.pethospital.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public final class SessionJwtValidator implements OAuth2TokenValidator<Jwt> {
    public static final String EXPIRED_ERROR_CODE = "TOKEN_EXPIRED";
    private static final OAuth2Error INVALID = new OAuth2Error(
        "invalid_token",
        "The access token is not valid",
        null
    );
    private static final OAuth2Error EXPIRED = new OAuth2Error(
        EXPIRED_ERROR_CODE,
        "The access token has expired",
        null
    );

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final Clock clock;

    public SessionJwtValidator(ObjectProvider<DataSource> dataSourceProvider, Clock clock) {
        this.dataSourceProvider = dataSourceProvider;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant now = clock.instant();
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !expiresAt.plusSeconds(60).isAfter(now)) {
            return OAuth2TokenValidatorResult.failure(EXPIRED);
        }
        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null || issuedAt.isAfter(now.plusSeconds(60))) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }

        ParsedClaims claims = parseClaims(jwt);
        if (claims == null) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }

        try {
            SessionState state = new JdbcTemplate(dataSource).query(
                """
                    SELECT s.user_id,s.client_type,s.expires_at,s.revoked_at,
                           u.status,u.token_version
                    FROM auth_session s
                    JOIN sys_user u ON u.id=s.user_id
                    WHERE s.id=?
                    """,
                result -> result.next()
                    ? new SessionState(
                        result.getLong("user_id"),
                        result.getString("client_type"),
                        result.getTimestamp("expires_at"),
                        result.getTimestamp("revoked_at"),
                        result.getString("status"),
                        result.getInt("token_version")
                    )
                    : null,
                claims.sessionId()
            );
            if (state == null
                || state.userId() != claims.userId()
                || !state.clientType().equals(claims.audience())
                || state.revokedAt() != null
                || !state.expiresAt().toInstant().isAfter(now)
                || !"ACTIVE".equals(state.userStatus())
                || state.tokenVersion() != claims.tokenVersion()) {
                return OAuth2TokenValidatorResult.failure(INVALID);
            }
            return OAuth2TokenValidatorResult.success();
        } catch (DataAccessException exception) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
    }

    private static ParsedClaims parseClaims(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getClaimAsString("userId"));
            long sessionId = Long.parseLong(jwt.getClaimAsString("sessionId"));
            Number version = jwt.getClaim("tokenVersion");
            long versionValue = version == null ? -1 : version.longValue();
            List<String> audience = jwt.getAudience();
            Object rolesValue = jwt.getClaim("roles");
            if (userId <= 0
                || sessionId <= 0
                || versionValue < 0
                || versionValue > Integer.MAX_VALUE
                || version.doubleValue() != (double) versionValue
                || audience.size() != 1
                || !("MINI".equals(audience.getFirst()) || "WEB".equals(audience.getFirst()))
                || !(rolesValue instanceof Collection<?> roles)
                || roles.isEmpty()
                || roles.stream().anyMatch(role -> !(role instanceof String value) || value.isBlank())) {
                return null;
            }
            return new ParsedClaims(userId, sessionId, (int) versionValue, audience.getFirst());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private record ParsedClaims(long userId, long sessionId, int tokenVersion, String audience) {
    }

    private record SessionState(
        long userId,
        String clientType,
        Timestamp expiresAt,
        Timestamp revokedAt,
        String userStatus,
        int tokenVersion
    ) {
    }
}
