package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pethospital.persistence.CrossTableRelationGuard;
import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import com.pethospital.shared.BusinessException;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@Tag(TestTags.MYSQL_IT)
class M0207IT {
    private static final Set<String> EXPECTED_TABLES = Set.of(
        "sys_user", "sys_role", "sys_user_role", "sys_menu", "sys_role_menu", "auth_session",
        "doctor_profile", "file_object", "file_binding", "audit_log", "user_notice", "consent_record",
        "pet", "pet_health_log", "doctor_slot", "appointment", "appointment_status_log",
        "appointment_change_request", "medical_record", "medical_record_version",
        "ai_session", "ai_message", "ai_call_log", "ai_reference", "triage_rule", "triage_event",
        "knowledge_document", "knowledge_version", "knowledge_chunk", "knowledge_index_job",
        "health_guide", "health_standard", "guide_view_event"
    );

    private static final List<IndexExpectation> MAIN_QUERY_INDEXES = List.of(
        index("sys_user", "uk_sys_user_username", "username"),
        index("sys_role", "uk_sys_role_code", "code"),
        index("sys_menu", "ix_sys_menu_parent_sort", "parent_id", "sort_no"),
        index("sys_user_role", "uk_sys_user_role", "user_id", "role_id"),
        index("sys_role_menu", "uk_sys_role_menu", "role_id", "menu_id"),
        index("auth_session", "ix_auth_session_user_revoked", "user_id", "revoked_at"),
        index("doctor_profile", "ix_doctor_profile_status", "status"),
        index("file_object", "ix_file_object_uploader_created", "uploader_user_id", "created_at"),
        index("file_binding", "ix_file_binding_business", "business_type", "business_id"),
        index("audit_log", "ix_audit_resource_created", "resource_type", "resource_id", "created_at"),
        index("user_notice", "ix_user_notice_unread_created", "recipient_user_id", "read_at", "created_at"),
        index("consent_record", "ix_consent_user_purpose_created", "user_id", "purpose", "created_at", "id"),
        index("pet", "ix_pet_owner_status", "owner_user_id", "status"),
        index("pet_health_log", "ix_pet_health_log_pet_observed", "pet_id", "observed_at", "id"),
        index("doctor_slot", "ix_doctor_slot_status_start", "doctor_id", "status", "start_at"),
        index("appointment", "ix_appointment_user_created", "user_id", "created_at", "id"),
        index("appointment", "ix_appointment_doctor_status_start", "doctor_id", "status", "scheduled_start_at"),
        index("appointment", "ix_appointment_pet_status_start", "pet_id", "status", "scheduled_start_at"),
        index("appointment_status_log", "ix_appointment_status_log_created", "appointment_id", "created_at", "id"),
        index("appointment_change_request", "uk_change_request_open_guard", "appointment_id", "open_guard"),
        index("appointment_change_request", "ix_change_request_status_expires", "status", "expires_at"),
        index("medical_record", "uk_medical_record_appointment", "appointment_id"),
        index("medical_record", "ix_medical_record_pet_submitted", "pet_id", "submitted_at"),
        index("medical_record", "ix_medical_record_doctor_submitted", "doctor_id", "submitted_at"),
        index("medical_record_version", "uk_medical_record_version_revision", "record_id", "revision_no"),
        index("ai_session", "ix_ai_session_user_last_message", "user_id", "last_message_at", "id"),
        index("ai_message", "uk_ai_message_session_seq", "session_id", "seq_no"),
        index("ai_call_log", "ix_ai_call_status_started", "status", "started_at"),
        index("ai_reference", "uk_ai_reference_message_rank", "assistant_message_id", "rank_no"),
        index("triage_rule", "ix_triage_rule_enabled_review_species", "enabled", "review_status", "species_code"),
        index("triage_event", "ix_triage_event_message_generation", "user_message_id", "generation_no"),
        index("knowledge_document", "ix_knowledge_document_status_species_category", "status", "species_code", "category_code"),
        index("knowledge_version", "uk_knowledge_version_number", "document_id", "version_no"),
        index("knowledge_chunk", "uk_knowledge_chunk_ordinal", "knowledge_version_id", "ordinal"),
        index("knowledge_index_job", "ix_knowledge_index_status_lease", "status", "lease_until"),
        index("health_guide", "ix_health_guide_status_published", "status", "published_at"),
        index("health_standard", "ix_health_standard_status_species_metric", "status", "species_code", "metric_code"),
        index("guide_view_event", "ix_guide_view_date_guide", "view_date", "guide_id")
    );

    private static MySqlTestContainer mysql;
    private static DataSource dataSource;
    private static CrossTableRelationGuard guard;

    @BeforeAll
    static void migrateAndSeedIsolatedMySql() throws Exception {
        mysql = MySqlTestContainer.start();
        dataSource = mysql.dataSource();
        Flyway.configure()
            .dataSource(mysql.jdbcUrl(), mysql.username(), mysql.password())
            .locations("classpath:db/migration")
            .validateOnMigrate(true)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .outOfOrder(false)
            .target(MigrationVersion.fromVersion("5"))
            .load()
            .migrate();
        guard = new CrossTableRelationGuard(dataSource);
        seedRelations();
    }

