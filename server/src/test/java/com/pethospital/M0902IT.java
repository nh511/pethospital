package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class M0902IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 93_001;
    private static final long DOCTOR = 93_002;
    private static final long ADMIN_SESSION = 93_101;
    private static final long DOCTOR_SESSION = 93_102;
    private static final long COVER = 93_201;
    private static final long GUIDE = 93_301;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Rs256AccessTokenIssuer tokenIssuer;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM guide_view_event");
        jdbc.update("DELETE FROM file_binding WHERE business_type='GUIDE'");
        jdbc.update("DELETE FROM health_guide");
        jdbc.update("DELETE FROM file_object WHERE id>=93200");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=93000");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=93000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=93000");
        jdbc.update("DELETE FROM sys_user WHERE id>=93000");
        seedUser(ADMIN, "guide-publisher", "ADMIN");
        seedUser(DOCTOR, "guide-reviewer", "DOCTOR");
        seedSession(ADMIN_SESSION, ADMIN);
        seedSession(DOCTOR_SESSION, DOCTOR);
    }

    @Test
    void publishRequiresCompleteContentAndReadyBoundCover() throws Exception {
        seedGuide("DRAFT", 0, null, true);
        publish(GUIDE, 0, "m0902-missing-cover")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("HEALTH_GUIDE_PUBLISH_REQUIREMENTS_NOT_MET"));

        jdbc.update("DELETE FROM health_guide WHERE id=?", GUIDE);
        seedFile("QUARANTINED", "GUIDE");
        seedGuide("DRAFT", 0, COVER, true);
        seedBinding();
        publish(GUIDE, 0, "m0902-unready-cover")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("HEALTH_GUIDE_PUBLISH_REQUIREMENTS_NOT_MET"));
        assertThat(statusOf(GUIDE)).isEqualTo("DRAFT");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_GUIDE_PUBLISH'"))
            .isZero();
    }

    @Test
    void publishesAndReplaysIdempotentlyWithoutCreatingRagRecords() throws Exception {
        seedReadyDraft();
        publish(GUIDE, 0, "m0902-publish-0001")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.publishedAt").isNotEmpty());
        publish(GUIDE, 0, "m0902-publish-0001")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.version").value(1));

        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_GUIDE_PUBLISH'"
            + " AND resource_id=" + GUIDE)).isOne();
        assertThat(count("SELECT COUNT(*) FROM knowledge_document")).isZero();
        assertThat(count("SELECT COUNT(*) FROM knowledge_version")).isZero();
        assertThat(count("SELECT COUNT(*) FROM knowledge_chunk")).isZero();
        assertThat(count("SELECT COUNT(*) FROM knowledge_index_job")).isZero();
    }

    @Test
    void offlinesPublishedGuideAndReplaysWithReason() throws Exception {
        seedPublished();
        offline(GUIDE, 1, "m0902-offline-0001", "来源需要复核")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("OFFLINE"))
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.publishedAt").isNotEmpty());
        offline(GUIDE, 1, "m0902-offline-0001", "来源需要复核")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(2));
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_GUIDE_OFFLINE'"
            + " AND resource_id=" + GUIDE + " AND reason='来源需要复核'"))
            .isOne();
    }

    @Test
    void rejectsDoctorStaleVersionInvalidStateAndUnknownFields() throws Exception {
        seedReadyDraft();
        mockMvc.perform(post("/api/v1/staff/health/guides/{guideId}/publish", GUIDE)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer())
                .header("Idempotency-Key", "m0902-doctor-publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
            .andExpect(status().isForbidden());
        publish(GUIDE, 9, "m0902-stale-version")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        offline(GUIDE, 0, "m0902-draft-offline", "错误状态")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("HEALTH_GUIDE_STATE_CONFLICT"));
        mockMvc.perform(post("/api/v1/staff/health/guides/{guideId}/publish", GUIDE)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .header("Idempotency-Key", "m0902-unknown-field")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"status\":\"PUBLISHED\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void offlineGuideCanBeEditedAndRepublished() throws Exception {
        seedPublished();
        offline(GUIDE, 1, "m0902-cycle-offline", "更新正文")
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/staff/health/guides/{guideId}", GUIDE)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"<p>复核后的正文</p>\",\"version\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("OFFLINE"))
            .andExpect(jsonPath("$.data.version").value(3));
        publish(GUIDE, 3, "m0902-cycle-publish")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.version").value(4));
    }

    private org.springframework.test.web.servlet.ResultActions publish(
        long guideId,
        int version,
        String key
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/health/guides/{guideId}/publish", guideId)
            .header(HttpHeaders.AUTHORIZATION, adminBearer())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions offline(
        long guideId,
        int version,
        String key,
        String reason
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/health/guides/{guideId}/offline", guideId)
            .header(HttpHeaders.AUTHORIZATION, adminBearer())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + ",\"reason\":\"" + reason + "\"}"));
    }

    private void seedReadyDraft() {
        seedFile("READY", "GUIDE");
        seedGuide("DRAFT", 0, COVER, true);
        seedBinding();
    }

    private void seedPublished() {
        seedFile("READY", "GUIDE");
        seedGuide("PUBLISHED", 1, COVER, true);
        seedBinding();
    }

    private void seedGuide(String status, int version, Long coverId, boolean complete) {
        jdbc.update("""
            INSERT INTO health_guide(
                id,title,species_code,category_code,body,cover_file_id,source_note,status,
                published_at,editor_user_id,version
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, GUIDE, "猫咪护理", "CAT", "DAILY_CARE",
            complete ? "<p>完整正文</p>" : "", coverId,
            complete ? "兽医护理手册" : null, status,
            "PUBLISHED".equals(status) ? Timestamp.from(Instant.now()) : null,
            ADMIN, version);
    }

    private void seedFile(String status, String purpose) {
        jdbc.update("""
            INSERT INTO file_object(
                id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,
                purpose,status,validated_at
            ) VALUES (?,?,?,?,?,128,?,?,?,CURRENT_TIMESTAMP(3))
            """, COVER, ADMIN, "m0902/cover.png", "cover.png", "image/png",
            "%064x".formatted(COVER), purpose, status);
    }

    private void seedBinding() {
        jdbc.update("""
            INSERT INTO file_binding(id,file_id,business_type,business_id,owner_user_id,created_by)
            VALUES (?,?, 'GUIDE', ?,?,?)
            """, 93_401, COVER, GUIDE, ADMIN, ADMIN);
    }

    private String statusOf(long guideId) {
        return jdbc.queryForObject("SELECT status FROM health_guide WHERE id=?", String.class, guideId);
    }

    private String adminBearer() {
        return bearer(ADMIN, ADMIN_SESSION, "ADMIN");
    }

    private String doctorBearer() {
        return bearer(DOCTOR, DOCTOR_SESSION, "DOCTOR");
    }

    private String bearer(long userId, long sessionId, String role) {
        return "Bearer " + tokenIssuer.issue(
            userId, sessionId, 0, "WEB", List.of(role), Instant.now(), 1800
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

    private void seedSession(long id, long userId) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?, 'WEB',?,?,?,?,?,?)
            """, id, userId, "%064d".formatted(id), "family-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now),
            Timestamp.from(now), Timestamp.from(now));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
