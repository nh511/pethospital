package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class M0901IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 90_001;
    private static final long DOCTOR = 90_002;
    private static final long USER = 90_003;
    private static final long OTHER_ADMIN = 90_004;
    private static final long ADMIN_SESSION = 91_001;
    private static final long DOCTOR_SESSION = 91_002;
    private static final long USER_SESSION = 91_003;
    private static final long COVER = 92_001;
    private static final long WRONG_PURPOSE = 92_002;
    private static final long OTHER_OWNER_COVER = 92_003;

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
        jdbc.update("DELETE FROM guide_view_event");
        jdbc.update("DELETE FROM file_binding WHERE business_type='GUIDE'");
        jdbc.update("DELETE FROM health_guide");
        jdbc.update("DELETE FROM file_object WHERE id>=92000");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=90000");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=90000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=90000");
        jdbc.update("DELETE FROM sys_user WHERE id>=90000");
        seedUser(ADMIN, "guide-admin", "ADMIN");
        seedUser(DOCTOR, "guide-doctor", "DOCTOR");
        seedUser(USER, "guide-user", "USER");
        seedUser(OTHER_ADMIN, "guide-other-admin", "ADMIN");
        seedSession(ADMIN_SESSION, ADMIN, "WEB");
        seedSession(DOCTOR_SESSION, DOCTOR, "WEB");
        seedSession(USER_SESSION, USER, "MINI");
        seedFile(COVER, ADMIN, "GUIDE", "READY");
        seedFile(WRONG_PURPOSE, ADMIN, "PET", "READY");
        seedFile(OTHER_OWNER_COVER, OTHER_ADMIN, "GUIDE", "READY");
    }

    @Test
    void adminCreatesSanitizedDraftWithCoverIdempotencyAndAudit() throws Exception {
        MvcResult created = create(COVER, "m0901-create-0001", unsafeBody())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.coverFileId").value(Long.toString(COVER)))
            .andExpect(jsonPath("$.data.body").value(
                "<h2>护理</h2><p>正文<strong>重点</strong></p>坏链接"
            ))
            .andReturn();
        JsonNode data = data(created);
        long guideId = data.path("guideId").asLong();
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='GUIDE'"
            + " AND business_id=" + guideId + " AND file_id=" + COVER)).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_GUIDE_CREATE'"
            + " AND resource_id=" + guideId)).isOne();

        String replayId = data(create(COVER, "m0901-create-0001", unsafeBody())
            .andExpect(status().isCreated()).andReturn()).path("guideId").asText();
        assertThat(replayId).isEqualTo(Long.toString(guideId));
        assertThat(count("SELECT COUNT(*) FROM health_guide")).isOne();
    }

    @Test
    void doctorCanReadButCannotCreateOrPatchAndMiniUserCannotRead() throws Exception {
        long guideId = createId(null, "m0901-read-00001", "<p>安全正文</p>");
        mockMvc.perform(get("/api/v1/staff/health/guides")
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("1"))
            .andExpect(jsonPath("$.data.items[0].guideId").value(Long.toString(guideId)));
        mockMvc.perform(get("/api/v1/staff/health/guides/{guideId}", guideId)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/staff/health/guides/{guideId}", guideId)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"越权\",\"version\":0}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/staff/health/guides")
                .header(HttpHeaders.AUTHORIZATION, doctorBearer())
                .header("Idempotency-Key", "m0901-doctor-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createJson(null, "<p>正文</p>")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff/health/guides")
                .header(HttpHeaders.AUTHORIZATION, userBearer()))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminPatchUsesVersionRebindsCoverAndWritesAudit() throws Exception {
        long guideId = createId(null, "m0901-patch-0001", "<p>旧正文</p>");
        mockMvc.perform(patch("/api/v1/staff/health/guides/{guideId}", guideId)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"更新标题","body":"<p onmouseover='x'>新正文</p>",
                     "coverFileId":"92001","sourceNote":"更新来源","version":0}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("更新标题"))
            .andExpect(jsonPath("$.data.body").value("<p>新正文</p>"))
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_id=" + guideId
            + " AND file_id=" + COVER)).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_GUIDE_UPDATE'"
            + " AND resource_id=" + guideId)).isOne();

        mockMvc.perform(patch("/api/v1/staff/health/guides/{guideId}", guideId)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"旧版本覆盖\",\"version\":0}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void rejectsWrongPurposeAndForeignUploaderWithoutPartialRows() throws Exception {
        create(WRONG_PURPOSE, "m0901-wrong-purpose", "<p>正文</p>")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("FILE_PURPOSE_MISMATCH"));
        create(OTHER_OWNER_COVER, "m0901-foreign-file", "<p>正文</p>")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FILE_SOURCE_FORBIDDEN"));
        assertThat(count("SELECT COUNT(*) FROM health_guide")).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_GUIDE_CREATE'")).isZero();
    }

    @Test
    void publishedGuideIsImmutableAndUnknownGuideReturns404() throws Exception {
        long guideId = createId(null, "m0901-published-01", "<p>已发布正文</p>");
        jdbc.update("UPDATE health_guide SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(3) WHERE id=?", guideId);
        mockMvc.perform(patch("/api/v1/staff/health/guides/{guideId}", guideId)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"原地篡改\",\"version\":0}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PUBLISHED_GUIDE_IMMUTABLE"));
        mockMvc.perform(get("/api/v1/staff/health/guides/{guideId}", 999_999)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("HEALTH_GUIDE_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.ResultActions create(
        Long coverFileId,
        String key,
        String body
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/health/guides")
            .header(HttpHeaders.AUTHORIZATION, adminBearer())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createJson(coverFileId, body)));
    }

    private String createJson(Long coverFileId, String body) throws Exception {
        var payload = objectMapper.createObjectNode();
        payload.put("title", "猫咪日常护理");
        payload.put("speciesCode", "cat");
        payload.put("categoryCode", "daily_care");
        payload.put("body", body);
        payload.put("sourceNote", "兽医护理手册 2026");
        if (coverFileId != null) {
            payload.put("coverFileId", Long.toString(coverFileId));
        }
        return objectMapper.writeValueAsString(payload);
    }

    private long createId(Long coverFileId, String key, String body) throws Exception {
        return data(create(coverFileId, key, body)
            .andExpect(status().isCreated()).andReturn()).path("guideId").asLong();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static String unsafeBody() {
        return "<script>alert(1)</script><h2>护理</h2>"
            + "<p onclick='bad()'>正文<strong>重点</strong></p>"
            + "<a href='javascript:bad()'>坏链接</a>";
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

    private void seedFile(long id, long uploader, String purpose, String status) {
        jdbc.update("""
            INSERT INTO file_object(
                id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,
                purpose,status,validated_at
            ) VALUES (?,?,?,?,?,128,?,?,?,CURRENT_TIMESTAMP(3))
            """, id, uploader, "m0901/" + id + ".png", id + ".png", "image/png",
            "%064x".formatted(id), purpose, status);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
