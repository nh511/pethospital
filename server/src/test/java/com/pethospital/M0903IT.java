package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(
    classes = {PetHospitalApplication.class, M0903IT.FixedClockConfig.class},
    properties = {"springdoc.api-docs.enabled=false", "identity.jwt.allow-ephemeral=true"}
)
@AutoConfigureMockMvc
class M0903IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final Instant NOW = Instant.parse("2026-01-01T16:30:00Z");
    private static final long USER1 = 94_001;
    private static final long USER2 = 94_002;
    private static final long DOCTOR = 94_003;
    private static final long SESSION1 = 94_101;
    private static final long SESSION2 = 94_102;
    private static final long DOCTOR_SESSION = 94_103;
    private static final long GUIDE1 = 94_201;
    private static final long GUIDE2 = 94_202;
    private static final long DRAFT = 94_203;
    private static final long OFFLINE = 94_204;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock m0903Clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

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
        jdbc.update("DELETE FROM guide_view_event");
        jdbc.update("DELETE FROM health_guide WHERE id>=94200");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=94000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=94000");
        jdbc.update("DELETE FROM sys_user WHERE id>=94000");
        seedUser(USER1, "guide-reader-1", "USER");
        seedUser(USER2, "guide-reader-2", "USER");
        seedUser(DOCTOR, "guide-web-doctor", "DOCTOR");
        seedSession(SESSION1, USER1, "MINI");
        seedSession(SESSION2, USER2, "MINI");
        seedSession(DOCTOR_SESSION, DOCTOR, "WEB");
        seedGuide(GUIDE1, "猫咪护理", "CAT", "DAILY_CARE", "PUBLISHED", NOW.minusSeconds(60));
        seedGuide(GUIDE2, "犬只护理", "DOG", "DAILY_CARE", "PUBLISHED", NOW.minusSeconds(120));
        seedGuide(DRAFT, "猫咪草稿", "CAT", "DRAFT_ONLY", "DRAFT", null);
        seedGuide(OFFLINE, "猫咪下架", "CAT", "OFFLINE_ONLY", "OFFLINE", NOW.minusSeconds(180));
    }

    @Test
    void listReturnsOnlyPublishedStablePublicSummariesWithLiteralFilters() throws Exception {
        mockMvc.perform(get("/api/v1/health/guides")
                .header(HttpHeaders.AUTHORIZATION, user1Bearer())
                .param("speciesCode", "cat")
                .param("categoryCode", "daily_care")
                .param("keyword", "猫咪"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("1"))
            .andExpect(jsonPath("$.data.items[0].guideId").value(Long.toString(GUIDE1)))
            .andExpect(jsonPath("$.data.items[0].body").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].editorUserId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].version").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].status").doesNotExist());
        mockMvc.perform(get("/api/v1/health/guides")
                .header(HttpHeaders.AUTHORIZATION, user1Bearer())
                .param("keyword", "%"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("0"));
    }

    @Test
    void detailReturnsSanitizedBodyAndRecordsShanghaiDate() throws Exception {
        jdbc.update("UPDATE health_guide SET body=? WHERE id=?",
            "<p onclick='bad()'>安全<strong>正文</strong></p><script>bad()</script>", GUIDE1);
        mockMvc.perform(get("/api/v1/health/guides/{guideId}", GUIDE1)
                .header(HttpHeaders.AUTHORIZATION, user1Bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.body").value("<p>安全<strong>正文</strong></p>"))
            .andExpect(jsonPath("$.data.status").doesNotExist())
            .andExpect(jsonPath("$.data.version").doesNotExist());
        LocalDate storedDate = jdbc.queryForObject(
            "SELECT view_date FROM guide_view_event WHERE guide_id=? AND user_id=?",
            LocalDate.class, GUIDE1, USER1);
        assertThat(storedDate).isEqualTo(LocalDate.of(2026, 1, 2));
    }

    @Test
    void repeatedViewsBySameUserSameShanghaiDayAreDeduplicated() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(get("/api/v1/health/guides/{guideId}", GUIDE1)
                    .header(HttpHeaders.AUTHORIZATION, user1Bearer()))
                .andExpect(status().isOk());
        }
        assertThat(count("SELECT COUNT(*) FROM guide_view_event WHERE guide_id=" + GUIDE1
            + " AND user_id=" + USER1)).isOne();
    }

    @Test
    void differentUsersProduceIndependentDailyEvents() throws Exception {
        mockMvc.perform(get("/api/v1/health/guides/{guideId}", GUIDE1)
                .header(HttpHeaders.AUTHORIZATION, user1Bearer()))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/health/guides/{guideId}", GUIDE1)
                .header(HttpHeaders.AUTHORIZATION, user2Bearer()))
            .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM guide_view_event WHERE guide_id=" + GUIDE1)).isEqualTo(2);
    }

    @Test
    void unpublishedUnknownAndWrongAudienceAreRejectedWithoutViewEvents() throws Exception {
        mockMvc.perform(get("/api/v1/health/guides/{guideId}", DRAFT)
                .header(HttpHeaders.AUTHORIZATION, user1Bearer()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/health/guides/{guideId}", OFFLINE)
                .header(HttpHeaders.AUTHORIZATION, user1Bearer()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/health/guides/{guideId}", 999_999)
                .header(HttpHeaders.AUTHORIZATION, user1Bearer()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/health/guides")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/health/guides")
                .header(HttpHeaders.AUTHORIZATION, doctorBearer()))
            .andExpect(status().isForbidden());
        assertThat(count("SELECT COUNT(*) FROM guide_view_event")).isZero();
    }

    private void seedGuide(
        long id,
        String title,
        String species,
        String category,
        String status,
        Instant publishedAt
    ) {
        jdbc.update("""
            INSERT INTO health_guide(
                id,title,species_code,category_code,body,cover_file_id,source_note,status,
                published_at,editor_user_id,version
            ) VALUES (?,?,?,?,?,NULL,?,?,?,?,0)
            """, id, title, species, category, "<p>安全正文</p>", "公开来源", status,
            publishedAt == null ? null : Timestamp.from(publishedAt), DOCTOR);
    }

    private String user1Bearer() {
        return bearer(USER1, SESSION1, "USER", "MINI");
    }

    private String user2Bearer() {
        return bearer(USER2, SESSION2, "USER", "MINI");
    }

    private String doctorBearer() {
        return bearer(DOCTOR, DOCTOR_SESSION, "DOCTOR", "WEB");
    }

    private String bearer(long userId, long sessionId, String role, String audience) {
        return "Bearer " + tokenIssuer.issue(
            userId, sessionId, 0, audience, List.of(role), Instant.now(), 86_400
        ).value();
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
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """, id, userId, clientType, "%064d".formatted(id), "family-" + id,
            Timestamp.from(NOW.plusSeconds(172_800)), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
