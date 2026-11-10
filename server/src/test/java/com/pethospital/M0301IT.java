package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "identity.auth.access-token-seconds=1800",
    "identity.auth.refresh-token-seconds=604800"
})
@AutoConfigureMockMvc
class M0301IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final Pattern WEB_REFRESH = Pattern.compile("__Host-pet_refresh=([^;]+)");
    private static final String PASSWORD = "M0301-fixture-only-01!";

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
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtDecoder jwtDecoder;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetAccounts() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_session WHERE user_id > 0");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id > 0");
        jdbc.update("DELETE FROM sys_user WHERE id > 0");
        seedUser(1000, "mini-user", "ACTIVE", "USER");
        seedUser(1001, "disabled-user", "DISABLED", "USER");
        seedUser(1002, "doctor-user", "ACTIVE", "DOCTOR");
        seedUser(1003, "admin-user", "ACTIVE", "ADMIN");
    }

    @Test
    void registersOnlyUserRoleWithBcryptAndRejectsRoleInjectionAndDuplicateUsername() throws Exception {
        String body = """
            {
              "username":"New.Owner",
              "password":"Safe-Password-42",
              "displayName":"新用户",
              "privacyPolicyVersion":"2026-10"
            }
            """;
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.username").value("new.owner"))
            .andExpect(jsonPath("$.data.roles[0]").value("USER"))
            .andReturn();
        assertThat(registered.getResponse().getContentAsString()).doesNotContain("password", "hash");

        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='new.owner'", Long.class);
        String hash = jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, userId);
        assertThat(hash).startsWith("$2").isNotEqualTo("Safe-Password-42");
        assertThat(passwordEncoder.matches("Safe-Password-42", hash)).isTrue();
        assertThat(jdbc.queryForList("""
            SELECT r.code FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=?
            """, String.class, userId)).containsExactly("USER");

        mockMvc.perform(post("/api/v1/auth/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));

        mockMvc.perform(post("/api/v1/auth/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"role-attacker",
                      "password":"Safe-Password-42",
                      "displayName":"越权注册",
                      "privacyPolicyVersion":"2026-10",
                      "role":"ADMIN"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(count("SELECT COUNT(*) FROM sys_user WHERE username='role-attacker'")) .isZero();
    }

    @Test
    void miniLoginCreatesHashedSessionSignedJwtAndAuthLoginAudit() throws Exception {
        MvcResult result = login("/api/v1/auth/user/login", "mini-user", PASSWORD)
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.data.refreshToken").isString())
            .andExpect(jsonPath("$.data.expiresIn").value(1800))
            .andExpect(jsonPath("$.data.user.roles[0]").value("USER"))
            .andReturn();
        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain("password_hash", "$2a$", "$2b$");

        JsonNode data = objectMapper.readTree(response).path("data");
        String sessionId = data.path("sessionId").asText();
        String refreshToken = data.path("refreshToken").asText();
        String storedHash = jdbc.queryForObject(
            "SELECT refresh_token_hash FROM auth_session WHERE id=? AND client_type='MINI'",
            String.class,
            Long.parseLong(sessionId)
        );
        assertThat(storedHash).hasSize(64).isEqualTo(sha256Hex(refreshToken)).doesNotContain(refreshToken);

        Jwt claims = jwtDecoder.decode(data.path("accessToken").asText());
        assertThat(claims.getSubject()).isEqualTo("1000");
        assertThat(claims.getClaimAsString("sessionId")).isEqualTo(sessionId);
        assertThat(((Number) claims.getClaim("tokenVersion")).intValue()).isZero();
        assertThat(claims.getAudience()).containsExactly("MINI");
        assertThat(claims.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(Duration.between(claims.getIssuedAt(), claims.getExpiresAt()).toSeconds()).isEqualTo(1800);

        assertThat(count("""
            SELECT COUNT(*) FROM audit_log
            WHERE actor_user_id=1000 AND action='AUTH_LOGIN' AND resource_type='AUTH_SESSION'
              AND resource_id=%s AND result='SUCCESS'
            """.formatted(sessionId))).isOne();
    }

    @Test
    void webLoginAllowsDoctorAndAdminUsesSecureCookieAndOmitsRefreshFromJson() throws Exception {
        MvcResult doctor = login("/api/v1/auth/web/login", "doctor-user", PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andExpect(jsonPath("$.data.user.roles[0]").value("DOCTOR"))
            .andReturn();
        assertWebCookieMatchesStoredHash(doctor);
        Jwt doctorClaims = jwtDecoder.decode(
            objectMapper.readTree(doctor.getResponse().getContentAsString()).path("data").path("accessToken").asText()
        );
        assertThat(doctorClaims.getAudience()).containsExactly("WEB");

        MvcResult admin = login("/api/v1/auth/web/login", "admin-user", PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.user.roles[0]").value("ADMIN"))
            .andReturn();
        assertWebCookieMatchesStoredHash(admin);

        login("/api/v1/auth/web/login", "mini-user", PASSWORD)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
        login("/api/v1/auth/user/login", "doctor-user", PASSWORD)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
        assertThat(count("SELECT COUNT(*) FROM auth_session")).isEqualTo(2);
    }

    @Test
    void rejectsWrongPasswordUnknownAndDisabledAccountsWithoutCreatingSessionsOrSuccessAudit() throws Exception {
        login("/api/v1/auth/user/login", "mini-user", "wrong-password")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("/api/v1/auth/user/login", "missing-user", "wrong-password")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("/api/v1/auth/user/login", "disabled-user", PASSWORD)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        assertThat(count("SELECT COUNT(*) FROM auth_session")).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='AUTH_LOGIN' AND result='SUCCESS'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='AUTH_LOGIN_FAILURE' AND result='FAILURE'"))
            .isEqualTo(3);
        List<String> failurePayloads = jdbc.queryForList("""
            SELECT CAST(after_json AS CHAR) FROM audit_log
            WHERE action='AUTH_LOGIN_FAILURE' ORDER BY id
            """, String.class);
        assertThat(failurePayloads)
            .allMatch(payload -> !payload.contains("mini-user")
                && !payload.contains("missing-user")
                && !payload.contains("wrong-password")
                && !payload.contains(PASSWORD));
    }

    @Test
    void rateLimitsRepeatedPasswordFailuresBeforeIssuingAnySession() throws Exception {
        seedUser(1004, "rate-user", "ACTIVE", "USER");

        for (int attempt = 0; attempt < 5; attempt++) {
            login("/api/v1/auth/user/login", "rate-user", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        login("/api/v1/auth/user/login", "rate-user", "wrong-password")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        login("/api/v1/auth/user/login", "rate-user", PASSWORD)
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        assertThat(count("SELECT COUNT(*) FROM auth_session")).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='AUTH_LOGIN_FAILURE'"))
            .isEqualTo(5);
    }

    @Test
    void migrationSeedsExactlyTheThreeActiveBuiltInRoles() {
        assertThat(jdbc.queryForList("""
            SELECT code FROM sys_role WHERE code IN ('USER','DOCTOR','ADMIN') AND status='ACTIVE' ORDER BY code
            """, String.class)).containsExactly("ADMIN", "DOCTOR", "USER");
    }

    private org.springframework.test.web.servlet.ResultActions login(
        String path,
        String username,
        String password
    ) throws Exception {
        return mockMvc.perform(post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new LoginBody(username, password))));
    }

    private void assertWebCookieMatchesStoredHash(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull()
            .contains("__Host-pet_refresh=", "Path=/", "Secure", "HttpOnly", "SameSite=Strict");
        Matcher matcher = WEB_REFRESH.matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        String rawRefresh = matcher.group(1);
        String sessionId;
        try {
            sessionId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("sessionId").asText();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        String storedHash = jdbc.queryForObject(
            "SELECT refresh_token_hash FROM auth_session WHERE id=? AND client_type='WEB'",
            String.class,
            Long.parseLong(sessionId)
        );
        assertThat(storedHash).isEqualTo(sha256Hex(rawRefresh)).doesNotContain(rawRefresh);
    }

    private void seedUser(long id, String username, String status, String roleCode) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,?,?,?,0,0)
            """, id, username, passwordEncoder.encode(PASSWORD), username, status);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, roleCode);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)", id + 10_000, id, roleId);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record LoginBody(String username, String password) {
    }
}
