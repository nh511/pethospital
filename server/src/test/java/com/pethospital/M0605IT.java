package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.identity.Rs256AccessTokenIssuer;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
class M0605IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long REQUESTER = 75_001;
    private static final long DOCTOR_USER = 75_101;
    private static final long DISABLED_USER = 75_102;
    private static final long REQUESTER_SESSION = 76_001;
    private static final long DOCTOR_WEB_SESSION = 76_101;
    private static final long DOCTOR = 77_101;
    private static final long DISABLED_DOCTOR = 77_102;

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
        jdbc.update("DELETE FROM doctor_slot WHERE id BETWEEN 78000 AND 78999");
        jdbc.update("DELETE FROM doctor_profile WHERE id BETWEEN 77000 AND 77999");
        jdbc.update("DELETE FROM auth_session WHERE user_id BETWEEN 75000 AND 75999");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id BETWEEN 75000 AND 75999");
        jdbc.update("DELETE FROM sys_user WHERE id BETWEEN 75000 AND 75999");
        jdbc.update("UPDATE sys_role SET status='ACTIVE' WHERE code='DOCTOR'");

        seedUser(REQUESTER, "m0605-requester", "ACTIVE", "USER");
        seedUser(DOCTOR_USER, "m0605-doctor", "ACTIVE", "DOCTOR");
        seedUser(DISABLED_USER, "m0605-disabled", "DISABLED", "DOCTOR");
        seedSession(REQUESTER_SESSION, REQUESTER, "MINI");
        seedSession(DOCTOR_WEB_SESSION, DOCTOR_USER, "WEB");
        seedProfile(DOCTOR, DOCTOR_USER, "可预约医生", "ACTIVE");
        seedProfile(DISABLED_DOCTOR, DISABLED_USER, "停用医生", "ACTIVE");
    }

    @Test
    void returnsCrossMidnightSlotByShanghaiStartDateInStableOrder() throws Exception {
        LocalDate firstDate = futureDate(2);
        seedSlot(78_001, firstDate, 23, 30, firstDate.plusDays(1), 0, 30, 3, 1, "OPEN");
        seedSlot(78_002, firstDate.plusDays(1), 9, 0, firstDate.plusDays(1), 9, 30, 2, 0, "OPEN");

        request(firstDate, firstDate)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value("78001"))
            .andExpect(jsonPath("$.data.items[0].doctorId").value(Long.toString(DOCTOR)))
            .andExpect(jsonPath("$.data.items[0].availableCount").value(2))
            .andExpect(jsonPath("$.data.items[0].startAt").value(atShanghai(firstDate, 23, 30).toString()));

        request(firstDate, firstDate.plusDays(1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value("78001"))
            .andExpect(jsonPath("$.data.items[1].id").value("78002"));
    }

    @Test
    void excludesPastClosedAndFullSlots() throws Exception {
        LocalDate future = futureDate(3);
        seedSlot(78_010, future, 9, 0, future, 9, 30, 1, 0, "OPEN");
        seedSlot(78_011, future, 10, 0, future, 10, 30, 1, 0, "CLOSED");
        seedSlot(78_012, future, 11, 0, future, 11, 30, 2, 2, "OPEN");
        LocalDate yesterday = LocalDate.now(SHANGHAI).minusDays(1);
        seedSlot(78_013, yesterday, 9, 0, yesterday, 9, 30, 1, 0, "OPEN");

        request(yesterday, future)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value("78010"))
            .andExpect(jsonPath("$.data.items[0].status").value("OPEN"));
    }

    @Test
    void enforcesInclusiveFourteenDayWindow() throws Exception {
        LocalDate from = futureDate(1);
        seedSlot(78_020, from.plusDays(13), 9, 0, from.plusDays(13), 9, 30, 1, 0, "OPEN");
        request(from, from.plusDays(13))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1));
        request(from, from.plusDays(14))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        request(from, from.minusDays(1))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void remainingCapacityAlwaysComesFromCurrentDatabaseState() throws Exception {
        LocalDate date = futureDate(4);
        seedSlot(78_030, date, 9, 0, date, 9, 30, 4, 1, "OPEN");
        request(date, date)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].availableCount").value(3))
            .andExpect(jsonPath("$.data.items[0].occupiedCount").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].version").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].slotDate").doesNotExist());

        jdbc.update("UPDATE doctor_slot SET occupied_count=3 WHERE id=78030");
        String response = request(date, date)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].availableCount").value(1))
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("occupiedCount", "version", "slotDate");
    }

    @Test
    void requiresSelectableDoctorAndMiniAudience() throws Exception {
        LocalDate date = futureDate(2);
        mockMvc.perform(get("/api/v1/doctors/{doctorId}/slots", DOCTOR)
                .queryParam("fromDate", date.toString()).queryParam("toDate", date.toString()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/doctors/{doctorId}/slots", DOCTOR)
                .queryParam("fromDate", date.toString()).queryParam("toDate", date.toString())
                .header(HttpHeaders.AUTHORIZATION, webBearer()))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/doctors/{doctorId}/slots", DISABLED_DOCTOR)
                .queryParam("fromDate", date.toString()).queryParam("toDate", date.toString())
                .header(HttpHeaders.AUTHORIZATION, miniBearer()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("DOCTOR_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/doctors/{doctorId}/slots", 77_999)
                .queryParam("fromDate", date.toString()).queryParam("toDate", date.toString())
                .header(HttpHeaders.AUTHORIZATION, miniBearer()))
            .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions request(LocalDate fromDate, LocalDate toDate)
        throws Exception {
        return mockMvc.perform(get("/api/v1/doctors/{doctorId}/slots", DOCTOR)
            .queryParam("fromDate", fromDate.toString()).queryParam("toDate", toDate.toString())
            .header(HttpHeaders.AUTHORIZATION, miniBearer()));
    }

    private String miniBearer() {
        return bearer(REQUESTER, REQUESTER_SESSION, "USER", "MINI");
    }

    private String webBearer() {
        return bearer(DOCTOR_USER, DOCTOR_WEB_SESSION, "DOCTOR", "WEB");
    }

    private String bearer(long userId, long sessionId, String role, String audience) {
        return "Bearer " + tokenIssuer.issue(
            userId, sessionId, 0, audience, List.of(role), Instant.now(), 1800
        ).value();
    }

    private void seedUser(long id, String username, String status, String role) {
        jdbc.update("""
            INSERT INTO sys_user(
                id,username,password_hash,display_name,phone_cipher,phone_masked,status,token_version,version
            ) VALUES (?,?,NULL,?,X'0102','138****8000',?,0,0)
            """, id, username, username, status);
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
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private void seedProfile(long id, long userId, String doctorName, String status) {
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version) VALUES (?,?,?,?,0)
            """, id, userId, doctorName, status);
    }

    private void seedSlot(
        long id, LocalDate slotDate, int startHour, int startMinute,
        LocalDate endDate, int endHour, int endMinute,
        int capacity, int occupied, String status
    ) {
        jdbc.update("""
            INSERT INTO doctor_slot(
                id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
            ) VALUES (?,?,?,?,?,?,?,?,0)
            """, id, DOCTOR, slotDate,
            utcTimestamp(atShanghai(slotDate, startHour, startMinute)),
            utcTimestamp(atShanghai(endDate, endHour, endMinute)),
            capacity, occupied, status);
    }

    private static LocalDate futureDate(long days) {
        return LocalDate.now(SHANGHAI).plusDays(days);
    }

    private static Instant atShanghai(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(SHANGHAI).toInstant();
    }

    private static Timestamp utcTimestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
