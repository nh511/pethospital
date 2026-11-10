package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false", "identity.jwt.allow-ephemeral=true",
    "appointment.early-start-minutes=10", "appointment.no-show-grace-minutes=40"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M0706IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String STAFF = "/api/v1/staff/appointments/{id}";
    private static final String OWNER_PATH = "/api/v1/appointments/{id}";
    private static final long OWNER = 170_601;
    private static final long DOCTOR = 170_602;
    private static final long OTHER_DOCTOR = 170_603;
    private static final long ADMIN = 170_604;
    private static final long PET = 170_605;
    private static final long START = 170_611;
    private static final long TOO_EARLY = 170_612;
    private static final long IN_GRACE = 170_613;
    private static final long NO_SHOW = 170_614;
    private static final long RACE = 170_615;
    private static final String VERSION = """
        {"version":0}
        """;
    private static final String REASON = """
        {"version":0,"reason":"用户未到院"}
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
        seedAppointment(START, now.plusSeconds(8 * 60));
        seedAppointment(TOO_EARLY, now.plusSeconds(12 * 60));
        seedAppointment(IN_GRACE, now.minusSeconds(35 * 60));
        seedAppointment(NO_SHOW, now.minusSeconds(50 * 60));
        seedAppointment(RACE, now.plusSeconds(5 * 60));
    }

    @Test
    void startUsesConfiguredWindowAndRecordsBusinessTimeOnlyOnce() throws Exception {
        String doctor = doctor();
        mockMvc.perform(get(STAFF, TOO_EARLY).header(HttpHeaders.AUTHORIZATION, doctor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.appointment.allowedActions", not(hasItem("START"))));
        call(STAFF, TOO_EARLY, "start", "m0706-early-start", VERSION, doctor)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        call(STAFF, START, "start", "m0706-valid-start", VERSION, doctor)
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_CONSULTATION"));
        call(STAFF, START, "start", "m0706-valid-start", VERSION, doctor)
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbc.queryForObject("SELECT started_at IS NOT NULL FROM appointment WHERE id=?", Boolean.class, START))
            .isTrue();
        assertThat(occupied(START)).isOne();
        assertOneTransition(START, "IN_CONSULTATION");
    }

    @Test
    void noShowRequiresDoctorAndConfiguredGraceAndKeepsConsumedCapacity() throws Exception {
        assertThat(scanner.scan()).isZero();
        assertThat(state(NO_SHOW)).isEqualTo("CONFIRMED");
        call(STAFF, IN_GRACE, "no-show", "m0706-in-grace-period", REASON, doctor())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        call(STAFF, NO_SHOW, "no-show", "m0706-no-show-no-reason", VERSION, doctor())
            .andExpect(status().isBadRequest());
        call(STAFF, NO_SHOW, "no-show", "m0706-record-no-show", REASON, doctor())
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NO_SHOW"));
        call(STAFF, NO_SHOW, "start", "m0706-terminal-start", """
            {"version":1}
            """, doctor()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(occupied(NO_SHOW)).isOne();
        assertThat(jdbc.queryForObject("SELECT terminal_reason FROM appointment WHERE id=?", String.class, NO_SHOW))
            .isEqualTo("用户未到院");
        assertOneTransition(NO_SHOW, "NO_SHOW");
    }

    @Test
    void administratorAndOtherDoctorCannotStartOrMarkNoShow() throws Exception {
        call(STAFF, TOO_EARLY, "start", "m0706-admin-start", VERSION, bearer(ADMIN, "WEB", "ADMIN"))
            .andExpect(status().isForbidden());
        call(STAFF, TOO_EARLY, "no-show", "m0706-other-doctor", REASON, bearer(OTHER_DOCTOR, "WEB", "DOCTOR"))
            .andExpect(status().isNotFound());
        assertThat(state(TOO_EARLY)).isEqualTo("CONFIRMED");
    }

    @Test
    void startAndOwnerCancelRaceWithExactlyOneWinner() throws Exception {
        String doctor = doctor();
        String owner = bearer(OWNER, "MINI", "USER");
        CountDownLatch go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = workers.submit(() -> {
                await(go);
                return call(STAFF, RACE, "start", "m0706-racing-start", VERSION, doctor)
                    .andReturn().getResponse().getStatus();
            });
            var cancel = workers.submit(() -> {
                await(go);
                return call(OWNER_PATH, RACE, "cancel", "m0706-racing-cancel", REASON, owner)
                    .andReturn().getResponse().getStatus();
            });
            go.countDown();
            assertThat(List.of(start.get(20, TimeUnit.SECONDS), cancel.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        }
        String finalState = state(RACE);
        assertThat(finalState).isIn("CANCELLED", "IN_CONSULTATION");
        assertThat(occupied(RACE)).isEqualTo("CANCELLED".equals(finalState) ? 0 : 1);
        assertOneTransition(RACE, finalState);
    }

    private static void await(CountDownLatch go) throws InterruptedException {
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发请求未同步启动");
        }
    }

    private void assertOneTransition(long id, String state) {
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM appointment_status_log WHERE appointment_id=? AND from_status='CONFIRMED' AND to_status=?
            """, Integer.class, id, state)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND resource_type='APPOINTMENT'
            """, Integer.class, id)).isOne();
    }

    private ResultActions call(String path, long id, String action, String key, String body, String bearer) throws Exception {
        return mockMvc.perform(post(path + "/" + action, id).header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String doctor() {
        return bearer(DOCTOR, "WEB", "DOCTOR");
    }

    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }

    private String state(long id) {
        return jdbc.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, id);
    }

    private int occupied(long id) {
        return jdbc.queryForObject("SELECT occupied_count FROM doctor_slot WHERE id=?", Integer.class, id + 100);
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,?,'ACTIVE')",
            id, "m0706-" + id, "测试账号");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0706-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private void seedAppointment(long id, Instant start) {
        long slot = id + 100;
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,3,1,'OPEN')
            """, slot, DOCTOR, utc(start).toLocalDate(), utc(start), utc(start.plusSeconds(1800)));
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,confirm_deadline_at,confirmed_at,idempotency_key,request_hash
            ) VALUES (?,?,?,?,?,?,?,?,?,'CONFIRMED','预约主诉',?,?,?,?)
            """, id, "M0706-" + id, OWNER, PET, DOCTOR, slot, utc(start), utc(start.plusSeconds(1800)),
            utc(start), utc(start.minusSeconds(3600)), utc(start.minusSeconds(7200)),
            "m0706-appointment-" + id, "%064d".formatted(id));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
