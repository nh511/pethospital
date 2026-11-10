package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
class M0710IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String MINI = "/api/v1/appointments";
    private static final String STAFF = "/api/v1/staff/appointments";
    private static final String CHANGE = "/api/v1/change-requests";
    private static final long OWNER = 171001;
    private static final long DOCTOR = 171002;
    private static final String VERSION = """
        {"version":%d}
        """;
    private static final String REASON = """
        {"version":%d,"reason":"专项测试"}
        """;
    private final Instant fixtureTime = Instant.now();
    private int slotSequence;
    private int petSequence;

    private enum Fault {
        CREATE(1), REJECT(1), CANCEL(2), START(0), RESCHEDULE(2);

        final int notices;

        Fault(int notices) {
            this.notices = notices;
        }
    }

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
    void seedActors() {
        seedActor(OWNER, "MINI", "USER");
        seedActor(DOCTOR, "WEB", "DOCTOR");
        jdbc.update("INSERT INTO doctor_profile(id,user_id,doctor_name,status) VALUES (?,?,'专项医生','ACTIVE')", DOCTOR, DOCTOR);
    }

    @RepeatedTest(3)
    void capacityAndStatusRacesKeepLedgerConsistent(RepetitionInfo repetition) throws Exception {
        String prefix = "m0710-round-" + repetition.getCurrentRepetition();
        long slot = seedSlot(true);
        List<Callable<Integer>> contenders = new ArrayList<>();
        String ownerToken = owner();
        for (int index = 0; index < 6; index++) {
            long pet = seedPet();
            String key = prefix + "-create-" + index;
            contenders.add(() -> http(create(slot, pet, key, ownerToken)));
        }
        List<Integer> outcomes = race(contenders);
        assertThat(outcomes).containsOnly(201, 409);
        assertThat(outcomes.stream().filter(code -> code == 201)).hasSize(1);
        long appointment = jdbc.queryForObject("SELECT id FROM appointment WHERE slot_id=?", Long.class, slot);
        assertInvariants();

        String doctorToken = doctor();
        assertThat(race(List.of(
            () -> http(call(STAFF + "/" + appointment + "/confirm", prefix + "-confirm", doctorToken, VERSION.formatted(0))),
            () -> http(call(STAFF + "/" + appointment + "/reject", prefix + "-reject", doctorToken, REASON.formatted(0)))
        ))).containsExactlyInAnyOrder(200, 409);
        assertInvariants();
        long confirmed = appointment;
        if ("REJECTED".equals(state(appointment))) {
            confirmed = created(slot, seedPet(), prefix + "-replace");
            call(STAFF + "/" + confirmed + "/confirm", prefix + "-replacement-confirm", doctorToken, VERSION.formatted(0))
                .andExpect(status().isOk());
        }
        long raceId = confirmed;
        assertThat(race(List.of(
            () -> http(call(STAFF + "/" + raceId + "/start", prefix + "-start", doctorToken, VERSION.formatted(1))),
            () -> http(call(MINI + "/" + raceId + "/cancel", prefix + "-cancel", ownerToken, REASON.formatted(1)))
        ))).containsExactlyInAnyOrder(200, 409);
        assertThat(state(raceId)).isIn("CANCELLED", "IN_CONSULTATION");
        assertInvariants();
    }

    @Test
    void twoDifferentAppointmentsCompeteForTheSameRescheduleTarget() throws Exception {
        long firstSlot = seedSlot(false);
        long secondSlot = seedSlot(false);
        long target = seedSlot(false);
        long first = created(firstSlot, seedPet(), "m0710-transfer-create-first");
        long second = created(secondSlot, seedPet(), "m0710-transfer-create-second");
        long firstChange = proposed(first, target, "m0710-transfer-propose-first");
        long secondChange = proposed(second, target, "m0710-transfer-propose-second");
        String doctor = doctor();
        assertThat(race(List.of(
            () -> http(call(CHANGE + "/" + firstChange + "/accept", "m0710-transfer-accept-first", doctor, VERSION.formatted(0))),
            () -> http(call(CHANGE + "/" + secondChange + "/accept", "m0710-transfer-accept-second", doctor, VERSION.formatted(0)))
        ))).containsExactlyInAnyOrder(200, 409);
        assertThat(count("SELECT COUNT(*) FROM appointment WHERE slot_id=? AND status='PENDING'", target)).isOne();
        assertThat(count("SELECT COUNT(*) FROM appointment WHERE slot_id IN (?,?) AND status='PENDING'", firstSlot, secondSlot)).isOne();
        assertThat(count("SELECT COUNT(*) FROM appointment_change_request WHERE new_slot_id=? AND status='OPEN' AND open_guard=1",
            target)).isOne();
        assertThat(count("""
            SELECT COUNT(*) FROM user_notice WHERE notice_type='APPOINTMENT_RESCHEDULED' AND resource_id IN (?,?)
            """, first, second)).isEqualTo(2);
        assertInvariants();
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void failureRollsBackEveryWriteAndOriginalKeyCanBeRetried(Fault fault) throws Exception {
        String prefix = "m0710-fault-" + fault.name().toLowerCase(java.util.Locale.ROOT);
        String key = prefix + "-retry";
        long slot = seedSlot(fault == Fault.START);
        long pet = seedPet();
        long appointment = fault == Fault.CREATE ? 0 : created(slot, pet, prefix + "-seed-create");
        long change = fault == Fault.RESCHEDULE ? proposed(appointment, seedSlot(false), prefix + "-seed-propose") : 0;
        if (fault == Fault.START) {
            call(STAFF + "/" + appointment + "/confirm", prefix + "-seed-confirm", doctor(), VERSION.formatted(0))
                .andExpect(status().isOk());
        }
        Snapshot before = snapshot();
        String table = fault == Fault.RESCHEDULE ? "user_notice" : "appointment_status_log";
        String condition = fault == Fault.RESCHEDULE
            ? "event_key<>'appointment-rescheduled:" + change + "'" : "operation_key<>'" + key + "'";
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT ck_m0710_fault CHECK (" + condition + ")");
        try {
            execute(fault, appointment, change, slot, pet, key).andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CHECK ck_m0710_fault");
        }
        assertThat(snapshot()).isEqualTo(before);
        assertInvariants();

        execute(fault, appointment, change, slot, pet, key)
            .andExpect(fault == Fault.CREATE ? status().isCreated() : status().isOk());
        Snapshot after = snapshot();
        assertThat(after.auditCount()).isEqualTo(before.auditCount() + 1);
        assertThat(after.noticeCount()).isEqualTo(before.noticeCount() + fault.notices);
        assertThat(after.logCount()).isEqualTo(before.logCount() + (fault == Fault.RESCHEDULE ? 0 : 1));
        execute(fault, appointment, change, slot, pet, key)
            .andExpect(fault == Fault.CREATE ? status().isCreated() : status().isOk());
        assertThat(snapshot()).isEqualTo(after);
        assertInvariants();
    }

    private ResultActions execute(Fault fault, long appointment, long change, long slot, long pet, String key) throws Exception {
        return switch (fault) {
            case CREATE -> create(slot, pet, key, owner());
            case REJECT -> call(STAFF + "/" + appointment + "/reject", key, doctor(), REASON.formatted(0));
            case CANCEL -> call(MINI + "/" + appointment + "/cancel", key, owner(), REASON.formatted(0));
            case START -> call(STAFF + "/" + appointment + "/start", key, doctor(), VERSION.formatted(1));
            case RESCHEDULE -> call(CHANGE + "/" + change + "/accept", key, doctor(), VERSION.formatted(0));
        };
    }

    private void assertInvariants() {
        assertThat(count("""
            SELECT COUNT(*) FROM doctor_slot s
            WHERE s.occupied_count<0 OR s.occupied_count>s.capacity OR s.occupied_count<>(
                SELECT COUNT(*) FROM appointment a WHERE a.slot_id=s.id
                AND a.status IN ('PENDING','CONFIRMED','IN_CONSULTATION','COMPLETED','NO_SHOW')
            )
            """)).isZero();
        assertThat(count("""
            SELECT COUNT(*) FROM appointment a WHERE NOT (a.status <=> (
                SELECT l.to_status FROM appointment_status_log l WHERE l.appointment_id=a.id
                ORDER BY l.created_at DESC,l.id DESC LIMIT 1
            ))
            """)).isZero();
    }

    private Snapshot snapshot() {
        return new Snapshot(
            jdbc.queryForList("SELECT id,occupied_count,version FROM doctor_slot ORDER BY id"),
            jdbc.queryForList("SELECT id,slot_id,status,scheduled_start_at,scheduled_end_at,version FROM appointment ORDER BY id"),
            jdbc.queryForList("SELECT id,status,open_guard,version FROM appointment_change_request ORDER BY id"),
            count("SELECT COUNT(*) FROM appointment_status_log"), count("SELECT COUNT(*) FROM user_notice"),
            count("SELECT COUNT(*) FROM audit_log"));
    }

    private List<Integer> race(List<Callable<Integer>> contenders) throws Exception {
        CountDownLatch ready = new CountDownLatch(contenders.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(contenders.size())) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> contender : contenders) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发请求未同步启动");
                    }
                    return contender.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> outcomes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    private long seedSlot(boolean near) {
        int sequence = ++slotSequence;
        long id = 179000 + sequence;
        Instant start = near ? fixtureTime.plusSeconds(360 + sequence * 31L) : fixtureTime.plusSeconds(3600L * sequence);
        LocalDateTime utc = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,1,0,'OPEN')
            """, id, DOCTOR, utc.toLocalDate(), utc, utc.plusSeconds(30));
        return id;
    }

    private long seedPet() {
        long id = 180000 + ++petSequence;
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'专项夹具','CAT')", id, OWNER);
        return id;
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,'测试账号','ACTIVE')", id, "m0710-" + id);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0710-" + id,
            Timestamp.from(fixtureTime.plusSeconds(3600)), Timestamp.from(fixtureTime), Timestamp.from(fixtureTime));
    }

    private long created(long slot, long pet, String key) throws Exception {
        return json.readTree(create(slot, pet, key, owner()).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsByteArray()).path("data").path("id").asLong();
    }

    private long proposed(long appointment, long target, String key) throws Exception {
        return json.readTree(call(MINI + "/" + appointment + "/change-requests", key, owner(), """
            {"version":0,"newSlotId":"%d","reason":"专项改期"}
            """.formatted(target)).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsByteArray()).path("data").path("id").asLong();
    }

    private ResultActions create(long slot, long pet, String key, String token) throws Exception {
        return call(MINI, key, token, """
            {"petId":"%d","doctorId":"%d","slotId":"%d","chiefComplaint":"专项夹具"}
            """.formatted(pet, DOCTOR, slot));
    }

    private ResultActions call(String path, String key, String token, String body) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, token)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static int http(ResultActions response) {
        return response.andReturn().getResponse().getStatus();
    }

    private String state(long id) { return jdbc.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, id); }
    private String owner() { return bearer(OWNER, "MINI", "USER"); }
    private String doctor() { return bearer(DOCTOR, "WEB", "DOCTOR"); }
    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private record Snapshot(
        List<Map<String, Object>> slots, List<Map<String, Object>> appointments, List<Map<String, Object>> changes,
        int logCount, int noticeCount, int auditCount
    ) { }
}
