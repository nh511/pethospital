package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class M0702IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String PATH = "/api/v1/appointments";
    private static final long OWNER = 170_201;
    private static final long OTHER_OWNER = 170_202;
    private static final long DOCTOR_USER = 170_203;
    private static final long DOCTOR = 170_211;
    private static final long OTHER_DOCTOR = 170_212;
    private static final long DISABLED_DOCTOR = 170_213;
    private static final long RACE_PET = 170_220;
    private static final long REPLAY_PET = 170_231;
    private static final long LINK_PET = 170_232;
    private static final long ARCHIVED_PET = 170_233;
    private static final long FOREIGN_PET = 170_234;
    private static final long ROLLBACK_PET = 170_235;
    private static final long RACE_SLOT = 170_241;
    private static final long REPLAY_SLOT = 170_242;
    private static final long LINK_SLOT = 170_243;
    private static final long OVERLAP_SLOT = 170_244;
    private static final long CLOSED_SLOT = 170_245;
    private static final long PAST_SLOT = 170_246;
    private static final long DISABLED_SLOT = 170_247;
    private static final long ROLLBACK_SLOT = 170_248;
    private static final long OWN_AI = 170_251;
    private static final long FOREIGN_AI = 170_252;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Rs256AccessTokenIssuer tokenIssuer;
    @Autowired ObjectMapper json;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeAll
    void seedFixtures() {
        seedUser(OWNER, "USER", "MINI");
        seedUser(OTHER_OWNER, "USER", "MINI");
        seedUser(DOCTOR_USER, "DOCTOR", "WEB");
        seedUser(DOCTOR_USER + 1, "DOCTOR", "WEB");
        seedUser(DOCTOR_USER + 2, "DOCTOR", "WEB");
        seedDoctor(DOCTOR, DOCTOR_USER, "ACTIVE");
        seedDoctor(OTHER_DOCTOR, DOCTOR_USER + 1, "ACTIVE");
        seedDoctor(DISABLED_DOCTOR, DOCTOR_USER + 2, "DISABLED");
        for (int i = 0; i < 10; i++) {
            seedPet(RACE_PET + i, OWNER, "ACTIVE");
        }
        seedPet(REPLAY_PET, OWNER, "ACTIVE");
        seedPet(LINK_PET, OWNER, "ACTIVE");
        seedPet(ARCHIVED_PET, OWNER, "ARCHIVED");
        seedPet(FOREIGN_PET, OTHER_OWNER, "ACTIVE");
        seedPet(ROLLBACK_PET, OWNER, "ACTIVE");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        seedSlot(RACE_SLOT, DOCTOR, now.plusSeconds(3600), 1, "OPEN");
        seedSlot(REPLAY_SLOT, DOCTOR, now.plusSeconds(7200), 3, "OPEN");
        seedSlot(LINK_SLOT, DOCTOR, now.plusSeconds(10800), 3, "OPEN");
        seedSlot(OVERLAP_SLOT, OTHER_DOCTOR, now.plusSeconds(10800), 3, "OPEN");
        seedSlot(CLOSED_SLOT, DOCTOR, now.plusSeconds(14400), 3, "CLOSED");
        seedSlot(PAST_SLOT, DOCTOR, now.minusSeconds(3600), 3, "OPEN");
        seedSlot(DISABLED_SLOT, DISABLED_DOCTOR, now.plusSeconds(3600), 3, "OPEN");
        seedSlot(ROLLBACK_SLOT, OTHER_DOCTOR, now.plusSeconds(18000), 3, "OPEN");
        jdbc.update("""
            INSERT INTO ai_session(id,user_id,pet_id,title,pet_snapshot_json)
            VALUES (?,?,?,'有效会话',JSON_OBJECT()),(?,?,?,'其他会话',JSON_OBJECT())
            """, OWN_AI, OWNER, LINK_PET, FOREIGN_AI, OTHER_OWNER, FOREIGN_PET);
    }

    @Test
    void tenConcurrentRequestsConsumeOnlyOneSlot() throws Exception {
        String bearer = bearer(OWNER, "MINI", "USER");
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch ready = new CountDownLatch(10);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int attempt = i;
                requests.add(executor.submit(() -> {
                    ready.countDown();
                    if (!go.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发请求未同步启动");
                    }
                    return book(bearer, "m0702-concurrent-" + attempt,
                        body(RACE_PET + attempt, DOCTOR, RACE_SLOT, null)).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> request : requests) {
                statuses.add(request.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(code -> code == 201)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(occupied(RACE_SLOT)).isOne();
        long id = jdbc.queryForObject("SELECT id FROM appointment WHERE slot_id=?", Long.class, RACE_SLOT);
        assertCreationTrail(id);
    }

    @Test
    void replayReturnsOriginalBookingWithoutDuplicateOccupancyOrSideEffects() throws Exception {
        String key = "m0702-repeat-booking";
        String bearer = bearer(OWNER, "MINI", "USER");
        ObjectNode request = body(REPLAY_PET, DOCTOR, REPLAY_SLOT, null);
        byte[] created = book(bearer, key, request).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.version").value(0))
            .andReturn().getResponse().getContentAsByteArray();
        byte[] replay = book(bearer, key, request).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(json.readTree(replay).path("data")).isEqualTo(json.readTree(created).path("data"));
        request.put("chiefComplaint", "其他主诉");
        book(bearer, key, request).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(occupied(REPLAY_SLOT)).isOne();
        long id = jdbc.queryForObject("SELECT id FROM appointment WHERE slot_id=? AND ai_session_id IS NULL", Long.class, REPLAY_SLOT);
        assertCreationTrail(id);
    }

    @Test
    void rejectsInvalidAssociationThenAcceptsOwnedAiAndBlocksCrossDoctorOverlap() throws Exception {
        String bearer = bearer(OWNER, "MINI", "USER");
        String key = "m0702-ai-association";
        book(bearer, key, body(LINK_PET, DOCTOR, LINK_SLOT, FOREIGN_AI))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AI_SESSION_MISMATCH"));
        assertThat(occupied(LINK_SLOT)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE actor_user_id=? AND operation_key=?", OWNER, key)).isZero();
        book(bearer, key, body(LINK_PET, DOCTOR, LINK_SLOT, OWN_AI)).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT ai_session_id FROM appointment WHERE slot_id=?", Long.class, LINK_SLOT))
            .isEqualTo(OWN_AI);
        book(bearer, "m0702-cross-overlap", body(LINK_PET, OTHER_DOCTOR, OVERLAP_SLOT, null))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PET_APPOINTMENT_OVERLAP"));
        assertThat(occupied(OVERLAP_SLOT)).isZero();
    }

    @Test
    void rejectsNonBookableSlotsAndPetsAndInvalidClient() throws Exception {
        String bearer = bearer(OWNER, "MINI", "USER");
        String key = "m0702-invalid-booking";
        book(bearer, key, body(REPLAY_PET, DOCTOR, CLOSED_SLOT, null)).andExpect(status().isConflict());
        book(bearer, key, body(REPLAY_PET, DOCTOR, PAST_SLOT, null)).andExpect(status().isConflict());
        book(bearer, key, body(REPLAY_PET, DISABLED_DOCTOR, DISABLED_SLOT, null)).andExpect(status().isUnprocessableEntity());
        book(bearer, key, body(ARCHIVED_PET, DOCTOR, ROLLBACK_SLOT, null)).andExpect(status().isConflict());
        book(bearer, key, body(FOREIGN_PET, DOCTOR, ROLLBACK_SLOT, null)).andExpect(status().isNotFound());
        book(bearer, key, body(REPLAY_PET, DOCTOR, OVERLAP_SLOT, null)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SLOT_DOCTOR_MISMATCH"));
        book(bearer(DOCTOR_USER, "WEB", "DOCTOR"), key, body(REPLAY_PET, DOCTOR, REPLAY_SLOT, null))
            .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
                .content(body(REPLAY_PET, DOCTOR, REPLAY_SLOT, null).toString()))
            .andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE actor_user_id=? AND operation_key=?", OWNER, key)).isZero();
    }

    @Test
    void failureAfterSlotUpdateRollsBackBookingLogAndIdempotencyReservation() throws Exception {
        String key = "m0702-rollback-notice";
        jdbc.execute("""
            ALTER TABLE user_notice ADD CONSTRAINT ck_m0702_notice_fault
            CHECK (recipient_user_id<>%d OR notice_type<>'APPOINTMENT_CREATED')
            """.formatted(DOCTOR_USER + 1));
        try {
            book(bearer(OWNER, "MINI", "USER"), key, body(ROLLBACK_PET, OTHER_DOCTOR, ROLLBACK_SLOT, null))
                .andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("ALTER TABLE user_notice DROP CHECK ck_m0702_notice_fault");
        }
        assertThat(occupied(ROLLBACK_SLOT)).isZero();
        assertThat(count("SELECT COUNT(*) FROM appointment WHERE slot_id=?", ROLLBACK_SLOT)).isZero();
        assertThat(count("SELECT COUNT(*) FROM appointment_status_log WHERE operation_key=?", key)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE actor_user_id=? AND operation_key=?", OWNER, key)).isZero();
    }

    private void assertCreationTrail(long id) {
        assertThat(count("""
            SELECT COUNT(*) FROM appointment_status_log
            WHERE appointment_id=? AND from_status IS NULL AND to_status='PENDING'
            """, id)).isOne();
        assertThat(count("""
            SELECT COUNT(*) FROM user_notice WHERE recipient_user_id=? AND resource_id=? AND notice_type='APPOINTMENT_CREATED'
            """, DOCTOR_USER, id)).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='APPOINTMENT_CREATE' AND resource_id=?", id)).isOne();
    }

    private ResultActions book(String bearer, String key, ObjectNode request) throws Exception {
        return mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(request.toString()));
    }

    private ObjectNode body(long pet, long doctor, long slot, Long ai) {
        ObjectNode request = json.createObjectNode().put("petId", Long.toString(pet)).put("doctorId", Long.toString(doctor))
            .put("slotId", Long.toString(slot)).put("chiefComplaint", "食欲下降");
        if (ai != null) {
            request.put("aiSessionId", ai.toString());
        }
        return request;
    }

    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }

    private int occupied(long slot) {
        return jdbc.queryForObject("SELECT occupied_count FROM doctor_slot WHERE id=?", Integer.class, slot);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void seedUser(long id, String role, String audience) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,?,'ACTIVE')",
            id, "m0702-" + id, "测试账号");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0702-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private void seedDoctor(long id, long user, String status) {
        jdbc.update("INSERT INTO doctor_profile(id,user_id,doctor_name,status) VALUES (?,?,'测试医生',?)", id, user, status);
    }

    private void seedPet(long id, long user, String status) {
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code,status) VALUES (?,?,'测试宠物','CAT',?)", id, user, status);
    }

    private void seedSlot(long id, long doctor, Instant start, int capacity, String status) {
        LocalDateTime at = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,status)
            VALUES (?,?,?,?,?,?,?)
            """, id, doctor, at.toLocalDate(), at, at.plusMinutes(30), capacity, status);
    }
}
