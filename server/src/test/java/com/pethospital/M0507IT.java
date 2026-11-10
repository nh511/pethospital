package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class M0507IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final String PATH = "/api/v1/pets/{petId}/timeline";
    private static final long OWNER = 150_701;
    private static final long OTHER = 150_702;
    private static final long DOCTOR_USER = 150_703;
    private static final long DOCTOR = 150_704;
    private static final long SLOT = 150_705;
    private static final long PET = 150_706;
    private static final long EMPTY_PET = 150_707;
    private static final long TIED_ID = 9_007_199_254_740_993L;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Rs256AccessTokenIssuer tokenIssuer;
    @Autowired ObjectMapper json;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeAll
    void seedFixtures() {
        seedActor(OWNER, "USER", "MINI");
        seedActor(OTHER, "USER", "MINI");
        seedActor(DOCTOR_USER, "DOCTOR", "WEB");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status) VALUES (?,?,'测试医生','ACTIVE')
            """, DOCTOR, DOCTOR_USER);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status)
            VALUES (?,?,'2026-10-01','2026-10-01 08:00:00','2026-10-01 11:00:00',3,3,'CLOSED')
            """, SLOT, DOCTOR);
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,sex,neutered,status)
            VALUES (?,?,'时间线宠物','CAT','UNKNOWN','UNKNOWN','ARCHIVED'),
                   (?,?,'空时间线宠物','CAT','UNKNOWN','UNKNOWN','ACTIVE')
            """, PET, OWNER, EMPTY_PET, OWNER);
        jdbc.update("""
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at,weight_kg,symptom_note)
            VALUES (?,?,?,'2026-10-01 09:00:00.123',4.25,'偶尔打喷嚏'),
                   (?,?,?,'2026-10-01 09:00:00.123',4.10,NULL),
                   (?,?,?,'2026-10-01 10:00:00.123',5.00,'其他用户日志')
            """, TIED_ID, PET, OWNER, TIED_ID - 1, PET, OWNER, TIED_ID + 1, PET, OTHER);
        jdbc.update("""
            INSERT INTO ai_session(id,user_id,pet_id,title,pet_snapshot_json,summary_text,created_at,last_message_at)
            VALUES (?,?,?,'打喷嚏咨询',JSON_OBJECT(),'会话内部摘要','2026-10-01 09:00:00.123','2026-10-01 11:00:00'),
                   (?,?,?,'早期咨询',JSON_OBJECT(),NULL,'2026-10-01 09:00:00.122',NULL),
                   (?,?,?,'其他用户会话',JSON_OBJECT(),NULL,'2026-10-01 10:00:00.123',NULL)
            """, TIED_ID, OWNER, PET, TIED_ID + 1, OWNER, PET, TIED_ID + 2, OTHER, PET);
        seedAppointment(TIED_ID, OWNER, "COMPLETED", "2026-10-01 09:00:00.123");
        seedAppointment(TIED_ID + 1, OWNER, "IN_CONSULTATION", "2026-10-01 08:00:00");
        seedAppointment(TIED_ID + 2, OTHER, "COMPLETED", "2026-10-01 10:00:00");
        jdbc.update("""
            INSERT INTO medical_record(id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at)
            VALUES (?,?,?,?,'SUBMITTED',JSON_OBJECT('diagnosis','已提交的诊断','examination','完整检查正文'),1,
                    '2026-10-01 09:00:00.123'),
                   (?,?,?,?,'DRAFT',JSON_OBJECT('diagnosis','草稿诊断秘密'),0,NULL),
                   (?,?,?,?,'SUBMITTED',JSON_OBJECT('diagnosis','其他用户病历'),1,'2026-10-01 10:00:00')
            """, TIED_ID, TIED_ID, PET, DOCTOR, TIED_ID + 1, TIED_ID + 1, PET, DOCTOR,
            TIED_ID + 2, TIED_ID + 2, PET, DOCTOR);
    }

    @Test
    void cursorCrossesTimeAndIdenticalCrossTableIdsWithoutDuplicatesOrGaps() throws Exception {
        List<String> events = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 7; page++) {
            JsonNode data = data(page(PET, "MINI", OWNER, null, cursor, 1));
            assertThat(data.path("items").size()).isOne();
            JsonNode event = data.path("items").get(0);
            assertThat(event.path("id").isTextual()).isTrue();
            events.add(event.path("type").asText() + ":" + event.path("id").asText());
            if (page < 6) {
                assertThat(data.path("nextCursor").isTextual()).isTrue();
                cursor = data.path("nextCursor").asText();
            } else {
                assertThat(data.path("nextCursor").isNull()).isTrue();
            }
        }
        assertThat(events).containsExactly(
            "MEDICAL:" + TIED_ID, "LOG:" + TIED_ID, "APPOINTMENT:" + TIED_ID, "AI:" + TIED_ID,
            "LOG:" + (TIED_ID - 1), "AI:" + (TIED_ID + 1), "APPOINTMENT:" + (TIED_ID + 1)
        );
    }

    @Test
    void returnsOnlyTheFourSafeSummariesAndOmitsDraftAndForeignRows() throws Exception {
        String body = page(PET, "MINI", OWNER, null, null, 20)
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.data.items.length()").value(7))
            .andExpect(jsonPath("$.data.items[0].occurredAt").value("2026-10-01T09:00:00.123Z"))
            .andExpect(jsonPath("$.data.items[0].summary").value("已提交的诊断"))
            .andExpect(jsonPath("$.data.items[1].summary").value("主人自述 · 4.25 kg · 偶尔打喷嚏"))
            .andExpect(jsonPath("$.data.items[2].summary").value("COMPLETED"))
            .andExpect(jsonPath("$.data.items[3].summary").value("打喷嚏咨询"))
            .andExpect(jsonPath("$.data.items[5].occurredAt").value("2026-10-01T09:00:00.122Z"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("草稿诊断秘密", "完整检查正文", "会话内部摘要", "内部主诉", "其他用户");
    }

    @Test
    void filtersAndRejectsInvalidOrOutOfScopeCursors() throws Exception {
        JsonNode first = data(page(PET, "MINI", OWNER, "LOG", null, 1));
        String cursor = first.path("nextCursor").asText();
        assertThat(first.path("items").get(0).path("id").asText()).isEqualTo(Long.toString(TIED_ID));
        JsonNode last = data(page(PET, "MINI", OWNER, "LOG", cursor, 1));
        assertThat(last.path("items").get(0).path("id").asText()).isEqualTo(Long.toString(TIED_ID - 1));
        assertThat(last.path("nextCursor").isNull()).isTrue();
        page(PET, "MINI", OWNER, "AI", cursor, 1).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_TIMELINE_CURSOR"));
        page(EMPTY_PET, "MINI", OWNER, "LOG", cursor, 1).andExpect(status().isBadRequest());
        page(PET, "MINI", OWNER, "LOG", "broken", 1).andExpect(status().isBadRequest());
        page(PET, "MINI", OWNER, "DRAFT", null, 1).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        page(PET, "MINI", OWNER, null, null, 101).andExpect(status().isBadRequest());
        assertThat(data(page(EMPTY_PET, "MINI", OWNER, null, null, 20)).path("items").size()).isZero();
    }

    @Test
    void deniesOtherOwnerAndWebDoctorEvenWithAnOwnedCursor() throws Exception {
        String cursor = data(page(PET, "MINI", OWNER, null, null, 1)).path("nextCursor").asText();
        page(PET, "MINI", OTHER, null, cursor, 1).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PET_NOT_FOUND"));
        page(PET, "WEB", DOCTOR_USER, null, cursor, 1).andExpect(status().isForbidden());
        mockMvc.perform(get(PATH, PET)).andExpect(status().isUnauthorized());
    }

    private ResultActions page(long petId, String audience, long actor, String type, String cursor, int size) throws Exception {
        String role = "WEB".equals(audience) ? "DOCTOR" : "USER";
        String token = tokenIssuer.issue(actor, actor, 0, audience, List.of(role), Instant.now(), 1800).value();
        var request = get(PATH, petId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .param("pageSize", Integer.toString(size));
        if (type != null) {
            request.param("type", type);
        }
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return mockMvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private void seedActor(long id, String role, String audience) {
        jdbc.update("INSERT INTO sys_user(id,username,display_name,status) VALUES (?,?,?,'ACTIVE')",
            id, "m0507-" + id, "测试账号");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) SELECT ?,?,id FROM sys_role WHERE code=?",
            id, id, role);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, id, audience, "%064d".formatted(id), "m0507-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
    }

    private void seedAppointment(long id, long owner, String status, String start) {
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,confirm_deadline_at,completed_at,idempotency_key,request_hash
            ) VALUES (?,?,?,?,?,?,?,DATE_ADD(?, INTERVAL 30 MINUTE),?,?,'内部主诉','2026-10-01 07:00:00',?,?,?)
            """, id, "M0507-" + id, owner, PET, DOCTOR, SLOT, start, start, start, status,
            "COMPLETED".equals(status) ? "2026-10-01 11:00:00" : null, "m0507-appointment-" + id, "%064d".formatted(id));
    }
}
