package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true"
})
@AutoConfigureMockMvc
class M1003IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 100_301;
    private static final long DOCTOR = 100_302;
    private static final long USER = 100_303;
    private static final long DOCTOR_PROFILE = 110_302;
    private static final long ADMIN_SESSION = 101_301;
    private static final long DOCTOR_SESSION = 101_302;
    private static final long USER_SESSION = 101_303;

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
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=100300");
        jdbc.update("DELETE FROM doctor_profile WHERE user_id>=100300");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=100300");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=100300");
        jdbc.update("DELETE FROM sys_user WHERE id>=100300");
        seedUser(ADMIN, "triage-review-admin", "ADMIN");
        seedUser(DOCTOR, "triage-review-doctor", "DOCTOR");
        seedUser(USER, "triage-review-user", "USER");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,?,'ACTIVE',0)
            """, DOCTOR_PROFILE, DOCTOR, "审核医生");
        seedSession(ADMIN_SESSION, ADMIN, "WEB");
        seedSession(DOCTOR_SESSION, DOCTOR, "WEB");
        seedSession(USER_SESSION, USER, "MINI");
    }

    @Test
    void adminSubmitsAndActiveDoctorApprovesWithAudit() throws Exception {
        long ruleId = createRule("APPROVAL_FLOW");

        submit(ruleId, 0, adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("IN_REVIEW"))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.reviewerUserId").doesNotExist());
        submit(ruleId, 0, adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("IN_REVIEW"))
            .andExpect(jsonPath("$.data.version").value(1));

        review(ruleId, "APPROVE", "依据来源审核通过", 1, doctorBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
            .andExpect(jsonPath("$.data.reviewerUserId").value(Long.toString(DOCTOR)))
            .andExpect(jsonPath("$.data.reviewNote").value("依据来源审核通过"))
            .andExpect(jsonPath("$.data.reviewedAt").isNotEmpty())
            .andExpect(jsonPath("$.data.version").value(2));
        review(ruleId, "APPROVE", "依据来源审核通过", 1, doctorBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
            .andExpect(jsonPath("$.data.version").value(2));

        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE resource_id=" + ruleId
            + " AND action IN ('TRIAGE_RULE_SUBMIT_REVIEW','TRIAGE_RULE_REVIEW')")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT enabled FROM triage_rule WHERE id=?", Boolean.class, ruleId))
            .isFalse();
    }

    @Test
    void nonDoctorAndInactiveDoctorCannotReview() throws Exception {
        long ruleId = createRule("REVIEW_PERMISSION");
        submit(ruleId, 0, adminBearer()).andExpect(status().isOk());

        review(ruleId, "APPROVE", null, 1, adminBearer())
            .andExpect(status().isForbidden());
        review(ruleId, "APPROVE", null, 1, userBearer())
            .andExpect(status().isForbidden());

        jdbc.update("UPDATE doctor_profile SET status='DISABLED' WHERE id=?", DOCTOR_PROFILE);
        review(ruleId, "APPROVE", null, 1, doctorBearer())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("DOCTOR_PROFILE_INACTIVE"));
        assertThat(jdbc.queryForObject("SELECT review_status FROM triage_rule WHERE id=?", String.class, ruleId))
            .isEqualTo("IN_REVIEW");
    }

    @Test
    void onlyInReviewCanBeReviewedAndVersionIsRequired() throws Exception {
        long ruleId = createRule("STATE_GUARD");
        review(ruleId, "APPROVE", null, 0, doctorBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRIAGE_REVIEW_STATE_CONFLICT"));

        submit(ruleId, 0, adminBearer()).andExpect(status().isOk());
        review(ruleId, "APPROVE", null, 0, doctorBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        submit(ruleId, 1, adminBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRIAGE_REVIEW_STATE_CONFLICT"));

        review(ruleId, "APPROVE", null, 1, doctorBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"));
        review(ruleId, "REJECT", "不能重复审核", 2, doctorBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRIAGE_REVIEW_STATE_CONFLICT"));
    }

    @Test
    void rejectedRuleCanBeEditedResubmittedAndApprovedPayloadThenFreezes() throws Exception {
        long ruleId = createRule("REWORK_FLOW");
        submit(ruleId, 0, adminBearer()).andExpect(status().isOk());
        review(ruleId, "REJECT", "请明确急救建议", 1, doctorBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("REJECTED"))
            .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(patch("/api/v1/staff/triage-rules/{ruleId}", ruleId)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"修订后的规则\",\"version\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("DRAFT"))
            .andExpect(jsonPath("$.data.reviewerUserId").doesNotExist())
            .andExpect(jsonPath("$.data.reviewNote").doesNotExist())
            .andExpect(jsonPath("$.data.version").value(3));

        submit(ruleId, 3, adminBearer()).andExpect(status().isOk());
        review(ruleId, "APPROVE", "修订后通过", 4, doctorBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
            .andExpect(jsonPath("$.data.version").value(5));

        mockMvc.perform(patch("/api/v1/staff/triage-rules/{ruleId}", ruleId)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"禁止覆盖\",\"version\":5}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRIAGE_RULE_IMMUTABLE"));
        assertThat(jdbc.queryForObject("SELECT title FROM triage_rule WHERE id=?", String.class, ruleId))
            .isEqualTo("修订后的规则");
    }

    @Test
    void rejectsInvalidReviewCommandsAndUnknownRule() throws Exception {
        long ruleId = createRule("COMMAND_VALIDATION");
        submit(ruleId, 0, adminBearer()).andExpect(status().isOk());

        review(ruleId, "REJECT", null, 1, doctorBearer())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_TRIAGE_RULE"));
        review(ruleId, "SKIP", "非法决策", 1, doctorBearer())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/staff/triage-rules/{ruleId}/review", ruleId)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer())
                .header("Idempotency-Key", "m1003-missing-version")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        submit(999_999, 0, adminBearer())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("TRIAGE_RULE_NOT_FOUND"));
    }

    private long createRule(String code) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("ruleCode", code);
        body.put("speciesCode", "CAT");
        body.put("title", "急症审核规则");
        body.set("predicate", objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["CAT"],
             "expression":{"fact":"SYMPTOM","code":"SEIZURE","operator":"IS_PRESENT"}}
            """));
        body.put("riskLevel", "EMERGENCY");
        body.put("fixedAdvice", "请立即联系宠物医院并尽快就医");
        body.put("sourceUrl", "https://example.org/triage-review-source");
        MvcResult result = mockMvc.perform(post("/api/v1/staff/triage-rules")
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .header("Idempotency-Key", "m1003-create-" + code.toLowerCase())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("ruleId").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions submit(long ruleId, int version, String bearer)
        throws Exception {
        return mockMvc.perform(post("/api/v1/staff/triage-rules/{ruleId}/submit-review", ruleId)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", "m1003-submit-" + ruleId + "-" + version)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions review(
        long ruleId,
        String decision,
        String note,
        int version,
        String bearer
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("decision", decision);
        if (note != null) {
            body.put("reviewNote", note);
        }
        body.put("version", version);
        return mockMvc.perform(post("/api/v1/staff/triage-rules/{ruleId}/review", ruleId)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", "m1003-review-" + ruleId + "-" + decision + "-" + version)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
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
