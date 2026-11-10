package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "identity.access.completed-appointment-days=30"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M0607IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = NOW.atZone(HOSPITAL_ZONE).toLocalDate();
    private static final Instant DAY_START = TODAY.atStartOfDay(HOSPITAL_ZONE).toInstant();
    private static final Instant NEXT_DAY = TODAY.plusDays(1).atStartOfDay(HOSPITAL_ZONE).toInstant();
    private static final Instant CUTOFF = NOW.minus(30, ChronoUnit.DAYS);
    private static final long OWNER = 160_701;
    private static final long DOCTOR = 160_702;
    private static final long OTHER_DOCTOR = 160_703;
    private static final long DISABLED_DOCTOR = 160_704;
    private static final long ADMIN = 160_705;
    private static final long PET_PENDING = 160_711;
    private static final long PET_CONFIRMED = 160_712;
    private static final long PET_AT_CUTOFF = 160_713;
    private static final long PET_EXPIRED = 160_714;
    private static final long PET_OTHER_DOCTOR = 160_715;
    private static final long PENDING = 160_721;
    private static final long CONFIRMED = 160_722;
    private static final long AT_CUTOFF = 160_723;
    private static final long EXPIRED = 160_724;
    private static final long OTHER = 160_725;
    private static final long TOMORROW = 160_726;
    private static final long HISTORICAL = 160_727;

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        Clock workspaceTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

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

    @BeforeAll
    void seedFixtures() {
        seedActor(OWNER, "MINI", "USER");
        seedActor(DOCTOR, "WEB", "DOCTOR");
        seedActor(OTHER_DOCTOR, "WEB", "DOCTOR");
        seedActor(DISABLED_DOCTOR, "WEB", "DOCTOR");
        seedActor(ADMIN, "WEB", "ADMIN");
        seedDoctor(DOCTOR, "ACTIVE");
        seedDoctor(OTHER_DOCTOR, "ACTIVE");
        seedDoctor(DISABLED_DOCTOR, "DISABLED");
        for (long pet : new long[]{PET_PENDING, PET_CONFIRMED, PET_AT_CUTOFF, PET_EXPIRED, PET_OTHER_DOCTOR}) {
            jdbc.update("""
                INSERT INTO pet(id,owner_user_id,name,species_code,owner_health_notes)
                VALUES (?,?,?,'CAT','主人私密备注')
                """, pet, OWNER, "测试宠物-" + pet);
        }
        seedAppointment(PENDING, PET_PENDING, DOCTOR, "PENDING", DAY_START, null);
        seedAppointment(CONFIRMED, PET_CONFIRMED, DOCTOR, "CONFIRMED", DAY_START, null);
        seedAppointment(AT_CUTOFF, PET_AT_CUTOFF, DOCTOR, "COMPLETED", CUTOFF.minusSeconds(3600), CUTOFF);
        seedAppointment(EXPIRED, PET_EXPIRED, DOCTOR, "COMPLETED", CUTOFF.minusSeconds(3600), CUTOFF.minusMillis(1));
        seedAppointment(OTHER, PET_OTHER_DOCTOR, OTHER_DOCTOR, "CONFIRMED", DAY_START, null);
        seedAppointment(TOMORROW, PET_CONFIRMED, DOCTOR, "CONFIRMED", NEXT_DAY, null);
        seedAppointment(HISTORICAL, PET_CONFIRMED, DOCTOR, "COMPLETED", CUTOFF.minusSeconds(7200), CUTOFF.minusSeconds(3600));
        jdbc.update("""
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at,weight_kg,symptom_note)
            VALUES (160731,?,?,?,4.20,'主人观察到打喷嚏'),(160732,?,?,?,4.00,'待确认健康正文')
            """, PET_CONFIRMED, OWNER, Timestamp.from(NOW), PET_PENDING, OWNER, Timestamp.from(NOW));
        jdbc.update("""
            INSERT INTO medical_record(id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at)
            VALUES (160741,?,?,?,'DRAFT',JSON_OBJECT('allergies',JSON_ARRAY('草稿隐私')),0,NULL),
                   (160742,?,?,?,'SUBMITTED',JSON_OBJECT('allergies',JSON_ARRAY('已提交过敏史')),1,?)
            """, CONFIRMED, PET_CONFIRMED, profileId(DOCTOR), HISTORICAL, PET_CONFIRMED,
            profileId(DOCTOR), utc(CUTOFF.minusSeconds(3600)));
    }

    @Test
    void dashboardShowsOnlyDoctorsOwnAppointmentsAndLimitedPendingCard() throws Exception {
        String response = mockMvc.perform(get("/api/v1/staff/dashboard").header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
            .andExpect(jsonPath("$.data.date").value(TODAY.toString()))
            .andExpect(jsonPath("$.data.today.length()").value(1))
            .andExpect(jsonPath("$.data.today[0].id").value(Long.toString(CONFIRMED)))
            .andExpect(jsonPath("$.data.today[0].petId").value(Long.toString(PET_CONFIRMED)))
            .andExpect(jsonPath("$.data.pending.length()").value(1))
            .andExpect(jsonPath("$.data.pending[0].id").value(Long.toString(PENDING)))
            .andExpect(jsonPath("$.data.pending[0].chiefComplaint").value("预约主诉"))
            .andExpect(jsonPath("$.data.pending[0].petId").value(nullValue()))
            .andExpect(jsonPath("$.data.recentCompleted.length()").value(1))
            .andExpect(jsonPath("$.data.recentCompleted[0].id").value(Long.toString(AT_CUTOFF)))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(response).doesNotContain("主人私密备注", "待确认健康正文", "草稿隐私", "ownerId");
    }

    @Test
    void petListDeduplicatesAppointmentsAndPaginatesOnlyCurrentDoctorScope() throws Exception {
        mockMvc.perform(get("/api/v1/staff/pets").param("pageSize", "1").header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").isNumber())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(PET_AT_CUTOFF)));
        mockMvc.perform(get("/api/v1/staff/pets").param("pageSize", "1").param("page", "2")
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(PET_CONFIRMED)));
    }

    @Test
    void pendingExpiredAndOtherDoctorCannotReadPetOrLogs() throws Exception {
        for (long pet : new long[]{PET_PENDING, PET_EXPIRED, PET_OTHER_DOCTOR}) {
            mockMvc.perform(get("/api/v1/staff/pets/{petId}", pet).header(HttpHeaders.AUTHORIZATION, doctorBearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PET_NOT_FOUND"));
            mockMvc.perform(get("/api/v1/staff/pets/{petId}/health-logs", pet).header(HttpHeaders.AUTHORIZATION, doctorBearer()))
                .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/v1/staff/pets/{petId}", PET_AT_CUTOFF).header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/staff/pets/{petId}/health-logs", PET_AT_CUTOFF)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk());
    }

    @Test
    void confirmedDoctorReadsSubmittedClinicalSummaryAndOwnerReportedLogs() throws Exception {
        String response = mockMvc.perform(get("/api/v1/staff/pets/{petId}", PET_CONFIRMED)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.clinicalSummary.latestWeight.weightKg").value(4.2))
            .andExpect(jsonPath("$.data.clinicalSummary.submittedHistory.allergies[0]").value("已提交过敏史"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(response).doesNotContain("草稿隐私", "主人私密备注");
        mockMvc.perform(get("/api/v1/staff/pets/{petId}/health-logs", PET_CONFIRMED)
                .param("from", NOW.toString()).param("to", NOW.plusMillis(1).toString())
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").isNumber())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].sourceType").value("OWNER_REPORTED"))
            .andExpect(jsonPath("$.data.items[0].symptomNote").value("主人观察到打喷嚏"));
    }

    @Test
    void onlyActiveWebDoctorsCanEnterWorkspace() throws Exception {
        mockMvc.perform(get("/api/v1/staff/dashboard").header(HttpHeaders.AUTHORIZATION, bearer(DISABLED_DOCTOR, "WEB", "DOCTOR")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff/pets").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, "WEB", "ADMIN")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff/pets").header(HttpHeaders.AUTHORIZATION, bearer(OWNER, "MINI", "USER")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff/dashboard")).andExpect(status().isUnauthorized());
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,?,'ACTIVE')",
            id, "m0607-" + id, "测试账号");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?",
            id, id, role);
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0607-" + id,
            Timestamp.from(NOW.plusSeconds(3600)), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void seedDoctor(long userId, String status) {
        jdbc.update("INSERT INTO doctor_profile(id,user_id,doctor_name,status) VALUES (?,?,'测试医生',?)",
            profileId(userId), userId, status);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,10,0,'OPEN')
            """, slotId(userId), profileId(userId), TODAY, utc(DAY_START), utc(DAY_START.plusSeconds(3600)));
    }

    private void seedAppointment(long id, long petId, long doctor, String status, Instant start, Instant completed) {
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,confirm_deadline_at,completed_at,idempotency_key,request_hash
            ) VALUES (?,?,?,?,?,?,?,?,?,?,'预约主诉',?,?,?,?)
            """, id, "M0607-" + id, OWNER, petId, profileId(doctor), slotId(doctor), utc(start),
            utc(start.plusSeconds(1800)), utc(start), status, utc(start.minusSeconds(60)),
            completed == null ? null : utc(completed), "m0607-appointment-" + id, "%064d".formatted(id));
    }

    private String doctorBearer() {
        return bearer(DOCTOR, "WEB", "DOCTOR");
    }

    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), NOW, 1800).value();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static long profileId(long userId) {
        return userId + 100;
    }

    private static long slotId(long userId) {
        return userId + 200;
    }
}
