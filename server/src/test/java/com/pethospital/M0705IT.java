package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.appointment.AppointmentTimeoutScanner;
import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false", "identity.jwt.allow-ephemeral=true",
    "appointment.timeout-scan.enabled=true", "appointment.timeout-scan.interval-ms=3600000"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M0705IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 170_501;
    private static final long OTHER = 170_502;
    private static final long DOCTOR = 170_503;
    private static final long ADMIN = 170_504;
    private static final long PET = 170_505;
    private static final long BY_USER = 170_511;
    private static final long BY_DOCTOR = 170_512;
    private static final long BY_ADMIN = 170_513;
    private static final long TOO_LATE = 170_514;
    private static final long EXPIRED = 170_515;
    private static final long ROLLBACK = 170_516;
    private static final long CHANGE_REQUEST = 170_517;
    private static final String BODY = """
        {"version":0,"reason":"改日就诊"}
        """;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Rs256AccessTokenIssuer tokenIssuer;
    @Autowired AppointmentTimeoutScanner scanner;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduler;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeAll
    void seedFixtures() {
        seedActor(OWNER, "MINI", "USER");
        seedActor(OTHER, "MINI", "USER");
        seedActor(DOCTOR, "WEB", "DOCTOR");
        seedActor(ADMIN, "WEB", "ADMIN");
        jdbc.update("INSERT INTO doctor_profile(id,user_id,doctor_name,status) VALUES (?,?,'医生夹具','ACTIVE')", DOCTOR, DOCTOR);
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'测试宠物','CAT')", PET, OWNER);
        Instant now = Instant.now();
        Instant userStart = now.plusSeconds(7200);
        Instant rollbackStart = now.plusSeconds(14400);
        seedAppointment(BY_USER, "CONFIRMED", userStart, now.plusSeconds(3600));
        seedAppointment(BY_DOCTOR, "CONFIRMED", now.minusSeconds(3600), now.minusSeconds(7200));
        seedAppointment(BY_ADMIN, "CONFIRMED", now.minusSeconds(7200), now.minusSeconds(10800));
        seedAppointment(TOO_LATE, "CONFIRMED", now.minusSeconds(10800), now.minusSeconds(14400));
        seedAppointment(EXPIRED, "PENDING", now.plusSeconds(10800), now.minusSeconds(60));
        seedAppointment(ROLLBACK, "CONFIRMED", rollbackStart, now.plusSeconds(3600));
        jdbc.update("""
            INSERT INTO appointment_change_request(
                id,appointment_id,proposer_user_id,proposer_type,old_slot_id,new_slot_id,base_appointment_version,
                old_start_at,new_start_at,status,open_guard,reason,expires_at,idempotency_key,request_hash
            ) VALUES (?,?,?,'USER',?,?,0,?,?,'OPEN',1,'拟改期',?,?,?)
            """, CHANGE_REQUEST, BY_USER, OWNER, slot(BY_USER), slot(ROLLBACK), utc(userStart), utc(rollbackStart),
            utc(now.plusSeconds(1800)), "m0705-change-request", "%064d".formatted(CHANGE_REQUEST));
    }

    @Test
    void userCancellationIsIdempotentAndInvalidatesOpenChange() throws Exception {
        String key = "m0705-user-cancel";
        String bearer = bearer(OWNER, "MINI", "USER");
        cancel(false, BY_USER, key, bearer).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED")).andExpect(jsonPath("$.data.version").value(1));
        cancel(false, BY_USER, key, bearer).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        assertCancelled(BY_USER, "USER");
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NOT NULL FROM appointment WHERE id=?", Boolean.class, BY_USER))
            .isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT status='EXPIRED' AND open_guard IS NULL FROM appointment_change_request WHERE id=?
            """, Boolean.class, CHANGE_REQUEST)).isTrue();
    }

    @Test
    void doctorAndAdminCancellationRecordTheirRoles() throws Exception {
        cancel(true, BY_DOCTOR, "m0705-doctor-cancel", bearer(DOCTOR, "WEB", "DOCTOR")).andExpect(status().isOk());
        cancel(true, BY_ADMIN, "m0705-admin-cancel", bearer(ADMIN, "WEB", "ADMIN")).andExpect(status().isOk());
        assertCancelled(BY_DOCTOR, "DOCTOR");
        assertCancelled(BY_ADMIN, "ADMIN");
    }

    @Test
    void userCannotCancelAfterStartOrCancelAnotherOwnersAppointment() throws Exception {
        cancel(false, TOO_LATE, "m0705-user-too-late", bearer(OWNER, "MINI", "USER"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        cancel(false, TOO_LATE, "m0705-other-owner", bearer(OTHER, "MINI", "USER"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
        assertThat(occupied(TOO_LATE)).isOne();
    }

    @Test
    void scheduledScannerCancelsDuePendingOnceAndRecordsSystemActor() {
        assertThat(scheduler.getScheduledTasks()).anySatisfy(task ->
            assertThat(task.toString()).contains("AppointmentTimeoutScanner.scan"));
        assertThat(scanner.scan()).isOne();
        assertThat(scanner.scan()).isZero();
        assertCancelled(EXPIRED, "SYSTEM");
        assertThat(count("""
            SELECT COUNT(*) FROM appointment_status_log WHERE appointment_id=? AND actor_type='SYSTEM' AND actor_user_id IS NULL
            """, EXPIRED)).isOne();
        assertThat(count("""
            SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND actor_type='SYSTEM' AND actor_user_id IS NULL
              AND action='APPOINTMENT_TIMEOUT_CANCEL'
            """, EXPIRED)).isOne();
    }

    @Test
    void noticeWriteFailureRollsBackStatusSeatAndAudit() throws Exception {
        jdbc.execute("""
            ALTER TABLE user_notice ADD CONSTRAINT ck_m0705_notice_fault
            CHECK (resource_id<>%d OR notice_type<>'APPOINTMENT_CANCELLED')
            """.formatted(ROLLBACK));
        try {
            cancel(false, ROLLBACK, "m0705-rollback-cancel", bearer(OWNER, "MINI", "USER"))
                .andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("ALTER TABLE user_notice DROP CHECK ck_m0705_notice_fault");
        }
        assertThat(occupied(ROLLBACK)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, ROLLBACK))
            .isEqualTo("CONFIRMED");
        assertThat(count("SELECT COUNT(*) FROM appointment_status_log WHERE appointment_id=?", ROLLBACK)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE actor_user_id=? AND operation_key='m0705-rollback-cancel'", OWNER))
            .isZero();
    }

    private void assertCancelled(long id, String source) {
        assertThat(occupied(id)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT status='CANCELLED' AND cancelled_by=? AND cancelled_at IS NOT NULL AND version=1
            FROM appointment WHERE id=?
            """, Boolean.class, source, id)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM appointment_status_log WHERE appointment_id=? AND to_status='CANCELLED'", id))
            .isOne();
        assertThat(count("SELECT COUNT(*) FROM user_notice WHERE resource_id=? AND notice_type='APPOINTMENT_CANCELLED'", id))
            .isEqualTo(2);
    }

    private ResultActions cancel(boolean staff, long id, String key, String bearer) throws Exception {
        String path = staff ? "/api/v1/staff/appointments/{id}/cancel" : "/api/v1/appointments/{id}/cancel";
        return mockMvc.perform(post(path, id).header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(BODY));
    }

    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }

    private int occupied(long appointment) {
        return jdbc.queryForObject("SELECT occupied_count FROM doctor_slot WHERE id=?", Integer.class, slot(appointment));
    }

    private int count(String sql, Object... params) {
        return jdbc.queryForObject(sql, Integer.class, params);
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,?,'ACTIVE')",
            id, "m0705-" + id, "测试账号");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0705-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private void seedAppointment(long id, String status, Instant start, Instant deadline) {
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,3,1,'OPEN')
            """, slot(id), DOCTOR, utc(start).toLocalDate(), utc(start), utc(start.plusSeconds(1800)));
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,confirm_deadline_at,confirmed_at,idempotency_key,request_hash
            ) VALUES (?,?,?,?,?,?,?,?,?,?,'预约主诉',?,?,?,?)
            """, id, "M0705-" + id, OWNER, PET, DOCTOR, slot(id), utc(start), utc(start.plusSeconds(1800)),
            utc(start), status, utc(deadline), "CONFIRMED".equals(status) ? utc(deadline.minusSeconds(60)) : null,
            "m0705-appointment-" + id, "%064d".formatted(id));
    }

    private static long slot(long appointment) {
        return appointment + 100;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
