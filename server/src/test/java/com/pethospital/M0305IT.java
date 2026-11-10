package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "identity.jwt.issuer=pet-hospital",
    "identity.profile.phone-key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
@AutoConfigureMockMvc
class M0305IT {
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
    PhoneCipher phoneCipher;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM file_binding");
        jdbc.update("UPDATE sys_user SET avatar_file_id=NULL WHERE id > 0");
        jdbc.update("DELETE FROM file_object WHERE uploader_user_id > 0");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id > 0");
        jdbc.update("DELETE FROM sys_user WHERE id > 0");
        jdbc.update("DELETE FROM sys_role_menu WHERE menu_id > 0");
        jdbc.update("UPDATE sys_menu SET parent_id=NULL WHERE id > 0");
        jdbc.update("DELETE FROM sys_menu WHERE id > 0");

        seedUser(4000, "mini-profile", "MINI", 5000, "USER");
        seedUser(4001, "web-profile", "WEB", 5001, "DOCTOR");
        seedUser(4002, "other-profile", "MINI", 5002, "USER");
        seedFiles();
        seedMenus();
    }

    @Test
    void currentProfileUsesDatabaseRolesAndPermissionsInsteadOfJwtRoleClaims() throws Exception {
        String token = issue(4000, 5000, "MINI", List.of("ADMIN"));

        authenticatedGet("/api/v1/me", token)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.data.id").value("4000"))
            .andExpect(jsonPath("$.data.displayName").value("mini-profile"))
            .andExpect(jsonPath("$.data.roles").value(hasItems("USER")))
            .andExpect(jsonPath("$.data.roles[?(@ == 'ADMIN')]").isEmpty())
            .andExpect(jsonPath("$.data.permissions").value(hasItems("profile:view")))
            .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void patchEncryptsPhoneMasksResponseBindsReadyOwnedAvatarAndIncrementsVersion() throws Exception {
        String token = issue(4000, 5000, "MINI", List.of("USER"));

        authenticatedPatch("/api/v1/me", token, """
            {"displayName":" 新名字 ","phone":"138-0013-8000","avatarFileId":"7000","version":0}
            """)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.data.displayName").value("新名字"))
            .andExpect(jsonPath("$.data.phoneMasked").value("138****8000"))
            .andExpect(jsonPath("$.data.avatarFileId").value("7000"))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.phone").doesNotExist());

        byte[] encrypted = jdbc.queryForObject(
            "SELECT phone_cipher FROM sys_user WHERE id=4000",
            byte[].class
        );
        assertThat(encrypted).isNotNull();
        assertThat(phoneCipher.decrypt(encrypted)).isEqualTo("13800138000");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM file_binding WHERE file_id=7000 AND business_type='USER_AVATAR' "
                + "AND business_id=4000 AND owner_user_id=4000 AND created_by=4000",
            Integer.class
        )).isEqualTo(1);
    }

    @Test
    void patchRejectsUnknownPrivilegeFieldsEmptyChangesAndStaleVersion() throws Exception {
        String token = issue(4000, 5000, "MINI", List.of("USER"));

        authenticatedPatch("/api/v1/me", token, """
            {"displayName":"越权","role":"ADMIN","version":0}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        authenticatedPatch("/api/v1/me", token, "{\"version\":0}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        authenticatedPatch("/api/v1/me", token, """
            {"displayName":"第一次","version":0}
            """).andExpect(status().isOk());
        authenticatedPatch("/api/v1/me", token, """
            {"displayName":"过期写入","version":0}
            """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT display_name FROM sys_user WHERE id=4000", String.class))
            .isEqualTo("第一次");
    }

    @Test
    void patchRejectsAvatarOwnedByAnotherUserWrongPurposeOrNotReady() throws Exception {
        String token = issue(4000, 5000, "MINI", List.of("USER"));
        for (long fileId : List.of(7001L, 7002L, 7003L)) {
            authenticatedPatch("/api/v1/me", token,
                "{\"avatarFileId\":\"" + fileId + "\",\"version\":0}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AVATAR"));
        }
        assertThat(jdbc.queryForObject("SELECT version FROM sys_user WHERE id=4000", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT avatar_file_id FROM sys_user WHERE id=4000", Long.class)).isNull();
    }

    @Test
    void webMenusReturnOnlyAssignedActiveMenuTreeAndMiniAudienceIsForbidden() throws Exception {
        String web = issue(4001, 5001, "WEB", List.of("DOCTOR"));
        authenticatedGet("/api/v1/me/menus", web)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value("6000"))
            .andExpect(jsonPath("$.data[0].children.length()").value(1))
            .andExpect(jsonPath("$.data[0].children[0].id").value("6001"))
            .andExpect(jsonPath("$.data[?(@.id == '6002')]").isEmpty())
            .andExpect(jsonPath("$.data[?(@.id == '6004')]").isEmpty());

        String mini = issue(4000, 5000, "MINI", List.of("USER"));
        authenticatedGet("/api/v1/me/menus", mini)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private org.springframework.test.web.servlet.ResultActions authenticatedGet(String path, String token)
        throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions authenticatedPatch(
        String path,
        String token,
        String body
    ) throws Exception {
        return mockMvc.perform(patch(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private String issue(long userId, long sessionId, String audience, List<String> roles) {
        return tokenIssuer.issue(userId, sessionId, 0, audience, roles, Instant.now(), 1800).value();
    }

    private void seedUser(long id, String username, String clientType, long sessionId, String roleCode) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, id, username, username);
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

    private void seedFiles() {
        seedFile(7000, 4000, "AVATAR", "READY");
        seedFile(7001, 4002, "AVATAR", "READY");
        seedFile(7002, 4000, "AVATAR", "QUARANTINED");
        seedFile(7003, 4000, "PET", "READY");
    }

    private void seedFile(long id, long uploader, String purpose, String status) {
        jdbc.update("""
            INSERT INTO file_object(
                id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status
            ) VALUES (?,?,?,?,?,1024,?,?,?)
            """,
            id,
            uploader,
            "m0305/" + id,
            "avatar-" + id + ".png",
            "image/png",
            "%064d".formatted(id),
            purpose,
            status
        );
    }

    private void seedMenus() {
        seedMenu(6000, null, "工作台", "/dashboard", "profile:view", "MENU", 1, "ACTIVE");
        seedMenu(6001, 6000L, "预约", "/appointments", "appointment:view", "MENU", 1, "ACTIVE");
        seedMenu(6002, 6000L, "确认按钮", null, "appointment:confirm", "BUTTON", 2, "ACTIVE");
        seedMenu(6003, null, "未授权", "/hidden", "hidden:view", "MENU", 3, "ACTIVE");
        seedMenu(6004, null, "已停用", "/disabled", "disabled:view", "MENU", 4, "DISABLED");
        Long doctorRole = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='DOCTOR'", Long.class);
        Long userRole = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='USER'", Long.class);
        for (long menuId : List.of(6000L, 6001L, 6002L, 6004L)) {
            jdbc.update(
                "INSERT INTO sys_role_menu(id,role_id,menu_id) VALUES (?,?,?)",
                20_000 + menuId,
                doctorRole,
                menuId
            );
        }
        jdbc.update(
            "INSERT INTO sys_role_menu(id,role_id,menu_id) VALUES (?,?,?)",
            30_000,
            userRole,
            6000
        );
    }

    private void seedMenu(
        long id,
        Long parentId,
        String name,
        String routePath,
        String permissionCode,
        String kind,
        int sortNo,
        String status
    ) {
        jdbc.update("""
            INSERT INTO sys_menu(id,parent_id,name,kind,route_path,permission_code,sort_no,status,version)
            VALUES (?,?,?,?,?,?,?,?,0)
            """, id, parentId, name, kind, routePath, permissionCode, sortNo, status);
    }
}
