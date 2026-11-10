package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class M0904IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 94_001;
    private static final long DOCTOR = 94_002;
    private static final long OTHER_DOCTOR = 94_003;
    private static final long USER = 94_004;
    private static final long ADMIN_SESSION = 94_101;
    private static final long DOCTOR_SESSION = 94_102;
    private static final long OTHER_DOCTOR_SESSION = 94_103;
    private static final long USER_SESSION = 94_104;
    private static final long DOCTOR_PROFILE = 94_201;
    private static final long OTHER_DOCTOR_PROFILE = 94_202;
    private static final long STANDARD = 94_301;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Rs256AccessTokenIssuer tokenIssuer;
    @Autowired ObjectMapper objectMapper;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM health_standard WHERE id>=94300");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=94000");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=94000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=94000");
        jdbc.update("DELETE FROM doctor_profile WHERE id>=94200");
        jdbc.update("DELETE FROM sys_user WHERE id>=94000");
        seedUser(ADMIN, "standard-admin", "ADMIN");
        seedUser(DOCTOR, "standard-doctor", "DOCTOR");
        seedUser(OTHER_DOCTOR, "standard-doctor-2", "DOCTOR");
        seedUser(USER, "standard-user", "USER");
        seedDoctor(DOCTOR_PROFILE, DOCTOR, "标准医生甲", "ACTIVE");
        seedDoctor(OTHER_DOCTOR_PROFILE, OTHER_DOCTOR, "标准医生乙", "ACTIVE");
        seedSession(ADMIN_SESSION, ADMIN);
        seedSession(DOCTOR_SESSION, DOCTOR);
        seedSession(OTHER_DOCTOR_SESSION, OTHER_DOCTOR);
        seedSession(USER_SESSION, USER);
    }

    @Test
    void doctorCreatesDraftWithServerBoundEditorAndIdempotentAudit() throws Exception {
        String body = completeBody("猫体温", "CAT", "TEMPERATURE", 12, 120, "℃", "38.0000", "39.2000", 0);
        String first = create(body, "m0904-create-0001", doctorBearer())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.editorDoctorId").value(Long.toString(DOCTOR_PROFILE)))
            .andExpect(jsonPath("$.data.version").value(0))
            .andReturn().getResponse().getContentAsString();
        String second = create(body, "m0904-create-0001", doctorBearer())
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String firstId = objectMapper.readTree(first).path("data").path("standardId").asText();
        String secondId = objectMapper.readTree(second).path("data").path("standardId").asText();
        assertThat(secondId).isEqualTo(firstId);
        assertThat(count("SELECT COUNT(*) FROM health_standard")).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_STANDARD_CREATE'"
            + " AND actor_user_id=" + DOCTOR)).isOne();
    }

    @Test
    void validatesPairedOrderedBoundsAndAllowsUnknownAge() throws Exception {
        create(rawBody("猫体温", "CAT", "TEMPERATURE", "\"ageMinMonths\":12,",
                "\"referenceMin\":38.0,\"referenceMax\":39.2,"),
            "m0904-age-pair-01", doctorBearer())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_HEALTH_STANDARD"));
        create(completeBody("猫体温", "CAT", "TEMPERATURE", 24, 12, "℃", "38.0", "39.2", 0),
            "m0904-age-order-1", doctorBearer())
            .andExpect(status().isBadRequest());
        create(rawBody("猫体温", "CAT", "TEMPERATURE", "",
                "\"referenceMin\":38.0,"),
            "m0904-ref-pair-001", doctorBearer())
            .andExpect(status().isBadRequest());
        create(completeBody("猫体温", "CAT", "TEMPERATURE", null, null, null, "40.0", "39.0", 0),
            "m0904-ref-order-1", doctorBearer())
            .andExpect(status().isBadRequest());
        create(completeBody("未知年龄猫体温", "CAT", "TEMPERATURE", null, null, "℃", "38.0", "39.2", 0),
            "m0904-unknown-age", doctorBearer())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.ageMinMonths").doesNotExist())
            .andExpect(jsonPath("$.data.ageMaxMonths").doesNotExist());
    }

    @Test
    void onlyDoctorWritesWhileDoctorAndAdminCanRead() throws Exception {
        seedStandard("DRAFT", 0, DOCTOR_PROFILE);
        create(completeBody("犬体温", "DOG", "TEMPERATURE", null, null, "℃", "38", "39", 0),
            "m0904-admin-create", adminBearer())
            .andExpect(status().isForbidden());
        create(completeBody("犬体温", "DOG", "TEMPERATURE", null, null, "℃", "38", "39", 0),
            "m0904-user-create1", userBearer())
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/staff/health/standards/{standardId}", STANDARD)
                .header(HttpHeaders.AUTHORIZATION, adminBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.standardId").value(Long.toString(STANDARD)));
        mockMvc.perform(get("/api/v1/staff/health/standards")
                .header(HttpHeaders.AUTHORIZATION, doctorBearer())
                .param("speciesCode", "CAT")
                .param("metricCode", "TEMPERATURE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].standardId").value(Long.toString(STANDARD)));
        mockMvc.perform(get("/api/v1/staff/health/standards")
                .header(HttpHeaders.AUTHORIZATION, userBearer()))
            .andExpect(status().isForbidden());
    }

    @Test
    void editingPublishedStandardResetsDraftChangesEditorAndAudits() throws Exception {
        seedStandard("PUBLISHED", 3, DOCTOR_PROFILE);
        patchStandard(STANDARD,
            completeBody("猫体温复核", "CAT", "TEMPERATURE", null, null, "℃", "37.8", "39.3", 3),
            otherDoctorBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.editorDoctorId").value(Long.toString(OTHER_DOCTOR_PROFILE)))
            .andExpect(jsonPath("$.data.version").value(4))
            .andExpect(jsonPath("$.data.ageMinMonths").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT status FROM health_standard WHERE id=?", String.class, STANDARD))
            .isEqualTo("DRAFT");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_STANDARD_UPDATE'"
            + " AND actor_user_id=" + OTHER_DOCTOR + " AND resource_id=" + STANDARD)).isOne();
    }

    @Test
    void rejectsStaleVersionUnknownFieldsAndInactiveDoctorProfile() throws Exception {
        seedStandard("DRAFT", 2, DOCTOR_PROFILE);
        patchStandard(STANDARD,
            completeBody("过期编辑", "CAT", "TEMPERATURE", null, null, "℃", "38", "39", 1),
            doctorBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        String unknown = completeBody("越界字段", "CAT", "TEMPERATURE", null, null, "℃", "38", "39", 2)
            .replace("\"version\":2", "\"version\":2,\"status\":\"PUBLISHED\"");
        patchStandard(STANDARD, unknown, doctorBearer())
            .andExpect(status().isBadRequest());

        jdbc.update("UPDATE doctor_profile SET status='DISABLED' WHERE id=?", DOCTOR_PROFILE);
        create(completeBody("停用医生编辑", "CAT", "TEMPERATURE", null, null, "℃", "38", "39", 0),
            "m0904-disabled-doc", doctorBearer())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("DOCTOR_PROFILE_INACTIVE"));
    }

    private org.springframework.test.web.servlet.ResultActions create(
        String body,
        String key,
        String bearer
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/health/standards")
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.replaceAll(",\\\"version\\\":\\d+}$", "}")));
    }

    private org.springframework.test.web.servlet.ResultActions patchStandard(
        long standardId,
        String body,
        String bearer
    ) throws Exception {
        return mockMvc.perform(patch("/api/v1/staff/health/standards/{standardId}", standardId)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private static String completeBody(
        String name,
        String species,
        String metric,
        Integer ageMin,
        Integer ageMax,
        String unit,
        String referenceMin,
        String referenceMax,
        int version
    ) {
        String age = ageMin == null ? "" : "\"ageMinMonths\":" + ageMin
            + ",\"ageMaxMonths\":" + ageMax + ",";
        String unitJson = unit == null ? "\"unit\":null," : "\"unit\":\"" + unit + "\",";
        String reference = referenceMin == null ? "" : "\"referenceMin\":" + referenceMin
            + ",\"referenceMax\":" + referenceMax + ",";
        return "{" +
            "\"name\":\"" + name + "\"," +
            "\"speciesCode\":\"" + species + "\"," +
            age +
            "\"metricCode\":\"" + metric + "\"," +
            unitJson + reference +
            "\"description\":\"仅作群体参考，不用于自动诊断\"," +
            "\"conditionsNote\":\"静息状态测量\"," +
            "\"sourceNote\":\"兽医专业资料\"," +
            "\"version\":" + version + "}";
    }

    private static String rawBody(
        String name,
        String species,
        String metric,
        String ageFields,
        String referenceFields
    ) {
        return "{" +
            "\"name\":\"" + name + "\"," +
            "\"speciesCode\":\"" + species + "\"," + ageFields +
            "\"metricCode\":\"" + metric + "\",\"unit\":\"℃\"," + referenceFields +
            "\"description\":\"仅作群体参考\"," +
            "\"conditionsNote\":\"静息状态测量\"," +
            "\"sourceNote\":\"兽医专业资料\"}";
    }

    private void seedStandard(String status, int version, long editorDoctorId) {
        jdbc.update("""
            INSERT INTO health_standard(
                id,name,species_code,age_min_months,age_max_months,metric_code,unit,
                reference_min,reference_max,description,conditions_note,source_note,
                status,editor_doctor_id,version
            ) VALUES (?,?,?,12,120,?,?,38.0000,39.2000,?,?,?,?,?,?)
            """, STANDARD, "猫体温参考", "CAT", "TEMPERATURE", "℃",
            "仅作群体参考，不用于自动诊断", "静息状态测量", "兽医专业资料",
            status, editorDoctorId, version);
    }

    private void seedDoctor(long id, long userId, String name, String status) {
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,?,?,0)
            """, id, userId, name, status);
    }

    private void seedUser(long id, String username, String role) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, id, username, username);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, role);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)", id + 10_000, id, roleId);
    }

    private void seedSession(long id, long userId) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?, 'WEB',?,?,?,?,?,?)
            """, id, userId, "%064d".formatted(id), "family-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now),
            Timestamp.from(now), Timestamp.from(now));
    }

    private String adminBearer() {
        return bearer(ADMIN, ADMIN_SESSION, "ADMIN");
    }

    private String doctorBearer() {
        return bearer(DOCTOR, DOCTOR_SESSION, "DOCTOR");
    }

    private String otherDoctorBearer() {
        return bearer(OTHER_DOCTOR, OTHER_DOCTOR_SESSION, "DOCTOR");
    }

    private String userBearer() {
        return bearer(USER, USER_SESSION, "USER");
    }

    private String bearer(long userId, long sessionId, String role) {
        return "Bearer " + tokenIssuer.issue(
            userId, sessionId, 0, "WEB", List.of(role), Instant.now(), 1800
        ).value();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
