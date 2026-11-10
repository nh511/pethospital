package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pethospital.identity.Rs256AccessTokenIssuer;
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
    "identity.jwt.allow-ephemeral=true"
})
@AutoConfigureMockMvc
class M1004IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 100_401;
    private static final long DOCTOR = 100_402;
    private static final long USER = 100_403;
    private static final long DOCTOR_PROFILE = 110_402;
    private static final long ADMIN_SESSION = 101_401;
    private static final long DOCTOR_SESSION = 101_402;
    private static final long USER_SESSION = 101_403;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired Rs256AccessTokenIssuer tokenIssuer;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM triage_event");
        jdbc.update("DELETE FROM triage_rule");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=100400");
        jdbc.update("DELETE FROM doctor_profile WHERE user_id>=100400");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=100400");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=100400");
        jdbc.update("DELETE FROM sys_user WHERE id>=100400");
        seedUser(ADMIN, "triage-enable-admin", "ADMIN");
        seedUser(DOCTOR, "triage-enable-doctor", "DOCTOR");
        seedUser(USER, "triage-enable-user", "USER");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,?,'ACTIVE',0)
            """, DOCTOR_PROFILE, DOCTOR, "规则预览医生");
        seedSession(ADMIN_SESSION, ADMIN, "WEB");
        seedSession(DOCTOR_SESSION, DOCTOR, "WEB");
        seedSession(USER_SESSION, USER, "MINI");
    }

    @Test
    void enablingApprovedRevisionAtomicallyDisablesOldRevisionAndReplays() throws Exception {
        long oldRule = 120_401;
        long newRule = 120_402;
        insertRule(oldRule, "ATOMIC_RULE", 1, "CAT", "APPROVED", true, 0, "SEIZURE");
        insertRule(newRule, "ATOMIC_RULE", 2, "CAT", "APPROVED", false, 0, "SEIZURE");

        enable(newRule, 0, "m1004-enable-atomic1", adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.version").value(1));
        enable(newRule, 0, "m1004-enable-atomic1", adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.version").value(1));

        assertThat(count("SELECT COUNT(*) FROM triage_rule WHERE rule_code='ATOMIC_RULE' AND enabled=1"))
            .isOne();
        assertThat(jdbc.queryForObject("SELECT enabled FROM triage_rule WHERE id=?", Boolean.class, oldRule))
            .isFalse();
        assertThat(jdbc.queryForObject("SELECT version FROM triage_rule WHERE id=?", Integer.class, oldRule))
            .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='TRIAGE_RULE_ENABLE'"
            + " OR action='TRIAGE_RULE_AUTO_DISABLE'")).isEqualTo(2);
    }

    @Test
    void onlyApprovedRuleCanBeEnabledByAdminWithCurrentVersion() throws Exception {
        insertRule(120_411, "DRAFT_RULE", 1, "CAT", "DRAFT", false, 0, "SEIZURE");
        insertRule(120_412, "REJECTED_RULE", 1, "CAT", "REJECTED", false, 0, "SEIZURE");
        insertRule(120_413, "STALE_RULE", 1, "CAT", "APPROVED", false, 2, "SEIZURE");

        enable(120_411, 0, "m1004-enable-draft01", adminBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRIAGE_RULE_NOT_APPROVED"));
        enable(120_412, 0, "m1004-enable-reject1", adminBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRIAGE_RULE_NOT_APPROVED"));
        enable(120_413, 1, "m1004-enable-stale01", adminBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        enable(120_413, 2, "m1004-enable-doctor1", doctorBearer())
            .andExpect(status().isForbidden());
        assertThat(count("SELECT COUNT(*) FROM triage_rule WHERE enabled=1")).isZero();
    }

    @Test
    void disableRequiresEnabledRuleReasonVersionAndIsIdempotent() throws Exception {
        long ruleId = 120_421;
        insertRule(ruleId, "DISABLE_RULE", 1, "CAT", "APPROVED", true, 3, "SEIZURE");

        disable(ruleId, 3, "规则资料待更新", "m1004-disable-rule01", adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.version").value(4));
        disable(ruleId, 3, "规则资料待更新", "m1004-disable-rule01", adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(4));
        disable(ruleId, 4, "重复停用", "m1004-disable-rule02", adminBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRIAGE_RULE_ENABLED_STATE_CONFLICT"));

        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='TRIAGE_RULE_DISABLE'"
            + " AND reason='规则资料待更新'")).isOne();
        mockMvc.perform(post("/api/v1/staff/triage-rules/{ruleId}/disable", ruleId)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .header("Idempotency-Key", "m1004-disable-no-reason")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":4}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void previewEvaluatesOnlyEnabledApprovedSpeciesRulesWithoutPersistence() throws Exception {
        insertRule(120_431, "ACTIVE_CAT", 1, "CAT", "APPROVED", true, 0, "SEIZURE");
        insertRule(120_432, "DISABLED_CAT", 1, "CAT", "APPROVED", false, 0, "UNCONTROLLED_BLEEDING");
        insertRule(120_433, "ACTIVE_DOG", 1, "DOG", "APPROVED", true, 0, "SEIZURE");
        long auditBefore = count("SELECT COUNT(*) FROM audit_log");

        preview("CAT", "猫咪现在正在抽搐", adminBearer(), "m1004-preview-active1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.evaluatedCount").value(1))
            .andExpect(jsonPath("$.data.matchedCount").value(1))
            .andExpect(jsonPath("$.data.results[0].ruleCode").value("ACTIVE_CAT"))
            .andExpect(jsonPath("$.data.results[0].outcome").value("MATCHED"))
            .andExpect(jsonPath("$.data.results[0].evidence[0].code").value("SEIZURE"))
            .andExpect(jsonPath("$.data.results[0].evidence[0].observedState").value("PRESENT"));

        assertThat(count("SELECT COUNT(*) FROM triage_event")).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log")).isEqualTo(auditBefore);
    }

    @Test
    void previewPreservesNegatedHypotheticalHistoricalAndUnknownContexts() throws Exception {
        insertRule(120_441, "CONTEXT_RULE", 1, "CAT", "APPROVED", true, 0, "SEIZURE");

        preview("CAT", "猫咪今天没有抽搐", doctorBearer(), "m1004-preview-absent1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results[0].outcome").value("NOT_MATCHED"))
            .andExpect(jsonPath("$.data.results[0].evidence[0].observedState").value("ABSENT"));
        preview("CAT", "如果猫咪抽搐应该怎么办", doctorBearer(), "m1004-preview-hypo001")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results[0].outcome").value("NOT_MATCHED"))
            .andExpect(jsonPath("$.data.results[0].evidence[0].observedState").value("HYPOTHETICAL"));
        preview("CAT", "之前抽搐但现在已经好了", doctorBearer(), "m1004-preview-history1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results[0].outcome").value("NOT_MATCHED"))
            .andExpect(jsonPath("$.data.results[0].evidence[0].observedState").value("HISTORICAL_RESOLVED"));
        preview("CAT", "精神状态不太好，描述不清", doctorBearer(), "m1004-preview-unknown1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results[0].outcome").value("UNKNOWN"))
            .andExpect(jsonPath("$.data.matchedCount").value(0));
        preview("CAT", "正在抽搐", userBearer(), "m1004-preview-user001")
            .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions enable(
        long ruleId,
        int version,
        String key,
        String bearer
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/triage-rules/{ruleId}/enable", ruleId)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions disable(
        long ruleId,
        int version,
        String reason,
        String key,
        String bearer
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("reason", reason);
        body.put("version", version);
        return mockMvc.perform(post("/api/v1/staff/triage-rules/{ruleId}/disable", ruleId)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions preview(
        String species,
        String content,
        String bearer,
        String key
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("speciesCode", species);
        body.put("content", content);
        body.putObject("screeningAnswers");
        return mockMvc.perform(post("/api/v1/staff/triage-rules/test")
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    private void insertRule(
        long id,
        String code,
        int revision,
        String species,
        String status,
        boolean enabled,
        int version,
        String symptom
    ) throws Exception {
        String predicate = objectMapper.writeValueAsString(objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["%s"],
             "expression":{"fact":"SYMPTOM","code":"%s","operator":"IS_PRESENT"}}
            """.formatted(species, symptom)));
        boolean reviewed = "APPROVED".equals(status) || "REJECTED".equals(status);
        jdbc.update("""
            INSERT INTO triage_rule(
                id,rule_code,revision_no,species_code,title,predicate_json,risk_level,fixed_advice,
                source_url,review_status,enabled,creator_user_id,reviewer_user_id,review_note,
                reviewed_at,version
            ) VALUES (?,?,?,?,?,CAST(? AS JSON),'EMERGENCY',?,'https://example.org/triage-source',
                      ?,?,?,?,?,?,?)
            """, id, code, revision, species, code + "标题", predicate, "请立即联系宠物医院",
            status, enabled, ADMIN, reviewed ? DOCTOR : null, reviewed ? "已审核" : null,
            reviewed ? Timestamp.from(Instant.now()) : null, version);
    }

    private String adminBearer() {
        return bearer(ADMIN, ADMIN_SESSION, "ADMIN", "WEB");
    }

    private String doctorBearer() {
        return bearer(DOCTOR, DOCTOR_SESSION, "DOCTOR", "WEB");
    }

    private String userBearer() {
        return bearer(USER, USER_SESSION, "USER", "MINI");
    }

    private String bearer(long userId, long sessionId, String role, String audience) {
        return "Bearer " + tokenIssuer.issue(
            userId, sessionId, 0, audience, List.of(role), Instant.now(), 1800
        ).value();
    }

    private void seedUser(long id, String username, String role) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, id, username, username);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, role);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)", id + 10_000, id, roleId);
    }

    private void seedSession(long id, long userId, String clientType) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """, id, userId, clientType, "%064d".formatted(id), "family-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
