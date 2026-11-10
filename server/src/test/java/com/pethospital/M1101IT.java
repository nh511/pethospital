package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
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
class M1101IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String DOCS = "/api/v1/staff/knowledge/documents";
    private static final String VERSIONS = "/api/v1/staff/knowledge/versions";
    private static final long ADMIN = 211001;
    private static final long DOCTOR = 211002;
    private static final long DISABLED = 211003;
    private static final long OWNER = 211004;
    private static final long ATTACHMENT = 211011;
    private static final long FOREIGN = 211012;
    private static final long WRONG_PURPOSE = 211013;
    private static final String VERSION_INPUT = """
        {"content":"知识正文夹具","sourceTitle":"引用来源","sourceUrl":"https://example.org/guide"}
        """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Rs256AccessTokenIssuer tokens;

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
        seedActor(DISABLED, "WEB", "DOCTOR");
        seedActor(OWNER, "MINI", "USER");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status)
            VALUES (?,?,'医生夹具','ACTIVE'),(?,?,'停用医生','DISABLED')
            """, DOCTOR, DOCTOR, DISABLED, DISABLED);
        seedFile(ATTACHMENT, ADMIN, "KNOWLEDGE");
        seedFile(FOREIGN, DOCTOR, "KNOWLEDGE");
        seedFile(WRONG_PURPOSE, ADMIN, "MEDICAL");
    }

    @AfterAll
    static void cleanup() {
        MYSQL.close();
    }

    @Test
    void adminWritesDoctorReadsAndDocumentPatchUsesExpectedVersion() throws Exception {
        String key = "m1101-document-access";
        long doc = createDocument(key);
        assertThat(createDocument(key)).isEqualTo(doc);
        createDoc(key, admin(), documentInput().replace("资料夹具", "不同请求"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        String doctor = bearer(DOCTOR, "WEB", "DOCTOR");
        read(DOCS + "/" + doc, doctor).andExpect(status().isOk()).andExpect(jsonPath("$.data.speciesCode").value("CAT"));
        mvc.perform(get(DOCS).param("pageSize", "1").header(HttpHeaders.AUTHORIZATION, doctor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.pageSize").value(1));
        read(DOCS + "/" + doc, bearer(DISABLED, "WEB", "DOCTOR")).andExpect(status().isForbidden());
        read(DOCS + "/" + doc, bearer(OWNER, "MINI", "USER")).andExpect(status().isForbidden());
        createDoc("m1101-doctor-denied", doctor, documentInput()).andExpect(status().isForbidden());
        edit(DOCS + "/" + doc, """
            {"version":0,"categoryCode":"PREVENTION","tags":["护理"],"title":"更新后的标题"}
            """).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.categoryCode").value("PREVENTION"));
        edit(DOCS + "/" + doc, "{\"version\":0,\"title\":\"过期修改\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        edit(DOCS + "/" + doc, "{\"version\":1,\"activeVersionId\":\"123\"}").andExpect(status().isBadRequest());
    }

    @Test
    void versionsValidateAndBindSourcesWithRollbackAndHashTheExactContent() throws Exception {
        long doc = createDocument("m1101-document-source");
        createVersion(doc, "m1101-source-missing", "{\"content\":\"正文\",\"sourceTitle\":\"缺失来源\"}")
            .andExpect(status().isBadRequest());
        createVersion(doc, "m1101-source-unsafe", VERSION_INPUT.replace("https://", "http://"))
            .andExpect(status().isBadRequest());
        createVersion(doc, "m1101-source-userinfo", VERSION_INPUT.replace("example.org", "viewer@example.org"))
            .andExpect(status().isBadRequest());
        createVersion(doc, "m1101-source-other-file", attachmentInput(FOREIGN))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FILE_SOURCE_FORBIDDEN"));
        createVersion(doc, "m1101-source-wrong-purpose", attachmentInput(WRONG_PURPOSE))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("FILE_PURPOSE_MISMATCH"));
        createVersion(doc, "m1101-source-missing-file", attachmentInput(211099))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("FILE_NOT_READY"));
        assertThat(count("SELECT COUNT(*) FROM knowledge_version WHERE document_id=?", doc)).isZero();
        long version = id(createVersion(doc, "m1101-source-own-file", attachmentInput(ATTACHMENT))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.versionNo").value(1)), "knowledgeVersionId");
        assertThat(count("""
            SELECT COUNT(*) FROM file_binding WHERE business_type='KNOWLEDGE_VERSION'
              AND business_id=? AND file_id=? AND owner_user_id=?
            """, version, ATTACHMENT, ADMIN)).isOne();
        edit(VERSIONS + "/" + version, """
            {"version":0,"content":"修订后的正文","sourceUrl":"https://example.org/revised"}
            """).andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("修订后的正文"))
            .andExpect(jsonPath("$.data.metadata.version").value(1))
            .andExpect(jsonPath("$.data.metadata.sourceFileId").isEmpty());
        assertThat(count("""
            SELECT COUNT(*) FROM knowledge_version WHERE id=? AND content_hash=SHA2(content,256) AND source_file_id IS NULL
            """, version)).isOne();
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='KNOWLEDGE_VERSION' AND business_id=?", version)).isOne();
        assertThat(count("""
            SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND
              (CAST(before_json AS CHAR) LIKE '%修订后的正文%' OR CAST(after_json AS CHAR) LIKE '%修订后的正文%')
            """, version)).isZero();
    }

    @Test
    void approvedBodyAndPublishedClassificationFreezeWhileAnotherVersionCanBeCreated() throws Exception {
        long doc = createDocument("m1101-document-approved");
        long other = createDocument("m1101-document-unrelated");
        long version = id(createVersion(doc, "m1101-version-approved", VERSION_INPUT).andExpect(status().isCreated()),
            "knowledgeVersionId");
        jdbc.update("""
            UPDATE knowledge_version SET review_status='APPROVED',reviewer_user_id=?,reviewed_at=CURRENT_TIMESTAMP(3),
                published_at=CURRENT_TIMESTAMP(3) WHERE id=?
            """, DOCTOR, version);
        jdbc.update("UPDATE knowledge_document SET status='OFFLINE' WHERE id=?", doc);
        edit(VERSIONS + "/" + version, "{\"version\":0,\"content\":\"原地覆盖\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KNOWLEDGE_VERSION_IMMUTABLE"));
        edit(DOCS + "/" + doc, "{\"version\":0,\"tags\":[\"改标签\"]}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KNOWLEDGE_CLASSIFICATION_FROZEN"));
        edit(DOCS + "/" + doc, "{\"version\":0,\"speciesCode\":\"DOG\"}").andExpect(status().isConflict());
        edit(DOCS + "/" + doc, "{\"version\":0,\"title\":\"允许更名\"}").andExpect(status().isOk());
        createVersion(doc, "m1101-new-draft-version", VERSION_INPUT).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.versionNo").value(2));
        String doctor = bearer(DOCTOR, "WEB", "DOCTOR");
        read(VERSIONS + "/" + version, doctor).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").value("知识正文夹具"));
        read(DOCS + "/" + doc + "/versions", doctor).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].versionNo").value(2))
            .andExpect(jsonPath("$.data.items[0].content").doesNotExist());
        read(DOCS + "/" + other + "/versions", doctor).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        jdbc.update("UPDATE knowledge_document SET active_version_id=? WHERE id=?", version, other);
        read(DOCS + "/" + other, doctor).andExpect(status().isNotFound());
    }

    @Test
    void concurrentVersionCreationSerializesNumbersAndReplaysOriginalReceipt() throws Exception {
        long doc = createDocument("m1101-document-race");
        String one = "m1101-version-race-one";
        String two = "m1101-version-race-two";
        CountDownLatch start = new CountDownLatch(1);
        long first;
        long second;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> concurrentVersion(start, doc, one));
            var b = workers.submit(() -> concurrentVersion(start, doc, two));
            start.countDown();
            first = a.get(20, TimeUnit.SECONDS);
            second = b.get(20, TimeUnit.SECONDS);
        }
        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.queryForList("SELECT version_no FROM knowledge_version WHERE document_id=? ORDER BY version_no",
            Integer.class, doc)).containsExactly(1, 2);
        edit(VERSIONS + "/" + first, "{\"version\":0,\"content\":\"幂等后编辑\"}").andExpect(status().isOk());
        assertThat(id(createVersion(doc, one, VERSION_INPUT).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.version").value(0)), "knowledgeVersionId")).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM knowledge_version WHERE document_id=?", doc)).isEqualTo(2);
    }

    private long concurrentVersion(CountDownLatch start, long doc, String key) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("版本请求未同步启动");
        }
        return id(createVersion(doc, key, VERSION_INPUT).andExpect(status().isCreated()), "knowledgeVersionId");
    }

    private long createDocument(String key) throws Exception {
        return id(createDoc(key, admin(), documentInput()).andExpect(status().isCreated()), "documentId");
    }

    private ResultActions createDoc(String key, String bearer, String body) throws Exception {
        return mvc.perform(post(DOCS).header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions createVersion(long documentId, String key, String body) throws Exception {
        return mvc.perform(post(DOCS + "/{id}/versions", documentId).header(HttpHeaders.AUTHORIZATION, admin())
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions edit(String path, String body) throws Exception {
        return mvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions read(String path, String bearer) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private long id(ResultActions result, String key) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data").path(key).asLong();
    }

    private static String documentInput() {
        return "{\"title\":\"资料夹具\",\"speciesCode\":\"cat\",\"categoryCode\":\"CARE\",\"tags\":[\"健康\"]}";
    }

    private static String attachmentInput(long id) {
        return """
            {"content":"附件来源正文","sourceTitle":"引用附件","sourceFileId":"%d"}
            """.formatted(id);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void seedFile(long id, long uploader, String purpose) {
        jdbc.update("""
            INSERT INTO file_object(id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status)
            VALUES (?,?,?,'fixture.pdf','application/pdf',10,?,?,'READY')
            """, id, uploader, "m1101/" + id, "%064d".formatted(id), purpose);
    }

    private void seedActor(long user, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,'测试账号','ACTIVE')", user, "m1101-" + user);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", user, user, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, user, user, audience, "%064d".formatted(user), "m1101-" + user,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private String admin() {
        return bearer(ADMIN, "WEB", "ADMIN");
    }

    private String bearer(long user, String audience, String role) {
        return "Bearer " + tokens.issue(user, user, 0, audience, List.of(role), Instant.now(), 1800).value();
    }
}
