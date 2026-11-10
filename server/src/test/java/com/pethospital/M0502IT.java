package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
class M0502IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 150_201;
    private static final long OTHER_OWNER = 150_202;
    private static final long DOCTOR_USER = 150_203;
    private static final long OWNER_SESSION = 151_201;
    private static final long OTHER_SESSION = 151_202;
    private static final long DOCTOR_PROFILE = 152_201;
    private static final long SLOT = 153_201;
    private static final long PET = 154_201;

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

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM file_binding WHERE business_id>=150000");
        jdbc.update("DELETE FROM medical_record_version WHERE record_id>=150000");
        jdbc.update("DELETE FROM medical_record WHERE id>=150000");
        jdbc.update("DELETE FROM appointment WHERE id>=150000");
        jdbc.update("DELETE FROM pet_health_log WHERE id>=150000");
        jdbc.update("DELETE FROM pet WHERE id>=150000");
        jdbc.update("DELETE FROM doctor_slot WHERE id>=150000");
        jdbc.update("DELETE FROM doctor_profile WHERE id>=150000");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=150000");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=150000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=150000");
        jdbc.update("DELETE FROM sys_user WHERE id>=150000");

        seedUser(OWNER, "m0502-owner", "USER");
        seedUser(OTHER_OWNER, "m0502-other", "USER");
        seedUser(DOCTOR_USER, "m0502-doctor", "DOCTOR");
        seedSession(OWNER_SESSION, OWNER);
        seedSession(OTHER_SESSION, OTHER_OWNER);
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,'测试医生','ACTIVE',0)
            """, DOCTOR_PROFILE, DOCTOR_USER);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version)
            VALUES (?,?,'2026-10-01','2026-10-01 09:00:00','2026-10-01 10:00:00',3,2,'CLOSED',0)
            """, SLOT, DOCTOR_PROFILE);
        jdbc.update("""
            INSERT INTO pet(
                id,owner_user_id,name,species_code,breed,sex,birth_date,neutered,
                owner_health_notes,status,version
            ) VALUES (?,?,'原名','CAT','英短','FEMALE','2022-05-01','YES','主人原始自述','ACTIVE',0)
            """, PET, OWNER);
    }

    @Test
    void detailSeparatesOwnerReportLatestWeightAndSubmittedHistoryWithoutDraftLeak() throws Exception {
        jdbc.update("""
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at,weight_kg,version)
            VALUES (155201,?,?,?,4.20,0),
                   (155202,?,?,?,4.80,0)
            """, PET, OWNER, Timestamp.from(Instant.parse("2026-09-01T08:00:00Z")),
            PET, OWNER, Timestamp.from(Instant.parse("2026-10-02T08:00:00Z")));
        seedRecord(156_201, 157_201, "SUBMITTED", 1, "2026-10-01T11:00:00Z", """
            {"allergies":["鸡肉"],"chronicConditions":["哮喘"],
             "medications":[{"name":"药A","dosage":"1片","frequency":"每日一次","duration":"7天"}]}
            """);
        seedRecord(156_202, 157_202, "DRAFT", 0, null, """
            {"allergies":["草稿秘密"],"chronicConditions":["草稿诊断"],"medications":[]}
            """);

        String body = mockMvc.perform(get("/api/v1/pets/{petId}", PET)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ownerReported.healthNotes").value("主人原始自述"))
            .andExpect(jsonPath("$.data.clinicalSummary.latestWeight.weightKg").value(4.8))
            .andExpect(jsonPath("$.data.clinicalSummary.latestWeight.observedAt")
                .value("2026-10-02T08:00:00Z"))
            .andExpect(jsonPath("$.data.clinicalSummary.submittedHistory.allergies[0]").value("鸡肉"))
            .andExpect(jsonPath("$.data.clinicalSummary.submittedHistory.chronicConditions[0]").value("哮喘"))
            .andExpect(jsonPath("$.data.clinicalSummary.submittedHistory.medications[0].name").value("药A"))
            .andExpect(jsonPath("$.data.clinicalSummary.submittedHistory.sourceAt")
                .value("2026-10-01T11:00:00Z"))
            .andExpect(jsonPath("$.data.clinicalSummary.submittedHistory.recordId").value("156201"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("草稿秘密", "草稿诊断");
    }

    @Test
    void patchUpdatesOnlyOwnerEditableFieldsWithVersionAndAudit() throws Exception {
        patchPet("""
            {"name":" 新名字 ","speciesCode":"DOG","breed":" 柯基 ","sex":"MALE",
             "birthDate":"2023-02-03","neutered":"NO","ownerHealthNotes":" 新自述 ","version":0}
            """, ownerBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("新名字"))
            .andExpect(jsonPath("$.data.speciesCode").value("DOG"))
            .andExpect(jsonPath("$.data.ownerReported.healthNotes").value("新自述"))
            .andExpect(jsonPath("$.data.version").value(1));

        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM pet WHERE id=?", Long.class, PET))
            .isEqualTo(OWNER);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='PET_UPDATE' AND resource_id=" + PET))
            .isOne();
    }

    @Test
    void rejectsStaleVersionWithoutOverwritingCurrentData() throws Exception {
        jdbc.update("UPDATE pet SET name='并发新值',version=2 WHERE id=?", PET);
        patchPet(validPatch(0), ownerBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT name FROM pet WHERE id=?", String.class, PET))
            .isEqualTo("并发新值");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='PET_UPDATE' AND resource_id=" + PET))
            .isZero();
    }

    @Test
    void crossOwnerCannotReadOrPatchPet() throws Exception {
        mockMvc.perform(get("/api/v1/pets/{petId}", PET)
                .header(HttpHeaders.AUTHORIZATION, otherBearer()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PET_NOT_FOUND"));
        patchPet(validPatch(0), otherBearer())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PET_NOT_FOUND"));
    }

    @Test
    void rejectsClinicalFieldsAndFutureBirthDate() throws Exception {
        patchPet(validPatch(0).replace("\"version\":0", "\"version\":0,\"diagnosis\":\"伪造诊断\""), ownerBearer())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        patchPet(validPatch(0).replace("2023-02-03", LocalDate.now().plusDays(1).toString()), ownerBearer())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"));
        assertThat(jdbc.queryForObject("SELECT version FROM pet WHERE id=?", Integer.class, PET)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions patchPet(String body, String bearer) throws Exception {
        return mockMvc.perform(patch("/api/v1/pets/{petId}", PET)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private void seedRecord(long recordId, long appointmentId, String status, int revision,
                            String submittedAt, String content) {
        Timestamp completed = Timestamp.valueOf("2026-10-01 10:30:00");
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,confirm_deadline_at,completed_at,
                idempotency_key,request_hash,version
            ) VALUES (?,?,?,?,?,?,'2026-10-01 09:00:00','2026-10-01 10:00:00',
                      '2026-10-01 09:00:00','COMPLETED','测试主诉','2026-10-01 08:00:00',?,?,?,0)
            """, appointmentId, "M0502-" + appointmentId, OWNER, PET, DOCTOR_PROFILE, SLOT,
            completed, "m0502-appointment-" + appointmentId,
            "%064d".formatted(appointmentId));
        jdbc.update("""
            INSERT INTO medical_record(
                id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at,version
            ) VALUES (?,?,?,?,?,CAST(? AS JSON),?,?,0)
            """, recordId, appointmentId, PET, DOCTOR_PROFILE, status, content, revision,
            submittedAt == null ? null : Timestamp.from(Instant.parse(submittedAt)));
    }

    private void seedUser(long userId, String username, String roleCode) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, userId, username, username);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, roleCode);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
            userId + 10_000, userId, roleId);
    }

    private void seedSession(long sessionId, long userId) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,'MINI',?,?,?,?,?,?)
            """, sessionId, userId, "%064d".formatted(sessionId), "family-" + sessionId,
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

    private static String validPatch(int version) {
        return """
            {"name":"更新宠物","speciesCode":"CAT","breed":"英短","sex":"FEMALE",
             "birthDate":"2023-02-03","neutered":"YES","ownerHealthNotes":"主人更新自述","version":%d}
            """.formatted(version);
    }
}
