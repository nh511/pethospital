package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class M1002IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 100_201;
    private static final long DOCTOR = 100_202;
    private static final long USER = 100_203;
    private static final long ADMIN_SESSION = 101_201;
    private static final long DOCTOR_SESSION = 101_202;
    private static final long USER_SESSION = 101_203;

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
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=100200");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=100200");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=100200");
        jdbc.update("DELETE FROM sys_user WHERE id>=100200");
        seedUser(ADMIN, "triage-admin", "ADMIN");
        seedUser(DOCTOR, "triage-doctor", "DOCTOR");
        seedUser(USER, "triage-user", "USER");
        seedSession(ADMIN_SESSION, ADMIN, "WEB");
        seedSession(DOCTOR_SESSION, DOCTOR, "WEB");
        seedSession(USER_SESSION, USER, "MINI");
    }

    @Test
    void adminCreatesSequentialRevisionsWithIdempotentAudit() throws Exception {
        MvcResult first = create("BREATHING_ALERT", "m1002-create-0001", predicate("CAT", "BREATHING_DIFFICULTY"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.revisionNo").value(1))
            .andExpect(jsonPath("$.data.reviewStatus").value("DRAFT"))
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andReturn();
        long firstId = data(first).path("ruleId").asLong();
        String replay = data(create("BREATHING_ALERT", "m1002-create-0001", predicate("CAT", "BREATHING_DIFFICULTY"))
            .andExpect(status().isCreated()).andReturn()).path("ruleId").asText();
        assertThat(replay).isEqualTo(Long.toString(firstId));

        create("BREATHING_ALERT", "m1002-create-0002", predicate("CAT", "OPEN_MOUTH_BREATHING"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.revisionNo").value(2));
        assertThat(count("SELECT COUNT(*) FROM triage_rule WHERE rule_code='BREATHING_ALERT'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='TRIAGE_RULE_REVISION_CREATE'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(DISTINCT revision_no) FROM triage_rule WHERE rule_code='BREATHING_ALERT'"))
            .isEqualTo(2);
    }

    @Test
    void rejectsMissingSourceInvalidPredicateAndSpeciesMismatch() throws Exception {
        ObjectNode missingSource = createPayload("SOURCELESS", predicate("CAT", "SEIZURE"));
        missingSource.remove("sourceUrl");
        performCreate(missingSource, "m1002-no-source01")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        ObjectNode malicious = createPayload("MALICIOUS", objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["CAT"],
             "expression":{"fact":"SCRIPT","code":"X","operator":"EXEC","value":"drop table"}}
            """));
        performCreate(malicious, "m1002-malicious01")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_TRIAGE_PREDICATE"));

        ObjectNode mismatch = createPayload("MISMATCH", predicate("DOG", "SEIZURE"));
        mismatch.put("speciesCode", "CAT");
        performCreate(mismatch, "m1002-mismatch001")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_TRIAGE_RULE"));
        assertThat(count("SELECT COUNT(*) FROM triage_rule")).isZero();
    }

    @Test
    void doctorReadsButCannotWriteAndMiniUserCannotRead() throws Exception {
        long ruleId = createId("READ_ONLY", "m1002-read-only01", predicate("DOG", "SEIZURE"));
        mockMvc.perform(get("/api/v1/staff/triage-rules")
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("1"));
        mockMvc.perform(get("/api/v1/staff/triage-rules/{ruleId}", ruleId)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/staff/triage-rules/{ruleId}", ruleId)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"越权\",\"version\":0}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/staff/triage-rules")
                .header(HttpHeaders.AUTHORIZATION, doctorBearer())
                .header("Idempotency-Key", "m1002-doctor-write1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createPayload("FORBIDDEN", predicate("CAT", "SEIZURE")))))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff/triage-rules")
                .header(HttpHeaders.AUTHORIZATION, userBearer()))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminEditsDraftAndRejectedRuleWithVersionAndAudit() throws Exception {
        long ruleId = createId("EDITABLE", "m1002-editable001", predicate("CAT", "SEIZURE"));
        mockMvc.perform(patch("/api/v1/staff/triage-rules/{ruleId}", ruleId)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(patchPayload("更新规则", 0))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("更新规则"))
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='TRIAGE_RULE_UPDATE'"
            + " AND resource_id=" + ruleId)).isOne();

        mockMvc.perform(patch("/api/v1/staff/triage-rules/{ruleId}", ruleId)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(patchPayload("旧版本", 0))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        jdbc.update("""
            UPDATE triage_rule SET review_status='REJECTED',reviewer_user_id=?,review_note='补充来源',
                reviewed_at=CURRENT_TIMESTAMP(3) WHERE id=?
            """, DOCTOR, ruleId);
        mockMvc.perform(patch("/api/v1/staff/triage-rules/{ruleId}", ruleId)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceUrl\":\"https://example.org/revised\",\"version\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("DRAFT"))
            .andExpect(jsonPath("$.data.reviewerUserId").doesNotExist())
            .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void approvedAndInReviewRulesAreImmutableAndUnknownReturns404() throws Exception {
        long approved = createId("APPROVED_RULE", "m1002-approved001", predicate("CAT", "SEIZURE"));
        jdbc.update("""
            UPDATE triage_rule SET review_status='APPROVED',reviewer_user_id=?,review_note='通过',
                reviewed_at=CURRENT_TIMESTAMP(3) WHERE id=?
            """, DOCTOR, approved);
        mockMvc.perform(patch("/api/v1/staff/triage-rules/{ruleId}", approved)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"原地修改\",\"version\":0}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRIAGE_RULE_IMMUTABLE"));

        long reviewing = createId("REVIEWING_RULE", "m1002-reviewing01", predicate("DOG", "SEIZURE"));
        jdbc.update("UPDATE triage_rule SET review_status='IN_REVIEW' WHERE id=?", reviewing);
        mockMvc.perform(patch("/api/v1/staff/triage-rules/{ruleId}", reviewing)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"审核中修改\",\"version\":0}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRIAGE_RULE_IMMUTABLE"));
        mockMvc.perform(get("/api/v1/staff/triage-rules/{ruleId}", 999_999)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("TRIAGE_RULE_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.ResultActions create(
        String code,
        String key,
        JsonNode predicate
    ) throws Exception {
        return performCreate(createPayload(code, predicate), key);
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(ObjectNode payload, String key)
        throws Exception {
        return mockMvc.perform(post("/api/v1/staff/triage-rules")
            .header(HttpHeaders.AUTHORIZATION, adminBearer())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)));
    }

    private ObjectNode createPayload(String code, JsonNode predicate) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("ruleCode", code);
        payload.put("speciesCode", predicate.path("applicableSpecies").get(0).asText());
        payload.put("title", "急症规则");
        payload.set("predicate", predicate);
        payload.put("riskLevel", "EMERGENCY");
        payload.put("fixedAdvice", "请立即联系宠物医院并尽快就医");
        payload.put("sourceUrl", "https://example.org/vet-guideline");
        return payload;
    }

    private ObjectNode patchPayload(String title, int version) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", title);
        payload.set("predicate", predicate("CAT", "OPEN_MOUTH_BREATHING"));
        payload.put("version", version);
        return payload;
    }

    private JsonNode predicate(String species, String symptom) throws Exception {
        return objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["%s"],
             "expression":{"fact":"SYMPTOM","code":"%s","operator":"IS_PRESENT"}}
            """.formatted(species, symptom));
    }

    private long createId(String code, String key, JsonNode predicate) throws Exception {
        return data(create(code, key, predicate).andExpect(status().isCreated()).andReturn())
            .path("ruleId").asLong();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
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
