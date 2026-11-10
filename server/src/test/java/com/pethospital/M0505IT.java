package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
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

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true"
})
@AutoConfigureMockMvc
class M0505IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 180_501;
    private static final long OTHER_OWNER = 180_502;
    private static final long DOCTOR = 180_503;
    private static final long OWNER_SESSION = 181_501;
    private static final long OTHER_SESSION = 181_502;
    private static final long DOCTOR_SESSION = 181_503;
    private static final long PET = 184_501;
    private static final long LOG = 186_501;
    private static final long OLD_FILE = 185_501;
    private static final long NEW_FILE = 185_502;
    private static final long OTHER_FILE = 185_503;

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

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM file_binding WHERE business_id>=180000 OR file_id>=180000");
        jdbc.update("DELETE FROM pet_health_log WHERE id>=180000");
        jdbc.update("DELETE FROM pet WHERE id>=180000");
        jdbc.update("DELETE FROM file_object WHERE id>=180000");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=180000");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=180000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=180000");
        jdbc.update("DELETE FROM sys_user WHERE id>=180000");

        seedUser(OWNER, "m0505-owner", "USER");
        seedUser(OTHER_OWNER, "m0505-other", "USER");
        seedUser(DOCTOR, "m0505-doctor", "DOCTOR");
        seedSession(OWNER_SESSION, OWNER, "MINI");
        seedSession(OTHER_SESSION, OTHER_OWNER, "MINI");
        seedSession(DOCTOR_SESSION, DOCTOR, "WEB");
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,sex,neutered,status,version)
            VALUES (?,?,'修订日志宠物','DOG','UNKNOWN','UNKNOWN','ACTIVE',0)
            """, PET, OWNER);
        seedFile(OLD_FILE, OWNER);
        seedFile(NEW_FILE, OWNER);
        seedFile(OTHER_FILE, OTHER_OWNER);
        jdbc.update("""
            INSERT INTO pet_health_log(
                id,pet_id,author_user_id,observed_at,weight_kg,diet_note,version
            ) VALUES (?,?,?,?,4.20,'旧饮食记录',0)
            """, LOG, PET, OWNER, Timestamp.from(Instant.parse("2026-10-01T08:00:00Z")));
        jdbc.update("""
            INSERT INTO file_binding(id,file_id,business_type,business_id,owner_user_id,created_by)
            VALUES (188501,?,'HEALTH_LOG',?,?,?),
                   (188502,?,'MEDICAL_VERSION',189999,?,?)
            """, OLD_FILE, LOG, OWNER, OWNER, OLD_FILE, OWNER, DOCTOR);
    }

    @Test
    void revisesContentAndAttachmentsWithBeforeAfterAuditWhileKeepingClinicalReference() throws Exception {
        patchLog(validBody("更新饮食", List.of(NEW_FILE), 0), ownerBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dietNote").value("更新饮食"))
            .andExpect(jsonPath("$.data.attachmentIds[0]").value(Long.toString(NEW_FILE)))
            .andExpect(jsonPath("$.data.version").value(1));

        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='HEALTH_LOG' AND file_id=" + OLD_FILE))
            .isZero();
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='MEDICAL_VERSION' AND file_id=" + OLD_FILE))
            .isOne();
        assertThat(count("SELECT COUNT(*) FROM file_object WHERE id=" + OLD_FILE)).isOne();
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='HEALTH_LOG' AND file_id=" + NEW_FILE))
            .isOne();
        String before = jdbc.queryForObject("""
            SELECT CAST(before_json AS CHAR) FROM audit_log
            WHERE action='HEALTH_LOG_UPDATE' AND resource_id=?
            """, String.class, LOG);
        String after = jdbc.queryForObject("""
            SELECT CAST(after_json AS CHAR) FROM audit_log
            WHERE action='HEALTH_LOG_UPDATE' AND resource_id=?
            """, String.class, LOG);
        assertThat(before).contains("旧饮食记录", Long.toString(OLD_FILE), "\"version\": 0");
        assertThat(after).contains("更新饮食", Long.toString(NEW_FILE), "\"version\": 1");
    }

    @Test
    void concurrentSameVersionUpdatesAllowExactlyOneSuccess() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MvcResult> first = executor.submit(() -> {
                await(start);
                return patchLog(validBody("并发甲", List.of(), 0), ownerBearer()).andReturn();
            });
            Future<MvcResult> second = executor.submit(() -> {
                await(start);
                return patchLog(validBody("并发乙", List.of(), 0), ownerBearer()).andReturn();
            });
            start.countDown();
            List<Integer> statuses = List.of(
                first.get(15, TimeUnit.SECONDS).getResponse().getStatus(),
                second.get(15, TimeUnit.SECONDS).getResponse().getStatus()
            );
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_LOG_UPDATE' AND resource_id=" + LOG))
                .isOne();
            assertThat(jdbc.queryForObject("SELECT version FROM pet_health_log WHERE id=?", Integer.class, LOG))
                .isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void otherOwnerAndDoctorCannotModifyOwnerReport() throws Exception {
        patchLog(validBody("越权主人", List.of(), 0), otherBearer())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PET_NOT_FOUND"));
        patchLog(validBody("医生越权", List.of(), 0), doctorBearer())
            .andExpect(status().isForbidden());
        assertUnchanged();
    }

    @Test
    void rejectsEmptyNonPositiveAndClinicalFields() throws Exception {
        patchLog("""
            {"observedAt":"2026-10-02T08:00:00Z","dietNote":"  ","attachmentIds":[],"version":0}
            """, ownerBearer())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMPTY_HEALTH_LOG"));
        patchLog("""
            {"observedAt":"2026-10-02T08:00:00Z","weightKg":-1,"attachmentIds":[],"version":0}
            """, ownerBearer())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        patchLog(validBody("合法值", List.of(), 0).replace("\"version\":0", "\"version\":0,\"diagnosis\":\"伪造\""), ownerBearer())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertUnchanged();
    }

    @Test
    void attachmentFailureRollsBackContentVersionAuditAndExistingBinding() throws Exception {
        patchLog(validBody("不应提交", List.of(OTHER_FILE), 0), ownerBearer())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FILE_SOURCE_FORBIDDEN"));

        assertUnchanged();
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='HEALTH_LOG' AND file_id=" + OLD_FILE))
            .isOne();
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='HEALTH_LOG' AND file_id=" + OTHER_FILE))
            .isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_LOG_UPDATE' AND resource_id=" + LOG))
            .isZero();
    }

    private org.springframework.test.web.servlet.ResultActions patchLog(String body, String bearer) throws Exception {
        return mockMvc.perform(patch("/api/v1/pets/{petId}/health-logs/{logId}", PET, LOG)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForObject("SELECT diet_note FROM pet_health_log WHERE id=?", String.class, LOG))
            .isEqualTo("旧饮食记录");
        assertThat(jdbc.queryForObject("SELECT version FROM pet_health_log WHERE id=?", Integer.class, LOG))
            .isZero();
    }

    private void seedFile(long fileId, long uploader) {
        jdbc.update("""
            INSERT INTO file_object(
                id,uploader_user_id,object_key,original_name,mime_type,size_bytes,
                sha256,purpose,status,validated_at
            ) VALUES (?,?,?,?,'image/png',128,
                      '5555555555555555555555555555555555555555555555555555555555555555',
                      'HEALTH_LOG','READY',CURRENT_TIMESTAMP(3))
            """, fileId, uploader, "m0505/" + fileId + ".png", "health-" + fileId + ".png");
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

    private void seedSession(long sessionId, long userId, String clientType) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """, sessionId, userId, clientType, "%064x".formatted(sessionId), "family-" + sessionId,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private String ownerBearer() {
        return "Bearer " + token(OWNER, OWNER_SESSION, "MINI", "USER");
    }

    private String otherBearer() {
        return "Bearer " + token(OTHER_OWNER, OTHER_SESSION, "MINI", "USER");
    }

    private String doctorBearer() {
        return "Bearer " + token(DOCTOR, DOCTOR_SESSION, "WEB", "DOCTOR");
    }

    private String token(long userId, long sessionId, String audience, String role) {
        return tokenIssuer.issue(userId, sessionId, 0, audience, List.of(role), Instant.now(), 1800).value();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static String validBody(String dietNote, List<Long> attachmentIds, int version) {
        String ids = attachmentIds.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return """
            {"observedAt":"2026-10-02T08:00:00Z","weightKg":4.50,"dietNote":"%s",
             "attachmentIds":[%s],"version":%d}
            """.formatted(dietNote, ids, version);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发开始信号超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", exception);
        }
    }
}
