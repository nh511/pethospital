package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.shared.ObjectStorageAdapter;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
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
@Tag("real-s3")
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false", "identity.jwt.allow-ephemeral=true", "storage.s3.enabled=true"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M0803IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String PATH = "/api/v1/staff/medical-records/{id}/revisions";
    private static final long OWNER = 180301;
    private static final long AUTHOR = 180302;
    private static final long OTHER = 180303;
    private static final long ADMIN = 180304;
    private static final long HISTORICAL = 180311;
    private static final long INVALID = 180312;
    private static final long DRAFT = 180313;
    private static final long RACE = 180314;
    private static final long OLD_FILE = 180321;
    private static final long NEW_FILE = 180322;
    private static final long FOREIGN_FILE = 180323;
    private static final byte[] OLD_CONTENT = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+rjWQAAAAASUVORK5CYII=");
    private final String objectKey = "m0803/" + UUID.randomUUID() + ".png";
    private boolean storedObject;

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired Rs256AccessTokenIssuer tokens;
    @Autowired ObjectStorageAdapter storage;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @BeforeAll
    void seed() {
        seedActor(OWNER, "MINI", "USER");
        seedActor(AUTHOR, "WEB", "DOCTOR");
        seedActor(OTHER, "WEB", "DOCTOR");
        seedActor(ADMIN, "WEB", "ADMIN");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status)
            VALUES (?,?,'原署名医生','ACTIVE'),(?,?,'其他医生','ACTIVE')
            """, AUTHOR, AUTHOR, OTHER, OTHER);
        for (long id : new long[] {HISTORICAL, INVALID, DRAFT, RACE}) {
            seedRecord(id);
        }
        seedFile(OLD_FILE, AUTHOR, objectKey);
        seedFile(NEW_FILE, AUTHOR, objectKey + "-new");
        seedFile(FOREIGN_FILE, OTHER, objectKey + "-foreign");
        jdbc.update("""
            INSERT INTO file_binding(id,file_id,business_type,business_id,owner_user_id,created_by)
            VALUES (?,?,'MEDICAL_VERSION',?,?,?)
            """, OLD_FILE, OLD_FILE, initialRevision(HISTORICAL), OWNER, AUTHOR);
        storage.put(objectKey, OLD_CONTENT, "image/png");
        storedObject = true;
    }

    @AfterAll
    void cleanup() {
        try {
            if (storedObject) {
                storage.delete(objectKey);
            }
        } finally {
            MYSQL.close();
        }
    }

    @Test
    void historicalRevisionPreservesPriorSnapshotAndOwnerCanDownloadOldAttachment() throws Exception {
        String body = withAttachment(payload(1, "补充复诊记录", "修订后的诊断"), NEW_FILE);
        revise(HISTORICAL, "m0803-history-revise", author(), body).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.medicalRecordId").value(Long.toString(HISTORICAL)))
            .andExpect(jsonPath("$.data.version").value(2)).andExpect(jsonPath("$.data.revisionNo").value(2));
        assertThat(count("""
            SELECT COUNT(*) FROM medical_record r
            JOIN medical_record_version prior ON prior.record_id=r.id AND prior.revision_no=1
            JOIN medical_record_version rev ON rev.record_id=r.id AND rev.revision_no=2
            JOIN file_binding f ON f.business_type='MEDICAL_VERSION' AND f.business_id=rev.id AND f.file_id=?
            WHERE r.id=? AND r.version=2 AND r.revision_no=2 AND prior.before_content_json IS NULL
              AND rev.before_content_json=prior.after_content_json AND rev.after_content_json=r.content_json
              AND prior.after_content_json->>'$.diagnosis'='首次诊断'
              AND rev.after_content_json->>'$.diagnosis'='修订后的诊断'
              AND rev.after_content_json->>'$.attachmentIds[0]'=?
              AND rev.reason='补充复诊记录' AND rev.actor_user_id=?
            """, NEW_FILE, HISTORICAL, Long.toString(NEW_FILE), AUTHOR)).isOne();
        assertThat(count("SELECT version FROM appointment WHERE id=?", appointment(HISTORICAL))).isEqualTo(7);
        assertThat(count("SELECT occupied_count FROM doctor_slot WHERE id=?", slot(HISTORICAL))).isOne();
        assertThat(count("""
            SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND action='MEDICAL_RECORD_REVISE'
              AND CAST(after_json AS CHAR) NOT LIKE '%修订后的诊断%'
            """, HISTORICAL)).isOne();

        String ticket = mockMvc.perform(post("/api/v1/files/{id}/download-ticket", OLD_FILE)
                .header(HttpHeaders.AUTHORIZATION, bearer(OWNER, "MINI", "USER"))
                .header("Idempotency-Key", "m0803-owner-old-file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"businessType":"MEDICAL_VERSION","businessId":"%d"}
                    """.formatted(initialRevision(HISTORICAL))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.url").isString())
            .andReturn().getResponse().getContentAsString();
        URI uri = URI.create(json.readTree(ticket).path("data").path("url").asText());
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<byte[]> result = client.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).isEqualTo(OLD_CONTENT);
        }
    }

    @Test
    void rejectsInvalidRequestsAndNonAuthors() throws Exception {
        String valid = payload(1, "修订说明", "新的诊断");
        revise(INVALID, "m0803-empty-reason", author(), payload(1, " ", "新的诊断")).andExpect(status().isBadRequest());
        revise(INVALID, "m0803-unknown-field", author(), valid.replace("\"treatment\":", "\"aiDiagnosis\":\"注入\",\"treatment\":"))
            .andExpect(status().isBadRequest());
        revise(INVALID, "m0803-wrong-version", author(), payload(0, "修订说明", "新的诊断")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        revise(INVALID, "m0803-other-doctor", bearer(OTHER, "WEB", "DOCTOR"), valid).andExpect(status().isNotFound());
        revise(INVALID, "m0803-administrator", bearer(ADMIN, "WEB", "ADMIN"), valid).andExpect(status().isForbidden());
        revise(INVALID, "m0803-owner-revise", bearer(OWNER, "MINI", "USER"), valid).andExpect(status().isForbidden());
        revise(INVALID, "m0803-foreign-file", author(), withAttachment(valid, FOREIGN_FILE))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FILE_SOURCE_FORBIDDEN"));
        revise(DRAFT, "m0803-draft-revise", author(), valid).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM medical_record_version WHERE record_id=?", INVALID)).isOne();
        assertThat(count("SELECT version FROM medical_record WHERE id=?", INVALID)).isOne();
        assertThat(count("SELECT COUNT(*) FROM medical_record_version WHERE record_id=?", DRAFT)).isZero();
    }

    @Test
    void concurrentRevisionsHaveOneWinnerAndReplayReturnsTheSameRevision() throws Exception {
        String first = "m0803-race-first";
        String second = "m0803-race-second";
        String body = payload(1, "并发修订", "并发后的诊断");
        String token = author();
        CountDownLatch start = new CountDownLatch(1);
        int firstResult;
        int secondResult;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> race(start, first, token, body));
            var b = workers.submit(() -> race(start, second, token, body));
            start.countDown();
            firstResult = a.get(20, TimeUnit.SECONDS);
            secondResult = b.get(20, TimeUnit.SECONDS);
        }
        assertThat(List.of(firstResult, secondResult)).containsExactlyInAnyOrder(200, 409);
        String winner = firstResult == 200 ? first : second;
        String revisionId = jdbc.queryForObject(
            "SELECT CAST(id AS CHAR) FROM medical_record_version WHERE record_id=? AND revision_no=2", String.class, RACE);
        revise(RACE, winner, token, body).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revisionId").value(revisionId));
        revise(RACE, winner, token, payload(1, "修改请求", "并发后的诊断")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM medical_record_version WHERE record_id=?", RACE)).isEqualTo(2);
        assertThat(count("SELECT version FROM medical_record WHERE id=?", RACE)).isEqualTo(2);
    }

    private int race(CountDownLatch start, String key, String token, String body) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发请求未同步启动");
        }
        return revise(RACE, key, token, body).andReturn().getResponse().getStatus();
    }

    private ResultActions revise(long id, String key, String token, String body) throws Exception {
        return mockMvc.perform(post(PATH, id).header(HttpHeaders.AUTHORIZATION, token)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String payload(int version, String reason, String diagnosis) {
        return """
            {"version":%d,"reason":"%s","medicalRecord":{"chiefComplaint":"测试主诉","examination":"检查",
            "diagnosis":"%s","treatment":"治疗"},"attachmentIds":[]}
            """.formatted(version, reason, diagnosis);
    }

    private static String withAttachment(String body, long file) {
        return body.replace("\"attachmentIds\":[]", "\"attachmentIds\":[\"%d\"]".formatted(file));
    }

    private void seedFile(long id, long uploader, String key) {
        jdbc.update("""
            INSERT INTO file_object(id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status)
            VALUES (?,?,?,'fixture.png','image/png',?,?,'MEDICAL','READY')
            """, id, uploader, key, OLD_CONTENT.length, "%064d".formatted(id));
    }

    private void seedRecord(long id) {
        boolean draft = id == DRAFT;
        LocalDateTime start = LocalDateTime.ofInstant(Instant.now().minus(35 + id - HISTORICAL, ChronoUnit.DAYS), ZoneOffset.UTC);
        LocalDateTime end = start.plusMinutes(30);
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'夹具','CAT')", pet(id), OWNER);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,1,1,'CLOSED')
            """, slot(id), AUTHOR, start.toLocalDate(), start, end);
        jdbc.update("""
            INSERT INTO appointment(id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,confirm_deadline_at,completed_at,idempotency_key,request_hash,version)
            VALUES (?,?,?,?,?,?,?,?,?,?,'测试主诉',?,?,?,?,7)
            """, appointment(id), "M0803-" + id, OWNER, pet(id), AUTHOR, slot(id), start, end, start,
            draft ? "IN_CONSULTATION" : "COMPLETED", start.minusHours(1), draft ? null : end,
            "m0803-appointment-" + id, "%064d".formatted(id));
        String content = """
            {"chiefComplaint":"测试主诉","examination":"检查","diagnosis":"首次诊断","treatment":"治疗","attachmentIds":[%s]}
            """.formatted(id == HISTORICAL ? Long.toString(OLD_FILE) : "");
        jdbc.update("""
            INSERT INTO medical_record(id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at,version)
            VALUES (?,?,?,?,?,CAST(? AS JSON),?,?,1)
            """, id, appointment(id), pet(id), AUTHOR, draft ? "DRAFT" : "SUBMITTED", content,
            draft ? 0 : 1, draft ? null : end);
        if (!draft) {
            jdbc.update("""
                INSERT INTO medical_record_version(id,record_id,revision_no,before_content_json,after_content_json,
                    reason,actor_user_id,idempotency_key,created_at)
                VALUES (?,?,1,NULL,CAST(? AS JSON),'首次提交',?,?,?)
                """, initialRevision(id), id, content, AUTHOR, "m0803-initial-" + id, end);
        }
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,'测试账号','ACTIVE')", id, "m0803-" + id);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0803-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private String author() { return bearer(AUTHOR, "WEB", "DOCTOR"); }
    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokens.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static long pet(long id) { return id + 100; }
    private static long slot(long id) { return id + 200; }
    private static long appointment(long id) { return id + 300; }
    private static long initialRevision(long id) { return id + 400; }
}
