package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.nio.charset.StandardCharsets;
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
class M0801IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String PATH = "/api/v1/staff/appointments/{appointmentId}/medical-draft";
    private static final long OWNER = 180101;
    private static final long DOCTOR = 180102;
    private static final long OTHER_DOCTOR = 180103;
    private static final long ADMIN = 180104;
    private static final long NORMAL = 180111;
    private static final long RACE = 180112;
    private static final long ATTACHMENTS = 180113;
    private static final long CONFIRMED = 180114;
    private static final long MISMATCH = 180115;
    private static final long DOCTOR_FILE = 180121;
    private static final long SECOND_FILE = 180122;
    private static final long FOREIGN_FILE = 180123;

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
    void seedFixtures() {
        seedActor(OWNER, "MINI", "USER");
        seedActor(DOCTOR, "WEB", "DOCTOR");
        seedActor(OTHER_DOCTOR, "WEB", "DOCTOR");
        seedActor(ADMIN, "WEB", "ADMIN");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status)
            VALUES (?,?,'医生夹具','ACTIVE'),(?,?,'其他医生','ACTIVE')
            """, DOCTOR, DOCTOR, OTHER_DOCTOR, OTHER_DOCTOR);
        seedAppointment(NORMAL, "IN_CONSULTATION");
        seedAppointment(RACE, "IN_CONSULTATION");
        seedAppointment(ATTACHMENTS, "IN_CONSULTATION");
        seedAppointment(CONFIRMED, "CONFIRMED");
        seedAppointment(MISMATCH, "IN_CONSULTATION");
        jdbc.update("""
            INSERT INTO medical_record(id,appointment_id,pet_id,doctor_id,content_json)
            VALUES (180116,?,?,?,JSON_OBJECT('diagnosis','不匹配的历史草稿'))
            """, MISMATCH, pet(MISMATCH), OTHER_DOCTOR);
        seedFile(DOCTOR_FILE, DOCTOR);
        seedFile(SECOND_FILE, DOCTOR);
        seedFile(FOREIGN_FILE, OTHER_DOCTOR);
    }

    @Test
    void createsAndReadsDraftWithoutSubmittingOrLeakingToOwner() throws Exception {
        read(NORMAL, doctor()).andExpect(status().isOk()).andExpect(jsonPath("$.data.draft").value(nullValue()));
        save(NORMAL, doctor(), payload(0, "仅医生可见的草稿", "[]")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.draft.version").value(1))
            .andExpect(jsonPath("$.data.draft.revisionNo").value(0));
        read(NORMAL, doctor()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.draft.medicalRecord.diagnosis").value("仅医生可见的草稿"))
            .andExpect(jsonPath("$.data.draft.medicalRecord.medications[0].dosage").value("医生填写剂量"));
        String owner = bearer(OWNER, "MINI", "USER");
        read(NORMAL, owner).andExpect(status().isForbidden());
        String timeline = mockMvc.perform(get("/api/v1/pets/{id}/timeline", pet(NORMAL))
                .header(HttpHeaders.AUTHORIZATION, owner)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(timeline).doesNotContain("仅医生可见的草稿");
        assertThat(count("""
            SELECT COUNT(*) FROM medical_record WHERE appointment_id=? AND status='DRAFT'
              AND revision_no=0 AND submitted_at IS NULL
            """, NORMAL)).isOne();
        assertThat(count("SELECT COUNT(*) FROM medical_record_version")).isZero();
    }

    @Test
    void onlyOneConcurrentInitialVersionZeroSaveSucceeds() throws Exception {
        String doctor = doctor();
        CountDownLatch go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> race(go, doctor, "草稿一"));
            var second = workers.submit(() -> race(go, doctor, "草稿二"));
            go.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(count("SELECT COUNT(*) FROM medical_record WHERE appointment_id=? AND version=1", RACE)).isOne();
        save(RACE, doctor, payload(0, "旧版本", "[]")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void unauthorizedAttachmentRollsBackContentVersionAndBindings() throws Exception {
        save(ATTACHMENTS, doctor(), payload(0, "原草稿", attachments(DOCTOR_FILE)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.draft.attachmentIds[0]").value(Long.toString(DOCTOR_FILE)));
        save(ATTACHMENTS, doctor(), payload(1, "越权写入", attachments(FOREIGN_FILE)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FILE_SOURCE_FORBIDDEN"));
        read(ATTACHMENTS, doctor()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.draft.version").value(1))
            .andExpect(jsonPath("$.data.draft.medicalRecord.diagnosis").value("原草稿"))
            .andExpect(jsonPath("$.data.draft.attachmentIds[0]").value(Long.toString(DOCTOR_FILE)));
        save(ATTACHMENTS, doctor(), payload(1, "新草稿", attachments(SECOND_FILE))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.draft.version").value(2))
            .andExpect(jsonPath("$.data.draft.attachmentIds[0]").value(Long.toString(SECOND_FILE)));
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE business_type='MEDICAL_DRAFT' AND file_id=?", DOCTOR_FILE))
            .isZero();
        assertThat(count("SELECT COUNT(*) FROM file_object WHERE id=? AND status='READY'", DOCTOR_FILE)).isOne();
    }

    @Test
    void otherDoctorAdminAndNonConsultationCannotReadOrSave() throws Exception {
        String otherDoctor = bearer(OTHER_DOCTOR, "WEB", "DOCTOR");
        String admin = bearer(ADMIN, "WEB", "ADMIN");
        read(NORMAL, otherDoctor).andExpect(status().isNotFound());
        save(NORMAL, otherDoctor, payload(0, "越权", "[]")).andExpect(status().isNotFound());
        read(NORMAL, admin).andExpect(status().isForbidden());
        save(NORMAL, admin, payload(0, "越权", "[]")).andExpect(status().isForbidden());
        read(CONFIRMED, doctor()).andExpect(status().isConflict());
        save(CONFIRMED, doctor(), payload(0, "未开始", "[]")).andExpect(status().isConflict());
        read(MISMATCH, doctor()).andExpect(status().isNotFound());
        save(MISMATCH, doctor(), payload(0, "关系不匹配", "[]")).andExpect(status().isNotFound());
        save(NORMAL, doctor(), payload(0, "额外字段", "[]").replace("\"version\":0", "\"version\":0,\"status\":\"SUBMITTED\""))
            .andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM medical_record WHERE appointment_id=?", CONFIRMED)).isZero();
    }

    private int race(CountDownLatch go, String doctor, String diagnosis) throws Exception {
        if (!go.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发请求未同步启动");
        }
        return save(RACE, doctor, payload(0, diagnosis, "[]")).andReturn().getResponse().getStatus();
    }

    private ResultActions read(long appointment, String token) throws Exception {
        return mockMvc.perform(get(PATH, appointment).header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions save(long appointment, String token, String body) throws Exception {
        return mockMvc.perform(put(PATH, appointment).header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String payload(int version, String diagnosis, String files) {
        return """
            {"version":%d,"medicalRecord":{"chiefComplaint":"主诉夹具","examination":"检查夹具","diagnosis":"%s",
              "treatment":"治疗夹具","medications":[{"name":"药品夹具","dosage":"医生填写剂量",
              "frequency":"医生填写频率","duration":"医生填写疗程"}]},"attachmentIds":%s}
            """.formatted(version, diagnosis, files);
    }

    private static String attachments(long file) { return "[\"" + file + "\"]"; }

    private void seedAppointment(long id, String state) {
        long pet = pet(id);
        long slot = id + 200;
        LocalDateTime start = LocalDateTime.ofInstant(Instant.now().minusSeconds(3600L * (id - NORMAL + 1)), ZoneOffset.UTC);
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'夹具','CAT')", pet, OWNER);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,1,1,'OPEN')
            """, slot, DOCTOR, start.toLocalDate(), start, start.plusMinutes(30));
        jdbc.update("""
            INSERT INTO appointment(id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,
                scheduled_end_at,initial_start_at,status,chief_complaint,confirm_deadline_at,idempotency_key,request_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?,'夹具',?,?,?)
            """, id, "M0801-" + id, OWNER, pet, DOCTOR, slot, start, start.plusMinutes(30), start, state,
            start.minusMinutes(1), "m0801-appointment-" + id, "%064d".formatted(id));
    }

    private void seedFile(long file, long uploader) {
        jdbc.update("""
            INSERT INTO file_object(id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status)
            VALUES (?,?,?,'fixture.png','image/png',10,?,'MEDICAL','READY')
            """, file, uploader, "m0801/" + file, "%064d".formatted(file));
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,'测试账号','ACTIVE')", id, "m0801-" + id);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0801-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private String doctor() { return bearer(DOCTOR, "WEB", "DOCTOR"); }
    private String bearer(long id, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(id, id, 0, audience, List.of(role), Instant.now(), 1800).value();
    }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static long pet(long appointment) { return appointment + 100; }
}
