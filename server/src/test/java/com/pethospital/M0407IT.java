package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag(TestTags.MYSQL_IT)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true"
})
class M0407IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long PLAIN_ADMIN = 40_701;
    private static final long AUDITOR = 40_702;
    private static final long OPS = 40_703;
    private static final long FULL = 40_704;
    private static final long AUDIT_ROLE = 40_711;
    private static final long OPS_ROLE = 40_712;
    private static final long CONTEXT_ROLE = 40_713;

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

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        cleanup();
        seedUsersAndPermissions();
        seedLogs();
    }

    @Test
    void ordinaryAdminWithoutDedicatedPermissionsIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs").with(admin(PLAIN_ADMIN)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/admin/ai/call-logs").with(admin(PLAIN_ADMIN)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(countAudit("actor_user_id=" + PLAIN_ADMIN)).isZero();
    }

    @Test
    void auditQueryFiltersPagesAndNeverReturnsStoredSnapshotsOrKeys() throws Exception {
        String response = mockMvc.perform(get("/api/v1/admin/audit-logs")
                .queryParam("actorUserId", Long.toString(AUDITOR))
                .queryParam("resourceType", "PET")
                .queryParam("action", "PET_UPDATE")
                .queryParam("page", "1")
                .queryParam("pageSize", "1")
                .with(admin(AUDITOR)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].action").value("PET_UPDATE"))
            .andExpect(jsonPath("$.data.items[0].resourceType").value("PET"))
            .andExpect(jsonPath("$.data.items[0].operationKey").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].requestHash").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].beforeJson").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].afterJson").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("plain-password-marker", "operation-secret-marker");
        assertThat(countAudit("actor_user_id=" + AUDITOR + " AND action='ADMIN_AUDIT_LOG_READ'"))
            .isEqualTo(1);
    }

    @Test
    void aiMetadataRequiresOwnPermissionAndContextRequiresAdditionalPermission() throws Exception {
        String metadata = mockMvc.perform(get("/api/v1/admin/ai/call-logs")
                .queryParam("status", "COMPLETED")
                .queryParam("model", "deepseek-flash")
                .with(admin(OPS)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].provider").value("deepseek"))
            .andExpect(jsonPath("$.data.items[0].promptTokens").value(12))
            .andExpect(jsonPath("$.data.items[0].inputSnapshot").isEmpty())
            .andExpect(jsonPath("$.data.items[0].providerRequestId").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        assertThat(metadata).doesNotContain("snapshot-marker", "provider-secret-request");

        mockMvc.perform(get("/api/v1/admin/ai/call-logs")
                .queryParam("includeContext", "true")
                .with(admin(OPS)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(countAudit("actor_user_id=" + OPS + " AND action='ADMIN_AI_CALL_LOG_READ'"))
            .isEqualTo(1);
        assertThat(countAudit("actor_user_id=" + OPS + " AND action='ADMIN_AI_CALL_CONTEXT_READ'"))
            .isZero();
    }

    @Test
    void authorizedContextReadIsAuditedWithoutCopyingContextIntoAudit() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai/call-logs")
                .queryParam("includeContext", "true")
                .queryParam("callKind", "GENERATION")
                .with(admin(FULL)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].inputSnapshot.species").value("CAT"))
            .andExpect(jsonPath("$.data.items[0].inputSnapshot.marker").value("snapshot-marker"));

        String storedAudit = jdbc.queryForObject("""
            SELECT CAST(after_json AS CHAR) FROM audit_log
            WHERE actor_user_id=? AND action='ADMIN_AI_CALL_CONTEXT_READ'
            """, String.class, FULL);
        assertThat(storedAudit).contains("includeContext").doesNotContain("snapshot-marker");
    }

    @Test
    void timeWindowIsCappedReadsDoNotRecurseAndNoMutationRoutesExist() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                .queryParam("from", "2026-01-01T00:00:00+08:00")
                .queryParam("to", "2026-03-01T00:00:00+08:00")
                .with(admin(AUDITOR)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(countAudit("actor_user_id=" + AUDITOR + " AND action='ADMIN_AUDIT_LOG_READ'"))
            .isZero();

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                .queryParam("action", "ADMIN_AUDIT_LOG_READ")
                .with(admin(AUDITOR)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("0"));
        assertThat(countAudit("actor_user_id=" + AUDITOR + " AND action='ADMIN_AUDIT_LOG_READ'"))
            .isEqualTo(1);

        mockMvc.perform(delete("/api/v1/admin/audit-logs").with(admin(AUDITOR)))
            .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/api/v1/admin/ai/call-logs").with(admin(OPS)))
            .andExpect(status().isMethodNotAllowed());
    }

    private void seedUsersAndPermissions() {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,'m0407-plain',NULL,'普通管理员','ACTIVE',0,0),
                   (?,'m0407-auditor',NULL,'审计管理员','ACTIVE',0,0),
                   (?,'m0407-ops',NULL,'模型运维','ACTIVE',0,0),
                   (?,'m0407-full',NULL,'模型审计','ACTIVE',0,0)
            """, PLAIN_ADMIN, AUDITOR, OPS, FULL);
        jdbc.update("""
            INSERT INTO sys_role(id,code,name,status,version)
            VALUES (?,'M0407_AUDITOR','审计读取','ACTIVE',0),
                   (?,'M0407_OPS','模型运维读取','ACTIVE',0),
                   (?,'M0407_CONTEXT','模型上下文读取','ACTIVE',0)
            """, AUDIT_ROLE, OPS_ROLE, CONTEXT_ROLE);
        jdbc.batchUpdate("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)", List.of(
            new Object[]{40_721L, PLAIN_ADMIN, -1103L},
            new Object[]{40_722L, AUDITOR, -1103L},
            new Object[]{40_723L, OPS, -1103L},
            new Object[]{40_724L, FULL, -1103L},
            new Object[]{40_725L, AUDITOR, AUDIT_ROLE},
            new Object[]{40_726L, OPS, OPS_ROLE},
            new Object[]{40_727L, FULL, OPS_ROLE},
            new Object[]{40_728L, FULL, CONTEXT_ROLE}
        ));
        jdbc.update("""
            INSERT INTO sys_menu(id,parent_id,name,kind,route_path,permission_code,sort_no,status,version)
            VALUES (40731,NULL,'审计读取','BUTTON',NULL,'audit:read',1,'ACTIVE',0),
                   (40732,NULL,'模型调用日志','BUTTON',NULL,'ai:call-logs:read',2,'ACTIVE',0),
                   (40733,NULL,'模型上下文审计','BUTTON',NULL,'ai-consult:audit',3,'ACTIVE',0)
            """);
        jdbc.update("""
            INSERT INTO sys_role_menu(id,role_id,menu_id)
            VALUES (40741,?,40731),(40742,?,40732),(40743,?,40733)
            """, AUDIT_ROLE, OPS_ROLE, CONTEXT_ROLE);
    }

    private void seedLogs() {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO audit_log(
              id,actor_user_id,actor_type,action,resource_type,resource_id,before_json,after_json,
              reason,trace_id,operation_key,request_hash,result,ip_masked,created_at
            ) VALUES (40751,?,'ADMIN','PET_UPDATE','PET',40761,NULL,
              CAST('{"password":"plain-password-marker"}' AS JSON),NULL,'trace-m0407-a',
              'operation-secret-marker',REPEAT('a',64),'SUCCESS','10.0.*.*',?)
            """, AUDITOR, Timestamp.from(now.minusSeconds(60)));
        jdbc.update("""
            INSERT INTO ai_call_log(
              id,session_id,user_message_id,call_kind,business_ref,generation_no,attempt_no,
              provider,model,provider_request_id,status,prompt_version,safety_version,input_snapshot_json,
              prompt_tokens,completion_tokens,cached_tokens,latency_ms,started_at,finished_at,error_code,created_at
            ) VALUES (40771,NULL,NULL,'GENERATION','message:40781',0,1,'deepseek','deepseek-flash',
              'provider-secret-request','COMPLETED','p1','s1',
              CAST('{"species":"CAT","marker":"snapshot-marker","symptomCodes":["APPETITE_DOWN"]}' AS JSON),
              12,4,0,350,?,?,NULL,?)
            """, Timestamp.from(now.minusSeconds(20)), Timestamp.from(now.minusSeconds(19)),
            Timestamp.from(now.minusSeconds(20)));
    }

    private int countAudit(String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE " + predicate, Integer.class);
    }

    private void cleanup() {
        if (!tableReady()) return;
        jdbc.update("DELETE FROM ai_call_log WHERE id BETWEEN 40700 AND 40799");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id BETWEEN 40700 AND 40799");
        jdbc.update("DELETE FROM sys_role_menu WHERE id BETWEEN 40700 AND 40799");
        jdbc.update("DELETE FROM sys_menu WHERE id BETWEEN 40700 AND 40799");
        jdbc.update("DELETE FROM sys_user_role WHERE id BETWEEN 40700 AND 40799");
        jdbc.update("DELETE FROM sys_user WHERE id BETWEEN 40700 AND 40799");
        jdbc.update("DELETE FROM sys_role WHERE id BETWEEN 40700 AND 40799");
    }

    private boolean tableReady() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_log", Integer.class);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
        admin(long userId) {
        return jwt()
            .jwt(builder -> builder
                .claim("userId", Long.toString(userId))
                .claim("sessionId", "40701")
                .claim("roles", List.of("ADMIN"))
                .audience(List.of("WEB")))
            .authorities(
                new SimpleGrantedAuthority("AUD_WEB"),
                new SimpleGrantedAuthority("ROLE_ADMIN")
            );
    }
}
