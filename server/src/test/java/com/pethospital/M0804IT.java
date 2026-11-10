package com.pethospital;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "identity.jwt.allow-ephemeral=true"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M0804IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final LocalDateTime CUTOFF = LocalDateTime.ofInstant(NOW.minus(30, ChronoUnit.DAYS), ZoneOffset.UTC);
    private static final long OWNER = 180401;
    private static final long STRANGER = 180402;
    private static final long AUTHOR = 180403;
    private static final long REVIEWER = 180404;
    private static final long ADMIN = 180405;
    private static final long OLD_PET = 180411;
    private static final long EDGE_PET = 180412;
    private static final long OLD_RECORD = 180421;
    private static final long OTHER_RECORD = 180422;
    private static final long EDGE_RECORD = 180423;
    private static final long DRAFT = 180424;
    private static final long INCONSISTENT = 180425;
    private static final String RECORD = "/api/v1/medical-records/{id}";
    private static final String VERSIONS = RECORD + "/versions";
    private static final String OWNER_LIST = "/api/v1/pets/{id}/medical-records";
    private static final String STAFF_LIST = "/api/v1/staff/pets/{id}/medical-records";

    @TestConfiguration
    static class WindowClock {
        @Bean
        @Primary
        Clock fixedWindowClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired Rs256AccessTokenIssuer tokens;

    @BeforeAll
    void seed() {
        seedActor(OWNER, "MINI", "USER");
        seedActor(STRANGER, "MINI", "USER");
        seedActor(AUTHOR, "WEB", "DOCTOR");
        seedActor(REVIEWER, "WEB", "DOCTOR");
        seedActor(ADMIN, "WEB", "ADMIN");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status)
            VALUES (?,?,'署名医生','ACTIVE'),(?,?,'其他医生','ACTIVE')
            """, AUTHOR, AUTHOR, REVIEWER, REVIEWER);
        jdbc.update("INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (?,?,'旧宠物','CAT'),(?,?,'边界宠物','CAT')",
            OLD_PET, OWNER, EDGE_PET, OWNER);
        LocalDateTime expired = CUTOFF.minus(1, ChronoUnit.MILLIS);
        seedAppointment(OLD_RECORD, OLD_PET, AUTHOR, expired, false);
        seedAppointment(OTHER_RECORD, OLD_PET, REVIEWER, expired, false);
        seedAppointment(EDGE_RECORD, EDGE_PET, AUTHOR, expired.minusDays(1), false);
        seedAppointment(DRAFT, EDGE_PET, AUTHOR, CUTOFF.plusDays(29), true);
        seedAppointment(INCONSISTENT, EDGE_PET, AUTHOR, expired.minusDays(2), false);
        seedAppointment(180426, EDGE_PET, REVIEWER, CUTOFF, false);
        seedRecord(OLD_RECORD, OLD_PET, AUTHOR, expired, "首次诊断");
        seedRecord(OTHER_RECORD, OLD_PET, REVIEWER, expired, "另一份正式病历");
        seedRecord(EDGE_RECORD, EDGE_PET, AUTHOR, expired.minusDays(1), "边界宠物既往诊断");
        seedRecord(DRAFT, EDGE_PET, AUTHOR, null, "草稿诊断");
        seedRecord(INCONSISTENT, OLD_PET, AUTHOR, expired.minusDays(2), "关系不一致的诊断");
        String revised = content("修订诊断", "180431");
        jdbc.update("""
            INSERT INTO medical_record_version(id,record_id,revision_no,before_content_json,after_content_json,
                reason,actor_user_id,idempotency_key,created_at)
            SELECT ?,id,2,content_json,CAST(? AS JSON),'补记复诊',?,'m0804-second-version',?
            FROM medical_record WHERE id=?
            """, OLD_RECORD + 400, revised, AUTHOR, expired.plusSeconds(1), OLD_RECORD);
        jdbc.update("UPDATE medical_record SET content_json=CAST(? AS JSON),version=2,revision_no=2 WHERE id=?", revised, OLD_RECORD);
        jdbc.update("""
            INSERT INTO file_object(id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status)
            VALUES (180431,?,'m0804/fixture.png','fixture.png','image/png',10,?,'MEDICAL','READY')
            """, AUTHOR, "%064d".formatted(180431));
        jdbc.update("""
            INSERT INTO file_binding(id,file_id,business_type,business_id,owner_user_id,created_by)
            VALUES (180431,180431,'MEDICAL_VERSION',?,?,?)
            """, OLD_RECORD + 400, OWNER, AUTHOR);
    }

    @AfterAll
    static void cleanup() {
        MYSQL.close();
    }

    @Test
    void ownerListsAndReadsOnlyOwnFormalRecordsAndCanSeeTheVersionDiff() throws Exception {
        String owner = bearer(OWNER, "MINI", "USER");
        mockMvc.perform(get(OWNER_LIST, OLD_PET).param("pageSize", "1").header(HttpHeaders.AUTHORIZATION, owner))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(OTHER_RECORD)));
        mockMvc.perform(get(OWNER_LIST, OLD_PET).param("pageSize", "1").param("page", "2")
                .header(HttpHeaders.AUTHORIZATION, owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(Long.toString(OLD_RECORD)));
        read(OWNER_LIST, EDGE_PET, owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        read(RECORD, OLD_RECORD, owner).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.medicalRecord.diagnosis").value("修订诊断"))
            .andExpect(jsonPath("$.data.content.medicalRecord.internalNote").doesNotExist())
            .andExpect(jsonPath("$.data.content.attachmentIds", contains("180431")));
        read(VERSIONS, OLD_RECORD, owner).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].before.medicalRecord.diagnosis").value("首次诊断"))
            .andExpect(jsonPath("$.data.items[0].after.medicalRecord.diagnosis").value("修订诊断"))
            .andExpect(jsonPath("$.data.items[0].changedFields", contains("diagnosis", "attachmentIds")))
            .andExpect(jsonPath("$.data.items[0].reason").value("补记复诊"))
            .andExpect(jsonPath("$.data.items[1].before").isEmpty());
        read(RECORD, DRAFT, owner).andExpect(status().isNotFound());
        read(VERSIONS, DRAFT, owner).andExpect(status().isNotFound());
        read(RECORD, INCONSISTENT, owner).andExpect(status().isNotFound());
        String stranger = bearer(STRANGER, "MINI", "USER");
        read(OWNER_LIST, OLD_PET, stranger).andExpect(status().isNotFound());
        read(RECORD, OLD_RECORD, stranger).andExpect(status().isNotFound());
        read(VERSIONS, OLD_RECORD, stranger).andExpect(status().isNotFound());
    }

    @Test
    void exactCutoffGrantsPetHistoryWhileExpiredAuthorCanOnlyUseTheirOwnRecordIds() throws Exception {
        String reviewer = bearer(REVIEWER, "WEB", "DOCTOR");
        read(STAFF_LIST, EDGE_PET, reviewer).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(EDGE_RECORD)));
        read(RECORD, EDGE_RECORD, reviewer).andExpect(status().isOk());
        read(VERSIONS, EDGE_RECORD, reviewer).andExpect(status().isOk());
        read(RECORD, DRAFT, reviewer).andExpect(status().isNotFound());
        read(STAFF_LIST, OLD_PET, reviewer).andExpect(status().isNotFound());
        read(RECORD, OLD_RECORD, reviewer).andExpect(status().isNotFound());
        String author = bearer(AUTHOR, "WEB", "DOCTOR");
        read(STAFF_LIST, OLD_PET, author).andExpect(status().isNotFound());
        read(RECORD, OLD_RECORD, author).andExpect(status().isOk());
        read(VERSIONS, OLD_RECORD, author).andExpect(status().isOk());
        read(RECORD, OTHER_RECORD, author).andExpect(status().isNotFound());
        read(RECORD, DRAFT, author).andExpect(status().isOk()).andExpect(jsonPath("$.data.record.status").value("DRAFT"));
        read(VERSIONS, DRAFT, author).andExpect(status().isNotFound());
    }

    @Test
    void adminNeedsAuditPermissionAndCannotUseDoctorScopeOrReadDraft() throws Exception {
        String admin = bearer(ADMIN, "WEB", "ADMIN");
        read(RECORD, OLD_RECORD, admin).andExpect(status().isNotFound());
        read(VERSIONS, OLD_RECORD, admin).andExpect(status().isNotFound());
        jdbc.update("""
            INSERT INTO sys_menu(id,name,kind,permission_code,sort_no,status,version)
            VALUES (180451,'病历审计','BUTTON','audit:read',0,'ACTIVE',0)
            """);
        jdbc.update("INSERT INTO sys_role_menu(id,role_id,menu_id) SELECT 180451,id,180451 FROM sys_role WHERE code='ADMIN'");
        read(RECORD, OLD_RECORD, admin).andExpect(status().isOk());
        read(VERSIONS, OLD_RECORD, admin).andExpect(status().isOk());
        read(RECORD, DRAFT, admin).andExpect(status().isNotFound());
        read(STAFF_LIST, EDGE_PET, admin).andExpect(status().isForbidden());
    }

    private void seedAppointment(long id, long pet, long doctor, LocalDateTime when, boolean draft) {
        LocalDateTime start = when.minusMinutes(30);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,?,?,?,1,1,'CLOSED')
            """, id + 100, doctor, start.toLocalDate(), start, when);
        jdbc.update("""
            INSERT INTO appointment(id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,
                scheduled_end_at,initial_start_at,status,chief_complaint,confirm_deadline_at,completed_at,idempotency_key,request_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?,'测试主诉',?,?,?,?)
            """, id, "M0804-" + id, OWNER, pet, doctor, id + 100, start, when, start, draft ? "IN_CONSULTATION" : "COMPLETED",
            start.minusHours(1), draft ? null : when, "m0804-appointment-" + id, "%064d".formatted(id));
    }

    private void seedRecord(long id, long pet, long doctor, LocalDateTime when, String diagnosis) {
        boolean draft = when == null;
        String content = content(diagnosis, "");
        jdbc.update("""
            INSERT INTO medical_record(id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at,version)
            VALUES (?,?,?,?,?,CAST(? AS JSON),?,?,1)
            """, id, id, pet, doctor, draft ? "DRAFT" : "SUBMITTED", content, draft ? 0 : 1, when);
        if (!draft) {
            jdbc.update("""
                INSERT INTO medical_record_version(id,record_id,revision_no,before_content_json,after_content_json,
                    reason,actor_user_id,idempotency_key,created_at)
                VALUES (?,?,1,NULL,CAST(? AS JSON),'首次提交',?,?,?)
                """, id + 200, id, content, doctor, "m0804-first-" + id, when);
        }
    }

    private static String content(String diagnosis, String attachments) {
        return """
            {"chiefComplaint":"测试主诉","examination":"检查","diagnosis":"%s","treatment":"治疗",
            "attachmentIds":[%s],"internalNote":"仅数据库的旧字段"}
            """.formatted(diagnosis, attachments);
    }

    private void seedActor(long id, String audience, String role) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,'测试账号','ACTIVE')", id, "m0804-" + id);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?", id, id, role);
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0804-" + id,
            Timestamp.from(NOW.plusSeconds(3600)), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private String bearer(long user, String audience, String role) {
        return "Bearer " + tokens.issue(user, user, 0, audience, List.of(role), NOW, 1800).value();
    }

    private ResultActions read(String path, long id, String token) throws Exception {
        return mockMvc.perform(get(path, id).header(HttpHeaders.AUTHORIZATION, token));
    }
}
