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
import java.time.temporal.ChronoUnit;
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
class M0708IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String PATH = "/api/v1/change-requests/{id}/accept";
    private static final long OWNER = 170_801;
    private static final long OTHER = 170_802;
    private static final long DOCTOR = 170_803;
    private static final long OTHER_DOCTOR = 170_804;
    private static final long ADMIN = 170_805;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private enum Scenario { PENDING, CONFIRMED, PERMISSION, STALE, EXPIRED, FULL, CLOSED, OVERLAP, RACE, ROLLBACK }

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
        seedActor(OTHER, "MINI", "USER");
        seedActor(DOCTOR, "WEB", "DOCTOR");
        seedActor(OTHER_DOCTOR, "WEB", "DOCTOR");
        seedActor(ADMIN, "WEB", "ADMIN");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status)
            VALUES (?,?,'医生夹具','ACTIVE'),(?,?,'其他医生','ACTIVE')
            """, DOCTOR, DOCTOR, OTHER_DOCTOR, OTHER_DOCTOR);
        for (Scenario scenario : Scenario.values()) {
            seedScenario(scenario);
        }
        Scenario overlap = Scenario.OVERLAP;
        jdbc.update("""
            INSERT INTO appointment(id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,
                scheduled_end_at,initial_start_at,status,chief_complaint,confirm_deadline_at,idempotency_key,request_hash)
            VALUES (170999,'M0708-overlap',?,?,?,?,?,?,?,'CONFIRMED','夹具',?,'m0708-overlap-existing',?)
            """, OWNER, pet(overlap), DOCTOR, target(overlap), utc(targetStart(overlap)),
            utc(targetStart(overlap).plusSeconds(1800)), utc(targetStart(overlap)),
            utc(now), "%064d".formatted(170999));
    }

    @Test
    void doctorAcceptsOwnerRequestAndReplayDoesNotMoveCapacityTwice() throws Exception {
        Scenario scenario = Scenario.PENDING;
        for (int attempt = 0; attempt < 2; attempt++) {
            accept(scenario, "m0708-pending-accept", 0, doctor()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.newSlotId").value(Long.toString(target(scenario))))
                .andExpect(jsonPath("$.data.scheduledStartAt").value(targetStart(scenario).toString()))
                .andExpect(jsonPath("$.data.appointmentVersion").value(1))
                .andExpect(jsonPath("$.data.version").value(1));
        }
        accept(scenario, "m0708-pending-accept", 1, doctor()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertAccepted(scenario, "PENDING", DOCTOR);
        assertThat(jdbc.queryForObject("SELECT confirm_deadline_at FROM appointment WHERE id=?",
            LocalDateTime.class, appointment(scenario))).isEqualTo(utc(targetStart(scenario)));
        assertThat(count("""
            SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND action='APPOINTMENT_CHANGE_ACCEPT'
              AND JSON_UNQUOTE(JSON_EXTRACT(before_json,'$.slotId'))=?
              AND JSON_UNQUOTE(JSON_EXTRACT(after_json,'$.snapshot.newSlotId'))=?
            """, request(scenario), Long.toString(old(scenario)), Long.toString(target(scenario)))).isOne();
    }

    @Test
    void ownerAcceptsDoctorsRequestAndConfirmedStateRemains() throws Exception {
        accept(Scenario.CONFIRMED, "m0708-owner-accept", 0, owner()).andExpect(status().isOk());
        assertAccepted(Scenario.CONFIRMED, "CONFIRMED", OWNER);
    }

    @Test
    void deniesSelfApprovalAdminAndUnrelatedAccounts() throws Exception {
        Scenario scenario = Scenario.PERMISSION;
        accept(scenario, "m0708-self-approval", 0, owner()).andExpect(status().isForbidden());
        accept(scenario, "m0708-admin-approval", 0, bearer(ADMIN, "WEB", "ADMIN")).andExpect(status().isForbidden());
        accept(scenario, "m0708-other-owner", 0, bearer(OTHER, "MINI", "USER")).andExpect(status().isNotFound());
        accept(scenario, "m0708-other-doctor", 0, bearer(OTHER_DOCTOR, "WEB", "DOCTOR")).andExpect(status().isNotFound());
        assertUnchanged(scenario);
    }

    @Test
    void conflictsPreserveOriginalAppointmentCapacityAndOpenGuard() throws Exception {
        accept(Scenario.PERMISSION, "m0708-stale-request", 1, doctor()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        for (Scenario scenario : List.of(Scenario.STALE, Scenario.EXPIRED, Scenario.FULL, Scenario.CLOSED, Scenario.OVERLAP)) {
            accept(scenario, "m0708-conflict-" + scenario, 0, doctor()).andExpect(status().isConflict());
            assertUnchanged(scenario);
        }
    }

    @Test
    void concurrentRequestsHaveOneWinner() throws Exception {
        String token = doctor();
        CountDownLatch go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> race(go, "m0708-race-first", token));
            var second = workers.submit(() -> race(go, "m0708-race-second", token));
            go.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        }
        assertAccepted(Scenario.RACE, "PENDING", DOCTOR);
    }

    @Test
    void secondNoticeFailureRollsBackBothCapacitiesAndEveryWrite() throws Exception {
        Scenario scenario = Scenario.ROLLBACK;
        jdbc.execute("""
            ALTER TABLE user_notice ADD CONSTRAINT ck_m0708_notice_fault
            CHECK (resource_id<>%d OR recipient_user_id<>%d)
            """.formatted(appointment(scenario), DOCTOR));
        try {
            accept(scenario, "m0708-failed-notice", 0, doctor()).andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("ALTER TABLE user_notice DROP CHECK ck_m0708_notice_fault");
        }
        assertUnchanged(scenario);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE operation_key='m0708-failed-notice'")).isZero();
    }

    private void assertAccepted(Scenario scenario, String state, long responder) {
        assertThat(count("""
            SELECT COUNT(*) FROM appointment WHERE id=? AND slot_id=? AND status=? AND version=1
              AND initial_start_at=? AND scheduled_start_at=? AND scheduled_end_at=?
            """, appointment(scenario), target(scenario), state, utc(oldStart(scenario)),
            utc(targetStart(scenario)), utc(targetStart(scenario).plusSeconds(1800)))).isOne();
        assertThat(count("""
            SELECT COUNT(*) FROM appointment_change_request WHERE id=? AND status='ACCEPTED' AND open_guard IS NULL
              AND responder_user_id=? AND responded_at IS NOT NULL AND version=1
            """, request(scenario), responder)).isOne();
        assertThat(occupied(old(scenario))).isZero();
        assertThat(occupied(target(scenario))).isOne();
        assertThat(count("SELECT COUNT(*) FROM user_notice WHERE resource_id=? AND notice_type='APPOINTMENT_RESCHEDULED'",
            appointment(scenario))).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM appointment_status_log WHERE appointment_id=?", appointment(scenario))).isZero();
    }

    private void assertUnchanged(Scenario scenario) {
        assertThat(occupied(old(scenario))).isOne();
        assertThat(occupied(target(scenario))).isEqualTo(scenario == Scenario.FULL ? 2 : scenario == Scenario.OVERLAP ? 1 : 0);
        assertThat(count("SELECT COUNT(*) FROM appointment WHERE id=? AND slot_id=? AND scheduled_start_at=? AND version=?",
            appointment(scenario), old(scenario), utc(oldStart(scenario)), scenario == Scenario.STALE ? 1 : 0)).isOne();
        assertThat(count("SELECT COUNT(*) FROM appointment_change_request WHERE id=? AND status='OPEN' AND open_guard=1 AND version=0",
            request(scenario))).isOne();
        assertThat(count("SELECT COUNT(*) FROM user_notice WHERE resource_id=?", appointment(scenario))).isZero();
    }

    private void seedScenario(Scenario scenario) {
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'夹具','CAT')", pet(scenario), OWNER);
        seedSlot(old(scenario), oldStart(scenario), "OPEN", 1);
        seedSlot(target(scenario), targetStart(scenario), scenario == Scenario.CLOSED ? "CLOSED" : "OPEN",
            scenario == Scenario.FULL ? 2 : scenario == Scenario.OVERLAP ? 1 : 0);
        jdbc.update("""
            INSERT INTO appointment(id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,
                scheduled_end_at,initial_start_at,status,chief_complaint,confirm_deadline_at,idempotency_key,request_hash,version)
            VALUES (?,?,?,?,?,?,?,?,?,?,'夹具',?,?,?,?)
            """, appointment(scenario), "M0708-" + scenario, OWNER, pet(scenario), DOCTOR, old(scenario),
            utc(oldStart(scenario)), utc(oldStart(scenario).plusSeconds(1800)), utc(oldStart(scenario)),
            scenario == Scenario.CONFIRMED ? "CONFIRMED" : "PENDING", utc(oldStart(scenario).minusSeconds(1800)),
            "m0708-appointment-" + scenario, "%064d".formatted(appointment(scenario)), scenario == Scenario.STALE ? 1 : 0);
        boolean doctorProposed = scenario == Scenario.CONFIRMED;
        jdbc.update("""
            INSERT INTO appointment_change_request(id,appointment_id,proposer_user_id,proposer_type,old_slot_id,new_slot_id,
                base_appointment_version,old_start_at,new_start_at,status,open_guard,reason,expires_at,idempotency_key,request_hash,created_at)
            VALUES (?,?,?,?,?,?,0,?,?,'OPEN',1,'夹具',?,?,?,?)
            """, request(scenario), appointment(scenario), doctorProposed ? DOCTOR : OWNER,
            doctorProposed ? "DOCTOR" : "USER", old(scenario), target(scenario), utc(oldStart(scenario)),
            utc(targetStart(scenario)), utc(now.plusSeconds(scenario == Scenario.EXPIRED ? -60 : 1800)),
            "m0708-change-" + scenario, "%064d".formatted(request(scenario)), utc(now.minusSeconds(3600)));
    }

    private void seedSlot(long id, Instant start, String state, int occupied) {
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,2,?,?)
            """, id, DOCTOR, utc(start).toLocalDate(), utc(start), utc(start.plusSeconds(1800)), occupied, state);
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,'测试账号','ACTIVE')", id, "m0708-" + id);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0708-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private int race(CountDownLatch go, String key, String token) throws Exception {
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发请求未同步启动");
        }
        return accept(Scenario.RACE, key, 0, token).andReturn().getResponse().getStatus();
    }

    private ResultActions accept(Scenario scenario, String key, int version, String token) throws Exception {
        return mockMvc.perform(post(PATH, request(scenario)).header(HttpHeaders.AUTHORIZATION, token)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"version":%d}
                """.formatted(version)));
    }

    private String owner() { return bearer(OWNER, "MINI", "USER"); }
    private String doctor() { return bearer(DOCTOR, "WEB", "DOCTOR"); }
    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private int occupied(long id) { return count("SELECT occupied_count FROM doctor_slot WHERE id=?", id); }
    private static long pet(Scenario s) { return 171000 + s.ordinal(); }
    private static long appointment(Scenario s) { return 172000 + s.ordinal(); }
    private static long request(Scenario s) { return 173000 + s.ordinal(); }
    private static long old(Scenario s) { return 174001 + 2L * s.ordinal(); }
    private static long target(Scenario s) { return old(s) - 1; }
    private Instant targetStart(Scenario s) { return now.plusSeconds(3600 + s.ordinal() * 14400L); }
    private Instant oldStart(Scenario s) { return targetStart(s).plusSeconds(7200); }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
