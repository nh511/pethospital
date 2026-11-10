package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.FileBindingService;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true"
})
class M0404IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 40_401;
    private static final long OTHER = 40_402;
    private static final long DOCTOR_USER = 40_403;
    private static final long ADMIN = 40_404;
    private static final long DOCTOR = 41_403;
    private static final long PET = 42_401;
    private static final long HEALTH_LOG = 43_401;
    private static final long SLOT = 44_401;
    private static final long APPOINTMENT = 45_401;
    private static final long RECORD = 46_401;
    private static final long MEDICAL_VERSION = 47_401;
    private static final long KNOWLEDGE_DOCUMENT = 48_401;
    private static final long KNOWLEDGE_VERSION = 49_401;
    private static final long OWNER_HEALTH_ONE = 50_401;
    private static final long OWNER_HEALTH_TWO = 50_402;
    private static final long OTHER_HEALTH = 50_403;
    private static final long DOCTOR_MEDICAL = 50_404;
    private static final long DOCTOR_KNOWLEDGE = 50_405;
    private static final long DOCTOR_WRONG_PURPOSE = 50_406;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FileBindingService bindings;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping requestMappings;

    @BeforeEach
    void resetFixtures() {
        SecurityContextHolder.clearContext();
        cleanup();
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,'m0404-owner',NULL,'附件主人','ACTIVE',0,0),
                   (?,'m0404-other',NULL,'其他主人','ACTIVE',0,0),
                   (?,'m0404-doctor',NULL,'附件医生','ACTIVE',0,0),
                   (?,'m0404-admin',NULL,'附件管理员','ACTIVE',0,0)
            """, OWNER, OTHER, DOCTOR_USER, ADMIN);
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,'附件医生','ACTIVE',0)
            """, DOCTOR, DOCTOR_USER);
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,sex,neutered,status,version)
            VALUES (?,?,'附件宠物','CAT','UNKNOWN','UNKNOWN','ACTIVE',0)
            """, PET, OWNER);
        jdbc.update("""
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at,symptom_note,version)
            VALUES (?,?,?,CURRENT_TIMESTAMP(3),'附件绑定测试',0)
            """, HEALTH_LOG, PET, OWNER);
        jdbc.update("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version)
            VALUES (?,?,'2026-11-01','2026-11-01 09:00:00','2026-11-01 10:00:00',1,1,'CLOSED',0)
            """, SLOT, DOCTOR);
        jdbc.update("""
            INSERT INTO appointment(
              id,appointment_no,user_id,pet_id,doctor_id,slot_id,
              scheduled_start_at,scheduled_end_at,initial_start_at,status,chief_complaint,
              confirm_deadline_at,confirmed_at,idempotency_key,request_hash,version
            ) VALUES (?, 'M0404-APPOINTMENT', ?, ?, ?, ?,
              '2026-11-01 09:00:00','2026-11-01 10:00:00','2026-11-01 09:00:00',
              'CONFIRMED','附件绑定测试','2026-10-31 09:00:00','2026-10-31 08:00:00',
              'm0404-appointment-key','0000000000000000000000000000000000000000000000000000000000000000',0)
            """, APPOINTMENT, OWNER, PET, DOCTOR, SLOT);
        jdbc.update("""
            INSERT INTO medical_record(
              id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at,version
            ) VALUES (?,?,?,?,'SUBMITTED',JSON_OBJECT('summary','附件测试'),1,CURRENT_TIMESTAMP(3),0)
            """, RECORD, APPOINTMENT, PET, DOCTOR);
        jdbc.update("""
            INSERT INTO medical_record_version(
              id,record_id,revision_no,before_content_json,after_content_json,reason,
              actor_user_id,idempotency_key
            ) VALUES (?,?,1,NULL,JSON_OBJECT('summary','附件测试'),'初版',?,'m0404-medical-version')
            """, MEDICAL_VERSION, RECORD, DOCTOR_USER);
        jdbc.update("""
            INSERT INTO knowledge_document(
              id,title,species_code,category_code,status,creator_user_id,version
            ) VALUES (?,'附件知识','COMMON','GENERAL','DRAFT',?,0)
            """, KNOWLEDGE_DOCUMENT, DOCTOR_USER);
        jdbc.update("""
            INSERT INTO knowledge_version(
              id,document_id,version_no,content,source_title,source_url,content_hash,review_status,version
            ) VALUES (?,?,1,'附件知识正文','附件来源','https://example.invalid/source',
              '1111111111111111111111111111111111111111111111111111111111111111','DRAFT',0)
            """, KNOWLEDGE_VERSION, KNOWLEDGE_DOCUMENT);

        insertFile(OWNER_HEALTH_ONE, OWNER, "HEALTH_LOG", "READY");
        insertFile(OWNER_HEALTH_TWO, OWNER, "HEALTH_LOG", "READY");
        insertFile(OTHER_HEALTH, OTHER, "HEALTH_LOG", "READY");
        insertFile(DOCTOR_MEDICAL, DOCTOR_USER, "MEDICAL", "READY");
        insertFile(DOCTOR_KNOWLEDGE, DOCTOR_USER, "KNOWLEDGE", "READY");
        insertFile(DOCTOR_WRONG_PURPOSE, DOCTOR_USER, "MEDICAL", "READY");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @Test
    void mutableHealthLogReplacementUnbindsOldFileAndRejectsAnotherUsersFile() {
        authenticate(OWNER, "MINI", "USER");
        bindings.replaceMutable(FileBusinessType.HEALTH_LOG, HEALTH_LOG, List.of(OWNER_HEALTH_ONE));
        bindings.replaceMutable(FileBusinessType.HEALTH_LOG, HEALTH_LOG, List.of(OWNER_HEALTH_TWO));

        assertThat(boundFiles(FileBusinessType.HEALTH_LOG, HEALTH_LOG))
            .containsExactly(OWNER_HEALTH_TWO);
        assertThatThrownBy(() -> bindings.replaceMutable(
            FileBusinessType.HEALTH_LOG,
            HEALTH_LOG,
            List.of(OTHER_HEALTH)
        )).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("FILE_SOURCE_FORBIDDEN")
        );
        assertThat(boundFiles(FileBusinessType.HEALTH_LOG, HEALTH_LOG))
            .containsExactly(OWNER_HEALTH_TWO);
    }

    @Test
    void medicalAndKnowledgeVersionsAreAppendOnlyAndReplayDoesNotDuplicate() {
        authenticate(DOCTOR_USER, "WEB", "DOCTOR");
        bindings.appendImmutable(
            FileBusinessType.MEDICAL_VERSION,
            MEDICAL_VERSION,
            List.of(DOCTOR_MEDICAL)
        );
        bindings.appendImmutable(
            FileBusinessType.KNOWLEDGE_VERSION,
            KNOWLEDGE_VERSION,
            List.of(DOCTOR_KNOWLEDGE)
        );
        bindings.appendImmutable(
            FileBusinessType.KNOWLEDGE_VERSION,
            KNOWLEDGE_VERSION,
            List.of(DOCTOR_KNOWLEDGE)
        );

        assertThat(boundFiles(FileBusinessType.MEDICAL_VERSION, MEDICAL_VERSION))
            .containsExactly(DOCTOR_MEDICAL);
        assertThat(boundFiles(FileBusinessType.KNOWLEDGE_VERSION, KNOWLEDGE_VERSION))
            .containsExactly(DOCTOR_KNOWLEDGE);
        assertThatThrownBy(() -> bindings.replaceMutable(
            FileBusinessType.MEDICAL_VERSION,
            MEDICAL_VERSION,
            List.of()
        )).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("IMMUTABLE_FILE_BINDING")
        );
        assertThatThrownBy(() -> bindings.replaceMutable(
            FileBusinessType.KNOWLEDGE_VERSION,
            KNOWLEDGE_VERSION,
            List.of()
        )).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("IMMUTABLE_FILE_BINDING")
        );
    }

    @Test
    void targetRoleAndFilePurposeAreCheckedBeforeWriting() {
        authenticate(OTHER, "MINI", "USER");
        assertThatThrownBy(() -> bindings.replaceMutable(
            FileBusinessType.HEALTH_LOG,
            HEALTH_LOG,
            List.of(OTHER_HEALTH)
        )).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("FILE_BINDING_TARGET_FORBIDDEN")
        );

        authenticate(DOCTOR_USER, "WEB", "DOCTOR");
        assertThatThrownBy(() -> bindings.appendImmutable(
            FileBusinessType.KNOWLEDGE_VERSION,
            KNOWLEDGE_VERSION,
            List.of(DOCTOR_WRONG_PURPOSE)
        )).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("FILE_PURPOSE_MISMATCH")
        );
        assertThat(boundFiles(FileBusinessType.HEALTH_LOG, HEALTH_LOG)).isEmpty();
        assertThat(boundFiles(FileBusinessType.KNOWLEDGE_VERSION, KNOWLEDGE_VERSION)).isEmpty();
    }

    @Test
    void outerBusinessTransactionRollbackLeavesNoBinding() {
        authenticate(OWNER, "MINI", "USER");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            bindings.replaceMutable(
                FileBusinessType.HEALTH_LOG,
                HEALTH_LOG,
                List.of(OWNER_HEALTH_ONE)
            );
            throw new IllegalStateException("force business rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(boundFiles(FileBusinessType.HEALTH_LOG, HEALTH_LOG)).isEmpty();
    }

    @Test
    void applicationPublishesNoArbitraryFileBindingEndpoint() {
        boolean hasBindingEndpoint = requestMappings.getHandlerMethods().keySet().stream()
            .flatMap(info -> info.getPatternValues().stream())
            .anyMatch(path -> path.toLowerCase().contains("binding"));

        assertThat(hasBindingEndpoint).isFalse();
    }

    private void insertFile(long id, long uploader, String purpose, String status) {
        jdbc.update("""
            INSERT INTO file_object(
              id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status,validated_at
            ) VALUES (?,?,?,?,'image/png',10,
              '2222222222222222222222222222222222222222222222222222222222222222',?,?,CURRENT_TIMESTAMP(3))
            """, id, uploader, "m0404/" + id, "m0404-" + id + ".png", purpose, status);
    }

    private List<Long> boundFiles(FileBusinessType type, long businessId) {
        return jdbc.queryForList("""
            SELECT file_id FROM file_binding
            WHERE business_type=? AND business_id=? ORDER BY file_id
            """, Long.class, type.name(), businessId);
    }

    private void cleanup() {
        if (!tableReady()) {
            return;
        }
        jdbc.update("DELETE FROM file_binding WHERE business_id IN (?,?,?,?,?)",
            HEALTH_LOG, MEDICAL_VERSION, KNOWLEDGE_VERSION, PET, OWNER);
        jdbc.update("DELETE FROM knowledge_version WHERE id=?", KNOWLEDGE_VERSION);
        jdbc.update("DELETE FROM knowledge_document WHERE id=?", KNOWLEDGE_DOCUMENT);
        jdbc.update("DELETE FROM medical_record_version WHERE id=?", MEDICAL_VERSION);
        jdbc.update("DELETE FROM medical_record WHERE id=?", RECORD);
        jdbc.update("DELETE FROM appointment WHERE id=?", APPOINTMENT);
        jdbc.update("DELETE FROM doctor_slot WHERE id=?", SLOT);
        jdbc.update("DELETE FROM pet_health_log WHERE id=?", HEALTH_LOG);
        jdbc.update("DELETE FROM pet WHERE id=?", PET);
        jdbc.update("DELETE FROM file_object WHERE id BETWEEN ? AND ?",
            OWNER_HEALTH_ONE, DOCTOR_WRONG_PURPOSE);
        jdbc.update("DELETE FROM doctor_profile WHERE id=?", DOCTOR);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?,?,?,?)", OWNER, OTHER, DOCTOR_USER, ADMIN);
    }

    private boolean tableReady() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM file_binding", Integer.class);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void authenticate(long userId, String audience, String role) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("m0404-test-token")
            .header("alg", "RS256")
            .subject(Long.toString(userId))
            .claim("userId", Long.toString(userId))
            .claim("sessionId", "40401")
            .claim("roles", List.of(role))
            .audience(List.of(audience))
            .issuedAt(now.minusSeconds(1))
            .expiresAt(now.plusSeconds(300))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            jwt,
            List.of(
                new SimpleGrantedAuthority("AUD_" + audience),
                new SimpleGrantedAuthority("ROLE_" + role)
            )
        ));
    }
}
