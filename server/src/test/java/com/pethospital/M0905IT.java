package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.math.BigDecimal;
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
class M0905IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 95_001;
    private static final long DOCTOR = 95_002;
    private static final long USER = 95_003;
    private static final long ADMIN_SESSION = 95_101;
    private static final long DOCTOR_SESSION = 95_102;
    private static final long USER_SESSION = 95_103;
    private static final long DOCTOR_PROFILE = 95_201;
    private static final long STANDARD = 95_301;

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

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM health_standard WHERE id>=95300");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=95000");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=95000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=95000");
        jdbc.update("DELETE FROM doctor_profile WHERE id>=95200");
        jdbc.update("DELETE FROM sys_user WHERE id>=95000");
        seedUser(ADMIN, "standard-publisher", "ADMIN");
        seedUser(DOCTOR, "standard-editor", "DOCTOR");
        seedUser(USER, "standard-reader", "USER");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,?,'ACTIVE',0)
            """, DOCTOR_PROFILE, DOCTOR, "标准维护医生");
        seedSession(ADMIN_SESSION, ADMIN, "WEB");
        seedSession(DOCTOR_SESSION, DOCTOR, "WEB");
        seedSession(USER_SESSION, USER, "MINI");
    }

    @Test
    void adminPublishesIdempotentlyWithoutChangingProfessionalContent() throws Exception {
        seedStandard(STANDARD, "猫体温参考", "CAT", 12, 120, "TEMPERATURE",
            new BigDecimal("38.0000"), new BigDecimal("39.2000"), "DRAFT", 0);
        publish(STANDARD, 0, "m0905-publish-001", adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.description").value("仅作群体参考，不用于自动诊断"));
        publish(STANDARD, 0, "m0905-publish-001", adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1));

        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_STANDARD_PUBLISH'"
            + " AND resource_id=" + STANDARD)).isOne();
        assertThat(jdbc.queryForObject("SELECT editor_doctor_id FROM health_standard WHERE id=?",
            Long.class, STANDARD)).isEqualTo(DOCTOR_PROFILE);
    }

    @Test
    void adminOfflinesWithReasonAndPublicQueryStopsReturningIt() throws Exception {
        seedStandard(STANDARD, "猫体温参考", "CAT", null, null, "TEMPERATURE",
            null, null, "PUBLISHED", 1);
        publicQuery("CAT", null, "TEMPERATURE", userBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].standardId").value(Long.toString(STANDARD)));
        offline(STANDARD, 1, "m0905-offline-001", "来源版本需要复核", adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("OFFLINE"))
            .andExpect(jsonPath("$.data.version").value(2));
        offline(STANDARD, 1, "m0905-offline-001", "来源版本需要复核", adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(2));
        publicQuery("CAT", null, "TEMPERATURE", userBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isEmpty());
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='HEALTH_STANDARD_OFFLINE'"
            + " AND reason='来源版本需要复核' AND resource_id=" + STANDARD)).isOne();
    }

    @Test
    void publicQueryNeverCrossesSpeciesMetricStatusOrAgeBoundaries() throws Exception {
        seedStandard(STANDARD, "A通用猫体温", "CAT", null, null, "TEMPERATURE",
            null, null, "PUBLISHED", 0);
        seedStandard(STANDARD + 1, "B成年猫体温", "CAT", 12, 120, "TEMPERATURE",
            new BigDecimal("38"), new BigDecimal("39.2"), "PUBLISHED", 0);
        seedStandard(STANDARD + 2, "C幼猫体温", "CAT", 0, 11, "TEMPERATURE",
            new BigDecimal("38.2"), new BigDecimal("39.5"), "PUBLISHED", 0);
        seedStandard(STANDARD + 3, "犬体温", "DOG", null, null, "TEMPERATURE",
            null, null, "PUBLISHED", 0);
        seedStandard(STANDARD + 4, "猫体重", "CAT", null, null, "WEIGHT_KG",
            null, null, "PUBLISHED", 0);
        seedStandard(STANDARD + 5, "草稿猫体温", "CAT", null, null, "TEMPERATURE",
            null, null, "DRAFT", 0);

        publicQuery("CAT", 24, "TEMPERATURE", userBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].name").value("A通用猫体温"))
            .andExpect(jsonPath("$.data[1].name").value("B成年猫体温"));
        publicQuery("CAT", null, "TEMPERATURE", userBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("A通用猫体温"));
    }

    @Test
    void roleMatrixKeepsProfessionalEditingWithDoctorAndPublishingWithAdmin() throws Exception {
        seedStandard(STANDARD, "猫体温参考", "CAT", null, null, "TEMPERATURE",
            null, null, "DRAFT", 0);
        publish(STANDARD, 0, "m0905-doctor-pub01", doctorBearer())
            .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/staff/health/standards/{standardId}", STANDARD)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(standardPatchBody(0)))
            .andExpect(status().isForbidden());
        publicQuery("CAT", null, "TEMPERATURE", adminBearer())
            .andExpect(status().isForbidden());
    }

    @Test
    void textOnlyStandardsPublishWhileInvalidStatesVersionsAndFieldsAreRejected() throws Exception {
        seedStandard(STANDARD, "猫行为观察", "CAT", null, null, "BEHAVIOR",
            null, null, "DRAFT", 0);
        publish(STANDARD, 0, "m0905-text-publish", adminBearer())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.referenceMin").doesNotExist())
            .andExpect(jsonPath("$.data.referenceMax").doesNotExist());
        publish(STANDARD, 1, "m0905-repeat-state", adminBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("HEALTH_STANDARD_STATE_CONFLICT"));
        offline(STANDARD, 0, "m0905-stale-offline", "过期版本", adminBearer())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(post("/api/v1/staff/health/standards/{standardId}/offline", STANDARD)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .header("Idempotency-Key", "m0905-unknown-field")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"reason\":\"复核\",\"description\":\"管理员修改\"}"))
            .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions publish(
        long id,
        int version,
        String key,
        String bearer
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/health/standards/{standardId}/publish", id)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions offline(
        long id,
        int version,
        String key,
        String reason,
        String bearer
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/health/standards/{standardId}/offline", id)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + ",\"reason\":\"" + reason + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions publicQuery(
        String species,
        Integer age,
        String metric,
        String bearer
    ) throws Exception {
        var request = get("/api/v1/health/standards")
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .param("speciesCode", species)
            .param("metricCode", metric);
        if (age != null) {
            request.param("ageMonths", Integer.toString(age));
        }
        return mockMvc.perform(request);
    }

    private void seedStandard(
        long id,
        String name,
        String species,
        Integer ageMin,
        Integer ageMax,
        String metric,
        BigDecimal referenceMin,
        BigDecimal referenceMax,
        String status,
        int version
    ) {
        jdbc.update("""
            INSERT INTO health_standard(
                id,name,species_code,age_min_months,age_max_months,metric_code,unit,
                reference_min,reference_max,description,conditions_note,source_note,
                status,editor_doctor_id,version
            ) VALUES (?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?)
            """, id, name, species, ageMin, ageMax, metric,
            referenceMin == null ? null : "℃", referenceMin, referenceMax,
            "仅作群体参考，不用于自动诊断", "适用条件以标准正文为准", "兽医专业资料",
            status, DOCTOR_PROFILE, version);
    }

    private static String standardPatchBody(int version) {
        return "{" +
            "\"name\":\"管理员越权修改\",\"speciesCode\":\"CAT\"," +
            "\"metricCode\":\"TEMPERATURE\",\"unit\":null," +
            "\"description\":\"仅作参考\",\"conditionsNote\":\"观察\"," +
            "\"sourceNote\":\"来源\",\"version\":" + version + "}";
    }

    private void seedUser(long id, String username, String role) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, id, username, username);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, role);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)", id + 10_000, id, roleId);
    }

    private void seedSession(long id, long userId, String clientType) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """, id, userId, clientType, "%064d".formatted(id), "family-" + id,
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now),
            Timestamp.from(now), Timestamp.from(now));
    }

    private String adminBearer() {
        return bearer(ADMIN, ADMIN_SESSION, "WEB", "ADMIN");
    }

    private String doctorBearer() {
        return bearer(DOCTOR, DOCTOR_SESSION, "WEB", "DOCTOR");
    }

    private String userBearer() {
        return bearer(USER, USER_SESSION, "MINI", "USER");
    }

    private String bearer(long userId, long sessionId, String audience, String role) {
        return "Bearer " + tokenIssuer.issue(
            userId, sessionId, 0, audience, List.of(role), Instant.now(), 1800
        ).value();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
