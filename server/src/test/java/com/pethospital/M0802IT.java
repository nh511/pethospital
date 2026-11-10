package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class M0802IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String PATH = "/api/v1/staff/appointments/{id}/complete";
    private static final long OWNER = 180201;
    private static final long DOCTOR = 180202;
    private static final long OTHER = 180203;
    private static final long NEW = 180211;
    private static final long DRAFT = 180212;
    private static final long INVALID = 180213;
    private static final long RACE = 180214;
    private static final long FILE = 180221;
    private static final long FOREIGN_FILE = 180222;
    private static final String RECORD = """
        {"chiefComplaint":"主诉夹具","examination":"检查夹具","diagnosis":"正式诊断夹具","treatment":"治疗夹具"}
        """;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Rs256AccessTokenIssuer tokenIssuer;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeAll
    void seed() {
        seedActor(OWNER, "MINI", "USER");
        seedActor(DOCTOR, "WEB", "DOCTOR");
        seedActor(OTHER, "WEB", "DOCTOR");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status)
            VALUES (?,?,'医生夹具','ACTIVE'),(?,?,'其他医生','ACTIVE')
            """, DOCTOR, DOCTOR, OTHER, OTHER);
        for (long id : new long[] {NEW, DRAFT, INVALID, RACE}) {
            seedAppointment(id);
        }
        seedFile(FILE, DOCTOR);
        seedFile(FOREIGN_FILE, OTHER);
    }

    @Test
    void completesWithoutDraftAndReplaysSingleInitialRevision() throws Exception {
        String key = "m0802-new-complete";
        String body = payload(0, 0, RECORD, FILE);
        for (int attempt = 0; attempt < 2; attempt++) {
            complete(NEW, key, doctor(), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointment.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.appointment.version").value(1))
                .andExpect(jsonPath("$.data.revisionNo").value(1));
        }
        complete(NEW, key, doctor(), payload(0, 0, RECORD, 0)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertCompleted(NEW, 1, FILE);
        mockMvc.perform(get("/api/v1/pets/{id}/timeline", pet(NEW))
                .header(HttpHeaders.AUTHORIZATION, bearer(OWNER, "MINI", "USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].type").value("MEDICAL"))
            .andExpect(jsonPath("$.data.items[0].summary").value("正式诊断夹具"));
    }

    @Test
    void noticeFailureRestoresExistingDraftAttachmentAndAppointment() throws Exception {
        mockMvc.perform(put("/api/v1/staff/appointments/{id}/medical-draft", DRAFT)
                .header(HttpHeaders.AUTHORIZATION, doctor()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":0,"medicalRecord":%s,"attachmentIds":["%d"]}
                    """.formatted(RECORD.replace("正式诊断夹具", "旧草稿夹具"), FILE)))
            .andExpect(status().isOk());
        long record = recordId(DRAFT);
        String key = "m0802-notice-rollback";
        String body = payload(0, 1, RECORD, FILE);
        jdbc.execute("""
            ALTER TABLE user_notice ADD CONSTRAINT ck_m0802_notice_fault
            CHECK (resource_id<>%d OR notice_type<>'MEDICAL_COMPLETED')
            """.formatted(record));
        try {
            complete(DRAFT, key, doctor(), body).andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("ALTER TABLE user_notice DROP CHECK ck_m0802_notice_fault");
        }
        assertInConsultation(DRAFT);
        assertThat(count("""
            SELECT COUNT(*) FROM medical_record WHERE id=? AND status='DRAFT' AND revision_no=0 AND version=1
              AND JSON_UNQUOTE(JSON_EXTRACT(content_json,'$.diagnosis'))='旧草稿夹具'
            """, record)).isOne();
        assertThat(count("SELECT COUNT(*) FROM medical_record_version WHERE record_id=?", record)).isZero();
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='MEDICAL_DRAFT' AND business_id=? AND file_id=?",
            record, FILE)).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE operation_key=?", key)).isZero();
        complete(DRAFT, key, doctor(), body).andExpect(status().isOk());
        assertThat(recordId(DRAFT)).isEqualTo(record);
        assertCompleted(DRAFT, 2, FILE);
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='MEDICAL_DRAFT' AND business_id=?", record))
            .isZero();
    }

    @Test
    void rejectsInvalidFieldsVersionsAndForeignAttachmentsWithoutFormalRecord() throws Exception {
        complete(INVALID, "m0802-missing-diagnosis", doctor(),
            payload(0, 0, RECORD.replace(",\"diagnosis\":\"正式诊断夹具\"", ""), 0)).andExpect(status().isBadRequest());
        complete(INVALID, "m0802-stale-appointment", doctor(), payload(1, 0, RECORD, 0)).andExpect(status().isConflict());
        complete(INVALID, "m0802-stale-record", doctor(), payload(0, 1, RECORD, 0)).andExpect(status().isConflict());
        complete(INVALID, "m0802-other-doctor", bearer(OTHER, "WEB", "DOCTOR"), payload(0, 0, RECORD, 0))
            .andExpect(status().isNotFound());
        complete(INVALID, "m0802-foreign-attachment", doctor(), payload(0, 0, RECORD, FOREIGN_FILE))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FILE_SOURCE_FORBIDDEN"));
        assertInConsultation(INVALID);
        assertThat(count("SELECT COUNT(*) FROM medical_record WHERE appointment_id=?", INVALID)).isZero();
    }

    @Test
    void competingCompletionsCreateOneVersionAndOneStatusTransition() throws Exception {
        String doctor = doctor();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> race(start, doctor, "m0802-race-first"));
            var second = workers.submit(() -> race(start, doctor, "m0802-race-second"));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        }
        assertCompleted(RACE, 1, 0);
    }

    private void assertCompleted(long appointment, int recordVersion, long file) {
        long record = recordId(appointment);
        assertThat(count("SELECT COUNT(*) FROM appointment WHERE id=? AND status='COMPLETED' AND completed_at IS NOT NULL AND version=1",
            appointment)).isOne();
        assertThat(count("SELECT occupied_count FROM doctor_slot WHERE id=?", slot(appointment))).isOne();
        assertThat(count("""
            SELECT COUNT(*) FROM medical_record WHERE id=? AND status='SUBMITTED' AND revision_no=1
              AND submitted_at IS NOT NULL AND version=?
            """, record, recordVersion)).isOne();
        assertThat(count("SELECT COUNT(*) FROM medical_record_version WHERE record_id=? AND revision_no=1 AND before_content_json IS NULL",
            record)).isOne();
        assertThat(count("SELECT COUNT(*) FROM appointment_status_log WHERE appointment_id=? AND to_status='COMPLETED'",
            appointment)).isOne();
        assertThat(count("SELECT COUNT(*) FROM user_notice WHERE resource_id=? AND recipient_user_id=? AND notice_type='MEDICAL_COMPLETED'",
            record, OWNER)).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE resource_id=? AND action='APPOINTMENT_COMPLETE'", appointment)).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='APPOINTMENT_COMPLETE' AND CAST(after_json AS CHAR) LIKE '%正式诊断夹具%'"))
            .isZero();
        if (file != 0) {
            assertThat(count("""
                SELECT COUNT(*) FROM file_binding f JOIN medical_record_version v ON v.id=f.business_id
                WHERE f.business_type='MEDICAL_VERSION' AND v.record_id=? AND f.file_id=? AND f.owner_user_id=?
                """, record, file, OWNER)).isOne();
        }
    }

    private void assertInConsultation(long id) {
        assertThat(count("SELECT COUNT(*) FROM appointment WHERE id=? AND status='IN_CONSULTATION' AND completed_at IS NULL AND version=0",
            id)).isOne();
        assertThat(count("SELECT COUNT(*) FROM appointment_status_log WHERE appointment_id=? AND to_status='COMPLETED'", id)).isZero();
    }

    private int race(CountDownLatch start, String doctor, String key) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发请求未同步启动");
        }
        return complete(RACE, key, doctor, payload(0, 0, RECORD, 0)).andReturn().getResponse().getStatus();
    }

    private ResultActions complete(long id, String key, String token, String body) throws Exception {
        return mockMvc.perform(post(PATH, id).header(HttpHeaders.AUTHORIZATION, token)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String payload(int version, int recordVersion, String record, long attachment) {
        String ids = attachment == 0 ? "[]" : "[\"" + attachment + "\"]";
        return """
            {"version":%d,"recordVersion":%d,"medicalRecord":%s,"attachmentIds":%s}
            """.formatted(version, recordVersion, record, ids);
    }

    private void seedAppointment(long id) {
        LocalDateTime start = LocalDateTime.ofInstant(Instant.now().minusSeconds(3600L * (id - NEW + 1)), ZoneOffset.UTC);
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'夹具','CAT')", pet(id), OWNER);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,1,1,'OPEN')
            """, slot(id), DOCTOR, start.toLocalDate(), start, start.plusMinutes(30));
        jdbc.update("""
            INSERT INTO appointment(id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,
                scheduled_end_at,initial_start_at,status,chief_complaint,confirm_deadline_at,idempotency_key,request_hash)
            VALUES (?,?,?,?,?,?,?,?,?,'IN_CONSULTATION','夹具',?,?,?)
            """, id, "M0802-" + id, OWNER, pet(id), DOCTOR, slot(id), start, start.plusMinutes(30), start,
            start.minusMinutes(1), "m0802-appointment-" + id, "%064d".formatted(id));
    }

    private void seedFile(long file, long uploader) {
        jdbc.update("""
            INSERT INTO file_object(id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status)
            VALUES (?,?,?,'fixture.png','image/png',10,?,'MEDICAL','READY')
            """, file, uploader, "m0802/" + file, "%064d".formatted(file));
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,'测试账号','ACTIVE')", id, "m0802-" + id);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0802-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private long recordId(long id) { return jdbc.queryForObject("SELECT id FROM medical_record WHERE appointment_id=?", Long.class, id); }
    private String doctor() { return bearer(DOCTOR, "WEB", "DOCTOR"); }
    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static long pet(long id) { return id + 100; }
    private static long slot(long id) { return id + 200; }
}
