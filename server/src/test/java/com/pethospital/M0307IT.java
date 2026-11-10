package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MvcResult;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "identity.jwt.issuer=pet-hospital",
    "identity.profile.phone-key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
@AutoConfigureMockMvc
class M0307IT {
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
    ObjectMapper objectMapper;

    @Autowired
    Rs256AccessTokenIssuer tokenIssuer;

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
        jdbc.update("DELETE FROM sys_role_menu WHERE id > 0 OR role_id > 0 OR menu_id > 0");
        jdbc.update("UPDATE sys_menu SET parent_id=NULL WHERE id > 0");
        jdbc.update("DELETE FROM sys_menu WHERE id > 0");
        jdbc.update("DELETE FROM sys_role WHERE id > 0");
        jdbc.update("""
            UPDATE sys_role SET status='ACTIVE',version=0
            WHERE code IN ('USER','DOCTOR','ADMIN')
            """);
        jdbc.update("""
            UPDATE sys_menu SET status='ACTIVE',version=0
            WHERE permission_code IN (
                'admin:users:view','admin:users:manage','admin:rbac:view','admin:rbac:manage'
            )
            """);

        seedUser(8000, "admin-one", "WEB", 9000, List.of("ADMIN"));
        seedUser(8001, "admin-two", "WEB", 9001, List.of("ADMIN"));
        seedUser(8002, "target-user", "MINI", 9002, List.of("USER"));
        seedUser(8003, "target-doctor", "WEB", 9003, List.of("DOCTOR"));
    }

    @Test
    void actionPermissionIsCheckedInDatabaseForEveryRequest() throws Exception {
        authenticatedGet("/api/v1/admin/roles", adminToken())
            .andExpect(status().isOk());

        jdbc.update("UPDATE sys_menu SET status='DISABLED' WHERE permission_code='admin:rbac:manage'");
        authenticatedPost("/api/v1/admin/roles", adminToken(), """
            {"code":"NO_PERMISSION","name":"不应创建"}
            """)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE code='NO_PERMISSION'",
            Integer.class
        )).isZero();

        authenticatedGet("/api/v1/admin/roles", issue(8003, 9003, 0, "WEB", List.of("DOCTOR")))
            .andExpect(status().isForbidden());
    }

    @Test
    void roleAndMenuCrudProtectBuiltinCodesBackendCapabilitiesAndTreeCycles() throws Exception {
        MvcResult roleResult = authenticatedPost("/api/v1/admin/roles", adminToken(), """
            {"code":"OPERATIONS","name":" 运营人员 "}
            """)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.code").value("OPERATIONS"))
            .andExpect(jsonPath("$.data.version").value(0))
            .andReturn();
        long roleId = dataId(roleResult);
        authenticatedPatch("/api/v1/admin/roles/" + roleId, adminToken(), """
            {"name":"运营主管","version":0}
            """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("运营主管"))
            .andExpect(jsonPath("$.data.version").value(1));
        authenticatedPost("/api/v1/admin/roles", adminToken(), """
            {"code":"ADMIN","name":"覆盖内置"}
            """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUILTIN_ROLE_PROTECTED"));
        authenticatedPatch("/api/v1/admin/roles/-1103", adminToken(), """
            {"status":"DISABLED","version":0}
            """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUILTIN_ROLE_PROTECTED"));

        long rootMenuId = dataId(authenticatedPost("/api/v1/admin/menus", adminToken(), """
            {"name":"资料","kind":"MENU","routePath":"/profile","permissionCode":"profile:view","sortNo":10}
            """)
            .andExpect(status().isCreated())
            .andReturn());
        long childMenuId = dataId(authenticatedPost("/api/v1/admin/menus", adminToken(), """
            {"parentId":"%d","name":"资料编辑","kind":"MENU","routePath":"/profile/edit","permissionCode":"profile:update","sortNo":20}
            """.formatted(rootMenuId))
            .andExpect(status().isCreated())
            .andReturn());

        authenticatedPatch("/api/v1/admin/menus/" + rootMenuId, adminToken(), """
            {"parentId":"%d","name":"资料","kind":"MENU","routePath":"/profile","sortNo":10,"status":"ACTIVE","version":0}
            """.formatted(childMenuId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        authenticatedPost("/api/v1/admin/menus", adminToken(), """
            {"name":"伪权限","kind":"BUTTON","permissionCode":"not:a:backend:capability","sortNo":30}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        authenticatedGet("/api/v1/admin/menus", adminToken())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.id == '%d')].children[0].id".formatted(rootMenuId))
                .value(Long.toString(childMenuId)));
    }

    @Test
    void userRoleReplacementUsesVersionAndRevokesAllOldSessions() throws Exception {
        authenticatedGet("/api/v1/admin/users/8002/roles", adminToken())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roleIds[0]").value("-1101"))
            .andExpect(jsonPath("$.data.userVersion").value(0));

        String oldTargetToken = issue(8002, 9002, 0, "MINI", List.of("USER"));
        authenticatedPut("/api/v1/admin/users/8002/roles", adminToken(), """
            {"roleIds":["-1101","-1102"],"userVersion":0}
            """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userVersion").value(1))
            .andExpect(jsonPath("$.data.roleIds.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT token_version FROM sys_user WHERE id=8002", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=9002", Timestamp.class))
            .isNotNull();
        authenticatedGet("/api/v1/me", oldTargetToken)
            .andExpect(status().isUnauthorized());

        authenticatedPut("/api/v1/admin/users/8002/roles", adminToken(), """
            {"roleIds":["-1101"],"userVersion":0}
            """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void lastEffectiveAdministratorAndCorePermissionsCannotBeRemoved() throws Exception {
        Long userRole = roleId("USER");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=8001");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (18001,8001,?)", userRole);

        authenticatedPut("/api/v1/admin/users/8000/roles", adminToken(), """
            {"roleIds":["-1101"],"userVersion":0}
            """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id
            WHERE ur.user_id=8000 AND r.code='ADMIN'
            """, Integer.class)).isEqualTo(1);

        authenticatedPut("/api/v1/admin/roles/-1103/menus", adminToken(), """
            {"menuIds":["-1201","-1202","-1203"],"roleVersion":0}
            """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role_menu WHERE role_id=-1103 AND menu_id=-1204",
            Integer.class
        )).isEqualTo(1);
    }

    @Test
    void rolePermissionReplacementAdvancesVersionAndRevokesAffectedUsers() throws Exception {
        long customRoleId = dataId(authenticatedPost("/api/v1/admin/roles", adminToken(), """
            {"code":"PROFILE_READER","name":"资料只读"}
            """).andExpect(status().isCreated()).andReturn());
        long menuId = dataId(authenticatedPost("/api/v1/admin/menus", adminToken(), """
            {"name":"资料查看","kind":"BUTTON","permissionCode":"profile:view","sortNo":50}
            """).andExpect(status().isCreated()).andReturn());
        jdbc.update(
            "INSERT INTO sys_user_role(id,user_id,role_id) VALUES (28003,8003,?)",
            customRoleId
        );
        String oldDoctorToken = issue(8003, 9003, 0, "WEB", List.of("DOCTOR", "PROFILE_READER"));

        authenticatedPut("/api/v1/admin/roles/" + customRoleId + "/menus", adminToken(), """
            {"menuIds":["%d"],"roleVersion":0}
            """.formatted(menuId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.menuIds[0]").value(Long.toString(menuId)))
            .andExpect(jsonPath("$.data.roleVersion").value(1));
        assertThat(jdbc.queryForObject("SELECT token_version FROM sys_user WHERE id=8003", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=9003", Timestamp.class))
            .isNotNull();
        authenticatedGet("/api/v1/me", oldDoctorToken)
            .andExpect(status().isUnauthorized());
        authenticatedGet("/api/v1/admin/roles/" + customRoleId + "/menus", adminToken())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roleVersion").value(1));
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

    private org.springframework.test.web.servlet.ResultActions authenticatedPut(String path, String token, String body)
        throws Exception {
        return mockMvc.perform(put(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private long dataId(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.path("data").path("id").asLong();
    }

    private String adminToken() {
        return issue(8000, 9000, 0, "WEB", List.of("ADMIN"));
    }

    private String issue(long userId, long sessionId, int tokenVersion, String audience, List<String> roles) {
        return tokenIssuer.issue(userId, sessionId, tokenVersion, audience, roles, Instant.now(), 1800).value();
    }

    private Long roleId(String code) {
        return jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, code);
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
                id + 10_000 + index++,
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
