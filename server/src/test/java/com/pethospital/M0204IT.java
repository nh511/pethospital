package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(TestTags.MYSQL_IT)
class M0204IT {
    private static final String DISCLAIMER = "AI 生成可能有误，请以线下诊断为主";
    private static final Set<String> EXPECTED_TABLES = Set.of(
        "sys_user", "sys_role", "sys_user_role", "sys_menu", "sys_role_menu", "auth_session",
        "doctor_profile", "file_object", "file_binding", "audit_log", "user_notice", "consent_record",
        "pet", "pet_health_log", "doctor_slot", "appointment", "appointment_status_log",
        "appointment_change_request", "medical_record", "medical_record_version",
        "ai_session", "ai_message", "ai_call_log", "ai_reference", "triage_rule", "triage_event",
        "knowledge_document", "knowledge_version", "knowledge_chunk", "knowledge_index_job",
        "health_guide", "health_standard", "guide_view_event"
    );
    private static final Set<String> NEW_TABLES = Set.of(
        "ai_session", "ai_message", "ai_call_log", "ai_reference", "triage_rule", "triage_event",
        "knowledge_document", "knowledge_version", "knowledge_chunk", "knowledge_index_job",
        "health_guide", "health_standard", "guide_view_event"
    );
    private static final Set<String> MUTABLE_TABLES = Set.of(
        "ai_session", "ai_message", "ai_call_log", "triage_rule", "knowledge_document",
        "knowledge_version", "knowledge_index_job", "health_guide", "health_standard"
    );
    private static final Set<String> VERSIONED_TABLES = Set.of(
        "ai_session", "ai_message", "triage_rule", "knowledge_document", "knowledge_version",
        "health_guide", "health_standard"
    );
    private static final Set<String> IMMUTABLE_TABLES = Set.of(
        "ai_reference", "triage_event", "knowledge_chunk", "guide_view_event"
    );

    private static MySqlTestContainer mysql;
    private static DataSource dataSource;

    @BeforeAll
    static void migrateIsolatedMySql() {
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
    }

    @AfterAll
    static void stopIsolatedMySql() {
        if (mysql != null) {
            mysql.close();
        }
    }

    @Test
    void createsExactlyThirtyThreeBusinessTablesWithoutFakeVectorColumns() throws Exception {
        assertThat(queryStrings("""
            SELECT table_name FROM information_schema.tables
            WHERE table_schema=DATABASE()
              AND table_type='BASE TABLE'
              AND table_name <> 'flyway_schema_history'
            """)).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);

