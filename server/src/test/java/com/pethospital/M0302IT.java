package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.WechatOpenIdProtector;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.util.Arrays;
import java.util.Base64;
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
    "identity.wechat.mode=stub"
})
@AutoConfigureMockMvc
class M0302IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String STUB_CODE = "wx-temporary-code";
    private static final String STUB_OPEN_ID = "openid-integration-001";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
        registry.add("identity.wechat.stub.code", () -> STUB_CODE);
        registry.add("identity.wechat.stub.openid", () -> STUB_OPEN_ID);
        registry.add("identity.wechat.openid-hmac-key-base64", () -> key((byte) 0x51));
        registry.add("identity.wechat.openid-encryption-key-base64", () -> key((byte) 0x62));
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    WechatOpenIdProtector openIdProtector;

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
    }

    @Test
    void firstWechatLoginCreatesProtectedUserRoleConsentAndMiniSession() throws Exception {
        MvcResult result = login(STUB_CODE, "2026-10")
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.data.user.username").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.user.roles[0]").value("USER"))
            .andExpect(jsonPath("$.data.refreshToken").isString())
            .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        long userId = data.path("user").path("id").asLong();
        assertThat(userId).isPositive();
        String storedHash = jdbc.queryForObject(
            "SELECT wechat_openid_hash FROM sys_user WHERE id=?",
            String.class,
            userId
        );
        byte[] storedCipher = jdbc.queryForObject(
            "SELECT wechat_openid_cipher FROM sys_user WHERE id=?",
            byte[].class,
            userId
        );
        assertThat(storedHash).isEqualTo(openIdProtector.hash(STUB_OPEN_ID)).doesNotContain(STUB_OPEN_ID);
        assertThat(openIdProtector.decrypt(storedCipher)).isEqualTo(STUB_OPEN_ID);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(STUB_OPEN_ID, storedHash);
        assertThat(count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id "
            + "WHERE ur.user_id=" + userId + " AND r.code='USER'")) .isOne();
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE user_id=" + userId
            + " AND purpose='PRIVACY' AND action='ACCEPT'")) .isOne();
        Jwt jwt = jwtDecoder.decode(data.path("accessToken").asText());
        assertThat(jwt.getSubject()).isEqualTo(Long.toString(userId));
        assertThat(jwt.getAudience()).containsExactly("MINI");
    }

    @Test
    void repeatedSameOpenIdReusesExactlyOneUserAndCreatesIndependentSessions() throws Exception {
        String firstUserId = userId(login(STUB_CODE, "2026-10").andExpect(status().isOk()).andReturn());
        String secondUserId = userId(login(STUB_CODE, "2026-10").andExpect(status().isOk()).andReturn());

        assertThat(secondUserId).isEqualTo(firstUserId);
        assertThat(count("SELECT COUNT(*) FROM sys_user WHERE wechat_openid_hash='"
            + openIdProtector.hash(STUB_OPEN_ID) + "'")) .isOne();
        assertThat(count("SELECT COUNT(*) FROM auth_session WHERE user_id=" + firstUserId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE user_id=" + firstUserId)).isOne();
    }

    @Test
    void rejectsForgedOpenIdFieldAndInvalidCodeWithoutCreatingIdentity() throws Exception {
        mockMvc.perform(post("/api/v1/auth/wechat/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"wx-temporary-code","privacyPolicyVersion":"2026-10","openId":"forged"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        login("forged-code", "2026-10")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("WECHAT_CODE_INVALID"));
        assertThat(count("SELECT COUNT(*) FROM sys_user")).isZero();
    }

    @Test
    void rejectsOutdatedPrivacyPolicyBeforeCallingIdentityAdapter() throws Exception {
        login(STUB_CODE, "outdated")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("POLICY_VERSION_OUTDATED"));
        assertThat(count("SELECT COUNT(*) FROM sys_user")).isZero();
    }

    @Test
    void rejectsDisabledWechatAccountWithoutIssuingAnotherSession() throws Exception {
        String userId = userId(login(STUB_CODE, "2026-10").andExpect(status().isOk()).andReturn());
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_session WHERE user_id=?", Long.parseLong(userId));
        jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", Long.parseLong(userId));

        login(STUB_CODE, "2026-10")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        assertThat(count("SELECT COUNT(*) FROM auth_session WHERE user_id=" + userId)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions login(String code, String policyVersion) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/wechat/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new WechatBody(code, policyVersion))));
    }

    private String userId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("user").path("id").asText();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static String key(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private record WechatBody(String code, String privacyPolicyVersion) {
    }
}
