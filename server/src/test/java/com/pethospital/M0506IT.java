package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true"
})
@AutoConfigureMockMvc
class M0506IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 200_501;
    private static final long CORRECT_ADMIN = 200_502;
    private static final long AUDIT_ADMIN = 200_503;
    private static final long PLAIN_ADMIN = 200_504;
    private static final long DOCTOR = 200_505;
    private static final long PET = 204_501;
    private static final long LOG = 206_501;
    private static final long CORRECT_ROLE = 207_501;
    private static final long AUDIT_ROLE = 207_502;

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
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=200000 OR resource_id>=200000");
        jdbc.update("DELETE FROM pet_health_log WHERE id>=200000");
        jdbc.update("DELETE FROM pet WHERE id>=200000");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=200000");
        jdbc.update("DELETE FROM sys_role_menu WHERE id>=200000");
        jdbc.update("DELETE FROM sys_menu WHERE id>=200000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=200000 OR role_id>=200000");
        jdbc.update("DELETE FROM sys_role WHERE id>=200000");
        jdbc.update("DELETE FROM sys_user WHERE id>=200000");

        seedUser(OWNER, "m0506-owner", "宠物主人甲", "USER");
        seedUser(CORRECT_ADMIN, "m0506-correct", "纠错管理员", "ADMIN");
        seedUser(AUDIT_ADMIN, "m0506-audit", "审计管理员", "ADMIN");
        seedUser(PLAIN_ADMIN, "m0506-plain", "普通管理员", "ADMIN");
        seedUser(DOCTOR, "m0506-doctor", "普通医生", "DOCTOR");
        seedPermissionRole(CORRECT_ROLE, "M0506_CORRECT", 208_501, "admin:pets:correct", CORRECT_ADMIN);
        seedPermissionRole(AUDIT_ROLE, "M0506_AUDIT", 208_502, "audit:read", AUDIT_ADMIN);
        seedSession(CORRECT_ADMIN, "WEB");
        seedSession(AUDIT_ADMIN, "WEB");
        seedSession(PLAIN_ADMIN, "WEB");
        seedSession(DOCTOR, "WEB");
        jdbc.update("""
            INSERT INTO pet(
                id,owner_user_id,name,species_code,breed,sex,birth_date,neutered,
                owner_health_notes,status,version
            ) VALUES (?,?,'纠错前宠物','DOG','旧品种','UNKNOWN','2020-01-02','UNKNOWN',
                      '高度敏感主人备注','ACTIVE',0)
            """, PET, OWNER);
        jdbc.update("""
            INSERT INTO pet_health_log(
                id,pet_id,author_user_id,observed_at,weight_kg,symptom_note,version
            ) VALUES (?,?,?,?,5.60,'完整敏感症状正文',0)
            """, LOG, PET, OWNER, Timestamp.from(Instant.parse("2026-10-10T08:00:00Z")));
    }

    @Test
    void adminListReturnsMaskedSummaryWithoutOwnerNarrative() throws Exception {
        String content = mockMvc.perform(get("/api/v1/admin/pets")
                .header(HttpHeaders.AUTHORIZATION, plainBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(PET)))
            .andExpect(jsonPath("$.data.items[0].owner.displayNameMasked").value("宠***"))
            .andReturn().getResponse().getContentAsString();

        assertThat(content).doesNotContain("宠物主人甲", "高度敏感主人备注", "完整敏感症状正文");
    }

    @Test
    void correctionRequiresPermissionAndPersistsReasonedBeforeAfterAudit() throws Exception {
        mockMvc.perform(get("/api/v1/admin/pets/{petId}", PET)
                .header(HttpHeaders.AUTHORIZATION, plainBearer()))
            .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/admin/pets/{petId}", PET)
                .header(HttpHeaders.AUTHORIZATION, correctBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPatch(0)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("纠错后宠物"))
            .andExpect(jsonPath("$.data.owner.id").value(Long.toString(OWNER)))
            .andExpect(jsonPath("$.data.version").value(1));

        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM pet WHERE id=?", Long.class, PET))
            .isEqualTo(OWNER);
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_log WHERE action='ADMIN_PET_CORRECT'", String.class))
            .isEqualTo("主人提交更正证明");
        String before = jdbc.queryForObject(
            "SELECT CAST(before_json AS CHAR) FROM audit_log WHERE action='ADMIN_PET_CORRECT'", String.class);
        String after = jdbc.queryForObject(
            "SELECT CAST(after_json AS CHAR) FROM audit_log WHERE action='ADMIN_PET_CORRECT'", String.class);
        assertThat(before).contains("纠错前宠物", Long.toString(OWNER), "\"version\": 0");
        assertThat(after).contains("纠错后宠物", Long.toString(OWNER), "\"version\": 1");

        mockMvc.perform(patch("/api/v1/admin/pets/{petId}", PET)
                .header(HttpHeaders.AUTHORIZATION, correctBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPatch(0)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='ADMIN_PET_CORRECT'"))
            .isOne();
    }

    @Test
    void missingReasonAndOwnerOrDiagnosisInjectionAreRejectedWithoutMutation() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/pets/{petId}", PET)
                .header(HttpHeaders.AUTHORIZATION, correctBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPatch(0).replace(",\"reason\":\"主人提交更正证明\"", "")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(patch("/api/v1/admin/pets/{petId}", PET)
                .header(HttpHeaders.AUTHORIZATION, correctBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPatch(0).replace("\"version\":0", "\"ownerUserId\":999,\"version\":0")))
            .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/admin/pets/{petId}", PET)
                .header(HttpHeaders.AUTHORIZATION, correctBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPatch(0).replace("\"version\":0", "\"diagnosis\":\"伪造诊断\",\"version\":0")))
            .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT name FROM pet WHERE id=?", String.class, PET))
            .isEqualTo("纠错前宠物");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='ADMIN_PET_CORRECT'"))
            .isZero();
    }

    @Test
    void fullHealthLogsRequireAuditPermissionAndReadIsAudited() throws Exception {
        mockMvc.perform(get("/api/v1/admin/pets/{petId}/health-logs", PET)
                .header(HttpHeaders.AUTHORIZATION, correctBearer()))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/pets/{petId}/health-logs", PET)
                .header(HttpHeaders.AUTHORIZATION, auditBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].symptomNote").value("完整敏感症状正文"))
            .andExpect(jsonPath("$.data.items[0].sourceType").value("OWNER_REPORTED"));
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='ADMIN_PET_HEALTH_LOG_READ'"
            + " AND actor_user_id=" + AUDIT_ADMIN + " AND resource_id=" + PET)).isOne();
    }

    @Test
    void nonAdminCannotUseAdminPetEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin/pets")
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/pets/{petId}/health-logs", PET)
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isForbidden());
    }

    private void seedUser(long userId, String username, String displayName, String roleCode) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, userId, username, displayName);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, roleCode);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
            userId + 10_000, userId, roleId);
    }

    private void seedPermissionRole(long roleId, String roleCode, long menuId, String permission, long userId) {
        jdbc.update("INSERT INTO sys_role(id,code,name,status,version) VALUES (?,?,?,'ACTIVE',0)",
            roleId, roleCode, roleCode);
        jdbc.update("""
            INSERT INTO sys_menu(id,parent_id,name,kind,permission_code,sort_no,status,version)
            VALUES (?,NULL,?,'BUTTON',?,0,'ACTIVE',0)
            """, menuId, permission, permission);
        jdbc.update("INSERT INTO sys_role_menu(id,role_id,menu_id) VALUES (?,?,?)",
            menuId + 1_000, roleId, menuId);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
            roleId + 10_000, userId, roleId);
    }

    private void seedSession(long userId, String clientType) {
        long sessionId = userId + 1_000;
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """, sessionId, userId, clientType, "%064x".formatted(sessionId), "family-" + sessionId,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private String correctBearer() {
        return bearer(CORRECT_ADMIN, "ADMIN");
    }

    private String auditBearer() {
        return bearer(AUDIT_ADMIN, "ADMIN");
    }

    private String plainBearer() {
        return bearer(PLAIN_ADMIN, "ADMIN");
    }

    private String doctorBearer() {
        return bearer(DOCTOR, "DOCTOR");
    }

    private String bearer(long userId, String role) {
        long sessionId = userId + 1_000;
        return "Bearer " + tokenIssuer.issue(
            userId, sessionId, 0, "WEB", List.of(role), Instant.now(), 1800
        ).value();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static String validPatch(int version) {
        return """
            {"name":"纠错后宠物","speciesCode":"DOG","breed":"新品种","sex":"FEMALE",
             "birthDate":"2020-01-02","neutered":"YES","ownerHealthNotes":"更正后的主人备注",
             "version":%d,"reason":"主人提交更正证明"}
            """.formatted(version);
    }
}
