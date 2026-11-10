package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "identity.jwt.allow-ephemeral=true"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M0704IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String PATH = "/api/v1/staff/appointments/{id}/{action}";
    private static final long OWNER = 170_401;
    private static final long DOCTOR = 170_402;
    private static final long OTHER_DOCTOR = 170_403;
    private static final long ADMIN = 170_404;
    private static final long PET = 170_405;
    private static final long CONFIRM = 170_411;
    private static final long REJECT = 170_412;
    private static final long EXPIRED = 170_413;
    private static final long RACE = 170_414;
    private static final long CHANGE_REQUEST = 170_415;
    private static final String VERSION = """
        {"version":0}
        """;
    private static final String REASON = """
        {"version":0,"reason":"无法接诊"}
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

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeAll
    void seedFixtures() {
        seedActor(OWNER, "MINI", "USER");
        seedActor(DOCTOR, "WEB", "DOCTOR");
        seedActor(OTHER_DOCTOR, "WEB", "DOCTOR");
        seedActor(ADMIN, "WEB", "ADMIN");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status)
            VALUES (?,?,'医生夹具','ACTIVE'),(?,?,'其他医生','ACTIVE')
            """, DOCTOR, DOCTOR, OTHER_DOCTOR, OTHER_DOCTOR);
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'测试宠物','CAT')", PET, OWNER);
        Instant now = Instant.now();
        Instant confirmStart = now.plusSeconds(7200);
        Instant rejectStart = now.plusSeconds(10800);
        seedAppointment(CONFIRM, confirmStart, now.plusSeconds(3600));
        seedAppointment(REJECT, rejectStart, now.plusSeconds(3600));
        seedAppointment(EXPIRED, now.plusSeconds(14400), now.minusSeconds(60));
        seedAppointment(RACE, now.plusSeconds(18000), now.plusSeconds(3600));
        jdbc.update("""
            INSERT INTO appointment_change_request(
                id,appointment_id,proposer_user_id,proposer_type,old_slot_id,new_slot_id,base_appointment_version,
                old_start_at,new_start_at,status,open_guard,reason,expires_at,idempotency_key,request_hash
            ) VALUES (?,?,?,'USER',?,?,0,?,?,'OPEN',1,'测试改期',?,?,?)
            """, CHANGE_REQUEST, CONFIRM, OWNER, slot(CONFIRM), slot(REJECT), utc(confirmStart), utc(rejectStart),
            utc(now.plusSeconds(1800)), "m0704-change-request", "%064d".formatted(CHANGE_REQUEST));
    }

    @Test
    void confirmationKeepsOriginalSeatAndExpiresOpenChangeRequest() throws Exception {
        String key = "m0704-confirm-repeat";
        call(CONFIRM, "confirm", key, VERSION, doctor())
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.version").value(1));
        call(CONFIRM, "confirm", key, VERSION, doctor())
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        assertThat(occupied(CONFIRM)).isOne();
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NOT NULL FROM appointment WHERE id=?", Boolean.class, CONFIRM))
            .isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT status='EXPIRED' AND open_guard IS NULL AND version=1
            FROM appointment_change_request WHERE id=?
            """, Boolean.class, CHANGE_REQUEST)).isTrue();
        assertSingleTransition(CONFIRM, "CONFIRMED", "APPOINTMENT_CONFIRMED");
    }

    @Test
    void rejectionReleasesSeatOnceAndRequiresVersionAndReason() throws Exception {
        call(REJECT, "reject", "m0704-reject-invalid", VERSION, doctor()).andExpect(status().isBadRequest());
        call(REJECT, "reject", "m0704-reject-version", """
            {"version":1,"reason":"无法接诊"}
            """, doctor())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        String key = "m0704-reject-repeat";
        call(REJECT, "reject", key, REASON, doctor())
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));
        call(REJECT, "reject", key, REASON, doctor())
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        assertThat(occupied(REJECT)).isZero();
        assertThat(jdbc.queryForObject("SELECT terminal_reason FROM appointment WHERE id=?", String.class, REJECT))
            .isEqualTo("无法接诊");
        assertSingleTransition(REJECT, "REJECTED", "APPOINTMENT_REJECTED");
    }

    @Test
    void expiredDeadlineAndUnauthorizedActorsLeaveAppointmentPending() throws Exception {
        call(EXPIRED, "confirm", "m0704-expired-confirm", VERSION, doctor())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        call(EXPIRED, "confirm", "m0704-missing-version", "{}", doctor()).andExpect(status().isBadRequest());
        call(EXPIRED, "reject", "m0704-other-doctor", REASON, bearer(OTHER_DOCTOR, "WEB", "DOCTOR"))
            .andExpect(status().isNotFound());
        call(EXPIRED, "reject", "m0704-admin-forbid", REASON, bearer(ADMIN, "WEB", "ADMIN"))
            .andExpect(status().isForbidden());
        call(EXPIRED, "reject", "m0704-owner-forbid", REASON, bearer(OWNER, "MINI", "USER"))
            .andExpect(status().isForbidden());
        assertThat(occupied(EXPIRED)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, EXPIRED))
            .isEqualTo("PENDING");
        assertThat(count("SELECT COUNT(*) FROM appointment_status_log WHERE appointment_id=?", EXPIRED)).isZero();
    }

    @Test
    void concurrentConfirmAndRejectApplyExactlyOneDecision() throws Exception {
        String bearer = doctor();
        CountDownLatch go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var confirm = workers.submit(() -> raceCall(go, "confirm", "m0704-racing-confirm", VERSION, bearer));
            var reject = workers.submit(() -> raceCall(go, "reject", "m0704-racing-reject", REASON, bearer));
            go.countDown();
            assertThat(List.of(confirm.get(20, TimeUnit.SECONDS), reject.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        }
        String finalStatus = jdbc.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, RACE);
        assertThat(finalStatus).isIn("CONFIRMED", "REJECTED");
        assertThat(occupied(RACE)).isEqualTo("CONFIRMED".equals(finalStatus) ? 1 : 0);
        assertSingleTransition(RACE, finalStatus, "APPOINTMENT_" + finalStatus);
    }

    private int raceCall(CountDownLatch go, String action, String key, String body, String bearer) throws Exception {
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发请求未同步启动");
        }
        return call(RACE, action, key, body, bearer).andReturn().getResponse().getStatus();
    }

    private void assertSingleTransition(long id, String target, String notice) {
        assertThat(count("""
            SELECT COUNT(*) FROM appointment_status_log WHERE appointment_id=? AND from_status='PENDING' AND to_status=?
            """, id, target)).isOne();
        assertThat(count("SELECT COUNT(*) FROM user_notice WHERE resource_id=? AND recipient_user_id=? AND notice_type=?",
            id, OWNER, notice)).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND resource_type='APPOINTMENT'", id)).isOne();
    }

    private ResultActions call(long id, String action, String key, String body, String bearer) throws Exception {
        return mockMvc.perform(post(PATH, id, action).header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String doctor() {
        return bearer(DOCTOR, "WEB", "DOCTOR");
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
            id, "m0704-" + id, "测试账号");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0704-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private void seedAppointment(long id, Instant start, Instant deadline) {
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,3,1,'OPEN')
            """, slot(id), DOCTOR, utc(start).toLocalDate(), utc(start), utc(start.plusSeconds(1800)));
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,confirm_deadline_at,idempotency_key,request_hash
            ) VALUES (?,?,?,?,?,?,?,?,?,'PENDING','预约主诉',?,?,?)
            """, id, "M0704-" + id, OWNER, PET, DOCTOR, slot(id), utc(start), utc(start.plusSeconds(1800)),
            utc(start), utc(deadline), "m0704-appointment-" + id, "%064d".formatted(id));
    }

    private static long slot(long appointment) {
        return appointment + 100;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
