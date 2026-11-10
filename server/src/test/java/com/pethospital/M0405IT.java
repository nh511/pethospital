package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.FileDtos.DiscardRequest;
import com.pethospital.shared.FileDtos.DownloadTicketRequest;
import com.pethospital.shared.FileService;
import com.pethospital.shared.ObjectStorageAdapter;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag(TestTags.MYSQL_IT)
@Tag("real-s3")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "storage.s3.enabled=true"
})
class M0405IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 40_501;
    private static final long OTHER = 40_502;
    private static final long DOCTOR_USER = 40_503;
    private static final long DOCTOR = 41_503;
    private static final long PET = 42_501;
    private static final long HEALTH_LOG = 43_501;
    private static final long SLOT = 44_501;
    private static final long APPOINTMENT = 45_501;
    private static final long UNBOUND_FILE = 50_501;
    private static final long BOUND_FILE = 50_502;
    private static final long IMMUTABLE_FILE = 50_503;
    private static final long FAILURE_FILE = 50_504;
    private static final byte[] CONTENT = "M04-05真实私有对象".getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FileService files;

    @Autowired
    ObjectStorageAdapter storage;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetFixtures() {
        cleanup();
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,'m0405-owner',NULL,'下载主人','ACTIVE',0,0),
                   (?,'m0405-other',NULL,'其他主人','ACTIVE',0,0),
                   (?,'m0405-doctor',NULL,'过期医生','ACTIVE',0,0)
            """, OWNER, OTHER, DOCTOR_USER);
        grantRole(OWNER, "USER", 60_501);
        grantRole(OTHER, "USER", 60_502);
        grantRole(DOCTOR_USER, "DOCTOR", 60_503);
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,'过期医生','ACTIVE',0)
            """, DOCTOR, DOCTOR_USER);
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,sex,neutered,status,version)
            VALUES (?,?,'下载宠物','CAT','UNKNOWN','UNKNOWN','ACTIVE',0)
            """, PET, OWNER);
        jdbc.update("""
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at,symptom_note,version)
            VALUES (?,?,?,CURRENT_TIMESTAMP(3),'下载附件测试',0)
            """, HEALTH_LOG, PET, OWNER);
        jdbc.update("""
            INSERT INTO doctor_slot(
              id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
            ) VALUES (?,?,CURRENT_DATE - INTERVAL 31 DAY,
              CURRENT_TIMESTAMP(3) - INTERVAL 31 DAY,
              CURRENT_TIMESTAMP(3) - INTERVAL 31 DAY + INTERVAL 30 MINUTE,
              1,1,'CLOSED',0)
            """, SLOT, DOCTOR);
        jdbc.update("""
            INSERT INTO appointment(
              id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,
              scheduled_end_at,initial_start_at,status,chief_complaint,confirm_deadline_at,
              confirmed_at,completed_at,idempotency_key,request_hash,version
            ) VALUES (?,'M0405-EXPIRED',?,?,?,?,
              CURRENT_TIMESTAMP(3) - INTERVAL 31 DAY,
              CURRENT_TIMESTAMP(3) - INTERVAL 31 DAY + INTERVAL 30 MINUTE,
              CURRENT_TIMESTAMP(3) - INTERVAL 31 DAY,'COMPLETED','授权过期测试',
              CURRENT_TIMESTAMP(3) - INTERVAL 32 DAY,
              CURRENT_TIMESTAMP(3) - INTERVAL 32 DAY,
              CURRENT_TIMESTAMP(3) - INTERVAL 31 DAY,
              'm0405-expired-appointment',
              '5555555555555555555555555555555555555555555555555555555555555555',0)
            """, APPOINTMENT, OWNER, PET, DOCTOR, SLOT);

        insertFile(UNBOUND_FILE, OWNER, "AVATAR", objectKey(UNBOUND_FILE));
        insertFile(BOUND_FILE, OWNER, "HEALTH_LOG", objectKey(BOUND_FILE));
        insertFile(IMMUTABLE_FILE, DOCTOR_USER, "MEDICAL", objectKey(IMMUTABLE_FILE));
        insertFile(FAILURE_FILE, OWNER, "AVATAR", "../m0405-invalid-key");
        jdbc.update("""
            INSERT INTO file_binding(id,file_id,business_type,business_id,owner_user_id,created_by)
            VALUES (?,?,?,?,?,?),(?,?,?,?,?,?)
            """,
            70_501, BOUND_FILE, "HEALTH_LOG", HEALTH_LOG, OWNER, OWNER,
            70_502, IMMUTABLE_FILE, "MEDICAL_VERSION", 99_505, OWNER, DOCTOR_USER
        );
        storage.put(objectKey(UNBOUND_FILE), CONTENT, "text/plain; charset=utf-8");
        storage.put(objectKey(BOUND_FILE), CONTENT, "text/plain; charset=utf-8");
        storage.put(objectKey(IMMUTABLE_FILE), CONTENT, "text/plain; charset=utf-8");
    }

    @AfterEach
    void cleanupAfterTest() {
        SecurityContextHolder.clearContext();
        cleanup();
    }

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @Test
    void unboundUploaderCanPreviewThroughHttpAndReadRealPrivateObject() throws Exception {
        var auth = jwtFor(OWNER, "MINI", "USER");
        String body = mockMvc.perform(post("/api/v1/files/{fileId}/download-ticket", UNBOUND_FILE)
                .contentType("application/json")
                .content("{}")
                .header("Idempotency-Key", "m0405-unbound-ticket-01")
                .with(auth))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.url").isString())
            .andExpect(jsonPath("$.data.expiresAt").isString())
            .andReturn().getResponse().getContentAsString();

        URI url = URI.create(objectMapper.readTree(body).path("data").path("url").asText());
        HttpResponse<byte[]> response = get(url);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(CONTENT);

        authenticate(OTHER, "MINI", "USER");
        assertDenied(() -> files.downloadTicket(
            UNBOUND_FILE, new DownloadTicketRequest(null, null), "m0405-other-ticket-01"
        ));
    }

    @Test
    void boundTicketRequiresExactBindingAndCurrentObjectScope() {
        authenticate(OWNER, "MINI", "USER");
        var ticket = files.downloadTicket(
            BOUND_FILE,
            new DownloadTicketRequest("HEALTH_LOG", Long.toString(HEALTH_LOG)),
            "m0405-bound-ticket-001"
        );
        assertThat(ticket.url()).contains("X-Amz-Expires=60");

        assertDenied(() -> files.downloadTicket(
            BOUND_FILE,
            new DownloadTicketRequest("HEALTH_LOG", "999999"),
            "m0405-swapped-ticket-01"
        ));
        authenticate(OTHER, "MINI", "USER");
        assertDenied(() -> files.downloadTicket(
            BOUND_FILE,
            new DownloadTicketRequest("HEALTH_LOG", Long.toString(HEALTH_LOG)),
            "m0405-cross-user-ticket"
        ));
    }

    @Test
    void expiredDoctorScopeCannotIssueTicket() {
        authenticate(DOCTOR_USER, "WEB", "DOCTOR");
        assertDenied(() -> files.downloadTicket(
            BOUND_FILE,
            new DownloadTicketRequest("HEALTH_LOG", Long.toString(HEALTH_LOG)),
            "m0405-expired-ticket-01"
        ));
    }

    @Test
    void discardDeletesRealObjectAndSameKeyReplaysExactlyOnce() {
        authenticate(OWNER, "MINI", "USER");
        DiscardRequest request = new DiscardRequest("用户放弃未绑定预览文件");
        var first = files.discard(UNBOUND_FILE, request, "m0405-discard-replay1");
        var replay = files.discard(UNBOUND_FILE, request, "m0405-discard-replay1");

        assertThat(replay).isEqualTo(first);
        assertThat(first.status()).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM file_object WHERE id=?", String.class, UNBOUND_FILE
        )).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject(
            "SELECT deleted_at IS NOT NULL FROM file_object WHERE id=?", Boolean.class, UNBOUND_FILE
        )).isTrue();
        assertThatThrownBy(() -> storage.get(objectKey(UNBOUND_FILE))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_log
            WHERE actor_user_id=? AND operation_key='m0405-discard-replay1'
            """, Integer.class, OWNER)).isEqualTo(1);
        String auditAfter = jdbc.queryForObject("""
            SELECT CAST(after_json AS CHAR) FROM audit_log
            WHERE actor_user_id=? AND operation_key='m0405-discard-replay1'
            """, String.class, OWNER);
        assertThat(auditAfter).doesNotContain("http", "url", "m0405/");
    }

    @Test
    void boundVersionCannotDiscardAndStorageFailurePreservesReadyMetadata() {
        authenticate(DOCTOR_USER, "WEB", "DOCTOR");
        assertThatThrownBy(() -> files.discard(
            IMMUTABLE_FILE,
            new DiscardRequest("错误删除历史版本"),
            "m0405-bound-discard-01"
        )).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("FILE_DISCARD_FORBIDDEN")
        );
        assertThat(storage.get(objectKey(IMMUTABLE_FILE))).isEqualTo(CONTENT);

        authenticate(OWNER, "MINI", "USER");
        assertThatThrownBy(() -> files.discard(
            FAILURE_FILE,
            new DiscardRequest("触发对象存储失败"),
            "m0405-failed-discard-1"
        )).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.code).isEqualTo("OBJECT_STORAGE_UNAVAILABLE");
            assertThat(error.status.value()).isEqualTo(503);
        });
        assertThat(jdbc.queryForObject(
            "SELECT status FROM file_object WHERE id=?", String.class, FAILURE_FILE
        )).isEqualTo("READY");
        assertThat(jdbc.queryForObject(
            "SELECT deleted_at IS NULL FROM file_object WHERE id=?", Boolean.class, FAILURE_FILE
        )).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_log
            WHERE actor_user_id=? AND operation_key='m0405-failed-discard-1'
            """, Integer.class, OWNER)).isZero();
    }

    private void insertFile(long id, long uploader, String purpose, String key) {
        jdbc.update("""
            INSERT INTO file_object(
              id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,
              purpose,status,validated_at
            ) VALUES (?,?,?,?,'text/plain',?,
              '5555555555555555555555555555555555555555555555555555555555555555',
              ?,'READY',CURRENT_TIMESTAMP(3))
            """, id, uploader, key, "m0405-" + id + ".txt", CONTENT.length, purpose);
    }

    private void grantRole(long userId, String roleCode, long assignmentId) {
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, roleCode);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
            assignmentId, userId, roleId);
    }

    private void cleanup() {
        SecurityContextHolder.clearContext();
        if (!tableReady()) {
            return;
        }
        jdbc.queryForList(
            "SELECT object_key FROM file_object WHERE id BETWEEN ? AND ?",
            String.class, UNBOUND_FILE, FAILURE_FILE
        ).forEach(key -> {
            try {
                storage.delete(key);
            } catch (RuntimeException ignored) {
                // 断言失败时仍继续清理数据库；非法键也是本任务的故障注入夹具。
            }
        });
        jdbc.update("DELETE FROM file_binding WHERE file_id BETWEEN ? AND ?", UNBOUND_FILE, FAILURE_FILE);
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id IN (?,?,?)", OWNER, OTHER, DOCTOR_USER);
        jdbc.update("DELETE FROM appointment WHERE id=?", APPOINTMENT);
        jdbc.update("DELETE FROM doctor_slot WHERE id=?", SLOT);
        jdbc.update("DELETE FROM pet_health_log WHERE id=?", HEALTH_LOG);
        jdbc.update("DELETE FROM pet WHERE id=?", PET);
        jdbc.update("DELETE FROM file_object WHERE id BETWEEN ? AND ?", UNBOUND_FILE, FAILURE_FILE);
        jdbc.update("DELETE FROM doctor_profile WHERE id=?", DOCTOR);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (?,?,?)", OWNER, OTHER, DOCTOR_USER);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?,?,?)", OWNER, OTHER, DOCTOR_USER);
    }

    private boolean tableReady() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM file_object", Integer.class);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String objectKey(long fileId) {
        return "m0405/" + fileId + ".txt";
    }

    private static void assertDenied(ThrowingCall call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("FILE_ACCESS_DENIED")
        );
    }

    private static HttpResponse<byte[]> get(URI uri) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
        jwtFor(long userId, String audience, String role) {
        return jwt()
            .jwt(builder -> builder
                .claim("userId", Long.toString(userId))
                .claim("sessionId", "40501")
                .claim("roles", List.of(role))
                .audience(List.of(audience)))
            .authorities(
                new SimpleGrantedAuthority("AUD_" + audience),
                new SimpleGrantedAuthority("ROLE_" + role)
            );
    }

    private static void authenticate(long userId, String audience, String role) {
        Instant now = Instant.now();
        Jwt token = Jwt.withTokenValue("m0405-test-token")
            .header("alg", "RS256")
            .subject(Long.toString(userId))
            .claim("userId", Long.toString(userId))
            .claim("sessionId", "40501")
            .claim("roles", List.of(role))
            .audience(List.of(audience))
            .issuedAt(now.minusSeconds(1))
            .expiresAt(now.plusSeconds(300))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            token,
            List.of(
                new SimpleGrantedAuthority("AUD_" + audience),
                new SimpleGrantedAuthority("ROLE_" + role)
            )
        ));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
