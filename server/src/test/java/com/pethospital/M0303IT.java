package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.identity.JwtKeyMaterial;
import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.shared.ApiResponse;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "identity.jwt.issuer=pet-hospital"
})
@AutoConfigureMockMvc
@Import(M0303IT.IdentityProbe.class)
class M0303IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final Instant SESSION_CREATED = Instant.now().minusSeconds(60);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Rs256AccessTokenIssuer tokenIssuer;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JwtKeyMaterial keyMaterial;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetIdentityData() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id > 0");
        jdbc.update("DELETE FROM sys_user WHERE id > 0");
        seedUser(2000, "mini-auth", "ACTIVE", 0, "USER");
        seedUser(2001, "web-auth", "ACTIVE", 0, "DOCTOR");
        seedUser(2002, "disabled-auth", "DISABLED", 0, "USER");
        seedUser(2003, "version-auth", "ACTIVE", 1, "USER");
        seedUser(2004, "expired-session", "ACTIVE", 0, "USER");
        seedSession(3000, 2000, "MINI", SESSION_CREATED.plusSeconds(604800), null);
        seedSession(3001, 2001, "WEB", SESSION_CREATED.plusSeconds(604800), null);
        seedSession(3002, 2002, "MINI", SESSION_CREATED.plusSeconds(604800), null);
        seedSession(3003, 2003, "MINI", SESSION_CREATED.plusSeconds(604800), null);
        seedSession(3004, 2004, "MINI", Instant.now().minusSeconds(1), null);
    }

    @Test
    void validTokensPopulateCurrentIdentityRolesAndAudienceAuthorities() throws Exception {
        String mini = issue(2000, 3000, 0, "MINI", List.of("USER"));
        authenticatedGet("/api/v1/me/auth-probe", mini)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value("2000"))
            .andExpect(jsonPath("$.data.sessionId").value("3000"))
            .andExpect(jsonPath("$.data.clientType").value("MINI"))
            .andExpect(jsonPath("$.data.roles", hasItems("USER")))
            .andExpect(jsonPath("$.data.authorities", hasItems("ROLE_USER", "AUD_MINI")));
        authenticatedGet("/api/v1/pets", mini)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("0"));

        String web = issue(2001, 3001, 0, "WEB", List.of("DOCTOR"));
        authenticatedGet("/api/v1/me/auth-probe", web)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.clientType").value("WEB"))
            .andExpect(jsonPath("$.data.authorities", hasItems("ROLE_DOCTOR", "AUD_WEB")));
        authenticatedGet("/api/v1/staff/dashboard", web)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void validTokenWithWrongEndpointAudienceIsForbidden() throws Exception {
        String mini = issue(2000, 3000, 0, "MINI", List.of("USER"));
        String web = issue(2001, 3001, 0, "WEB", List.of("DOCTOR"));

        authenticatedGet("/api/v1/staff/dashboard", mini)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        authenticatedGet("/api/v1/pets", web)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void expiredWrongAudienceIssuerAndForgedSignatureAreUnauthorized() throws Exception {
        String expired = tokenIssuer.issue(
            2000,
            3000,
            0,
            "MINI",
            List.of("USER"),
            Instant.now().minusSeconds(3600),
            60
        ).value();
        authenticatedGet("/api/v1/me/auth-probe", expired)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

        String sessionAudienceMismatch = issue(2000, 3000, 0, "WEB", List.of("USER"));
        authenticatedGet("/api/v1/me/auth-probe", sessionAudienceMismatch)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        authenticatedGet("/api/v1/me/auth-probe", encodeWith(jwtEncoder, keyMaterial.keyId(), "other-issuer"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        authenticatedGet("/api/v1/me/auth-probe", forgedToken())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void revokedDisabledExpiredSessionAndTokenVersionMismatchAreUnauthorized() throws Exception {
        String revoked = issue(2000, 3000, 0, "MINI", List.of("USER"));
        jdbc.update("UPDATE auth_session SET revoked_at=? WHERE id=3000", Timestamp.from(Instant.now()));
        authenticatedGet("/api/v1/me/auth-probe", revoked)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        authenticatedGet("/api/v1/me/auth-probe", issue(2002, 3002, 0, "MINI", List.of("USER")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        authenticatedGet("/api/v1/me/auth-probe", issue(2003, 3003, 0, "MINI", List.of("USER")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        authenticatedGet("/api/v1/me/auth-probe", issue(2004, 3004, 0, "MINI", List.of("USER")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void publicWhitelistIsExactAndAllAuthenticationErrorsUseEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/hospital"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("OK"));
        mockMvc.perform(post("/api/v1/auth/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/hospital/not-public"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
        mockMvc.perform(get("/api/v1/pets"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        authenticatedGet("/api/v1/me/auth-probe", "not-a-jwt")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private org.springframework.test.web.servlet.ResultActions authenticatedGet(String path, String token)
        throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String issue(long userId, long sessionId, int tokenVersion, String audience, List<String> roles) {
        return tokenIssuer.issue(
            userId,
            sessionId,
            tokenVersion,
            audience,
            roles,
            Instant.now(),
            1800
        ).value();
    }

    private String forgedToken() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey((RSAPrivateKey) pair.getPrivate())
            .keyID("forged-key")
            .algorithm(JWSAlgorithm.RS256)
            .build();
        JwtEncoder encoder = new NimbusJwtEncoder(
            new ImmutableJWKSet<SecurityContext>(new JWKSet(key))
        );
        return encodeWith(encoder, "forged-key", "pet-hospital");
    }

    private static String encodeWith(JwtEncoder encoder, String keyId, String issuer) {
        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").keyId(keyId).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("2000")
            .claim("userId", "2000")
            .claim("sessionId", "3000")
            .claim("tokenVersion", 0)
            .audience(List.of("MINI"))
            .claim("roles", List.of("USER"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(1800))
            .id("test-token")
            .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private void seedUser(long id, String username, String status, int tokenVersion, String roleCode) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,?,?,0)
            """, id, username, username, status, tokenVersion);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, roleCode);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)", id + 10_000, id, roleId);
    }

    private void seedSession(
        long id,
        long userId,
        String clientType,
        Instant expiresAt,
        Instant revokedAt
    ) {
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,revoked_at,
                last_seen_at,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?)
            """,
            id,
            userId,
            clientType,
            "%064d".formatted(id),
            "family-" + id,
            Timestamp.from(expiresAt),
            revokedAt == null ? null : Timestamp.from(revokedAt),
            Timestamp.from(SESSION_CREATED),
            Timestamp.from(SESSION_CREATED),
            Timestamp.from(SESSION_CREATED)
        );
    }

    @RestController
    static class IdentityProbe {
        private final CurrentIdentityProvider identities;

        IdentityProbe(CurrentIdentityProvider identities) {
            this.identities = identities;
        }

        @GetMapping("/api/v1/me/auth-probe")
        ApiResponse<?> currentIdentity() {
            return ApiResponse.ok(identities.require());
        }
    }
}
