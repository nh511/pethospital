package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class M0504IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 170_401;
    private static final long OTHER_OWNER = 170_402;
    private static final long OWNER_SESSION = 171_401;
    private static final long OTHER_SESSION = 171_402;
    private static final long PET = 174_401;
    private static final long OWN_FILE = 175_401;
    private static final long OTHER_FILE = 175_402;

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
    Rs256AccessTokenIssuer tokenIssuer;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM file_binding WHERE business_id>=170000 OR file_id>=170000");
        jdbc.update("DELETE FROM pet_health_log WHERE id>=170000");
        jdbc.update("DELETE FROM pet WHERE id>=170000");
        jdbc.update("DELETE FROM file_object WHERE id>=170000");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=170000");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=170000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=170000");
        jdbc.update("DELETE FROM sys_user WHERE id>=170000");

        seedUser(OWNER, "m0504-owner");
        seedUser(OTHER_OWNER, "m0504-other");
        seedSession(OWNER_SESSION, OWNER);
        seedSession(OTHER_SESSION, OTHER_OWNER);
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,sex,neutered,status,version)
            VALUES (?,?,'健康日志宠物','CAT','UNKNOWN','UNKNOWN','ACTIVE',0)
            """, PET, OWNER);
        seedFile(OWN_FILE, OWNER);
        seedFile(OTHER_FILE, OTHER_OWNER);
    }

    @Test
    void createsListsAndReadsOwnedLogWithAttachmentAndIdempotency() throws Exception {
        String body = """
            {"observedAt":"2026-10-04T08:30:00Z","weightKg":4.35,
             "dietNote":"  食欲正常  ","symptomNote":"偶尔打喷嚏","attachmentIds":["175401"]}
            """;
        String key = "m0504-create-health-log";
        MvcResult created = create(body, key, ownerBearer())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.observedAt").value("2026-10-04T08:30:00Z"))
            .andExpect(jsonPath("$.data.weightKg").value(4.35))
            .andExpect(jsonPath("$.data.dietNote").value("食欲正常"))
            .andExpect(jsonPath("$.data.attachmentIds[0]").value("175401"))
            .andExpect(jsonPath("$.data.sourceType").value("OWNER_REPORTED"))
            .andReturn();
        JsonNode data = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        String logId = data.path("id").asText();

        create(body, key, ownerBearer())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").value(logId));
        mockMvc.perform(get("/api/v1/pets/{petId}/health-logs", PET)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(logId));
        mockMvc.perform(get("/api/v1/pets/{petId}/health-logs/{logId}", PET, logId)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.symptomNote").value("偶尔打喷嚏"));

        assertThat(count("SELECT COUNT(*) FROM pet_health_log WHERE pet_id=" + PET)).isOne();
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='HEALTH_LOG' AND file_id=" + OWN_FILE))
            .isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_LOG_CREATE' AND resource_id=" + logId))
            .isOne();
    }

    @Test
    void hidesPetAndLogsFromOtherOwner() throws Exception {
        seedLog(176_401, "2026-10-01T08:00:00Z", "主人私密自述");
        mockMvc.perform(get("/api/v1/pets/{petId}/health-logs", PET)
                .header(HttpHeaders.AUTHORIZATION, otherBearer()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PET_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/pets/{petId}/health-logs/{logId}", PET, 176_401)
                .header(HttpHeaders.AUTHORIZATION, otherBearer()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PET_NOT_FOUND"));
        create(validBody(List.of()), "m0504-cross-owner-create", otherBearer())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PET_NOT_FOUND"));
    }

    @Test
    void rejectsEmptyRecordAndNonPositiveWeight() throws Exception {
        create("""
            {"observedAt":"2026-10-04T08:30:00Z","dietNote":"  ","attachmentIds":[]}
            """, "m0504-empty-health-log", ownerBearer())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMPTY_HEALTH_LOG"));
        create("""
            {"observedAt":"2026-10-04T08:30:00Z","weightKg":0,"attachmentIds":[]}
            """, "m0504-zero-weight-log", ownerBearer())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(count("SELECT COUNT(*) FROM pet_health_log WHERE pet_id=" + PET)).isZero();
    }

    @Test
    void foreignAttachmentFailsAndRollsBackLogAndAudit() throws Exception {
        create(validBody(List.of(OTHER_FILE)), "m0504-foreign-file-log", ownerBearer())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FILE_SOURCE_FORBIDDEN"));

        assertThat(count("SELECT COUNT(*) FROM pet_health_log WHERE pet_id=" + PET)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_LOG_CREATE' AND actor_user_id=" + OWNER))
            .isZero();
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE file_id=" + OTHER_FILE)).isZero();
    }

    @Test
    void rangePaginationIsStableAndArchivedPetRemainsReadableButNotWritable() throws Exception {
        seedLog(176_401, "2026-10-01T08:00:00Z", "早期");
        seedLog(176_402, "2026-10-02T08:00:00Z", "同刻小ID");
        seedLog(176_403, "2026-10-02T08:00:00Z", "同刻大ID");

        mockMvc.perform(get("/api/v1/pets/{petId}/health-logs", PET)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer())
                .param("from", "2026-10-02T08:00:00Z")
                .param("to", "2026-10-03T08:00:00Z")
                .param("page", "1")
                .param("pageSize", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].id").value("176403"));
        mockMvc.perform(get("/api/v1/pets/{petId}/health-logs", PET)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer())
                .param("from", "2026-10-02T08:00:00Z")
                .param("to", "2026-10-03T08:00:00Z")
                .param("page", "2")
                .param("pageSize", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value("176402"));

        jdbc.update("UPDATE pet SET status='ARCHIVED',version=version+1 WHERE id=?", PET);
        mockMvc.perform(get("/api/v1/pets/{petId}/health-logs/{logId}", PET, 176_401)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dietNote").value("早期"));
        create(validBody(List.of()), "m0504-archived-pet-log", ownerBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PET_ARCHIVED"));
    }

    private org.springframework.test.web.servlet.ResultActions create(String body, String key, String bearer)
        throws Exception {
        return mockMvc.perform(post("/api/v1/pets/{petId}/health-logs", PET)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private void seedLog(long logId, String observedAt, String dietNote) {
        jdbc.update("""
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at,diet_note,version)
            VALUES (?,?,?,?,?,0)
            """, logId, PET, OWNER, Timestamp.from(Instant.parse(observedAt)), dietNote);
    }

    private void seedFile(long fileId, long uploader) {
        jdbc.update("""
            INSERT INTO file_object(
                id,uploader_user_id,object_key,original_name,mime_type,size_bytes,
                sha256,purpose,status,validated_at
            ) VALUES (?,?,?,?,'image/png',128,
                      '4444444444444444444444444444444444444444444444444444444444444444',
                      'HEALTH_LOG','READY',CURRENT_TIMESTAMP(3))
            """, fileId, uploader, "m0504/" + fileId + ".png", "health-" + fileId + ".png");
    }

    private void seedUser(long userId, String username) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, userId, username, username);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='USER'", Long.class);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
            userId + 10_000, userId, roleId);
    }

    private void seedSession(long sessionId, long userId) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,'MINI',?,?,?,?,?,?)
            """, sessionId, userId, "%064x".formatted(sessionId), "family-" + sessionId,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private String ownerBearer() {
        return "Bearer " + token(OWNER, OWNER_SESSION);
    }

    private String otherBearer() {
        return "Bearer " + token(OTHER_OWNER, OTHER_SESSION);
    }

    private String token(long userId, long sessionId) {
        return tokenIssuer.issue(userId, sessionId, 0, "MINI", List.of("USER"), Instant.now(), 1800).value();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static String validBody(List<Long> attachmentIds) {
        String ids = attachmentIds.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return """
            {"observedAt":"2026-10-04T08:30:00Z","weightKg":4.35,
             "dietNote":"食欲正常","attachmentIds":[%s]}
            """.formatted(ids);
    }
}