    @AfterAll
    static void stopIsolatedMySql() {
        if (mysql != null) {
            mysql.close();
        }
    }

    @Test
    void acceptsConsistentOwnershipAndAllEightFileTargetTypes() {
        assertThatCode(() -> {
            guard.requireHealthLogAuthor(1000, 100);
            guard.requireAppointmentRelations(100, 1000, 2000, 2100);
            guard.requireMedicalRecordRelations(3000, 1000, 2000);
            guard.requireAiSessionRelations(100, 1000);
            guard.requireKnowledgeVersionBelongsToDocument(5000, 5100);
            guard.requireFileBindingTarget(FileBusinessType.PET, 1000, 100);
            guard.requireFileBindingTarget(FileBusinessType.HEALTH_LOG, 1100, 100);
            guard.requireFileBindingTarget(FileBusinessType.MEDICAL_DRAFT, 4000, 100);
            guard.requireFileBindingTarget(FileBusinessType.MEDICAL_VERSION, 4100, 100);
            guard.requireFileBindingTarget(FileBusinessType.KNOWLEDGE_VERSION, 5100, 300);
            guard.requireFileBindingTarget(FileBusinessType.GUIDE, 7000, 300);
            guard.requireFileBindingTarget(FileBusinessType.QUALIFICATION, 2000, 200);
            guard.requireFileBindingTarget(FileBusinessType.USER_AVATAR, 100, 100);
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsCrossUserCrossDoctorAndCrossRecordRelationships() {
        assertRelationMismatch(() -> guard.requireHealthLogAuthor(1000, 101));
        assertRelationMismatch(() -> guard.requireAppointmentRelations(101, 1000, 2000, 2100));
        assertRelationMismatch(() -> guard.requireAppointmentRelations(100, 1000, 2001, 2100));
        assertRelationMismatch(() -> guard.requireAppointmentRelations(100, 1000, 200, 2100));
        assertRelationMismatch(() -> guard.requireMedicalRecordRelations(3000, 1001, 2000));
        assertRelationMismatch(() -> guard.requireMedicalRecordRelations(3000, 1000, 2001));
        assertRelationMismatch(() -> guard.requireAiSessionRelations(101, 1000));
    }

    @Test
    void rejectsCrossDocumentVersionAndInvalidPolymorphicAttachmentTargets() {
        assertRelationMismatch(() -> guard.requireKnowledgeVersionBelongsToDocument(5000, 5101));
        assertRelationMismatch(() -> guard.requireFileBindingTarget(FileBusinessType.PET, 1000, 101));
        assertRelationMismatch(() -> guard.requireFileBindingTarget(FileBusinessType.MEDICAL_VERSION, 4100, 101));
        assertRelationMismatch(() -> guard.requireFileBindingTarget(FileBusinessType.KNOWLEDGE_VERSION, 5101, 300));
        assertRelationMismatch(() -> guard.requireFileBindingTarget(FileBusinessType.GUIDE, 7000, 100));
        assertRelationMismatch(() -> guard.requireFileBindingTarget(FileBusinessType.GUIDE, 1000, 100));
        assertRelationMismatch(() -> guard.requireFileBindingTarget(FileBusinessType.USER_AVATAR, 100, 101));
    }

    @Test
    void keepsBaseForeignKeysRestrictiveForMissingResources() {
        assertSqlRejected("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,chief_complaint,confirm_deadline_at,idempotency_key,request_hash
            ) VALUES (
                3999,'APT-MISSING-PET',100,999999,2000,2100,
                '2035-01-01 09:00:00.000','2035-01-01 09:30:00.000','2035-01-01 09:00:00.000',
                '非法宠物','2034-12-31 09:00:00.000','missing-pet',REPEAT('f',64)
            )
            """, "fk_appointment_pet");
        assertSqlRejected("""
            INSERT INTO file_binding(id,file_id,business_type,business_id,owner_user_id,created_by)
            VALUES (6999,999999,'PET',1000,100,100)
            """, "fk_file_binding_file");
    }

    @Test
    void mapsAllThirtyThreeTablesToMainQueryIndexesWithExactLeftPrefixes() throws Exception {
        assertThat(MAIN_QUERY_INDEXES.stream().map(IndexExpectation::table).collect(Collectors.toSet()))
            .containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
        for (IndexExpectation expectation : MAIN_QUERY_INDEXES) {
            assertThat(indexColumns(expectation.table(), expectation.indexName()))
                .as(expectation.table() + "." + expectation.indexName())
                .startsWith(expectation.columns().toArray(String[]::new));
        }
    }

    private static void seedRelations() throws Exception {
        executeSql("""
            INSERT INTO sys_user(id,username,display_name) VALUES
            (100,'owner-a','主人甲'),(101,'owner-b','主人乙'),
            (200,'doctor-a','医生甲'),(201,'doctor-b','医生乙'),(300,'editor','内容编辑')
            """);
        executeSql("""
            INSERT INTO doctor_profile(id,user_id,doctor_name) VALUES
            (2000,200,'医生甲'),(2001,201,'医生乙')
            """);
        executeSql("""
            INSERT INTO pet(id,owner_user_id,name,species_code) VALUES
            (1000,100,'宠物甲','CAT'),(1001,101,'宠物乙','DOG')
            """);
        executeSql("""
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at,symptom_note) VALUES
            (1100,1000,100,'2035-01-01 08:00:00.000','精神正常'),
            (1101,1001,101,'2035-01-01 08:00:00.000','精神正常')
            """);
        executeSql("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at) VALUES
            (2100,2000,'2035-01-01','2035-01-01 09:00:00.000','2035-01-01 09:30:00.000'),
            (2101,2001,'2035-01-01','2035-01-01 10:00:00.000','2035-01-01 10:30:00.000')
            """);
        executeSql(appointmentSql(3000, "APT-3000", 100, 1000, 2000, 2100, "09:00", "09:30"));
        executeSql(appointmentSql(3001, "APT-3001", 101, 1001, 2001, 2101, "10:00", "10:30"));
        executeSql("""
            INSERT INTO medical_record(
                id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at
            ) VALUES
            (4000,3000,1000,2000,'SUBMITTED',JSON_OBJECT('diagnosis','健康'),1,'2035-01-01 09:30:00.000'),
            (4001,3001,1001,2001,'SUBMITTED',JSON_OBJECT('diagnosis','健康'),1,'2035-01-01 10:30:00.000')
            """);
        executeSql("""
            INSERT INTO medical_record_version(
                id,record_id,revision_no,before_content_json,after_content_json,reason,actor_user_id,idempotency_key
            ) VALUES
            (4100,4000,1,NULL,JSON_OBJECT('diagnosis','健康'),'首次提交',200,'record-4000-v1'),
            (4101,4001,1,NULL,JSON_OBJECT('diagnosis','健康'),'首次提交',201,'record-4001-v1')
            """);
        executeSql("""
            INSERT INTO knowledge_document(id,title,species_code,category_code,creator_user_id) VALUES
            (5000,'文档甲','CAT','GENERAL',300),(5001,'文档乙','DOG','GENERAL',101)
            """);
        executeSql("""
            INSERT INTO knowledge_version(
                id,document_id,version_no,content,source_title,source_url,content_hash
            ) VALUES
            (5100,5000,1,'文档甲正文','来源甲','https://example.test/a',REPEAT('a',64)),
            (5101,5001,1,'文档乙正文','来源乙','https://example.test/b',REPEAT('b',64))
            """);
        executeSql("""
            INSERT INTO health_guide(
                id,title,species_code,category_code,body,source_note,editor_user_id
            ) VALUES (7000,'护理指南','CAT','GENERAL','指南正文','专业资料',300)
            """);
        executeSql("""
            INSERT INTO file_object(
                id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status
            ) VALUES (6000,100,'m02-07/file','file.txt','text/plain',4,REPEAT('c',64),'PET','READY')
            """);
    }

    private static String appointmentSql(
        long id,
        String appointmentNo,
        long userId,
        long petId,
        long doctorId,
        long slotId,
        String start,
        String end
    ) {
        return """
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,chief_complaint,confirm_deadline_at,idempotency_key,request_hash
            ) VALUES (
                %1$d,'%2$s',%3$d,%4$d,%5$d,%6$d,
                '2035-01-01 %7$s:00.000','2035-01-01 %8$s:00.000','2035-01-01 %7$s:00.000',
                '健康检查','2034-12-31 09:00:00.000','create-%1$d',REPEAT('d',64)
            )
            """.formatted(id, appointmentNo, userId, petId, doctorId, slotId, start, end);
    }

    private static void assertRelationMismatch(ThrowingRunnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.code).isEqualTo("RELATION_MISMATCH");
                assertThat(exception.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            });
    }

    private static void assertSqlRejected(String sql, String constraintName) {
        assertThatThrownBy(() -> executeSql(sql))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining(constraintName);
    }

    private static List<String> indexColumns(String table, String indexName) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT column_name
                 FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                 ORDER BY seq_in_index
                 """)) {
            statement.setString(1, table);
            statement.setString(2, indexName);
            try (ResultSet result = statement.executeQuery()) {
                List<String> columns = new java.util.ArrayList<>();
                while (result.next()) {
                    columns.add(result.getString(1));
                }
                return columns;
            }
        }
    }

    private static void executeSql(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static IndexExpectation index(String table, String indexName, String... columns) {
        return new IndexExpectation(table, indexName, List.of(columns));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record IndexExpectation(String table, String indexName, List<String> columns) {
    }
}
