package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.appointment.AppointmentChangeTimeoutScanner;
import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
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
class M0709IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 170_901;
    private static final long OTHER = 170_902;
    private static final long DOCTOR = 170_903;
    private static final long ADMIN = 170_904;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private enum Scenario { REJECT, WITHDRAW, EXPIRE, CONFIRM }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Rs256AccessTokenIssuer tokenIssuer;
    @Autowired AppointmentChangeTimeoutScanner scanner;
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
        jdbc.update("INSERT INTO doctor_profile(id,user_id,doctor_name,status) VALUES (?,?,'医生夹具','ACTIVE')",
            DOCTOR, DOCTOR);
        for (Scenario scenario : Scenario.values()) {
            seedScenario(scenario);
        }
    }

    @Test
    void onlyOtherPartyRejectsWithVersionAndReasonAndReplaysOnce() throws Exception {
        Scenario s = Scenario.REJECT;
        String body = """
            {"version":0,"reason":"保留原预约"}
            """;
        action(s, "reject", "m0709-self-reject", owner(), body).andExpect(status().isForbidden());
        action(s, "reject", "m0709-admin-reject", bearer(ADMIN, "WEB", "ADMIN"), body).andExpect(status().isForbidden());
        action(s, "reject", "m0709-stale-reject", doctor(), """
            {"version":1,"reason":"保留原预约"}
            """).andExpect(status().isConflict());
        action(s, "reject", "m0709-empty-reason", doctor(), """
            {"version":0}
            """).andExpect(status().isBadRequest());
        for (int attempt = 0; attempt < 2; attempt++) {
            action(s, "reject", "m0709-valid-reject", doctor(), body)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));
        }
        assertClosed(s, "REJECTED", DOCTOR, "保留原预约");
    }

    @Test
    void onlyProposerWithdrawsAndOtherOwnerCannotSeeRequest() throws Exception {
        Scenario s = Scenario.WITHDRAW;
        String body = """
            {"version":0}
            """;
        action(s, "withdraw", "m0709-other-party-withdraw", doctor(), body).andExpect(status().isForbidden());
        action(s, "withdraw", "m0709-other-owner-withdraw", bearer(OTHER, "MINI", "USER"), body).andExpect(status().isNotFound());
        action(s, "withdraw", "m0709-owner-withdraw", owner(), body)
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        action(s, "withdraw", "m0709-repeat-different-key", owner(), """
            {"version":1}
            """).andExpect(status().isConflict());
        assertClosed(s, "WITHDRAWN", OWNER, null);
    }

    @Test
    void registeredExpiryScannerClosesDueRequestOnceWithSystemAudit() throws Exception {
        Scenario s = Scenario.EXPIRE;
        action(s, "withdraw", "m0709-expired-withdraw", owner(), """
            {"version":0}
            """).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("REQUEST_EXPIRED"));
        assertThat(scheduler.getScheduledTasks()).anySatisfy(task ->
            assertThat(task.toString()).contains("AppointmentChangeTimeoutScanner.scan"));
        assertThat(scanner.scan()).isOne();
        assertThat(scanner.scan()).isZero();
        assertClosed(s, "EXPIRED", null, "改期申请已到期");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND actor_user_id IS NULL AND actor_type='SYSTEM'",
            request(s))).isOne();
    }

    @Test
    void appointmentConfirmationInvalidatesOpenRequestWithoutMovingCapacity() throws Exception {
        Scenario s = Scenario.CONFIRM;
        mockMvc.perform(post("/api/v1/staff/appointments/{id}/confirm", appointment(s))
                .header(HttpHeaders.AUTHORIZATION, doctor()).header("Idempotency-Key", "m0709-confirm-request")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"version":0}
                    """)).andExpect(status().isOk());
        assertThat(count("""
            SELECT COUNT(*) FROM appointment_change_request WHERE id=? AND status='EXPIRED'
                AND open_guard IS NULL AND version=1
            """, request(s))).isOne();
        assertThat(count("SELECT COUNT(*) FROM appointment WHERE id=? AND slot_id=? AND status='CONFIRMED'",
            appointment(s), old(s))).isOne();
        assertCapacity(s);
    }

    private void assertClosed(Scenario s, String state, Long responder, String reason) {
        assertThat(count("""
            SELECT COUNT(*) FROM appointment_change_request WHERE id=? AND status=? AND open_guard IS NULL
                AND version=1 AND responded_at IS NOT NULL AND responder_user_id <=> ? AND response_reason <=> ?
            """, request(s), state, responder, reason)).isOne();
        assertThat(count("SELECT COUNT(*) FROM appointment WHERE id=? AND slot_id=? AND version=0 AND status='PENDING'",
            appointment(s), old(s))).isOne();
        assertThat(count("SELECT COUNT(*) FROM user_notice WHERE resource_id=? AND notice_type=?",
            appointment(s), "APPOINTMENT_CHANGE_" + state)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND resource_type='APPOINTMENT_CHANGE_REQUEST'",
            request(s))).isOne();
        assertCapacity(s);
    }

    private void assertCapacity(Scenario s) {
        assertThat(count("SELECT occupied_count FROM doctor_slot WHERE id=?", old(s))).isOne();
        assertThat(count("SELECT occupied_count FROM doctor_slot WHERE id=?", target(s))).isZero();
    }

    private void seedScenario(Scenario s) {
        long pet = 175000 + s.ordinal();
        Instant start = now.plusSeconds(7200 + s.ordinal() * 14400L);
        Instant newStart = start.plusSeconds(3600);
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'夹具','CAT')", pet, OWNER);
        seedSlot(old(s), start, 1);
        seedSlot(target(s), newStart, 0);
        jdbc.update("""
            INSERT INTO appointment(id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,
                scheduled_end_at,initial_start_at,status,chief_complaint,confirm_deadline_at,idempotency_key,request_hash)
            VALUES (?,?,?,?,?,?,?,?,?,'PENDING','夹具',?,?,?)
            """, appointment(s), "M0709-" + s, OWNER, pet, DOCTOR, old(s), utc(start), utc(start.plusSeconds(1800)),
            utc(start), utc(now.plusSeconds(3600)), "m0709-appointment-" + s, "%064d".formatted(appointment(s)));
        jdbc.update("""
            INSERT INTO appointment_change_request(id,appointment_id,proposer_user_id,proposer_type,old_slot_id,new_slot_id,
                base_appointment_version,old_start_at,new_start_at,status,open_guard,reason,expires_at,idempotency_key,request_hash,created_at)
            VALUES (?,?,?,'USER',?,?,0,?,?,'OPEN',1,'夹具',?,?,?,?)
            """, request(s), appointment(s), OWNER, old(s), target(s), utc(start), utc(newStart),
            utc(now.plusSeconds(s == Scenario.EXPIRE ? -60 : 1800)), "m0709-change-" + s, "%064d".formatted(request(s)),
            utc(now.minusSeconds(3600)));
    }

    private void seedSlot(long id, Instant start, int occupied) {
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,2,?,'OPEN')
            """, id, DOCTOR, utc(start).toLocalDate(), utc(start), utc(start.plusSeconds(1800)), occupied);
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,'测试账号','ACTIVE')", id, "m0709-" + id);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0709-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private ResultActions action(Scenario s, String action, String key, String token, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/change-requests/{id}/" + action, request(s))
            .header(HttpHeaders.AUTHORIZATION, token).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String owner() { return bearer(OWNER, "MINI", "USER"); }
    private String doctor() { return bearer(DOCTOR, "WEB", "DOCTOR"); }
    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static long appointment(Scenario s) { return 176000 + s.ordinal(); }
    private static long request(Scenario s) { return 177000 + s.ordinal(); }
    private static long old(Scenario s) { return 178000 + 2L * s.ordinal(); }
    private static long target(Scenario s) { return old(s) + 1; }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
