package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.AiConsentEvaluator;
import com.pethospital.identity.ConsentPurpose;
import com.pethospital.identity.PolicyCatalog;
import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.shared.BusinessException;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "identity.jwt.issuer=pet-hospital",
    "identity.profile.phone-key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "identity.policies.privacy.version=privacy-2026-10",
    "identity.policies.privacy.text=测试隐私政策正文",
    "identity.policies.ai-service.version=ai-2026-10",
    "identity.policies.ai-service.text=测试AI服务政策正文"
})
@AutoConfigureMockMvc
class M0309IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long USER_ID = 9100;
    private static final long SESSION_ID = 9200;
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
    PolicyCatalog policies;

    @Autowired
    AiConsentEvaluator aiConsent;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id > 0");
        jdbc.update("DELETE FROM sys_user WHERE id > 0");

        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, USER_ID, "consent-owner", "授权用户");
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='USER'", Long.class);
        jdbc.update(
            "INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
            USER_ID + 10_000,
            USER_ID,
            roleId
        );
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,
                created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """,
            SESSION_ID,
            USER_ID,
            "MINI",
            "%064d".formatted(SESSION_ID),
            "family-" + SESSION_ID,
            Timestamp.from(SESSION_CREATED.plusSeconds(604800)),
            Timestamp.from(SESSION_CREATED),
            Timestamp.from(SESSION_CREATED),
            Timestamp.from(SESSION_CREATED)
        );
    }

    @Test
    void currentPoliciesArePublicAndExposeServerCalculatedHashes() throws Exception {
        mockMvc.perform(get("/api/v1/policies/current"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].purpose").value("PRIVACY"))
            .andExpect(jsonPath("$.data.items[0].policyVersion").value("privacy-2026-10"))
            .andExpect(jsonPath("$.data.items[0].noticeHash")
                .value(policies.current(ConsentPurpose.PRIVACY).noticeHash()))
            .andExpect(jsonPath("$.data.items[1].purpose").value("AI_SERVICE"))
            .andExpect(jsonPath("$.data.items[1].noticeHash")
                .value(policies.current(ConsentPurpose.AI_SERVICE).noticeHash()));
        assertThat(policies.current(ConsentPurpose.PRIVACY).noticeHash())
            .matches("[0-9a-f]{64}");
        assertThat(policies.current(ConsentPurpose.AI_SERVICE).noticeHash())
            .matches("[0-9a-f]{64}");
    }

    @Test
    void registrationRejectsOutdatedPrivacyVersionAndRecordsCurrentPolicyInSameTransaction()
        throws Exception {
        mockMvc.perform(post("/api/v1/auth/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"old-policy-owner",
                      "password":"Registration-Pass-42",
                      "displayName":"旧政策用户",
                      "privacyPolicyVersion":"privacy-2025-01"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("POLICY_VERSION_OUTDATED"));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE username='old-policy-owner'",
            Integer.class
        )).isZero();

        String currentVersion = policies.current(ConsentPurpose.PRIVACY).policyVersion();
        String currentHash = policies.current(ConsentPurpose.PRIVACY).noticeHash();
        mockMvc.perform(post("/api/v1/auth/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username":"current-policy-owner",
                      "password":"Registration-Pass-42",
                      "displayName":"当前政策用户",
                      "privacyPolicyVersion":"%s"
                    }
                    """.formatted(currentVersion)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.username").value("current-policy-owner"));

        Long registeredUserId = jdbc.queryForObject(
            "SELECT id FROM sys_user WHERE username='current-policy-owner'",
            Long.class
        );
        var event = jdbc.queryForMap("""
            SELECT purpose,policy_version,action,notice_hash,idempotency_key
            FROM consent_record WHERE user_id=?
            """, registeredUserId);
        assertThat(event)
            .containsEntry("purpose", "PRIVACY")
            .containsEntry("policy_version", currentVersion)
            .containsEntry("action", "ACCEPT")
            .containsEntry("notice_hash", currentHash);
        assertThat(event.get("idempotency_key").toString()).startsWith("register-privacy:");
    }

    @Test
    void consentEndpointsRequireLoginAndStrictRequestShape() throws Exception {
        mockMvc.perform(get("/api/v1/me/consents"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/me/consents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken())
                .header("Idempotency-Key", "strict-consent-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(command(ConsentPurpose.AI_SERVICE, "ACCEPT").replace(
                    "\"action\":\"ACCEPT\"",
                    "\"action\":\"ACCEPT\",\"unexpected\":true"
                )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(consentCount()).isZero();
    }

    @Test
    void acceptIsEffectiveAndSameIdempotencyKeyDoesNotDuplicateEvent() throws Exception {
        authenticatedGetConsents()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[1].purpose").value("AI_SERVICE"))
            .andExpect(jsonPath("$.data.items[1].action").doesNotExist())
            .andExpect(jsonPath("$.data.items[1].effective").value(false));

        String body = command(ConsentPurpose.AI_SERVICE, "ACCEPT");
        consentPost("accept-ai-consent-01", body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.action").value("ACCEPT"))
            .andExpect(jsonPath("$.data.effective").value(true));
        assertThat(aiConsent.hasValidConsent(USER_ID)).isTrue();
        aiConsent.requireValidConsent(USER_ID);

        consentPost("accept-ai-consent-01", body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.effective").value(true));
        assertThat(consentCount()).isEqualTo(1);

        consentPost(
            "accept-ai-consent-01",
            command(ConsentPurpose.AI_SERVICE, "WITHDRAW")
        )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(consentCount()).isEqualTo(1);
    }

    @Test
    void outdatedVersionAndWrongHashAreRejectedBeforeInsert() throws Exception {
        String currentHash = policies.current(ConsentPurpose.AI_SERVICE).noticeHash();
        consentPost("old-policy-vers-01", """
            {"purpose":"AI_SERVICE","policyVersion":"ai-2025-01","noticeHash":"%s","action":"ACCEPT"}
            """.formatted(currentHash))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("POLICY_VERSION_OUTDATED"));

        String currentVersion = policies.current(ConsentPurpose.AI_SERVICE).policyVersion();
        consentPost("wrong-policy-hash1", """
            {"purpose":"AI_SERVICE","policyVersion":"%s","noticeHash":"%s","action":"ACCEPT"}
            """.formatted(currentVersion, "a".repeat(64)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("POLICY_HASH_MISMATCH"));
        assertThat(consentCount()).isZero();
        assertThat(aiConsent.hasValidConsent(USER_ID)).isFalse();
    }

    @Test
    void withdrawalBlocksOnlyFutureAiGateAndLeavesNormalFeaturesAvailable() throws Exception {
        consentPost("accept-before-draw1", command(ConsentPurpose.AI_SERVICE, "ACCEPT"))
            .andExpect(status().isOk());
        consentPost("withdraw-ai-00001", command(ConsentPurpose.AI_SERVICE, "WITHDRAW"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.action").value("WITHDRAW"))
            .andExpect(jsonPath("$.data.effective").value(false));

        assertThat(consentCount()).isEqualTo(2);
        assertThat(aiConsent.hasValidConsent(USER_ID)).isFalse();
        assertThatThrownBy(() -> aiConsent.requireValidConsent(USER_ID))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.status.value()).isEqualTo(403);
                assertThat(ex.code).isEqualTo("CONSENT_REQUIRED");
            });

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(Long.toString(USER_ID)));
        authenticatedGetConsents()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[1].action").value("WITHDRAW"))
            .andExpect(jsonPath("$.data.items[1].effective").value(false));
    }

    private org.springframework.test.web.servlet.ResultActions authenticatedGetConsents()
        throws Exception {
        return mockMvc.perform(get("/api/v1/me/consents")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken()));
    }

    private org.springframework.test.web.servlet.ResultActions consentPost(String key, String body)
        throws Exception {
        return mockMvc.perform(post("/api/v1/me/consents")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private String command(ConsentPurpose purpose, String action) {
        var policy = policies.current(purpose);
        return """
            {"purpose":"%s","policyVersion":"%s","noticeHash":"%s","action":"%s"}
            """.formatted(purpose, policy.policyVersion(), policy.noticeHash(), action);
    }

    private String ownerToken() {
        return tokenIssuer.issue(
            USER_ID,
            SESSION_ID,
            0,
            "MINI",
            List.of("USER"),
            Instant.now(),
            1800
        ).value();
    }

    private int consentCount() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM consent_record WHERE user_id=?",
            Integer.class,
            USER_ID
        );
    }
}
