package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class M1102IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 211101;
    private static final long DOCTOR = 211102;
    private static final long OTHER_DOCTOR = 211103;
    private static final long DISABLED_DOCTOR = 211104;
    private static final long OWNER = 211105;
    private static final long FILE = 211111;
    private static final long PREVIOUS_FILE = 211112;
    private static final String DOCS = "/api/v1/staff/knowledge/documents";
    private static final String VERSIONS = "/api/v1/staff/knowledge/versions";
    private static final String URL_VERSION = """
        {"content":"知识正文夹具","sourceTitle":"来源说明","sourceUrl":"https://example.org/citation"}
        """;
    private static final byte[] SOURCE_BYTES = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+rjWQAAAAASUVORK5CYII=");
    private final String objectKey = "m1102/" + UUID.randomUUID() + ".png";
    private boolean storedObject;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
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
        seedActor(ADMIN, "WEB", "ADMIN");
        seedActor(DOCTOR, "WEB", "DOCTOR");
        seedActor(OTHER_DOCTOR, "WEB", "DOCTOR");
        seedActor(DISABLED_DOCTOR, "WEB", "DOCTOR");
        seedActor(OWNER, "MINI", "USER");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status)
            VALUES (?,?,'医生甲','ACTIVE'),(?,?,'医生乙','ACTIVE'),(?,?,'停用医生','DISABLED')
            """, DOCTOR, DOCTOR, OTHER_DOCTOR, OTHER_DOCTOR, DISABLED_DOCTOR, DISABLED_DOCTOR);
        seedFile(FILE, objectKey);
        seedFile(PREVIOUS_FILE, objectKey + "-previous");
        storage.put(objectKey, SOURCE_BYTES, "image/png");
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
    void rejectionRequiresAnActualEditAndApprovalFreezesTheNextSubmission() throws Exception {
        long doc = createDoc("m1102-doc-lifecycle");
        long id = createVersion(doc, "m1102-version-lifecycle", URL_VERSION);
        review(id, doctor(), "m1102-draft-review", "{\"version\":0,\"decision\":\"APPROVE\"}")
            .andExpect(status().isConflict());
        write(VERSIONS + "/" + id + "/submit-review", doctor(), "m1102-doctor-submit", "{\"version\":0}")
            .andExpect(status().isForbidden());
        submit(id, 0, "m1102-admin-submit-one").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewStatus").value("IN_REVIEW"));
        review(id, admin(), "m1102-admin-approve", "{\"version\":1,\"decision\":\"APPROVE\"}").andExpect(status().isForbidden());
        review(id, bearer(DISABLED_DOCTOR, "WEB", "DOCTOR"), "m1102-inactive-review",
            "{\"version\":1,\"decision\":\"APPROVE\"}").andExpect(status().isForbidden());
        review(id, doctor(), "m1102-empty-reject", "{\"version\":1,\"decision\":\"REJECT\",\"reviewNote\":\" \"}")
            .andExpect(status().isBadRequest());
        review(id, doctor(), "m1102-doctor-reject", "{\"version\":1,\"decision\":\"REJECT\",\"reviewNote\":\"补充来源\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value("REJECTED"))
            .andExpect(jsonPath("$.data.version").value(2));
        submit(id, 2, "m1102-unedited-submit").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("KNOWLEDGE_REVIEW_STATE_CONFLICT"));
        edit(id, "{\"version\":2,\"content\":\"知识正文夹具\"}").andExpect(status().isBadRequest());
        edit(id, "{\"version\":2,\"content\":\"补充后的知识正文\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.metadata.reviewStatus").value("DRAFT"))
            .andExpect(jsonPath("$.data.metadata.reviewerUserId").isEmpty())
            .andExpect(jsonPath("$.data.metadata.reviewNote").isEmpty());
        submit(id, 3, "m1102-admin-submit-two").andExpect(status().isOk());
        String key = "m1102-doctor-approve";
        String body = "{\"version\":4,\"decision\":\"APPROVE\"}";
        for (int retry = 0; retry < 2; retry++) {
            review(id, doctor(), key, body).andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewerUserId").value(Long.toString(DOCTOR)))
                .andExpect(jsonPath("$.data.version").value(5));
        }
        review(id, doctor(), key, "{\"version\":4,\"decision\":\"APPROVE\",\"reviewNote\":\"不同请求\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        edit(id, "{\"version\":5,\"content\":\"批准后覆盖\"}").andExpect(status().isConflict());
        assertThat(count("""
            SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND action='KNOWLEDGE_REVIEW_DECIDE' AND actor_type='DOCTOR'
            """, id)).isEqualTo(2);
    }

    @Test
    void currentAttachmentCanBeReadOnlyDuringReviewAndUnboundSourceCannotBeSubmitted() throws Exception {
        long doc = createDoc("m1102-doc-file-review");
        long id = createVersion(doc, "m1102-version-file-review", fileInput(PREVIOUS_FILE));
        edit(id, "{\"version\":0,\"sourceFileId\":\"%d\"}".formatted(FILE)).andExpect(status().isOk());
        ticket(FILE, id, doctor(), "m1102-draft-ticket").andExpect(status().isForbidden());
        submit(id, 1, "m1102-file-review-submit").andExpect(status().isOk());
        ticket(PREVIOUS_FILE, id, doctor(), "m1102-previous-ticket").andExpect(status().isForbidden());
        ticket(FILE, id, bearer(OWNER, "MINI", "USER"), "m1102-owner-ticket").andExpect(status().isForbidden());
        ticket(FILE, id, bearer(DISABLED_DOCTOR, "WEB", "DOCTOR"), "m1102-disabled-ticket").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/files/{id}", FILE).header(HttpHeaders.AUTHORIZATION, doctor())).andExpect(status().isOk());
        String response = ticket(FILE, id, doctor(), "m1102-review-download").andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        URI url = URI.create(json.readTree(response).path("data").path("url").asText());
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<byte[]> file = client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertThat(file.statusCode()).isEqualTo(200);
            assertThat(file.body()).isEqualTo(SOURCE_BYTES);
        }
        review(id, doctor(), "m1102-file-review-done", "{\"version\":2,\"decision\":\"APPROVE\"}").andExpect(status().isOk());
        ticket(FILE, id, doctor(), "m1102-finished-ticket").andExpect(status().isForbidden());

        long invalid = createVersion(doc, "m1102-url-fixture", URL_VERSION);
        jdbc.update("UPDATE knowledge_version SET source_url=NULL,source_file_id=? WHERE id=?", FILE, invalid);
        submit(invalid, 0, "m1102-invalid-source").andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("KNOWLEDGE_SOURCE_UNAVAILABLE"));
        assertThat(count("SELECT COUNT(*) FROM knowledge_version WHERE id=? AND review_status='DRAFT' AND version=0", invalid))
            .isOne();
    }

    @Test
    void concurrentDoctorsCannotDecideTwiceAndReviewKeepsTheOldPublishedPointer() throws Exception {
        long doc = createDoc("m1102-doc-review-race");
        long old = createVersion(doc, "m1102-version-published", URL_VERSION);
        jdbc.update("""
            UPDATE knowledge_version SET review_status='APPROVED',reviewer_user_id=?,reviewed_at=CURRENT_TIMESTAMP(3),
                published_at=CURRENT_TIMESTAMP(3) WHERE id=?
            """, DOCTOR, old);
        jdbc.update("UPDATE knowledge_document SET status='PUBLISHED',active_version_id=? WHERE id=?", old, doc);
        long draft = createVersion(doc, "m1102-version-new-draft", URL_VERSION);
        edit(draft, "{\"version\":0,\"content\":\"后续草稿内容\"}").andExpect(status().isOk());
        assertPublished(doc, old, draft);
        submit(draft, 1, "m1102-race-submit").andExpect(status().isOk());
        assertPublished(doc, old, draft);

        CountDownLatch start = new CountDownLatch(1);
        String firstDoctor = doctor();
        String secondDoctor = bearer(OTHER_DOCTOR, "WEB", "DOCTOR");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> race(start, draft, firstDoctor, "m1102-race-doctor-one"));
            var two = workers.submit(() -> race(start, draft, secondDoctor, "m1102-race-doctor-two"));
            start.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(count("""
            SELECT COUNT(*) FROM knowledge_version WHERE id=? AND review_status='APPROVED' AND version=3 AND published_at IS NULL
            """, draft)).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='KNOWLEDGE_REVIEW_DECIDE' AND resource_id=?", draft)).isOne();
        assertPublished(doc, old, draft);
    }

    private void assertPublished(long doc, long old, long draft) throws Exception {
        mvc.perform(get(DOCS + "/" + doc).header(HttpHeaders.AUTHORIZATION, doctor())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.activeVersionId").value(Long.toString(old)));
        assertThat(count("SELECT COUNT(*) FROM knowledge_index_job WHERE knowledge_version_id=?", draft)).isZero();
    }

    private int race(CountDownLatch start, long id, String token, String key) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("审核请求未同步启动");
        }
        return review(id, token, key, "{\"version\":2,\"decision\":\"APPROVE\"}").andReturn().getResponse().getStatus();
    }

    private long createDoc(String key) throws Exception {
        return id(write(DOCS, admin(), key, "{\"title\":\"审核夹具\",\"speciesCode\":\"CAT\",\"categoryCode\":\"CARE\"}")
            .andExpect(status().isCreated()), "documentId");
    }

    private long createVersion(long doc, String key, String body) throws Exception {
        return id(write(DOCS + "/" + doc + "/versions", admin(), key, body).andExpect(status().isCreated()), "knowledgeVersionId");
    }

    private ResultActions submit(long id, int version, String key) throws Exception {
        return write(VERSIONS + "/" + id + "/submit-review", admin(), key, "{\"version\":" + version + "}");
    }

    private ResultActions review(long id, String token, String key, String body) throws Exception {
        return write(VERSIONS + "/" + id + "/review", token, key, body);
    }

    private ResultActions edit(long id, String body) throws Exception {
        return mvc.perform(patch(VERSIONS + "/" + id).header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions ticket(long fileId, long versionId, String token, String key) throws Exception {
        return write("/api/v1/files/" + fileId + "/download-ticket", token, key, """
            {"businessType":"KNOWLEDGE_VERSION","businessId":"%d"}
            """.formatted(versionId));
    }

    private ResultActions write(String path, String token, String key, String body) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, token).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long id(ResultActions result, String key) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data").path(key).asLong();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static String fileInput(long fileId) {
        return """
            {"content":"附件来源正文","sourceTitle":"来源附件","sourceFileId":"%d"}
            """.formatted(fileId);
    }

    private void seedFile(long id, String key) {
        jdbc.update("""
            INSERT INTO file_object(id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status)
            VALUES (?,?,?,'source.png','image/png',?,?,'KNOWLEDGE','READY')
            """, id, ADMIN, key, SOURCE_BYTES.length, "%064d".formatted(id));
    }

    private void seedActor(long user, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,'测试账号','ACTIVE')", user, "m1102-" + user);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", user, user, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, user, user, audience, "%064d".formatted(user), "m1102-" + user,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private String doctor() {
        return bearer(DOCTOR, "WEB", "DOCTOR");
    }

    private String admin() {
        return bearer(ADMIN, "WEB", "ADMIN");
    }

    private String bearer(long user, String audience, String role) {
        return "Bearer " + tokens.issue(user, user, 0, audience, List.of(role), Instant.now(), 1800).value();
    }
}
