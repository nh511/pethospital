package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.pet.ActivePetGuard;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true"
})
@AutoConfigureMockMvc
class M0503IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 160_301;
    private static final long OTHER_OWNER = 160_302;
    private static final long DOCTOR_USER = 160_303;
    private static final long OWNER_SESSION = 161_301;
    private static final long OTHER_SESSION = 161_302;
    private static final long DOCTOR_PROFILE = 162_301;
    private static final long SLOT = 163_301;
    private static final long PET = 164_301;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Rs256AccessTokenIssuer tokenIssuer;

    @Autowired
    ActivePetGuard activePets;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE m FROM ai_message m JOIN ai_session s ON s.id=m.session_id WHERE s.id>=160000");
        jdbc.update("DELETE FROM appointment WHERE id>=160000");
        jdbc.update("DELETE FROM ai_session WHERE id>=160000");
        jdbc.update("DELETE FROM pet_health_log WHERE id>=160000");
        jdbc.update("DELETE FROM pet WHERE id>=160000");
        jdbc.update("DELETE FROM doctor_slot WHERE id>=160000");
        jdbc.update("DELETE FROM doctor_profile WHERE id>=160000");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=160000");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=160000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=160000");
        jdbc.update("DELETE FROM sys_user WHERE id>=160000");

        seedUser(OWNER, "m0503-owner", "USER");
        seedUser(OTHER_OWNER, "m0503-other", "USER");
        seedUser(DOCTOR_USER, "m0503-doctor", "DOCTOR");
        seedSession(OWNER_SESSION, OWNER);
        seedSession(OTHER_SESSION, OTHER_OWNER);
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,'归档测试医生','ACTIVE',0)
            """, DOCTOR_PROFILE, DOCTOR_USER);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version)
            VALUES (?,?,'2026-12-01','2026-12-01 09:00:00','2026-12-01 10:00:00',5,0,'OPEN',0)
            """, SLOT, DOCTOR_PROFILE);
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,sex,neutered,status,version)
            VALUES (?,?,'归档测试宠物','DOG','UNKNOWN','UNKNOWN','ACTIVE',0)
            """, PET, OWNER);
    }

    @Test
    void archivesWithoutActiveDependenciesAndKeepsHistoryReadable() throws Exception {
        seedAppointment(165_301, "COMPLETED");
        jdbc.update("""
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at,weight_kg,version)
            VALUES (166301,?,?,?,6.25,0)
            """, PET, OWNER, Timestamp.from(Instant.parse("2026-10-03T08:00:00Z")));

        archive("{\"reason\":\"不再新增业务\",\"version\":0}", "m0503-archive-success", ownerBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.petId").value(Long.toString(PET)))
            .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
            .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(get("/api/v1/pets/{petId}", PET)
                .header(HttpHeaders.AUTHORIZATION, ownerBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
            .andExpect(jsonPath("$.data.clinicalSummary.latestWeight.weightKg").value(6.25));
        assertThat(count("SELECT COUNT(*) FROM appointment WHERE id=165301")).isOne();
        assertThat(count("SELECT COUNT(*) FROM pet_health_log WHERE id=166301")).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='PET_ARCHIVE' AND resource_id=" + PET))
            .isOne();
    }

    @Test
    void blocksUnfinishedAppointmentsWithCounts() throws Exception {
        seedAppointment(165_302, "PENDING");

        archive("{\"version\":0}", "m0503-archive-appointment", ownerBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PET_ARCHIVE_BLOCKED"))
            .andExpect(jsonPath("$.data.pendingAppointmentCount").value(1))
            .andExpect(jsonPath("$.data.processingMessageCount").value(0));

        assertPetActiveWithoutArchiveAudit();
    }

    @Test
    void blocksQueuedAndProcessingMessagesButIgnoresCompletedHistory() throws Exception {
        seedAiSession(167_301);
        seedUserMessage(168_301, 167_301, 1, "COMPLETED");
        seedUserMessage(168_302, 167_301, 2, "QUEUED");
        seedUserMessage(168_303, 167_301, 3, "PROCESSING");

        archive("{\"version\":0}", "m0503-archive-ai-rounds", ownerBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PET_ARCHIVE_BLOCKED"))
            .andExpect(jsonPath("$.data.pendingAppointmentCount").value(0))
            .andExpect(jsonPath("$.data.processingMessageCount").value(2));

        assertPetActiveWithoutArchiveAudit();
    }

    @Test
    void enforcesOwnerVersionAndIdempotentReplay() throws Exception {
        archive("{\"version\":0}", "m0503-other-owner-key", otherBearer())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PET_NOT_FOUND"));
        archive("{\"version\":3}", "m0503-stale-version-key", ownerBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        String body = "{\"reason\":\"主人归档\",\"version\":0}";
        String key = "m0503-idempotent-archive";
        archive(body, key, ownerBearer()).andExpect(status().isOk());
        archive(body, key, ownerBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1));
        archive("{\"reason\":\"改变请求\",\"version\":0}", key, ownerBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='PET_ARCHIVE' AND resource_id=" + PET))
            .isOne();
    }

    @Test
    void archiveSerializesWithNewWorkUsingSharedActivePetLock() throws Exception {
        CountDownLatch petLocked = new CountDownLatch(1);
        CountDownLatch allowProducerCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> producer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                activePets.lockOwnedActive(PET, OWNER);
                petLocked.countDown();
                await(allowProducerCommit);
                seedAiSession(167_302);
                seedUserMessage(168_304, 167_302, 1, "QUEUED");
            }));
            assertThat(petLocked.await(10, TimeUnit.SECONDS)).isTrue();

            CountDownLatch requestStarted = new CountDownLatch(1);
            Future<MvcResult> archive = executor.submit(() -> {
                requestStarted.countDown();
                return archive("{\"version\":0}", "m0503-concurrent-archive", ownerBearer())
                    .andReturn();
            });
            assertThat(requestStarted.await(10, TimeUnit.SECONDS)).isTrue();
            allowProducerCommit.countDown();
            producer.get(10, TimeUnit.SECONDS);

            MvcResult result = archive.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString()).contains("PET_ARCHIVE_BLOCKED");
            assertPetActiveWithoutArchiveAudit();
        } finally {
            allowProducerCommit.countDown();
            executor.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.ResultActions archive(String body, String key, String bearer)
        throws Exception {
        return mockMvc.perform(post("/api/v1/pets/{petId}/archive", PET)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private void seedAppointment(long appointmentId, String status) {
        Timestamp completedAt = "COMPLETED".equals(status)
            ? Timestamp.from(Instant.parse("2026-12-01T02:30:00Z"))
            : null;
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,confirm_deadline_at,completed_at,
                idempotency_key,request_hash,version
            ) VALUES (?,?,?,?,?,?,'2026-12-01 09:00:00','2026-12-01 10:00:00',
                      '2026-12-01 09:00:00',?,'历史主诉','2026-12-01 08:00:00',?,?,?,0)
            """, appointmentId, "M0503-" + appointmentId, OWNER, PET, DOCTOR_PROFILE, SLOT,
            status, completedAt, "m0503-appointment-" + appointmentId, "%064x".formatted(appointmentId));
    }

    private void seedAiSession(long sessionId) {
        jdbc.update("""
            INSERT INTO ai_session(id,user_id,pet_id,title,status,pet_snapshot_json,version)
            VALUES (?,?,?,'归档并发问诊','OPEN',CAST('{}' AS JSON),0)
            """, sessionId, OWNER, PET);
    }

    private void seedUserMessage(long messageId, long sessionId, int seqNo, String processingStatus) {
        jdbc.update("""
            INSERT INTO ai_message(
                id,session_id,seq_no,role,content,source,processing_status,
                client_message_id,request_hash,generation_no,version
            ) VALUES (?,?,?,'USER','测试消息','USER',?,?,?,0,0)
            """, messageId, sessionId, seqNo, processingStatus,
            "m0503-client-" + messageId, "%064x".formatted(messageId));
    }

    private void seedUser(long userId, String username, String roleCode) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, userId, username, username);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, roleCode);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
            userId + 10_000, userId, roleId);
    }

    private void seedSession(long sessionId, long userId) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,'MINI',?,?,?,?,?,?)
            """, sessionId, userId, "%064x".formatted(sessionId), "family-" + sessionId,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private String ownerBearer() {
        return "Bearer " + token(OWNER, OWNER_SESSION);
    }

    private String otherBearer() {
        return "Bearer " + token(OTHER_OWNER, OTHER_SESSION);
    }

    private String token(long userId, long sessionId) {
        return tokenIssuer.issue(userId, sessionId, 0, "MINI", List.of("USER"), Instant.now(), 1800).value();
    }

    private void assertPetActiveWithoutArchiveAudit() {
        assertThat(jdbc.queryForObject("SELECT status FROM pet WHERE id=?", String.class, PET)).isEqualTo("ACTIVE");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='PET_ARCHIVE' AND resource_id=" + PET))
            .isZero();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试信号超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", exception);
        }
    }
}
