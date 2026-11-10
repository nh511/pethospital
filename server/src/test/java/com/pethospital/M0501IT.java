package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.LocalDate;
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
    "identity.jwt.allow-ephemeral=true"
})
@AutoConfigureMockMvc
class M0501IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER_ONE = 50_001;
    private static final long OWNER_TWO = 50_002;
    private static final long SESSION_ONE = 51_001;
    private static final long SESSION_TWO = 51_002;
    private static final long AVATAR_FILE = 55_001;

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
        jdbc.update("DELETE FROM file_binding");
        jdbc.update("DELETE FROM pet_health_log");
        jdbc.update("DELETE FROM pet");
        jdbc.update("DELETE FROM file_object");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_session WHERE user_id>=50000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=50000");
        jdbc.update("DELETE FROM sys_user WHERE id>=50000");
        seedUser(OWNER_ONE, "pet-owner-one");
        seedUser(OWNER_TWO, "pet-owner-two");
        seedSession(SESSION_ONE, OWNER_ONE);
        seedSession(SESSION_TWO, OWNER_TWO);
        jdbc.update("""
            INSERT INTO file_object(
                id,uploader_user_id,object_key,original_name,mime_type,size_bytes,
                sha256,purpose,status,validated_at
            ) VALUES (?,?,?,?,?,?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'PET','READY',CURRENT_TIMESTAMP(3))
            """, AVATAR_FILE, OWNER_ONE, "m0501/pet-avatar.png", "pet-avatar.png", "image/png", 128);
    }

    @Test
    void createsOwnedPetWithStringIdAndBindsReadyAvatar() throws Exception {
        MvcResult result = create(OWNER_ONE, SESSION_ONE, "m0501-create-0001", """
            {
              "name":"  米米  ","speciesCode":"CAT","breed":"英短","sex":"FEMALE",
              "birthDate":"2024-05-20","neutered":"YES","avatarFileId":"55001",
              "ownerHealthNotes":"对陌生环境紧张"
            }
            """)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value("米米"))
            .andExpect(jsonPath("$.data.avatarFileId").value("55001"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        String petId = data.path("id").asText();
        assertThat(petId).matches("[1-9][0-9]*");
        assertThat(Long.parseLong(petId)).isGreaterThan(9_007_199_254_740_991L);
        assertThat(jdbc.queryForObject(
            "SELECT owner_user_id FROM pet WHERE id=?",
            Long.class,
            Long.parseLong(petId)
        )).isEqualTo(OWNER_ONE);
        assertThat(count("SELECT COUNT(*) FROM file_binding WHERE file_id=" + AVATAR_FILE
            + " AND business_type='PET' AND business_id=" + petId + " AND owner_user_id=" + OWNER_ONE))
            .isOne();
    }

    @Test
    void listIsOwnerScopedStatusFilteredAndDeterministicallyPaged() throws Exception {
        create(OWNER_ONE, SESSION_ONE, "m0501-list-00001", body("宠物甲"))
            .andExpect(status().isCreated());
        create(OWNER_ONE, SESSION_ONE, "m0501-list-00002", body("宠物乙"))
            .andExpect(status().isCreated());
        create(OWNER_ONE, SESSION_ONE, "m0501-list-00003", body("宠物丙"))
            .andExpect(status().isCreated());
        jdbc.update("UPDATE pet SET status='ARCHIVED' WHERE owner_user_id=? AND name='宠物甲'", OWNER_ONE);

        MvcResult first = list(OWNER_ONE, SESSION_ONE, "ACTIVE", 1, 1)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("2"))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString())
            .path("data").path("items").path(0).path("id").asText();
        MvcResult repeated = list(OWNER_ONE, SESSION_ONE, "ACTIVE", 1, 1)
            .andExpect(status().isOk())
            .andReturn();
        assertThat(objectMapper.readTree(repeated.getResponse().getContentAsString())
            .path("data").path("items").path(0).path("id").asText()).isEqualTo(firstId);
        list(OWNER_ONE, SESSION_ONE, "ARCHIVED", 1, 20)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("1"))
            .andExpect(jsonPath("$.data.items[0].name").value("宠物甲"));
        list(OWNER_TWO, SESSION_TWO, null, 1, 20)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("0"));
    }

    @Test
    void rejectsFutureBirthDateAndUnknownSpecies() throws Exception {
        create(OWNER_ONE, SESSION_ONE, "m0501-future-001", """
            {"name":"未来宠物","speciesCode":"CAT","sex":"UNKNOWN","birthDate":"%s","neutered":"UNKNOWN"}
            """.formatted(LocalDate.now().plusDays(1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"));
        create(OWNER_ONE, SESSION_ONE, "m0501-species-01", """
            {"name":"未知物种","speciesCode":"HUMAN","sex":"UNKNOWN","neutered":"UNKNOWN"}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(count("SELECT COUNT(*) FROM pet")).isZero();
    }

    @Test
    void rejectsForgedOwnerAndServerFields() throws Exception {
        create(OWNER_ONE, SESSION_ONE, "m0501-forged-001", """
            {
              "name":"越权宠物","speciesCode":"DOG","sex":"MALE","neutered":"NO",
              "ownerUserId":"50002","status":"ARCHIVED","version":99
            }
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(count("SELECT COUNT(*) FROM pet")).isZero();
    }

    @Test
    void idempotencyReplaysSamePetAndRejectsChangedPayload() throws Exception {
        String operationKey = "m0501-replay-0001";
        String firstId = objectMapper.readTree(create(
            OWNER_ONE, SESSION_ONE, operationKey, body("幂等宠物")
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
            .path("data").path("id").asText();
        String replayId = objectMapper.readTree(create(
            OWNER_ONE, SESSION_ONE, operationKey, body("幂等宠物")
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
            .path("data").path("id").asText();
        assertThat(replayId).isEqualTo(firstId);
        assertThat(count("SELECT COUNT(*) FROM pet WHERE owner_user_id=" + OWNER_ONE)).isOne();

        create(OWNER_ONE, SESSION_ONE, operationKey, body("不同宠物"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    private org.springframework.test.web.servlet.ResultActions create(
        long userId,
        long sessionId,
        String operationKey,
        String body
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/pets")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, sessionId))
            .header("Idempotency-Key", operationKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions list(
        long userId,
        long sessionId,
        String status,
        int page,
        int pageSize
    ) throws Exception {
        var request = get("/api/v1/pets")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, sessionId))
            .param("page", Integer.toString(page))
            .param("pageSize", Integer.toString(pageSize));
        if (status != null) {
            request.param("status", status);
        }
        return mockMvc.perform(request);
    }

    private String token(long userId, long sessionId) {
        return tokenIssuer.issue(
            userId,
            sessionId,
            0,
            "MINI",
            List.of("USER"),
            Instant.now(),
            1800
        ).value();
    }

    private void seedUser(long userId, String username) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, userId, username, username);
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='USER'", Long.class);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
            userId + 10_000, userId, roleId);
    }

    private void seedSession(long sessionId, long userId) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,'MINI',?,?,?, ?,?,?)
            """,
            sessionId,
            userId,
            "%064d".formatted(sessionId),
            "family-" + sessionId,
            Timestamp.from(now.plusSeconds(3600)),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static String body(String name) {
        return """
            {"name":"%s","speciesCode":"CAT","sex":"UNKNOWN","neutered":"UNKNOWN"}
            """.formatted(name);
    }
}