        for (String table : NEW_TABLES) {
            assertColumn(table, "id", "bigint", false);
            assertColumn(table, "created_at", "datetime", false);
        }
        for (String table : MUTABLE_TABLES) {
            assertColumn(table, "updated_at", "datetime", false);
        }
        for (String table : VERSIONED_TABLES) {
            assertColumn(table, "version", "int", false);
        }
        for (String table : IMMUTABLE_TABLES) {
            assertColumnMissing(table, "updated_at");
            assertColumnMissing(table, "version");
        }
        assertColumnMissing("ai_call_log", "version");
        assertColumnMissing("knowledge_index_job", "version");
        assertColumn("knowledge_index_job", "embedding_profile", "varchar", false);
        assertColumn("knowledge_index_job", "embedding_model", "varchar", false);

        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema=DATABASE() AND data_type='vector'
            """)).isZero();
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema=DATABASE()
              AND column_name IN ('vector','embedding','embedding_vector','vector_json','embedding_json')
            """)).isZero();
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema=DATABASE()
              AND table_name IN ('knowledge_chunk','knowledge_index_job')
              AND data_type IN ('binary','varbinary','tinyblob','blob','mediumblob','longblob')
            """)).isZero();
    }

    @Test
    void declaresAllDesignedUniqueKeysAndSixtySixRestrictiveForeignKeys() throws Exception {
        assertThat(queryStrings("""
            SELECT DISTINCT index_name FROM information_schema.statistics
            WHERE table_schema=DATABASE() AND non_unique=0 AND index_name <> 'PRIMARY'
            """)).contains(
                "uk_ai_message_session_seq", "uk_ai_message_client_message", "uk_ai_message_reply_to",
                "uk_ai_call_business_attempt", "uk_triage_rule_revision", "uk_knowledge_version_number",
                "uk_knowledge_chunk_ordinal", "uk_knowledge_index_active",
                "uk_knowledge_index_idempotency", "uk_ai_reference_message_rank", "uk_guide_view_daily"
            );
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.referential_constraints
            WHERE constraint_schema=DATABASE()
            """)).isEqualTo(66);
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.referential_constraints
            WHERE constraint_schema=DATABASE() AND delete_rule='RESTRICT' AND update_rule='RESTRICT'
            """)).isEqualTo(66);
        assertThat(queryStrings("""
            SELECT constraint_name FROM information_schema.referential_constraints
            WHERE constraint_schema=DATABASE()
            """)).contains("fk_appointment_ai_session", "fk_knowledge_document_active_version");
    }

    @Test
    void permitsOnlyOneFinalReplyPerUserMessageAndRequiresTheFixedDisclaimer() throws Exception {
        seedAiSession(1000);
        insertUserMessage(1000, 1000, 1, "client-one");
        insertAssistantMessage(1001, 1000, 2, 1000, DISCLAIMER);

        assertSqlRejected(assistantMessageSql(1002, 1000, 3, 1000, DISCLAIMER), "uk_ai_message_reply_to");
        insertUserMessage(1003, 1000, 4, "client-two");
        assertThat(queryLong("SELECT COUNT(*) FROM ai_message WHERE session_id=1000 AND reply_to_id IS NULL"))
            .isEqualTo(2);
        assertSqlRejected(assistantMessageSql(1004, 1000, 5, 1003, "仅供参考"),
            "ck_ai_message_role_fields");
    }

    @Test
    void enforcesOneActiveIndexJobWhileAllowingResolvedHistoryWithNullGuards() throws Exception {
        seedKnowledgeVersion(2000);
        insertIndexJob(2000, 2000, "profile-v1", "QUEUED", "1", "index-queued", 0, 1, null, null);

        assertSqlRejected(indexJobSql(
            2001, 2000, "profile-v1", "RUNNING", "1", "index-running", 0, 1,
            "'worker-one'", "'2035-01-01 00:00:00.000'"
        ), "uk_knowledge_index_active");
        insertIndexJob(2002, 2000, "profile-v1", "READY", "NULL", "index-ready", 1, 1, null, null);
        insertIndexJob(2003, 2000, "profile-v1", "FAILED", "NULL", "index-failed", 0, 1, null, null);
        assertThat(queryLong("SELECT COUNT(*) FROM knowledge_index_job WHERE knowledge_version_id=2000"))
            .isEqualTo(3);

        assertSqlRejected(indexJobSql(
            2004, 2000, "profile-invalid-queued", "QUEUED", "NULL", "index-invalid-queued",
            0, 1, null, null
        ), "ck_knowledge_index_guard");
        assertSqlRejected(indexJobSql(
            2005, 2000, "profile-invalid-ready", "READY", "1", "index-invalid-ready",
            1, 1, null, null
        ), "ck_knowledge_index_guard");
    }

    @Test
    void enforcesRiskReviewPublishingAndCrossModuleHistoryConstraints() throws Exception {
        seedAiSession(3000);
        insertUserMessage(3000, 3000, 1, "triage-user");
        executeSql("""
            INSERT INTO triage_rule(
                id,rule_code,revision_no,species_code,title,predicate_json,risk_level,
                fixed_advice,source_url,creator_user_id
            ) VALUES (
                3000,'BREATHING',1,'CAT','呼吸困难',JSON_OBJECT(),'EMERGENCY',
                '立即就医','https://example.test/rule',3000
            )
            """);
        assertSqlRejected("""
            INSERT INTO triage_event(
                id,user_message_id,source,rule_id,generation_no,symptom_codes_json,evidence_json,
                risk_level,final_risk_level,decision_json
            ) VALUES (3000,3000,'RULE',3000,0,JSON_ARRAY('BREATHING'),JSON_OBJECT(),
                'EMERGENCY','URGENT',JSON_OBJECT())
            """, "ck_triage_event_no_risk_downgrade");

        seedKnowledgeVersion(4000);
        assertSqlRejected("UPDATE knowledge_document SET status='PUBLISHED' WHERE id=4000",
            "ck_knowledge_document_publish");
        executeSql("""
            UPDATE knowledge_version
            SET review_status='APPROVED',reviewer_user_id=4000,reviewed_at='2035-01-01 00:00:00.000'
            WHERE id=4000
            """);
        executeSql("""
            UPDATE knowledge_document SET active_version_id=4000,status='PUBLISHED' WHERE id=4000
            """);
        executeSql("DELETE FROM knowledge_chunk WHERE id=4000");
        assertSqlRejected("DELETE FROM knowledge_version WHERE id=4000",
            "fk_knowledge_document_active_version");

        executeSql("INSERT INTO sys_user(id,username,display_name) VALUES (5000,'doctor-5000','标准医生')");
        executeSql("INSERT INTO doctor_profile(id,user_id,doctor_name) VALUES (5000,5000,'标准医生')");
        assertSqlRejected("""
            INSERT INTO health_standard(
                id,name,species_code,metric_code,reference_min,reference_max,description,
                conditions_note,source_note,editor_doctor_id
            ) VALUES (5000,'体温','CAT','TEMPERATURE',40.0000,38.0000,'仅作参考','成年猫','专业资料',5000)
            """, "ck_health_standard_reference");
    }

    private static void seedAiSession(long base) throws Exception {
        executeSql("""
            INSERT INTO sys_user(id,username,display_name) VALUES (%1$d,'ai-user-%1$d','AI用户')
            """.formatted(base));
        executeSql("""
            INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (%1$d,%1$d,'AI宠物','CAT')
            """.formatted(base));
        executeSql("""
            INSERT INTO ai_session(id,user_id,pet_id,title,pet_snapshot_json)
            VALUES (%1$d,%1$d,%1$d,'问诊会话',JSON_OBJECT('petId','%1$d'))
            """.formatted(base));
    }

    private static void insertUserMessage(long id, long sessionId, int seq, String clientMessageId) throws Exception {
        executeSql("""
            INSERT INTO ai_message(
                id,session_id,seq_no,role,content,source,processing_status,client_message_id,request_hash
            ) VALUES (%1$d,%2$d,%3$d,'USER','宠物精神不好','USER','COMPLETED','%4$s',REPEAT('a',64))
            """.formatted(id, sessionId, seq, clientMessageId));
    }

    private static void insertAssistantMessage(
        long id,
        long sessionId,
        int seq,
        long replyToId,
        String disclaimer
    ) throws Exception {
        executeSql(assistantMessageSql(id, sessionId, seq, replyToId, disclaimer));
    }

    private static String assistantMessageSql(
        long id,
        long sessionId,
        int seq,
        long replyToId,
        String disclaimer
    ) {
        return """
            INSERT INTO ai_message(
                id,session_id,seq_no,role,content,reply_to_id,source,processing_status,
                answer_json,risk_level,disclaimer
            ) VALUES (
                %1$d,%2$d,%3$d,'ASSISTANT','建议尽快观察并咨询兽医',%4$d,'MODEL','COMPLETED',
                JSON_OBJECT('riskLevel','ROUTINE'),'ROUTINE','%5$s'
            )
            """.formatted(id, sessionId, seq, replyToId, disclaimer);
    }

    private static void seedKnowledgeVersion(long base) throws Exception {
        executeSql("""
            INSERT INTO sys_user(id,username,display_name) VALUES (%1$d,'knowledge-user-%1$d','知识管理员')
            """.formatted(base));
        executeSql("""
            INSERT INTO knowledge_document(id,title,species_code,category_code,creator_user_id)
            VALUES (%1$d,'猫咪健康知识','CAT','GENERAL',%1$d)
            """.formatted(base));
        executeSql("""
            INSERT INTO knowledge_version(
                id,document_id,version_no,content,source_title,source_url,content_hash
            ) VALUES (
                %1$d,%1$d,1,'知识正文','来源标题','https://example.test/source',REPEAT('b',64)
            )
            """.formatted(base));
        executeSql("""
            INSERT INTO knowledge_chunk(
                id,knowledge_version_id,ordinal,content,content_hash,token_estimate,splitter_version
            ) VALUES (%1$d,%1$d,0,'知识片段',REPEAT('c',64),10,'splitter-v1')
            """.formatted(base));
    }

    private static void insertIndexJob(
        long id,
        long knowledgeVersionId,
        String profile,
        String status,
        String guard,
        String key,
        int indexedChunks,
        int expectedChunks,
        String workerToken,
        String leaseUntil
    ) throws Exception {
        executeSql(indexJobSql(
            id, knowledgeVersionId, profile, status, guard, key,
            indexedChunks, expectedChunks, workerToken, leaseUntil
        ));
    }

    private static String indexJobSql(
        long id,
        long knowledgeVersionId,
        String profile,
        String status,
        String guard,
        String key,
        int indexedChunks,
        int expectedChunks,
        String workerToken,
        String leaseUntil
    ) {
        String token = workerToken == null ? "NULL" : workerToken;
        String lease = leaseUntil == null ? "NULL" : leaseUntil;
        return """
            INSERT INTO knowledge_index_job(
                id,knowledge_version_id,embedding_profile,embedding_model,dimensions,distance_metric,
                splitter_version,status,active_guard,indexed_chunks,expected_chunks,attempt_no,
                lease_until,worker_token,creator_user_id,idempotency_key
            ) VALUES (
                %1$d,%2$d,'%3$s','BAAI/bge-small-zh-v1.5',512,'COSINE',
                'splitter-v1','%4$s',%5$s,%6$d,%7$d,1,%8$s,%9$s,%2$d,'%10$s'
            )
            """.formatted(
                id, knowledgeVersionId, profile, status, guard, indexedChunks, expectedChunks,
                lease, token, key
            );
    }

    private static void assertColumn(String table, String column, String dataType, boolean nullable) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT data_type, is_nullable, datetime_precision
                 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                 """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as(table + "." + column + " must exist").isTrue();
                assertThat(result.getString("data_type")).isEqualTo(dataType);
                assertThat(result.getString("is_nullable")).isEqualTo(nullable ? "YES" : "NO");
                if ("datetime".equals(dataType)) {
                    assertThat(result.getInt("datetime_precision")).isEqualTo(3);
                }
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static void assertColumnMissing(String table, String column) throws Exception {
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='%s' AND column_name='%s'
            """.formatted(table, column))).isZero();
    }

    private static void assertSqlRejected(String sql, String constraintName) {
        assertThatThrownBy(() -> executeSql(sql))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining(constraintName);
    }

    private static void executeSql(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long queryLong(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static Set<String> queryStrings(String sql) throws Exception {
        Set<String> values = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
