package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.PhoneCipher;
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
class M0306IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final Instant SESSION_CREATED = Instant.now().minusSeconds(60);
    private static final String INITIAL_PASSWORD = "M0306-secret-Password!";

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
    ObjectMapper objectMapper;

    @Autowired
    Rs256AccessTokenIssuer tokenIssuer;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    PhoneCipher phoneCipher;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id > 0");
        jdbc.update("DELETE FROM sys_user WHERE id > 0");
        jdbc.update("UPDATE sys_role SET status='ACTIVE',version=0 WHERE code IN ('USER','DOCTOR','ADMIN')");

        seedUser(6000, "admin-account", "WEB", 7000, "ADMIN", null, null);
        seedUser(6001, "ordinary-account", "WEB", 7001, "USER", null, null);
        seedUser(6002, "owner-account", "MINI", 7002, "USER",
            phoneCipher.encrypt("13800138000"), "138****8000");
        seedUser(6003, "doctor-account", "WEB", 7003, "DOCTOR", null, null);
    }

    @Test
    void ordinaryUserAndDoctorCannotUseAdminAccountEndpoints() throws Exception {
        authenticatedGet("/api/v1/admin/users", issue(6001, 7001, "WEB", List.of("USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        authenticatedGet("/api/v1/admin/users/6002", issue(6003, 7003, "WEB", List.of("DOCTOR")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void listAndDetailReturnOnlyMaskedAccountProfile() throws Exception {
        String admin = adminToken();
        authenticatedGet("/api/v1/admin/users?page=1&pageSize=2", admin)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(2))
            .andExpect(jsonPath("$.data.total").value(4))
            .andExpect(jsonPath("$.data.items.length()").value(2));

        authenticatedGet("/api/v1/admin/users/6002", admin)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("6002"))
            .andExpect(jsonPath("$.data.phoneMasked").value("138****8000"))
            .andExpect(jsonPath("$.data.roles[0]").value("USER"))
            .andExpect(jsonPath("$.data.phone").doesNotExist())
            .andExpect(jsonPath("$.data.phoneCipher").doesNotExist())
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void createHashesPasswordBindsBackendRoleAuditsSafelyAndRejectsDuplicateUsername() throws Exception {
        String body = """
            {
              "username":"New.Doctor",
              "initialPassword":"M0306-secret-Password!",
              "displayName":" 新医生 ",
              "phone":"139-0013-9000",
              "roleCode":"DOCTOR"
            }
            """;
        MvcResult created = authenticatedPost("/api/v1/admin/users", adminToken(), body)
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.data.username").value("new.doctor"))
            .andExpect(jsonPath("$.data.displayName").value("新医生"))
            .andExpect(jsonPath("$.data.phoneMasked").value("139****9000"))
            .andExpect(jsonPath("$.data.roles[0]").value("DOCTOR"))
            .andExpect(jsonPath("$.data.initialPassword").doesNotExist())
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
            .andReturn();

        JsonNode response = objectMapper.readTree(created.getResponse().getContentAsString());
        long userId = response.path("data").path("id").asLong();
        String passwordHash = jdbc.queryForObject(
            "SELECT password_hash FROM sys_user WHERE id=?",
            String.class,
            userId
        );
        assertThat(passwordHash).startsWith("$2");
        assertThat(passwordHash.substring(4, 6)).isEqualTo("12");
        assertThat(passwordEncoder.matches(INITIAL_PASSWORD, passwordHash)).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id
            WHERE ur.user_id=? AND r.code='DOCTOR'
            """, Integer.class, userId)).isEqualTo(1);
        String auditJson = jdbc.queryForObject(
            "SELECT CAST(after_json AS CHAR) FROM audit_log WHERE action='ADMIN_USER_CREATE' AND resource_id=?",
            String.class,
            userId
        );
        assertThat(auditJson).doesNotContain(INITIAL_PASSWORD).doesNotContainIgnoringCase("password");

        authenticatedPost("/api/v1/admin/users", adminToken(), """
            {"username":"NEW.DOCTOR","initialPassword":"another-password","displayName":"重复", "roleCode":"ADMIN"}
            """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE username='new.doctor'",
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action='ADMIN_USER_CREATE' AND resource_id=?",
            Integer.class,
            userId
        )).isEqualTo(1);
    }

    @Test
    void updateCorrectsOnlyAccountProfileWithOptimisticLockAndMaskedAudit() throws Exception {
        String admin = adminToken();
        authenticatedPatch("/api/v1/admin/users/6002", admin, """
            {"diagnosis":"禁止写入","roleCode":"ADMIN","version":0}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        authenticatedPatch("/api/v1/admin/users/6002", admin, """
            {"username":"Corrected.Owner","displayName":" 纠错姓名 ","phone":"137-0013-7000","version":0}
            """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value("corrected.owner"))
            .andExpect(jsonPath("$.data.displayName").value("纠错姓名"))
            .andExpect(jsonPath("$.data.phoneMasked").value("137****7000"))
            .andExpect(jsonPath("$.data.roles[0]").value("USER"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.version").value(1));

        byte[] encrypted = jdbc.queryForObject("SELECT phone_cipher FROM sys_user WHERE id=6002", byte[].class);
        assertThat(phoneCipher.decrypt(encrypted)).isEqualTo("13700137000");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id
            WHERE ur.user_id=6002 AND r.code='USER'
            """, Integer.class)).isEqualTo(1);
        String audit = jdbc.queryForObject(
            "SELECT CONCAT(CAST(before_json AS CHAR),CAST(after_json AS CHAR)) "
                + "FROM audit_log WHERE action='ADMIN_USER_PROFILE_UPDATE' AND resource_id=6002",
            String.class
        );
        assertThat(audit).contains("138****8000", "137****7000").doesNotContain("13700137000");

        authenticatedPatch("/api/v1/admin/users/6002", admin, """
            {"displayName":"过期覆盖","version":0}
            """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT display_name FROM sys_user WHERE id=6002", String.class))
            .isEqualTo("纠错姓名");
    }

    @Test
    void createRejectsUserRoleAndDetailReturnsNotFound() throws Exception {
        authenticatedPost("/api/v1/admin/users", adminToken(), """
            {"username":"forbidden-user","initialPassword":"temporary-password","displayName":"越界", "roleCode":"USER"}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        authenticatedGet("/api/v1/admin/users/999999", adminToken())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.ResultActions authenticatedGet(String path, String token)
        throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions authenticatedPost(String path, String token, String body)
        throws Exception {
        return mockMvc.perform(post(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions authenticatedPatch(String path, String token, String body)
        throws Exception {
        return mockMvc.perform(patch(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private String adminToken() {
        return issue(6000, 7000, "WEB", List.of("ADMIN"));
    }

    private String issue(long userId, long sessionId, String audience, List<String> roles) {
        return tokenIssuer.issue(userId, sessionId, 0, audience, roles, Instant.now(), 1800).value();
    }

    private void seedUser(
        long id,
        String username,
        String clientType,
        long sessionId,
        String roleCode,
        byte[] phoneCiphertext,
        String phoneMasked
    ) {
        jdbc.update("""
            INSERT INTO sys_user(
                id,username,password_hash,display_name,phone_cipher,phone_masked,status,token_version,version
            ) VALUES (?,?,NULL,?,?,?,'ACTIVE',0,0)
            """, id, username, username, phoneCiphertext, phoneMasked);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, roleCode);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)", id + 10_000, id, roleId);
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
