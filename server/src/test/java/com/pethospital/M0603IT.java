package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.web.servlet.MvcResult;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true"
})
@AutoConfigureMockMvc
class M0603IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long ADMIN = 67_001;
    private static final long DOCTOR_ONE_USER = 67_101;
    private static final long DOCTOR_TWO_USER = 67_102;
    private static final long ORDINARY_USER = 67_201;
    private static final long ADMIN_SESSION = 68_001;
    private static final long DOCTOR_ONE_SESSION = 68_101;
    private static final long USER_WEB_SESSION = 68_201;
    private static final long DOCTOR_ONE = 69_101;
    private static final long DOCTOR_TWO = 69_102;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired Rs256AccessTokenIssuer tokenIssuer;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM doctor_slot WHERE doctor_id BETWEEN 69000 AND 69999");
        jdbc.update("DELETE FROM doctor_profile WHERE id BETWEEN 69000 AND 69999");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id BETWEEN 67000 AND 67999");
        jdbc.update("DELETE FROM auth_session WHERE user_id BETWEEN 67000 AND 67999");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id BETWEEN 67000 AND 67999");
        jdbc.update("DELETE FROM sys_user WHERE id BETWEEN 67000 AND 67999");
        jdbc.update("UPDATE sys_role SET status='ACTIVE' WHERE code IN ('ADMIN','DOCTOR','USER')");
        seedUser(ADMIN, "slot-admin", "ADMIN");
        seedUser(DOCTOR_ONE_USER, "slot-doctor-one", "DOCTOR");
        seedUser(DOCTOR_TWO_USER, "slot-doctor-two", "DOCTOR");
        seedUser(ORDINARY_USER, "slot-ordinary", "USER");
        seedSession(ADMIN_SESSION, ADMIN, "WEB");
        seedSession(DOCTOR_ONE_SESSION, DOCTOR_ONE_USER, "WEB");
        seedSession(USER_WEB_SESSION, ORDINARY_USER, "WEB");
        seedDoctor(DOCTOR_ONE, DOCTOR_ONE_USER, "一号医生");
        seedDoctor(DOCTOR_TWO, DOCTOR_TWO_USER, "二号医生");
    }

    @Test
    void doctorCreatesOwnUtcSlotWithIdempotentAuditAndCanQueryIt() throws Exception {
        LocalDate date = futureDate(2);
        OffsetDateTime start = atShanghai(date, 9, 0);
        OffsetDateTime end = atShanghai(date, 9, 30);
        MvcResult created = create(
            doctorBearer(), DOCTOR_ONE, date, start, end, 3, "m0603-create-0001"
        ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.doctorId").value(Long.toString(DOCTOR_ONE)))
            .andExpect(jsonPath("$.data.slotDate").value(date.toString()))
            .andExpect(jsonPath("$.data.startAt").value(start.toInstant().toString()))
            .andExpect(jsonPath("$.data.availableCount").value(3))
            .andExpect(jsonPath("$.data.status").value("OPEN"))
            .andReturn();
        String slotId = objectMapper.readTree(created.getResponse().getContentAsString())
            .path("data").path("slotId").asText();
        LocalDateTime storedStart = jdbc.queryForObject(
            "SELECT start_at FROM doctor_slot WHERE id=?", LocalDateTime.class, Long.parseLong(slotId)
        );
        assertThat(storedStart).isEqualTo(LocalDateTime.ofInstant(start.toInstant(), ZoneOffset.UTC));

        create(doctorBearer(), DOCTOR_ONE, date, start, end, 3, "m0603-create-0001")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.slotId").value(slotId));
        assertThat(count("SELECT COUNT(*) FROM doctor_slot WHERE doctor_id=" + DOCTOR_ONE)).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='DOCTOR_SLOT_CREATE'"
            + " AND resource_id=" + slotId + " AND actor_user_id=" + DOCTOR_ONE_USER)).isOne();

        mockMvc.perform(get("/api/v1/staff/slots")
                .queryParam("fromDate", date.toString()).queryParam("toDate", date.toString())
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].slotId").value(slotId));
    }

    @Test
    void adjacentSlotsAreAllowedButOverlapIsRejected() throws Exception {
        LocalDate date = futureDate(3);
        OffsetDateTime nine = atShanghai(date, 9, 0);
        OffsetDateTime ten = atShanghai(date, 10, 0);
        OffsetDateTime eleven = atShanghai(date, 11, 0);
        create(doctorBearer(), DOCTOR_ONE, date, nine, ten, 1, "m0603-adjacent-01")
            .andExpect(status().isCreated());
        create(doctorBearer(), DOCTOR_ONE, date, ten, eleven, 1, "m0603-adjacent-02")
            .andExpect(status().isCreated());
        create(
            doctorBearer(), DOCTOR_ONE, date, atShanghai(date, 9, 30), atShanghai(date, 10, 30),
            1, "m0603-overlap-001"
        ).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SLOT_OVERLAP"));
        assertThat(count("SELECT COUNT(*) FROM doctor_slot WHERE doctor_id=" + DOCTOR_ONE)).isEqualTo(2);
    }

    @Test
    void concurrentOverlappingCreatesCommitOnlyOneSlot() throws Exception {
        LocalDate date = futureDate(4);
        String bearer = doctorBearer();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> concurrentCreate(
                ready, fire, bearer, date, atShanghai(date, 13, 0), atShanghai(date, 14, 0),
                "m0603-concurrent1"
            )));
            futures.add(executor.submit(() -> concurrentCreate(
                ready, fire, bearer, date, atShanghai(date, 13, 30), atShanghai(date, 14, 30),
                "m0603-concurrent2"
            )));
            ready.await();
            fire.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            Collections.sort(statuses);
            assertThat(statuses).containsExactly(201, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM doctor_slot WHERE doctor_id=" + DOCTOR_ONE)).isOne();
    }

    @Test
    void doctorCannotTargetAnotherDoctorWhileAdminCanManageAndQueryAll() throws Exception {
        LocalDate date = futureDate(5);
        OffsetDateTime start = atShanghai(date, 10, 0);
        OffsetDateTime end = atShanghai(date, 10, 30);
        create(doctorBearer(), DOCTOR_TWO, date, start, end, 2, "m0603-cross-doct1")
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff/slots")
                .queryParam("doctorId", Long.toString(DOCTOR_TWO))
                .queryParam("fromDate", date.toString()).queryParam("toDate", date.toString())
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isForbidden());

        create(adminBearer(), DOCTOR_TWO, date, start, end, 2, "m0603-admin-slot01")
            .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/staff/slots")
                .queryParam("fromDate", date.toString()).queryParam("toDate", date.toString())
                .header(HttpHeaders.AUTHORIZATION, adminBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].doctorId").value(Long.toString(DOCTOR_TWO)));

        mockMvc.perform(get("/api/v1/staff/slots")
                .queryParam("fromDate", date.toString()).queryParam("toDate", date.toString())
                .header(HttpHeaders.AUTHORIZATION, bearer(
                    ORDINARY_USER, USER_WEB_SESSION, "USER", "WEB"
                )))
            .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidCapacityTimeOrderPastAndShanghaiDateMismatch() throws Exception {
        LocalDate date = futureDate(6);
        OffsetDateTime start = atShanghai(date, 9, 0);
        create(doctorBearer(), DOCTOR_ONE, date, start, start.plusMinutes(30), 0, "m0603-capacity-001")
            .andExpect(status().isBadRequest());
        create(doctorBearer(), DOCTOR_ONE, date, start, start.minusMinutes(1), 1, "m0603-order-00001")
            .andExpect(status().isBadRequest());
        create(
            doctorBearer(), DOCTOR_ONE, date.plusDays(1), start, start.plusMinutes(30),
            1, "m0603-date-mism01"
        ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusHours(1);
        create(
            doctorBearer(), DOCTOR_ONE, past.toLocalDate(), past, past.plusMinutes(30),
            1, "m0603-past-slot01"
        ).andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM doctor_slot WHERE doctor_id=" + DOCTOR_ONE)).isZero();
    }

    private int concurrentCreate(
        CountDownLatch ready,
        CountDownLatch fire,
        String bearer,
        LocalDate date,
        OffsetDateTime start,
        OffsetDateTime end,
        String key
    ) throws Exception {
        ready.countDown();
        fire.await();
        return create(bearer, DOCTOR_ONE, date, start, end, 1, key)
            .andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions create(
        String bearer,
        long doctorId,
        LocalDate date,
        OffsetDateTime start,
        OffsetDateTime end,
        int capacity,
        String key
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/slots")
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"doctorId":"%d","date":"%s","startAt":"%s","endAt":"%s","capacity":%d}
                """.formatted(doctorId, date, start, end, capacity)));
    }

    private LocalDate futureDate(int days) {
        return LocalDate.now(SHANGHAI).plusDays(days);
    }

    private static OffsetDateTime atShanghai(LocalDate date, int hour, int minute) {
        return OffsetDateTime.of(date, LocalTime.of(hour, minute), ZoneOffset.ofHours(8));
    }

    private String doctorBearer() {
        return bearer(DOCTOR_ONE_USER, DOCTOR_ONE_SESSION, "DOCTOR", "WEB");
    }

    private String adminBearer() {
        return bearer(ADMIN, ADMIN_SESSION, "ADMIN", "WEB");
    }

    private String bearer(long userId, long sessionId, String role, String audience) {
        return "Bearer " + tokenIssuer.issue(
            userId, sessionId, 0, audience, List.of(role), Instant.now(), 1800
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
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,title,status,version)
            VALUES (?,?,?,'医师','ACTIVE',0)
            """, id, userId, name);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
