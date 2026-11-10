package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class M0707IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String MINI = "/api/v1/appointments/{id}/change-requests";
    private static final String WEB = "/api/v1/staff/appointments/{id}/change-requests";
    private static final long OWNER = 170_701;
    private static final long OTHER_OWNER = 170_702;
    private static final long DOCTOR = 170_703;
    private static final long OTHER_DOCTOR = 170_704;
    private static final long ADMIN = 170_705;
    private static final long PET = 170_706;
    private static final long PENDING = 170_711;
    private static final long WITH_EXPIRED = 170_712;
    private static final long RACE = 170_713;
    private static final long INVALID = 170_714;
    private static final long EXPIRED = 170_715;
    private static final long TARGET = 170_731;
    private static final long FOREIGN_TARGET = 170_732;
    private Instant pendingDeadline;
    private Instant targetStart;

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
            INSERT INTO doctor_profile(id,user_id,doctor_name,status)
            VALUES (?,?,'医生夹具','ACTIVE'),(?,?,'其他医生','ACTIVE')
            """, DOCTOR, DOCTOR, OTHER_DOCTOR, OTHER_DOCTOR);
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'测试宠物','CAT')", PET, OWNER);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        pendingDeadline = now.plusSeconds(1800);
        targetStart = now.plusSeconds(7200);
        seedSlot(TARGET, DOCTOR, targetStart, 0);
        seedSlot(FOREIGN_TARGET, OTHER_DOCTOR, targetStart, 0);
        seedAppointment(PENDING, "PENDING", now.plusSeconds(3600), pendingDeadline);
        Instant oldStart = now.plusSeconds(14400);
        seedAppointment(WITH_EXPIRED, "CONFIRMED", oldStart, pendingDeadline);
        seedAppointment(RACE, "CONFIRMED", now.plusSeconds(18000), pendingDeadline);
        seedAppointment(INVALID, "CONFIRMED", now.plusSeconds(21600), pendingDeadline);
        jdbc.update("""
            INSERT INTO appointment_change_request(
                id,appointment_id,proposer_user_id,proposer_type,old_slot_id,new_slot_id,base_appointment_version,
                old_start_at,new_start_at,status,open_guard,reason,expires_at,idempotency_key,request_hash,created_at
            ) VALUES (?,?,?,'USER',?,?,0,?,?,'OPEN',1,'旧申请',?,?,?,?)
            """, EXPIRED, WITH_EXPIRED, OWNER, slot(WITH_EXPIRED), TARGET, utc(oldStart), utc(targetStart),
            utc(now.minusSeconds(60)), "m0707-expired-request", "%064d".formatted(EXPIRED), utc(now.minusSeconds(3600)));
    }

    @Test
    void ownerProposalUsesPendingDeadlineAndDoesNotMoveOrReserveCapacity() throws Exception {
        String bearer = owner();
        String key = "m0707-owner-proposal";
        String first = create(MINI, PENDING, key, TARGET, bearer).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("OPEN"))
            .andExpect(jsonPath("$.data.expiresAt").value(pendingDeadline.toString()))
            .andReturn().getResponse().getContentAsString();
        String replay = create(MINI, PENDING, key, TARGET, bearer).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String originalId = com.jayway.jsonpath.JsonPath.read(first, "$.data.id");
        String replayId = com.jayway.jsonpath.JsonPath.read(replay, "$.data.id");
        assertThat(replayId).isEqualTo(originalId);
        assertUnchanged(PENDING);
        assertThat(openCount(PENDING)).isOne();
    }

    @Test
    void doctorProposalClearsExpiredGuardAndHistoryPaginatesBothRows() throws Exception {
        String bearer = bearer(DOCTOR, "WEB", "DOCTOR");
        create(WEB, WITH_EXPIRED, "m0707-doctor-proposal", TARGET, bearer).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.expiresAt").value(targetStart.toString()));
        mockMvc.perform(get(WEB, WITH_EXPIRED).param("pageSize", "1").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").isNumber()).andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].proposerType").value("DOCTOR"))
            .andExpect(jsonPath("$.data.items[0].status").value("OPEN"));
        mockMvc.perform(get(MINI, WITH_EXPIRED).param("pageSize", "1").param("page", "2")
                .header(HttpHeaders.AUTHORIZATION, owner()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(Long.toString(EXPIRED)))
            .andExpect(jsonPath("$.data.items[0].status").value("EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT open_guard IS NULL FROM appointment_change_request WHERE id=?",
            Boolean.class, EXPIRED)).isTrue();
        assertUnchanged(WITH_EXPIRED);
    }

    @Test
    void competingProposalsCreateExactlyOneOpenRequest() throws Exception {
        String bearer = owner();
        CountDownLatch go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> race(go, "m0707-racing-first", bearer));
            var second = workers.submit(() -> race(go, "m0707-racing-second", bearer));
            go.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(openCount(RACE)).isOne();
        assertUnchanged(RACE);
    }

    @Test
    void onlyOwnerOrAssignedDoctorCanProposeAndAdminCanRead() throws Exception {
        String admin = bearer(ADMIN, "WEB", "ADMIN");
        mockMvc.perform(get(WEB, INVALID).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk());
        create(WEB, INVALID, "m0707-admin-forbid", TARGET, admin).andExpect(status().isForbidden());
        create(MINI, INVALID, "m0707-other-owner", TARGET, bearer(OTHER_OWNER, "MINI", "USER"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get(WEB, INVALID).header(HttpHeaders.AUTHORIZATION, bearer(OTHER_DOCTOR, "WEB", "DOCTOR")))
            .andExpect(status().isNotFound());
        create(MINI, INVALID, "m0707-foreign-slot", FOREIGN_TARGET, owner()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SLOT_DOCTOR_MISMATCH"));
        create(MINI, INVALID, "m0707-same-slot-01", slot(INVALID), owner()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SAME_SLOT"));
        assertThat(openCount(INVALID)).isZero();
        assertUnchanged(INVALID);
    }

    private int race(CountDownLatch go, String key, String bearer) throws Exception {
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发请求未同步启动");
        }
        return create(MINI, RACE, key, TARGET, bearer).andReturn().getResponse().getStatus();
    }

    private void assertUnchanged(long appointment) {
        assertThat(jdbc.queryForObject("SELECT slot_id=? AND version=0 FROM appointment WHERE id=?",
            Boolean.class, slot(appointment), appointment)).isTrue();
        assertThat(jdbc.queryForObject("SELECT occupied_count FROM doctor_slot WHERE id=?", Integer.class, slot(appointment)))
            .isOne();
        assertThat(jdbc.queryForObject("SELECT occupied_count FROM doctor_slot WHERE id=?", Integer.class, TARGET))
            .isZero();
    }

    private int openCount(long appointment) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointment_change_request WHERE appointment_id=? AND open_guard=1",
            Integer.class, appointment);
    }

    private ResultActions create(String path, long appointment, String key, long slot, String bearer) throws Exception {
        return mockMvc.perform(post(path, appointment).header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("""
                {"version":0,"newSlotId":"%d","reason":"改日就诊"}
                """.formatted(slot)));
    }

    private String owner() {
        return bearer(OWNER, "MINI", "USER");
    }

    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,?,'ACTIVE')",
            id, "m0707-" + id, "测试账号");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0707-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private void seedSlot(long id, long doctor, Instant start, int occupied) {
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,3,?,'OPEN')
            """, id, doctor, utc(start).toLocalDate(), utc(start), utc(start.plusSeconds(1800)), occupied);
    }

    private void seedAppointment(long id, String status, Instant start, Instant deadline) {
        seedSlot(slot(id), DOCTOR, start, 1);
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,confirm_deadline_at,idempotency_key,request_hash
            ) VALUES (?,?,?,?,?,?,?,?,?,?,'预约主诉',?,?,?)
            """, id, "M0707-" + id, OWNER, PET, DOCTOR, slot(id), utc(start), utc(start.plusSeconds(1800)),
            utc(start), status, utc(deadline), "m0707-appointment-" + id, "%064d".formatted(id));
    }

    private static long slot(long appointment) {
        return appointment + 100;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
