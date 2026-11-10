package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.FileBindingService;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultActions;

@Tag(TestTags.MYSQL_IT)
@Tag("real-s3")
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false", "identity.jwt.allow-ephemeral=true", "storage.s3.enabled=true"
})
@AutoConfigureMockMvc
class M0805IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 180501;
    private static final long AUTHOR = 180502;
    private static final long OTHER_DOCTOR = 180503;
    private static final long ADMIN = 180504;
    private static final long STRANGER = 180505;
    private static final long PET = 180511;
    private static final long SLOT = 180521;
    private static final long APPOINTMENT = 180531;
    private static final long FILE = 180541;
    private static final String READ = "/api/v1/medical-records/{id}";
    private static final String REVISE = "/api/v1/staff/medical-records/{id}/revisions";
    private static final String TICKET = "/api/v1/files/{id}/download-ticket";
    private static final byte[] IMAGE = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+rjWQAAAAASUVORK5CYII=");
    private final String objectKey = "m0805/" + UUID.randomUUID() + ".png";
    private boolean storedObject;

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Rs256AccessTokenIssuer tokens;
    @Autowired FileBindingService bindings;
    @Autowired ObjectStorageAdapter storage;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @BeforeEach
    void seed() {
        seedActor(OWNER, "MINI", "USER");
        seedActor(AUTHOR, "WEB", "DOCTOR");
        seedActor(OTHER_DOCTOR, "WEB", "DOCTOR");
        seedActor(ADMIN, "WEB", "ADMIN");
        seedActor(STRANGER, "MINI", "USER");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status)
            VALUES (?,?,'署名医生','ACTIVE'),(?,?,'其他医生','ACTIVE')
            """, AUTHOR, AUTHOR, OTHER_DOCTOR, OTHER_DOCTOR);
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'历史夹具','CAT')", PET, OWNER);
        LocalDateTime start = LocalDateTime.ofInstant(Instant.now().minusSeconds(3600), ZoneOffset.UTC);
        LocalDateTime end = start.plusMinutes(30);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,1,1,'CLOSED')
            """, SLOT, AUTHOR, start.toLocalDate(), start, end);
        jdbc.update("""
            INSERT INTO appointment(id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,confirm_deadline_at,idempotency_key,request_hash)
            VALUES (?,'M0805-HISTORY',?,?,?,?,?,?,?,'IN_CONSULTATION','病历历史夹具',?,?,?)
            """, APPOINTMENT, OWNER, PET, AUTHOR, SLOT, start, end, start, start.minusHours(1),
            "m0805-appointment-key", "%064d".formatted(APPOINTMENT));
        jdbc.update("""
            INSERT INTO file_object(id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status)
            VALUES (?,?,?,'history.png','image/png',?,?,'MEDICAL','READY')
            """, FILE, AUTHOR, objectKey, IMAGE.length, "%064d".formatted(FILE));
        storage.put(objectKey, IMAGE, "image/png");
        storedObject = true;
    }

    @AfterEach
    void cleanupObject() {
        if (storedObject) {
            storage.delete(objectKey);
        }
    }

    @AfterAll
    static void cleanupDatabase() {
        MYSQL.close();
    }

    @Test
    void archivedOwnerKeepsImmutableHistoryAndDisabledAuthorCannotModifyOrReplay() throws Exception {
        String owner = bearer(OWNER, "MINI", "USER");
        String author = bearer(AUTHOR, "WEB", "DOCTOR");
        String admin = bearer(ADMIN, "WEB", "ADMIN");
        String other = bearer(OTHER_DOCTOR, "WEB", "DOCTOR");
        String stranger = bearer(STRANGER, "MINI", "USER");

        write("/api/v1/staff/appointments/{id}/complete", APPOINTMENT, author, "m0805-complete-first", """
            {"version":0,"recordVersion":0,"medicalRecord":%s,"attachmentIds":["%d"]}
            """.formatted(clinical("初版诊断"), FILE)).andExpect(status().isOk());
        long recordId = jdbc.queryForObject("SELECT id FROM medical_record WHERE appointment_id=?", Long.class, APPOINTMENT);
        long originalId = jdbc.queryForObject(
            "SELECT id FROM medical_record_version WHERE record_id=? AND revision_no=1", Long.class, recordId);
        String originalSnapshot = snapshot(originalId);

        String revision = revision(1, "修订诊断");
        String key = "m0805-revision-first";
        write(REVISE, recordId, admin, "m0805-admin-revision", revision).andExpect(status().isForbidden());
        write(REVISE, recordId, owner, "m0805-owner-revision", revision).andExpect(status().isForbidden());
        write(REVISE, recordId, other, "m0805-other-revision", revision).andExpect(status().isNotFound());
        String revisionResult = write(REVISE, recordId, author, key, revision).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revisionNo").value(2)).andReturn().getResponse().getContentAsString();
        String revisionId = json.readTree(revisionResult).path("data").path("revisionId").asText();
        write(REVISE, recordId, author, key, revision).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revisionId").value(revisionId));
        write(REVISE, recordId, author, key, revision(1, "不同请求")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThatThrownBy(() -> bindings.replaceMutable(FileBusinessType.MEDICAL_VERSION, originalId, List.of()))
            .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.code).isEqualTo("IMMUTABLE_FILE_BINDING"));
        write("/api/v1/files/{id}/discard", FILE, author, "m0805-discard-bound", "{\"reason\":\"历史附件保护\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_DISCARD_FORBIDDEN"));

        write("/api/v1/pets/{id}/archive", PET, owner, "m0805-pet-archive", "{\"version\":0,\"reason\":\"历史归档\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ARCHIVED"));
        write(REVISE, recordId, author, key, revision).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revisionId").value(revisionId));
        write("/api/v1/admin/doctors/{id}/disable", AUTHOR, admin, "m0805-doctor-disable", "{\"version\":0,\"reason\":\"停用验证\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"));
        write(REVISE, recordId, author, "m0805-disabled-revision", revision(2, "停用后修订")).andExpect(status().isForbidden());
        write(REVISE, recordId, author, key, revision).andExpect(status().isForbidden());
        read(READ, recordId, author).andExpect(status().isNotFound());
        read(READ, recordId, stranger).andExpect(status().isNotFound());

        read("/api/v1/pets/{id}/medical-records", PET, owner).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1));
        read(READ + "/versions", recordId, owner).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].after.medicalRecord.diagnosis").value("修订诊断"))
            .andExpect(jsonPath("$.data.items[1].after.medicalRecord.diagnosis").value("初版诊断"));
        assertThat(snapshot(originalId)).isEqualTo(originalSnapshot);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM file_binding b JOIN file_object f ON f.id=b.file_id
            WHERE b.business_type='MEDICAL_VERSION' AND b.business_id=? AND b.file_id=?
              AND f.status='READY' AND f.deleted_at IS NULL
            """, Integer.class, originalId, FILE)).isOne();

        write(TICKET, FILE, owner, "m0805-wrong-version", ticketBody(revisionId)).andExpect(status().isForbidden());
        write(TICKET, FILE, stranger, "m0805-stranger-ticket", ticketBody(Long.toString(originalId)))
            .andExpect(status().isForbidden());
        String response = write(TICKET, FILE, owner, "m0805-history-ticket", ticketBody(Long.toString(originalId)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        URI url = URI.create(json.readTree(response).path("data").path("url").asText());
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<byte[]> downloaded = client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.body()).isEqualTo(IMAGE);
        }
    }

    private String snapshot(long revisionId) {
        return jdbc.queryForObject("SELECT CAST(after_content_json AS CHAR) FROM medical_record_version WHERE id=?",
            String.class, revisionId);
    }

    private static String ticketBody(String revisionId) {
        return """
            {"businessType":"MEDICAL_VERSION","businessId":"%s"}
            """.formatted(revisionId);
    }

    private static String revision(int version, String diagnosis) {
        return """
            {"version":%d,"reason":"修订追溯","medicalRecord":%s,"attachmentIds":[]}
            """.formatted(version, clinical(diagnosis));
    }

    private static String clinical(String diagnosis) {
        return """
            {"chiefComplaint":"测试主诉","examination":"检查","diagnosis":"%s","treatment":"治疗"}
            """.formatted(diagnosis);
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,'测试账号','ACTIVE')", id, "m0805-" + id);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0805-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private String bearer(long user, String audience, String role) {
        return "Bearer " + tokens.issue(user, user, 0, audience, List.of(role), Instant.now(), 1800).value();
    }

    private ResultActions write(String path, long id, String token, String key, String body) throws Exception {
        return mvc.perform(post(path, id).header(HttpHeaders.AUTHORIZATION, token).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions read(String path, long id, String token) throws Exception {
        return mvc.perform(get(path, id).header(HttpHeaders.AUTHORIZATION, token));
    }
}
