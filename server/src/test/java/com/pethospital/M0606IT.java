package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.identity.ResourceAccessPolicy.Actor;
import com.pethospital.identity.ResourceAccessPolicy.AppointmentVisibility;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.test.web.servlet.ResultActions;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true"
})
@AutoConfigureMockMvc
class M0606IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long ADMIN = 79_001;
    private static final long DOCTOR_USER = 79_002;
    private static final long OWNER = 79_003;
    private static final long ORDINARY_USER = 79_004;
    private static final long ADMIN_SESSION = 79_101;
    private static final long DOCTOR_SESSION = 79_102;
    private static final long OWNER_SESSION = 79_103;
    private static final long ORDINARY_SESSION = 79_104;
    private static final long DOCTOR = 79_201;
    private static final long PET = 79_501;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Rs256AccessTokenIssuer tokenIssuer;
    @Autowired ResourceAccessPolicy accessPolicy;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id BETWEEN 79000 AND 79999");
        jdbc.update("DELETE FROM appointment_status_log WHERE appointment_id BETWEEN 79000 AND 79999");
        jdbc.update("DELETE FROM appointment WHERE id BETWEEN 79000 AND 79999");
        jdbc.update("DELETE FROM doctor_slot WHERE id BETWEEN 79000 AND 79999");
        jdbc.update("DELETE FROM pet WHERE id BETWEEN 79000 AND 79999");
        jdbc.update("DELETE FROM doctor_profile WHERE id BETWEEN 79000 AND 79999");
        jdbc.update("DELETE FROM auth_session WHERE user_id BETWEEN 79000 AND 79999");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id BETWEEN 79000 AND 79999");
        jdbc.update("DELETE FROM sys_user WHERE id BETWEEN 79000 AND 79999");
        jdbc.update("UPDATE sys_role SET status='ACTIVE' WHERE code IN ('ADMIN','DOCTOR','USER')");

        seedUser(ADMIN, "m0606-admin", "ACTIVE", "ADMIN");
        seedUser(DOCTOR_USER, "m0606-doctor", "ACTIVE", "DOCTOR");
        seedUser(OWNER, "m0606-owner", "ACTIVE", "USER");
        seedUser(ORDINARY_USER, "m0606-user", "ACTIVE", "USER");
        seedSession(ADMIN_SESSION, ADMIN, "WEB");
        seedSession(DOCTOR_SESSION, DOCTOR_USER, "WEB");
        seedSession(OWNER_SESSION, OWNER, "MINI");
        seedSession(ORDINARY_SESSION, ORDINARY_USER, "WEB");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,'启停测试医生','ACTIVE',0)
            """, DOCTOR, DOCTOR_USER);
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,sex,neutered,status,version)
            VALUES (?,?,'启停测试宠物','CAT','UNKNOWN','UNKNOWN','ACTIVE',0)
            """, PET, OWNER);
    }

    @Test
    void unfinishedAppointmentBlocksDisableUntilProcessed() throws Exception {
        LocalDate date = futureDate(2);
        seedSlot(79_301, date, 9, 10, 1, 1, "OPEN");
        seedAppointment(79_401, 79_301, date, "PENDING");

        disable(0, "先处理预约", "m0606-disable-pending")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("UNFINISHED_APPOINTMENTS"))
            .andExpect(jsonPath("$.data.pendingCount").value(1))
            .andExpect(jsonPath("$.data.doctorPendingCount").value(1));
        assertThat(profileStatus()).isEqualTo("ACTIVE");

        jdbc.update("UPDATE doctor_slot SET status='CLOSED',version=version+1 WHERE id=79301");
        disable(0, "关闭入口后仍需处理预约", "m0606-disable-still-pending")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.pendingCount").value(1));

        jdbc.update("UPDATE appointment SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(3) WHERE id=79401");
        disable(0, "预约已经处理", "m0606-disable-success")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DISABLED"))
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='DOCTOR_PROFILE_DISABLE'"
            + " AND resource_id=" + DOCTOR)).isOne();
    }

    @Test
    void disableIsIdempotentAndImmediatelyHidesPublicEntry() throws Exception {
        disable(0, "暂停接诊", "m0606-disable-replay")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DISABLED"));
        disable(0, "暂停接诊", "m0606-disable-replay")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='DOCTOR_PROFILE_DISABLE'"
            + " AND resource_id=" + DOCTOR)).isOne();

        mockMvc.perform(get("/api/v1/doctors/{doctorId}", DOCTOR)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer()))
            .andExpect(status().isNotFound());
    }

    @Test
    void enableRequiresActiveAccountAndDoctorRole() throws Exception {
        jdbc.update("UPDATE doctor_profile SET status='DISABLED' WHERE id=?", DOCTOR);
        jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", DOCTOR_USER);
        enable(0, "m0606-enable-account-off")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DOCTOR_ACCOUNT_NOT_ACTIVE"));

        jdbc.update("UPDATE sys_user SET status='ACTIVE' WHERE id=?", DOCTOR_USER);
        jdbc.update("UPDATE sys_role SET status='DISABLED' WHERE code='DOCTOR'");
        enable(0, "m0606-enable-role-off")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DOCTOR_ACCOUNT_NOT_ACTIVE"));

        jdbc.update("UPDATE sys_role SET status='ACTIVE' WHERE code='DOCTOR'");
        enable(0, "m0606-enable-success")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void oldDoctorSessionCannotCreateNewSlotAfterDisable() throws Exception {
        LocalDate completedDate = futureDate(1);
        seedSlot(79_320, completedDate, 8, 9, 1, 1, "CLOSED");
        seedAppointment(79_420, 79_320, completedDate, "CONFIRMED");
        jdbc.update("""
            UPDATE appointment SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(3) WHERE id=79420
            """);
        assertThat(accessPolicy.appointment(new Actor(DOCTOR_USER, "WEB"), 79_420))
            .isEqualTo(AppointmentVisibility.FULL);

        disable(0, "停止新临床写入", "m0606-disable-old-session")
            .andExpect(status().isOk());
        assertThat(accessPolicy.appointment(new Actor(DOCTOR_USER, "WEB"), 79_420))
            .isEqualTo(AppointmentVisibility.NONE);
        LocalDate date = futureDate(3);
        Instant start = atShanghai(date, 9);
        Instant end = atShanghai(date, 10);
        mockMvc.perform(post("/api/v1/staff/slots")
                .header(HttpHeaders.AUTHORIZATION, doctorBearer())
                .header("Idempotency-Key", "m0606-old-session-slot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"doctorId":"%d","date":"%s","startAt":"%s","endAt":"%s","capacity":1}
                    """.formatted(DOCTOR, date, start, end)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DOCTOR_NOT_AVAILABLE"));
        assertThat(count("SELECT COUNT(*) FROM doctor_slot WHERE doctor_id=" + DOCTOR)).isOne();
    }

    @Test
    void requiresManagePermissionAndRejectsStaleOrUnknownFields() throws Exception {
        mockMvc.perform(post("/api/v1/admin/doctors/{doctorId}/disable", DOCTOR)
                .header(HttpHeaders.AUTHORIZATION, ordinaryBearer())
                .header("Idempotency-Key", "m0606-ordinary-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"reason\":\"越权\"}"))
            .andExpect(status().isForbidden());
        disable(1, "旧版本", "m0606-stale-version")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(post("/api/v1/admin/doctors/{doctorId}/enable", DOCTOR)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .header("Idempotency-Key", "m0606-unknown-field")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"status\":\"ACTIVE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private ResultActions disable(int version, String reason, String operationKey) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/doctors/{doctorId}/disable", DOCTOR)
            .header(HttpHeaders.AUTHORIZATION, adminBearer())
            .header("Idempotency-Key", operationKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + ",\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions enable(int version, String operationKey) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/doctors/{doctorId}/enable", DOCTOR)
            .header(HttpHeaders.AUTHORIZATION, adminBearer())
            .header("Idempotency-Key", operationKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + "}"));
    }

    private String profileStatus() {
        return jdbc.queryForObject("SELECT status FROM doctor_profile WHERE id=?", String.class, DOCTOR);
    }

    private String adminBearer() {
        return bearer(ADMIN, ADMIN_SESSION, "ADMIN", "WEB");
    }

    private String doctorBearer() {
        return bearer(DOCTOR_USER, DOCTOR_SESSION, "DOCTOR", "WEB");
    }

    private String ownerBearer() {
        return bearer(OWNER, OWNER_SESSION, "USER", "MINI");
    }

    private String ordinaryBearer() {
        return bearer(ORDINARY_USER, ORDINARY_SESSION, "USER", "WEB");
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

    private void seedSlot(
        long id, LocalDate date, int startHour, int endHour, int capacity, int occupied, String status
    ) {
        jdbc.update("""
            INSERT INTO doctor_slot(
                id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
            ) VALUES (?,?,?,?,?,?,?,?,0)
            """, id, DOCTOR, date, utcTimestamp(atShanghai(date, startHour)),
            utcTimestamp(atShanghai(date, endHour)), capacity, occupied, status);
    }

    private void seedAppointment(long id, long slotId, LocalDate date, String status) {
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,
                scheduled_start_at,scheduled_end_at,initial_start_at,status,chief_complaint,
                confirm_deadline_at,idempotency_key,request_hash,version
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
            """, id, "M0606-" + id, OWNER, PET, DOCTOR, slotId,
            utcTimestamp(atShanghai(date, 9)), utcTimestamp(atShanghai(date, 10)),
            utcTimestamp(atShanghai(date, 9)), status, "医生停用前置检查",
            utcTimestamp(atShanghai(date.minusDays(1), 9)), "m0606-appointment-" + id,
            "%064x".formatted(id));
    }

    private int count(String sql) {
        Integer result = jdbc.queryForObject(sql, Integer.class);
        return result == null ? 0 : result;
    }

    private static LocalDate futureDate(long days) {
        return LocalDate.now(SHANGHAI).plusDays(days);
    }

    private static Instant atShanghai(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(SHANGHAI).toInstant();
    }

    private static Timestamp utcTimestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
