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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "identity.jwt.allow-ephemeral=true"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M0703IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String USER_PATH = "/api/v1/appointments";
    private static final String STAFF_PATH = "/api/v1/staff/appointments";
    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long OWNER = 170_301;
    private static final long OTHER_OWNER = 170_302;
    private static final long DOCTOR = 170_303;
    private static final long OTHER_DOCTOR = 170_304;
    private static final long ADMIN = 170_305;
    private static final long OWNER_PET = 170_311;
    private static final long OTHER_PET = 170_312;
    private static final long AI_SESSION = 170_313;
    private static final long PENDING = 9_007_199_254_740_993L;
    private static final long CONFIRMED = PENDING + 1;
    private static final long OTHER = PENDING + 2;
    private LocalDate hospitalDate;

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
        seedActor(OTHER_OWNER, "MINI", "USER");
        seedActor(DOCTOR, "WEB", "DOCTOR");
        seedActor(OTHER_DOCTOR, "WEB", "DOCTOR");
        seedActor(ADMIN, "WEB", "ADMIN");
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,owner_health_notes)
            VALUES (?,?,'猫甲','CAT','病史保密夹具'),(?,?,'猫乙','CAT','其他主人私密资料')
            """, OWNER_PET, OWNER, OTHER_PET, OTHER_OWNER);
        jdbc.update("""
            INSERT INTO ai_session(id,user_id,pet_id,title,pet_snapshot_json)
            VALUES (?,?,?,'会话夹具',JSON_OBJECT())
            """, AI_SESSION, OWNER, OWNER_PET);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        hospitalDate = now.atZone(HOSPITAL_ZONE).toLocalDate().plusDays(2);
        Instant start = hospitalDate.atStartOfDay(HOSPITAL_ZONE).toInstant();
        Instant nextDay = hospitalDate.plusDays(1).atStartOfDay(HOSPITAL_ZONE).toInstant();
        seedDoctor(DOCTOR, start);
        seedDoctor(OTHER_DOCTOR, start);
        seedSlot(DOCTOR + 100, DOCTOR, nextDay);
        seedAppointment(PENDING, OWNER, OWNER_PET, DOCTOR, "PENDING", start, now, AI_SESSION, "待确认内部夹具");
        seedAppointment(CONFIRMED, OWNER, OWNER_PET, DOCTOR, "CONFIRMED", nextDay, now, null, null);
        seedAppointment(OTHER, OTHER_OWNER, OTHER_PET, OTHER_DOCTOR, "PENDING", start, now, null, null);
        jdbc.update("""
            INSERT INTO appointment_status_log(
                id,appointment_id,from_status,to_status,actor_type,actor_user_id,operation_key,request_hash,created_at
            ) VALUES (170361,?,NULL,'PENDING','USER',?,'m0703-internal-initial',?,?),
                     (170362,?,'PENDING','CONFIRMED','DOCTOR',?,'m0703-internal-confirm',?,?)
            """, CONFIRMED, OWNER, "%064d".formatted(CONFIRMED), utc(now),
            CONFIRMED, DOCTOR, "%064d".formatted(CONFIRMED + 1), utc(now));
    }

    @Test
    void ownerReadsOnlyOwnAppointmentsWithStableIdTieBreak() throws Exception {
        String owner = bearer(OWNER, "MINI", "USER");
        mockMvc.perform(get(USER_PATH).param("pageSize", "1").header(HttpHeaders.AUTHORIZATION, owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").isNumber())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(CONFIRMED)));
        mockMvc.perform(get(USER_PATH).param("pageSize", "1").param("page", "2").header(HttpHeaders.AUTHORIZATION, owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(PENDING)));
        mockMvc.perform(get(USER_PATH + "/{id}", PENDING).header(HttpHeaders.AUTHORIZATION, owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.appointment.petId").value(Long.toString(OWNER_PET)))
            .andExpect(jsonPath("$.data.aiSessionId").value(Long.toString(AI_SESSION)))
            .andExpect(jsonPath("$.data.appointment.allowedActions.length()").value(1))
            .andExpect(jsonPath("$.data.appointment.allowedActions[0]").value("CANCEL"));
        mockMvc.perform(get(USER_PATH + "/{id}", OTHER).header(HttpHeaders.AUTHORIZATION, owner))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
    }

    @Test
    void doctorScopeAndHospitalDateFiltersAreAppliedBeforePagination() throws Exception {
        String doctor = bearer(DOCTOR, "WEB", "DOCTOR");
        mockMvc.perform(get(STAFF_PATH).header(HttpHeaders.AUTHORIZATION, doctor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get(STAFF_PATH).param("date", hospitalDate.toString())
                .param("status", "PENDING").param("petId", Long.toString(OWNER_PET))
                .header(HttpHeaders.AUTHORIZATION, doctor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(PENDING)))
            .andExpect(jsonPath("$.data.items[0].petId").value(nullValue()));
        mockMvc.perform(get(STAFF_PATH).param("date", hospitalDate.toString()).param("status", "CONFIRMED")
                .header(HttpHeaders.AUTHORIZATION, doctor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get(STAFF_PATH + "/{id}", OTHER).header(HttpHeaders.AUTHORIZATION, doctor))
            .andExpect(status().isNotFound());
        mockMvc.perform(get(STAFF_PATH).param("status", "UNKNOWN").header(HttpHeaders.AUTHORIZATION, doctor))
            .andExpect(status().isBadRequest());
    }

    @Test
    void pendingStaffDetailOmitsClinicalAndInternalFields() throws Exception {
        String doctor = bearer(DOCTOR, "WEB", "DOCTOR");
        String body = mockMvc.perform(get(STAFF_PATH + "/{id}", PENDING).header(HttpHeaders.AUTHORIZATION, doctor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.chiefComplaint").value("预约主诉"))
            .andExpect(jsonPath("$.data.appointment.petId").value(nullValue()))
            .andExpect(jsonPath("$.data.aiSessionId").value(nullValue()))
            .andExpect(jsonPath("$.data.terminalReason").value(nullValue()))
            .andExpect(jsonPath("$.data.appointment.allowedActions.length()").value(3))
            .andExpect(jsonPath("$.data.appointment.allowedActions[0]").value("CONFIRM"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("待确认内部夹具", "病史保密夹具", "ownerUserId", "userId");
        mockMvc.perform(get(STAFF_PATH + "/{id}", CONFIRMED).header(HttpHeaders.AUTHORIZATION, doctor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.appointment.petId").value(Long.toString(OWNER_PET)));
    }

    @Test
    void adminCanListAllAndReadChronologicalRedactedStatusLogs() throws Exception {
        String admin = bearer(ADMIN, "WEB", "ADMIN");
        mockMvc.perform(get(STAFF_PATH).header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        mockMvc.perform(get(STAFF_PATH + "/{id}", PENDING).header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.appointment.allowedActions.length()").value(1))
            .andExpect(jsonPath("$.data.appointment.allowedActions[0]").value("CANCEL"));
        String logs = mockMvc.perform(get(STAFF_PATH + "/{id}/status-logs", CONFIRMED).header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].fromStatus").value(nullValue()))
            .andExpect(jsonPath("$.data[0].toStatus").value("PENDING"))
            .andExpect(jsonPath("$.data[1].toStatus").value("CONFIRMED"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(logs).doesNotContain("operationKey", "requestHash", "actorUserId", "m0703-internal-");
        mockMvc.perform(get(STAFF_PATH + "/{id}/status-logs", CONFIRMED)
                .header(HttpHeaders.AUTHORIZATION, bearer(OTHER_DOCTOR, "WEB", "DOCTOR")))
            .andExpect(status().isNotFound());
        mockMvc.perform(get(STAFF_PATH).header(HttpHeaders.AUTHORIZATION, bearer(OWNER, "MINI", "USER")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(USER_PATH).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isForbidden());
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,?,'ACTIVE')",
            id, "m0703-" + id, "测试账号");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0703-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private void seedDoctor(long id, Instant start) {
        jdbc.update("INSERT INTO doctor_profile(id,user_id,doctor_name,status) VALUES (?,?,'医生夹具','ACTIVE')", id, id);
        seedSlot(id, id, start);
    }

    private void seedSlot(long id, long doctor, Instant start) {
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,status)
            VALUES (?,?,?,?,?,5,'OPEN')
            """, id, doctor, start.atZone(HOSPITAL_ZONE).toLocalDate(), utc(start), utc(start.plusSeconds(3600)));
    }

    private void seedAppointment(
        long id, long owner, long pet, long doctor, String status, Instant start, Instant created, Long ai, String reason
    ) {
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,initial_start_at,
                status,chief_complaint,ai_session_id,confirm_deadline_at,confirmed_at,terminal_reason,idempotency_key,request_hash,created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,'预约主诉',?,?,?,?,?,?,?)
            """, id, "M0703-" + id, owner, pet, doctor, id == CONFIRMED ? doctor + 100 : doctor,
            utc(start), utc(start.plusSeconds(1800)), utc(start),
            status, ai, utc(start.minusSeconds(60)), "CONFIRMED".equals(status) ? utc(created) : null, reason,
            "m0703-appointment-" + id, "%064d".formatted(id), utc(created));
    }

    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
