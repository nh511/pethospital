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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "identity.jwt.issuer=pet-hospital",
    "identity.profile.phone-key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
@AutoConfigureMockMvc
class M0308IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final Instant SESSION_CREATED = Instant.now().minusSeconds(60);

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

    @Autowired
    PasswordEncoder passwordEncoder;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM appointment_change_request");
        jdbc.update("DELETE FROM medical_record_version");
        jdbc.update("DELETE FROM medical_record");
        jdbc.update("DELETE FROM appointment_status_log");
        jdbc.update("DELETE FROM appointment");
        jdbc.update("DELETE FROM doctor_slot");
        jdbc.update("DELETE FROM pet_health_log");
        jdbc.update("DELETE FROM pet");
        jdbc.update("DELETE FROM doctor_profile");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id > 0");
        jdbc.update("DELETE FROM sys_user WHERE id > 0");
        jdbc.update("UPDATE sys_role SET status='ACTIVE',version=0 WHERE code IN ('USER','DOCTOR','ADMIN')");
        jdbc.update("""
            UPDATE sys_menu SET status='ACTIVE',version=0
            WHERE permission_code IN (
                'admin:users:view','admin:users:manage','admin:rbac:view','admin:rbac:manage'
            )
            """);

        seedUser(8100, "admin-life-one", "WEB", 9100, List.of("ADMIN"));
        seedUser(8101, "admin-life-two", "WEB", 9101, List.of("ADMIN"));
        seedUser(8102, "owner-life", "MINI", 9102, List.of("USER"));
        seedUser(8103, "doctor-life", "WEB", 9103, List.of("DOCTOR"));
    }

    @Test
    void lifecycleActionsRequireDatabaseManagePermissionAndAdminRole() throws Exception {
        jdbc.update("UPDATE sys_menu SET status='DISABLED' WHERE permission_code='admin:users:manage'");
        actionPost(8102, "disable-denied-01", """
            {"version":0,"reason":"权限不足时不得执行"}
            """, "disable", adminToken())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(statusOf(8102)).isEqualTo("ACTIVE");

        actionPost(8102, "disable-denied-02", """
            {"version":0,"reason":"医生不能执行"}
            """, "disable", doctorToken())
            .andExpect(status().isForbidden());
    }

    @Test
    void disableAndEnableAdvanceVersionsRevokeSessionsAndReplayIdempotently() throws Exception {
        String oldToken = ownerToken();
        String disableBody = """
            {"version":0,"reason":"用户申请停用"}
            """;
        actionPost(8102, "disable-owner-01", disableBody, "disable", adminToken())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value("8102"))
            .andExpect(jsonPath("$.data.status").value("DISABLED"))
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(statusOf(8102)).isEqualTo("DISABLED");
        assertThat(intValue("SELECT token_version FROM sys_user WHERE id=8102")).isEqualTo(1);
        assertThat(timestamp("SELECT revoked_at FROM auth_session WHERE id=9102")).isNotNull();
        authenticatedGet("/api/v1/me", oldToken).andExpect(status().isUnauthorized());

        actionPost(8102, "disable-owner-01", disableBody, "disable", adminToken())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(intValue("""
            SELECT COUNT(*) FROM audit_log
            WHERE actor_user_id=8100 AND operation_key='disable-owner-01'
            """)).isEqualTo(1);
        actionPost(8102, "disable-owner-01", """
            {"version":0,"reason":"同一键不同请求"}
            """, "disable", adminToken())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        actionPost(8102, "enable-owner-0001", """
            {"version":1}
            """, "enable", adminToken())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.version").value(2));
        assertThat(intValue("SELECT token_version FROM sys_user WHERE id=8102")).isEqualTo(2);
        actionPost(8102, "enable-owner-0002", """
            {"version":1}
            """, "enable", adminToken())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void unfinishedAppointmentsBlockOwnerDisableDoctorDisableAndDoctorRoleRemoval() throws Exception {
        seedPendingAppointment();

        actionPost(8102, "disable-booker-01", """
            {"version":0,"reason":"仍有预约"}
            """, "disable", adminToken())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("UNFINISHED_APPOINTMENTS"))
            .andExpect(jsonPath("$.data.pendingCount").value(1))
            .andExpect(jsonPath("$.data.ownerPendingCount").value(1));
        actionPost(8103, "disable-doctor-01", """
            {"version":0,"reason":"仍有接诊"}
            """, "disable", adminToken())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.doctorPendingCount").value(1));

        mockMvc.perform(put("/api/v1/admin/users/8103/roles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"roleIds":["%d"],"userVersion":0}
                    """.formatted(roleId("USER"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("UNFINISHED_APPOINTMENTS"))
            .andExpect(jsonPath("$.data.pendingCount").value(1));

        jdbc.update("""
            UPDATE appointment SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(3) WHERE id=8300
            """);
        mockMvc.perform(put("/api/v1/admin/users/8103/roles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"roleIds":["%d"],"userVersion":0}
                    """.formatted(roleId("USER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userVersion").value(1));
        actionPost(8103, "disable-doctor-02", """
            {"version":1,"reason":"预约已处理"}
            """, "disable", adminToken())
            .andExpect(status().isOk());
        actionPost(8102, "disable-booker-02", """
            {"version":0,"reason":"预约已处理"}
            """, "disable", adminToken())
            .andExpect(status().isOk());
    }

    @Test
    void lastEffectiveAdminCannotBeDisabled() throws Exception {
        jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=8101");
        actionPost(8100, "disable-last-adm", """
            {"version":0,"reason":"不允许最后管理员停用"}
            """, "disable", adminToken())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
        assertThat(statusOf(8100)).isEqualTo("ACTIVE");

        jdbc.update("UPDATE sys_user SET status='ACTIVE' WHERE id=8101");
        actionPost(8100, "disable-admin-02", """
            {"version":0,"reason":"已有另一有效管理员"}
            """, "disable", adminToken())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    @Test
    void passwordResetUsesBcryptRevokesSessionsAndNeverLeaksPassword() throws Exception {
        String temporaryPassword = "Temp-2026-Secure";
        String body = """
            {"version":0,"temporaryPassword":"%s","reason":"用户申请重置"}
            """.formatted(temporaryPassword);
        MvcResult result = actionPost(
            8103, "reset-doctor-001", body, "reset-password", adminToken()
        ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1))
            .andReturn();
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain(temporaryPassword, "temporaryPassword", "passwordHash");

        String storedHash = jdbc.queryForObject(
            "SELECT password_hash FROM sys_user WHERE id=8103", String.class
        );
        assertThat(storedHash).isNotEqualTo(temporaryPassword);
        assertThat(passwordEncoder.matches(temporaryPassword, storedHash)).isTrue();
        assertThat(intValue("SELECT token_version FROM sys_user WHERE id=8103")).isEqualTo(1);
        assertThat(timestamp("SELECT revoked_at FROM auth_session WHERE id=9103")).isNotNull();
        String auditText = jdbc.queryForObject("""
            SELECT CONCAT(COALESCE(CAST(after_json AS CHAR),''),'|',COALESCE(reason,''))
            FROM audit_log WHERE action='ADMIN_USER_PASSWORD_RESET' AND resource_id=8103
            """, String.class);
        assertThat(auditText).doesNotContain(temporaryPassword, storedHash);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/web/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"doctor-life","password":"%s"}
                    """.formatted(temporaryPassword)))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(login.getResponse().getContentAsString()).doesNotContain(temporaryPassword, storedHash);

        actionPost(8103, "reset-doctor-001", body, "reset-password", adminToken())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1));
        assertThat(intValue("""
            SELECT COUNT(*) FROM auth_session WHERE user_id=8103 AND revoked_at IS NULL
            """)).isEqualTo(1);
        actionPost(8103, "reset-doctor-001", """
            {"version":0,"temporaryPassword":"Another-2026-Password","reason":"用户申请重置"}
            """, "reset-password", adminToken())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    private org.springframework.test.web.servlet.ResultActions actionPost(
        long userId,
        String operationKey,
        String body,
        String action,
        String token
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/users/" + userId + "/" + action)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("Idempotency-Key", operationKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions authenticatedGet(String path, String token)
        throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String adminToken() {
        return issue(8100, 9100, 0, "WEB", List.of("ADMIN"));
    }

    private String ownerToken() {
        return issue(8102, 9102, 0, "MINI", List.of("USER"));
    }

    private String doctorToken() {
        return issue(8103, 9103, 0, "WEB", List.of("DOCTOR"));
    }

    private String issue(long userId, long sessionId, int tokenVersion, String audience, List<String> roles) {
        return tokenIssuer.issue(userId, sessionId, tokenVersion, audience, roles, Instant.now(), 1800).value();
    }

    private Long roleId(String code) {
        return jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, code);
    }

    private String statusOf(long userId) {
        return jdbc.queryForObject("SELECT status FROM sys_user WHERE id=?", String.class, userId);
    }

    private int intValue(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private Timestamp timestamp(String sql) {
        return jdbc.queryForObject(sql, Timestamp.class);
    }

    private void seedPendingAppointment() {
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (8200,8103,'生命周期医生','ACTIVE',0)
            """);
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,status,version)
            VALUES (8201,8102,'生命周期宠物','CAT','ACTIVE',0)
            """);
        jdbc.update("""
            INSERT INTO doctor_slot(
                id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
            ) VALUES (
                8202,8200,'2035-01-01','2035-01-01 09:00:00.000','2035-01-01 09:30:00.000',
                1,1,'OPEN',0
            )
            """);
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,
                scheduled_start_at,scheduled_end_at,initial_start_at,status,
                chief_complaint,confirm_deadline_at,idempotency_key,request_hash,version
            ) VALUES (
                8300,'APT-LIFECYCLE-8300',8102,8201,8200,8202,
                '2035-01-01 09:00:00.000','2035-01-01 09:30:00.000','2035-01-01 09:00:00.000','PENDING',
                '未终结预约测试','2034-12-31 09:00:00.000','lifecycle-appointment',REPEAT('a',64),0
            )
            """);
    }

    private void seedUser(
        long id,
        String username,
        String clientType,
        long sessionId,
        List<String> roleCodes
    ) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, id, username, username);
        int index = 0;
        for (String roleCode : roleCodes) {
            jdbc.update(
                "INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
                id + 20_000 + index++,
                id,
                roleId(roleCode)
            );
        }
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """,
            sessionId,
            id,
            clientType,
            "%064d".formatted(sessionId),
            "family-" + sessionId,
            Timestamp.from(SESSION_CREATED.plusSeconds(604800)),
            Timestamp.from(SESSION_CREATED),
            Timestamp.from(SESSION_CREATED),
            Timestamp.from(SESSION_CREATED)
        );
    }
}
