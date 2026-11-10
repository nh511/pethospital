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
class M0601IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 60_001;
    private static final long DOCTOR_ONE = 60_101;
    private static final long DOCTOR_TWO = 60_102;
    private static final long ORDINARY_USER = 60_201;
    private static final long ADMIN_SESSION = 61_001;
    private static final long DOCTOR_ONE_SESSION = 61_101;
    private static final long DOCTOR_TWO_SESSION = 61_102;
    private static final long USER_SESSION = 61_201;
    private static final long QUALIFICATION_ONE = 62_001;
    private static final long QUALIFICATION_TWO = 62_002;

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
        jdbc.update("DELETE FROM file_binding");
        jdbc.update("DELETE FROM doctor_profile");
        jdbc.update("DELETE FROM file_object");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=60000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=60000");
        jdbc.update("DELETE FROM sys_user WHERE id>=60000");
        seedUser(ADMIN, "doctor-admin", "ADMIN");
        seedUser(DOCTOR_ONE, "doctor-one", "DOCTOR");
        seedUser(DOCTOR_TWO, "doctor-two", "DOCTOR");
        seedUser(ORDINARY_USER, "ordinary-user", "USER");
        seedSession(ADMIN_SESSION, ADMIN, "WEB");
        seedSession(DOCTOR_ONE_SESSION, DOCTOR_ONE, "WEB");
        seedSession(DOCTOR_TWO_SESSION, DOCTOR_TWO, "WEB");
        seedSession(USER_SESSION, ORDINARY_USER, "MINI");
        seedQualification(QUALIFICATION_ONE, "qualification-one.pdf");
        seedQualification(QUALIFICATION_TWO, "qualification-two.pdf");
    }

    @Test
    void adminCreatesOnlyDoctorRoleProfileWithQualificationAndIdempotentAudit() throws Exception {
        MvcResult result = adminCreate(DOCTOR_ONE, QUALIFICATION_ONE, "m0601-create-0001")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.userId").value(Long.toString(DOCTOR_ONE)))
            .andExpect(jsonPath("$.data.qualificationFileId").value(Long.toString(QUALIFICATION_ONE)))
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        long doctorId = data.path("doctorId").asLong();
        assertThat(doctorId).isNotEqualTo(DOCTOR_ONE);
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='QUALIFICATION'"
            + " AND business_id=" + doctorId + " AND file_id=" + QUALIFICATION_ONE
            + " AND owner_user_id=" + DOCTOR_ONE)).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='DOCTOR_PROFILE_CREATE'"
            + " AND resource_id=" + doctorId + " AND actor_user_id=" + ADMIN)).isOne();

        String replayId = objectMapper.readTree(adminCreate(
            DOCTOR_ONE, QUALIFICATION_ONE, "m0601-create-0001"
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
            .path("data").path("doctorId").asText();
        assertThat(replayId).isEqualTo(Long.toString(doctorId));
    }

    @Test
    void rejectsProfileForAccountWithoutDoctorRole() throws Exception {
        adminCreate(ORDINARY_USER, null, "m0601-nondoctor-1")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DOCTOR_ROLE_REQUIRED"));
        assertThat(count("SELECT COUNT(*) FROM doctor_profile")).isZero();
    }

    @Test
    void doctorReadsAndUpdatesOnlyOwnPublicIntroductionFields() throws Exception {
        long firstId = createAndId(DOCTOR_ONE, null, "m0601-self-one01");
        long secondId = createAndId(DOCTOR_TWO, null, "m0601-self-two01");

        mockMvc.perform(get("/api/v1/staff/doctor-profile")
                .header(HttpHeaders.AUTHORIZATION, bearer(DOCTOR_ONE, DOCTOR_ONE_SESSION, "DOCTOR", "WEB")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.doctorId").value(Long.toString(firstId)));
        mockMvc.perform(patch("/api/v1/staff/doctor-profile")
                .header(HttpHeaders.AUTHORIZATION, bearer(DOCTOR_ONE, DOCTOR_ONE_SESSION, "DOCTOR", "WEB"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"specialties":"猫科内科","introduction":"本人公开介绍","version":0}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.specialties").value("猫科内科"))
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbc.queryForObject(
            "SELECT introduction FROM doctor_profile WHERE id=?", String.class, secondId
        )).isEqualTo("初始介绍");

        mockMvc.perform(patch("/api/v1/staff/doctor-profile")
                .header(HttpHeaders.AUTHORIZATION, bearer(DOCTOR_ONE, DOCTOR_ONE_SESSION, "DOCTOR", "WEB"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"introduction":"越权","qualificationFileId":"62002","status":"DISABLED","version":1}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='DOCTOR_PROFILE_SELF_UPDATE'"
            + " AND actor_user_id=" + DOCTOR_ONE + " AND resource_id=" + firstId)).isOne();
    }

    @Test
    void adminPatchUsesDoctorIdVersionAndRebindsQualificationWithAudit() throws Exception {
        long doctorId = createAndId(DOCTOR_ONE, QUALIFICATION_ONE, "m0601-patch-0001");
        mockMvc.perform(patch("/api/v1/admin/doctors/{doctorId}", doctorId)
                .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, ADMIN_SESSION, "ADMIN", "WEB"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"主任医师","qualificationFileId":"62002","version":0}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.doctorId").value(Long.toString(doctorId)))
            .andExpect(jsonPath("$.data.userId").value(Long.toString(DOCTOR_ONE)))
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_id=" + doctorId
            + " AND file_id=" + QUALIFICATION_TWO)).isOne();
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_id=" + doctorId
            + " AND file_id=" + QUALIFICATION_ONE)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='DOCTOR_PROFILE_ADMIN_UPDATE'"
            + " AND resource_id=" + doctorId)).isOne();

        mockMvc.perform(patch("/api/v1/admin/doctors/{doctorId}", doctorId)
                .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, ADMIN_SESSION, "ADMIN", "WEB"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"旧版本覆盖\",\"version\":0}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void roleAndAudienceBoundariesRejectCrossEntryAccess() throws Exception {
        mockMvc.perform(get("/api/v1/admin/doctors")
                .header(HttpHeaders.AUTHORIZATION, bearer(DOCTOR_ONE, DOCTOR_ONE_SESSION, "DOCTOR", "WEB")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff/doctor-profile")
                .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, ADMIN_SESSION, "ADMIN", "WEB")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff/doctor-profile")
                .header(HttpHeaders.AUTHORIZATION, bearer(ORDINARY_USER, USER_SESSION, "USER", "MINI")))
            .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions adminCreate(
        long userId,
        Long qualificationFileId,
        String key
    ) throws Exception {
        String qualification = qualificationFileId == null ? "" : ",\"qualificationFileId\":\"" + qualificationFileId + "\"";
        return mockMvc.perform(post("/api/v1/admin/doctors")
            .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, ADMIN_SESSION, "ADMIN", "WEB"))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"userId":"%d","doctorName":"测试医生","title":"医师","specialties":"全科",
                 "introduction":"初始介绍"%s}
                """.formatted(userId, qualification)));
    }

    private long createAndId(long userId, Long qualificationId, String key) throws Exception {
        MvcResult result = adminCreate(userId, qualificationId, key)
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("doctorId").asLong();
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

    private void seedQualification(long id, String name) {
        jdbc.update("""
            INSERT INTO file_object(
                id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,
                purpose,status,validated_at
            ) VALUES (?,?,?,?,?,256,?,'QUALIFICATION','READY',CURRENT_TIMESTAMP(3))
            """, id, ADMIN, "m0601/" + name, name, "application/pdf", "%064x".formatted(id));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
