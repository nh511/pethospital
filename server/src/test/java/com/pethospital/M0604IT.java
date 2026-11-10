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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
class M0604IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long ADMIN = 70_001;
    private static final long DOCTOR_ONE_USER = 70_101;
    private static final long DOCTOR_TWO_USER = 70_102;
    private static final long OWNER = 70_201;
    private static final long ADMIN_SESSION = 71_001;
    private static final long DOCTOR_SESSION = 71_101;
    private static final long OWNER_WEB_SESSION = 71_201;
    private static final long DOCTOR_ONE = 72_101;
    private static final long DOCTOR_TWO = 72_102;
    private static final long PET = 73_001;

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
        jdbc.update("DELETE FROM appointment_status_log");
        jdbc.update("DELETE FROM appointment_change_request");
        jdbc.update("DELETE FROM appointment WHERE id BETWEEN 74000 AND 74999");
        jdbc.update("DELETE FROM doctor_slot WHERE id BETWEEN 74000 AND 74999");
        jdbc.update("DELETE FROM pet WHERE id=?", PET);
        jdbc.update("DELETE FROM doctor_profile WHERE id BETWEEN 72000 AND 72999");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id BETWEEN 70000 AND 70999");
        jdbc.update("DELETE FROM auth_session WHERE user_id BETWEEN 70000 AND 70999");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id BETWEEN 70000 AND 70999");
        jdbc.update("DELETE FROM sys_user WHERE id BETWEEN 70000 AND 70999");
        seedUser(ADMIN, "slot-action-admin", "ADMIN");
        seedUser(DOCTOR_ONE_USER, "slot-action-doctor1", "DOCTOR");
        seedUser(DOCTOR_TWO_USER, "slot-action-doctor2", "DOCTOR");
        seedUser(OWNER, "slot-action-owner", "USER");
        seedSession(ADMIN_SESSION, ADMIN, "WEB");
        seedSession(DOCTOR_SESSION, DOCTOR_ONE_USER, "WEB");
        seedSession(OWNER_WEB_SESSION, OWNER, "WEB");
        seedDoctor(DOCTOR_ONE, DOCTOR_ONE_USER, "一号医生");
        seedDoctor(DOCTOR_TWO, DOCTOR_TWO_USER, "二号医生");
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code,status,version) VALUES (?,?,?,'CAT','ACTIVE',0)",
            PET, OWNER, "排班测试宠物");
    }

    @Test
    void capacityCannotDropBelowOccupiedAndSuccessfulPatchAuditsOnlyCapacity() throws Exception {
        long slotId = 74_001;
        seedSlot(slotId, DOCTOR_ONE, futureDate(2), 9, 10, 3, 2, "OPEN", 0);
        patchCapacity(doctorBearer(), slotId, 1, 0, "")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CAPACITY_BELOW_OCCUPIED"));
        patchCapacity(doctorBearer(), slotId, 2, 0, "")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.capacity").value(2))
            .andExpect(jsonPath("$.data.occupiedCount").value(2))
            .andExpect(jsonPath("$.data.availableCount").value(0))
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='DOCTOR_SLOT_CAPACITY_UPDATE'"
            + " AND resource_id=" + slotId)).isOne();
    }

    @Test
    void closingOccupiedSlotKeepsAppointmentAndIsIdempotentlyAudited() throws Exception {
        long slotId = 74_002;
        long appointmentId = 74_102;
        LocalDate date = futureDate(3);
        seedSlot(slotId, DOCTOR_ONE, date, 10, 11, 2, 1, "OPEN", 0);
        seedAppointment(appointmentId, slotId, date, 10, 11);
        close(doctorBearer(), slotId, 0, "临时停止新预约", "m0604-close-0001")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CLOSED"))
            .andExpect(jsonPath("$.data.occupiedCount").value(1))
            .andExpect(jsonPath("$.data.version").value(1));
        close(doctorBearer(), slotId, 0, "临时停止新预约", "m0604-close-0001")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbc.queryForObject(
            "SELECT status FROM appointment WHERE id=?", String.class, appointmentId
        )).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
            "SELECT occupied_count FROM doctor_slot WHERE id=?", Integer.class, slotId
        )).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='DOCTOR_SLOT_CLOSE'"
            + " AND resource_id=" + slotId)).isOne();
    }

    @Test
    void opensFutureClosedSlotButRejectsPastSlot() throws Exception {
        long futureSlot = 74_003;
        long pastSlot = 74_004;
        seedSlot(futureSlot, DOCTOR_ONE, futureDate(4), 9, 10, 1, 0, "CLOSED", 0);
        seedSlot(pastSlot, DOCTOR_ONE, LocalDate.now(SHANGHAI).minusDays(1), 9, 10, 1, 0, "CLOSED", 0);
        open(doctorBearer(), futureSlot, 0, "m0604-open-future")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("OPEN"))
            .andExpect(jsonPath("$.data.version").value(1));
        open(doctorBearer(), pastSlot, 0, "m0604-open-past01")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAST_SLOT_CANNOT_OPEN"));
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='DOCTOR_SLOT_OPEN'"
            + " AND resource_id=" + futureSlot)).isOne();
    }

    @Test
    void doctorCannotChangeAnotherDoctorSlotButAdminCan() throws Exception {
        long slotId = 74_005;
        seedSlot(slotId, DOCTOR_TWO, futureDate(5), 9, 10, 2, 0, "OPEN", 0);
        patchCapacity(doctorBearer(), slotId, 3, 0, "")
            .andExpect(status().isForbidden());
        patchCapacity(adminBearer(), slotId, 3, 0, "")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.capacity").value(3));
        patchCapacity(ownerWebBearer(), slotId, 4, 1, "")
            .andExpect(status().isForbidden());
    }

    @Test
    void rejectsImmutableFieldsAndStaleVersion() throws Exception {
        long slotId = 74_006;
        seedSlot(slotId, DOCTOR_ONE, futureDate(6), 9, 10, 2, 0, "OPEN", 1);
        patchCapacity(doctorBearer(), slotId, 3, 1, ",\"doctorId\":\"72102\"")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        patchCapacity(doctorBearer(), slotId, 3, 0, "")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject(
            "SELECT capacity FROM doctor_slot WHERE id=?", Integer.class, slotId
        )).isEqualTo(2);
    }

    private org.springframework.test.web.servlet.ResultActions patchCapacity(
        String bearer, long slotId, int capacity, int version, String extra
    ) throws Exception {
        return mockMvc.perform(patch("/api/v1/staff/slots/{slotId}", slotId)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"capacity\":" + capacity + ",\"version\":" + version + extra + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions close(
        String bearer, long slotId, int version, String reason, String key
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/slots/{slotId}/close", slotId)
            .header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + ",\"reason\":\"" + reason + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions open(
        String bearer, long slotId, int version, String key
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/slots/{slotId}/open", slotId)
            .header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + "}"));
    }

    private void seedSlot(
        long id, long doctorId, LocalDate date, int startHour, int endHour,
        int capacity, int occupied, String status, int version
    ) {
        Instant start = atShanghai(date, startHour).toInstant();
        Instant end = atShanghai(date, endHour).toInstant();
        jdbc.update("""
            INSERT INTO doctor_slot(
                id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """, id, doctorId, date, utcTimestamp(start), utcTimestamp(end),
            capacity, occupied, status, version);
    }

    private void seedAppointment(long id, long slotId, LocalDate date, int startHour, int endHour) {
        Instant start = atShanghai(date, startHour).toInstant();
        Instant end = atShanghai(date, endHour).toInstant();
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,
                scheduled_start_at,scheduled_end_at,initial_start_at,status,chief_complaint,
                confirm_deadline_at,confirmed_at,idempotency_key,request_hash,version
            ) VALUES (?,?,?,?,?,?,?,?,?,'CONFIRMED','复诊',?,?,?,REPEAT('a',64),0)
            """, id, "APT-M0604-" + id, OWNER, PET, DOCTOR_ONE, slotId,
            utcTimestamp(start), utcTimestamp(end), utcTimestamp(start),
            utcTimestamp(start.minusSeconds(3600)), utcTimestamp(Instant.now()), "m0604-appointment-" + id);
    }

    private LocalDate futureDate(int days) {
        return LocalDate.now(SHANGHAI).plusDays(days);
    }

    private static OffsetDateTime atShanghai(LocalDate date, int hour) {
        return OffsetDateTime.of(date, LocalTime.of(hour, 0), ZoneOffset.ofHours(8));
    }

    private static Timestamp utcTimestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private String doctorBearer() {
        return bearer(DOCTOR_ONE_USER, DOCTOR_SESSION, "DOCTOR");
    }

    private String adminBearer() {
        return bearer(ADMIN, ADMIN_SESSION, "ADMIN");
    }

    private String ownerWebBearer() {
        return bearer(OWNER, OWNER_WEB_SESSION, "USER");
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

    private void seedSession(long id, long userId, String clientType) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """, id, userId, clientType, "%064d".formatted(id), "family-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private void seedDoctor(long id, long userId, String name) {
        jdbc.update("INSERT INTO doctor_profile(id,user_id,doctor_name,status,version) VALUES (?,?,?,'ACTIVE',0)",
            id, userId, name);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
