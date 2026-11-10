package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class M0602IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long REQUESTER = 63_001;
    private static final long CAT_DOCTOR_USER = 63_101;
    private static final long DOG_DOCTOR_USER = 63_102;
    private static final long DISABLED_USER = 63_103;
    private static final long DISABLED_PROFILE_USER = 63_104;
    private static final long NO_DOCTOR_ROLE_USER = 63_105;
    private static final long REQUESTER_SESSION = 64_001;
    private static final long DOCTOR_WEB_SESSION = 64_101;
    private static final long CAT_DOCTOR = 65_101;
    private static final long DOG_DOCTOR = 65_102;
    private static final long DISABLED_ACCOUNT_DOCTOR = 65_103;
    private static final long DISABLED_PROFILE_DOCTOR = 65_104;
    private static final long NO_ROLE_DOCTOR = 65_105;
    private static final long QUALIFICATION = 66_001;

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
        jdbc.update("DELETE FROM file_binding");
        jdbc.update("DELETE FROM doctor_profile WHERE id BETWEEN 65000 AND 66999");
        jdbc.update("DELETE FROM file_object WHERE id BETWEEN 65000 AND 66999");
        jdbc.update("DELETE FROM auth_session WHERE user_id BETWEEN 63000 AND 63999");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id BETWEEN 63000 AND 63999");
        jdbc.update("DELETE FROM sys_user WHERE id BETWEEN 63000 AND 63999");
        jdbc.update("UPDATE sys_role SET status='ACTIVE' WHERE code='DOCTOR'");

        seedUser(REQUESTER, "public-requester", "ACTIVE", "USER");
        seedUser(CAT_DOCTOR_USER, "cat-doctor-account", "ACTIVE", "DOCTOR");
        seedUser(DOG_DOCTOR_USER, "dog-doctor-account", "ACTIVE", "DOCTOR");
        seedUser(DISABLED_USER, "disabled-doctor-account", "DISABLED", "DOCTOR");
        seedUser(DISABLED_PROFILE_USER, "disabled-profile-account", "ACTIVE", "DOCTOR");
        seedUser(NO_DOCTOR_ROLE_USER, "no-doctor-role", "ACTIVE", "USER");
        seedSession(REQUESTER_SESSION, REQUESTER, "MINI");
        seedSession(DOCTOR_WEB_SESSION, CAT_DOCTOR_USER, "WEB");
        seedQualification();
        seedProfile(CAT_DOCTOR, CAT_DOCTOR_USER, "安医生", "主治医师", "猫科内科,全科", "猫科公开介绍", "ACTIVE", QUALIFICATION);
        seedProfile(DOG_DOCTOR, DOG_DOCTOR_USER, "白医生", "医师", "犬科外科", "犬科公开介绍", "ACTIVE", null);
        seedProfile(DISABLED_ACCOUNT_DOCTOR, DISABLED_USER, "停用账号医生", "医师", "全科", "不可见", "ACTIVE", null);
        seedProfile(DISABLED_PROFILE_DOCTOR, DISABLED_PROFILE_USER, "停用资料医生", "医师", "全科", "不可见", "DISABLED", null);
        seedProfile(NO_ROLE_DOCTOR, NO_DOCTOR_ROLE_USER, "无角色医生", "医师", "全科", "不可见", "ACTIVE", null);
    }

    @Test
    void listsOnlySelectableDoctorsWithStrictSummaryWhitelist() throws Exception {
        String response = mockMvc.perform(get("/api/v1/doctors")
                .header(HttpHeaders.AUTHORIZATION, miniBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("2"))
            .andExpect(jsonPath("$.data.items[0].doctorId").value(Long.toString(CAT_DOCTOR)))
            .andExpect(jsonPath("$.data.items[1].doctorId").value(Long.toString(DOG_DOCTOR)))
            .andExpect(jsonPath("$.data.items[0].introduction").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].qualificationFileId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].userId").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        assertThat(response)
            .doesNotContain("138****8000", Long.toString(QUALIFICATION), "phone", "cat-doctor-account");
    }

    @Test
    void specialtyFilterIsLiteralAndPaginationKeepsFilteredTotal() throws Exception {
        mockMvc.perform(get("/api/v1/doctors")
                .queryParam("specialty", "猫科")
                .header(HttpHeaders.AUTHORIZATION, miniBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("1"))
            .andExpect(jsonPath("$.data.items[0].doctorId").value(Long.toString(CAT_DOCTOR)));

        mockMvc.perform(get("/api/v1/doctors")
                .queryParam("specialty", "%")
                .header(HttpHeaders.AUTHORIZATION, miniBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("0"));

        mockMvc.perform(get("/api/v1/doctors")
                .queryParam("page", "2").queryParam("pageSize", "1")
                .header(HttpHeaders.AUTHORIZATION, miniBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("2"))
            .andExpect(jsonPath("$.data.items[0].doctorId").value(Long.toString(DOG_DOCTOR)));
    }

    @Test
    void detailReturnsPublicIntroductionWithoutAccountOrQualificationFields() throws Exception {
        String response = mockMvc.perform(get("/api/v1/doctors/{doctorId}", CAT_DOCTOR)
                .header(HttpHeaders.AUTHORIZATION, miniBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.doctorName").value("安医生"))
            .andExpect(jsonPath("$.data.introduction").value("猫科公开介绍"))
            .andExpect(jsonPath("$.data.qualificationFileId").doesNotExist())
            .andExpect(jsonPath("$.data.userId").doesNotExist())
            .andExpect(jsonPath("$.data.status").doesNotExist())
            .andExpect(jsonPath("$.data.version").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("138****8000", Long.toString(QUALIFICATION), "phone");
    }

    @Test
    void hiddenAndUnknownDoctorsReturnNotFound() throws Exception {
        for (long doctorId : List.of(
            DISABLED_ACCOUNT_DOCTOR, DISABLED_PROFILE_DOCTOR, NO_ROLE_DOCTOR, 69_999L
        )) {
            mockMvc.perform(get("/api/v1/doctors/{doctorId}", doctorId)
                    .header(HttpHeaders.AUTHORIZATION, miniBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCTOR_NOT_FOUND"));
        }
    }

    @Test
    void requiresMiniAudienceAndActiveDoctorRole() throws Exception {
        mockMvc.perform(get("/api/v1/doctors"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/doctors")
                .header(HttpHeaders.AUTHORIZATION, bearer(
                    CAT_DOCTOR_USER, DOCTOR_WEB_SESSION, "DOCTOR", "WEB"
                )))
            .andExpect(status().isForbidden());

        jdbc.update("UPDATE sys_role SET status='DISABLED' WHERE code='DOCTOR'");
        mockMvc.perform(get("/api/v1/doctors")
                .header(HttpHeaders.AUTHORIZATION, miniBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("0"));
        mockMvc.perform(get("/api/v1/doctors/{doctorId}", CAT_DOCTOR)
                .header(HttpHeaders.AUTHORIZATION, miniBearer()))
            .andExpect(status().isNotFound());
    }

    private String miniBearer() {
        return bearer(REQUESTER, REQUESTER_SESSION, "USER", "MINI");
    }

    private String bearer(long userId, long sessionId, String role, String audience) {
        return "Bearer " + tokenIssuer.issue(
            userId, sessionId, 0, audience, List.of(role), Instant.now(), 1800
        ).value();
    }

    private void seedUser(long id, String username, String status, String role) {
        jdbc.update("""
            INSERT INTO sys_user(
                id,username,password_hash,display_name,phone_cipher,phone_masked,status,token_version,version
            ) VALUES (?,?,NULL,?,X'0102','138****8000',?,0,0)
            """, id, username, username, status);
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

    private void seedQualification() {
        jdbc.update("""
            INSERT INTO file_object(
                id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,
                purpose,status,validated_at
            ) VALUES (?,?,?,'private-qualification.pdf','application/pdf',128,?,
                      'QUALIFICATION','READY',CURRENT_TIMESTAMP(3))
            """, QUALIFICATION, CAT_DOCTOR_USER, "m0602/private-qualification.pdf", "%064x".formatted(QUALIFICATION));
    }

    private void seedProfile(
        long id,
        long userId,
        String doctorName,
        String title,
        String specialties,
        String introduction,
        String status,
        Long qualificationFileId
    ) {
        jdbc.update("""
            INSERT INTO doctor_profile(
                id,user_id,doctor_name,title,specialties,introduction,qualification_file_id,status,version
            ) VALUES (?,?,?,?,?,?,?,?,0)
            """, id, userId, doctorName, title, specialties, introduction, qualificationFileId, status);
    }
}
