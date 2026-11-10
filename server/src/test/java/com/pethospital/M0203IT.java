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
class M0203IT {
    private static final Set<String> EXPECTED_TABLES = Set.of(
        "sys_user", "sys_role", "sys_user_role", "sys_menu", "sys_role_menu", "auth_session",
        "doctor_profile", "file_object", "file_binding", "audit_log", "user_notice", "consent_record",
        "pet", "pet_health_log", "doctor_slot", "appointment", "appointment_status_log",
        "appointment_change_request", "medical_record", "medical_record_version"
    );
    private static final Set<String> NEW_TABLES = Set.of(
        "pet", "pet_health_log", "doctor_slot", "appointment", "appointment_status_log",
        "appointment_change_request", "medical_record", "medical_record_version"
    );
    private static final Set<String> MUTABLE_TABLES = Set.of(
        "pet", "pet_health_log", "doctor_slot", "appointment", "appointment_change_request", "medical_record"
    );
    private static final Set<String> IMMUTABLE_TABLES = Set.of(
        "appointment_status_log", "medical_record_version"
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
            .target(MigrationVersion.fromVersion("3"))
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
    void createsExactlyTwentyTablesThroughV3WithDesignedCommonColumns() throws Exception {
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
            assertColumn(table, "version", "int", false);
        }
        for (String table : IMMUTABLE_TABLES) {
            assertColumnMissing(table, "updated_at");
            assertColumnMissing(table, "version");
        }
        assertColumn("pet_health_log", "weight_kg", "decimal", true);
        assertColumn("medical_record", "content_json", "json", false);
        assertColumn("appointment", "ai_session_id", "bigint", true);
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.key_column_usage
            WHERE constraint_schema=DATABASE()
              AND table_name='appointment'
              AND column_name='ai_session_id'
              AND referenced_table_name IS NOT NULL
            """)).as("cross-module ai_session FK belongs to V5").isZero();
    }

    @Test
    void declaresDesignedUniqueKeysAndTwentyOneRestrictiveForeignKeys() throws Exception {
        assertThat(queryStrings("""
            SELECT DISTINCT index_name FROM information_schema.statistics
            WHERE table_schema=DATABASE() AND non_unique=0 AND index_name <> 'PRIMARY'
            """)).contains(
                "uk_doctor_slot_start", "uk_appointment_no", "uk_appointment_user_idempotency",
                "uk_appointment_status_operation", "uk_change_request_open_guard",
                "uk_change_request_idempotency", "uk_medical_record_appointment",
                "uk_medical_record_version_revision", "uk_medical_record_version_idempotency"
            );
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.referential_constraints
            WHERE constraint_schema=DATABASE()
              AND table_name IN (
                'pet','pet_health_log','doctor_slot','appointment','appointment_status_log',
                'appointment_change_request','medical_record','medical_record_version'
              )
            """)).isEqualTo(21);
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.referential_constraints
            WHERE constraint_schema=DATABASE()
              AND table_name IN (
                'pet','pet_health_log','doctor_slot','appointment','appointment_status_log',
                'appointment_change_request','medical_record','medical_record_version'
              )
              AND delete_rule='RESTRICT' AND update_rule='RESTRICT'
            """)).isEqualTo(21);
    }

    @Test
    void enforcesOneMedicalRecordAndUniqueImmutableRevisionsPerAppointment() throws Exception {
        seedAppointment(1000);
        executeSql("""
            INSERT INTO medical_record(
                id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at
            ) VALUES (1000,1000,1000,1000,'SUBMITTED',JSON_OBJECT('diagnosis','健康检查'),1,'2035-01-01 10:00:00.000')
            """);
        executeSql("""
            INSERT INTO medical_record_version(
                id,record_id,revision_no,before_content_json,after_content_json,reason,actor_user_id,idempotency_key
            ) VALUES (1000,1000,1,NULL,JSON_OBJECT('diagnosis','健康检查'),'首次提交',1001,'record-v1')
            """);

        assertSqlRejected("""
            INSERT INTO medical_record(
                id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at
            ) VALUES (1001,1000,1000,1000,'SUBMITTED',JSON_OBJECT(),1,'2035-01-01 10:01:00.000')
            """, "uk_medical_record_appointment");
        assertSqlRejected("""
            INSERT INTO medical_record_version(
                id,record_id,revision_no,before_content_json,after_content_json,reason,actor_user_id,idempotency_key
            ) VALUES (1001,1000,1,NULL,JSON_OBJECT(),'重复版本',1001,'record-v1-duplicate')
            """, "uk_medical_record_version_revision");
        assertSqlRejected("""
            INSERT INTO medical_record_version(
                id,record_id,revision_no,before_content_json,after_content_json,reason,actor_user_id,idempotency_key
            ) VALUES (1002,1000,2,JSON_OBJECT(),JSON_OBJECT(),'重复请求键',1001,'record-v1')
            """, "uk_medical_record_version_idempotency");
    }

    @Test
    void enforcesOneOpenChangeRequestWhileAllowingMultipleResolvedRows() throws Exception {
        seedAppointment(2000);
        insertChangeRequest(2000, 2000, "OPEN", "1", "change-open");

        assertSqlRejected(changeRequestSql(2001, 2000, "OPEN", "1", "change-open-two"),
            "uk_change_request_open_guard");
        insertChangeRequest(2002, 2000, "REJECTED", "NULL", "change-rejected-one");
        insertChangeRequest(2003, 2000, "WITHDRAWN", "NULL", "change-withdrawn-two");
        assertThat(queryLong("SELECT COUNT(*) FROM appointment_change_request WHERE appointment_id=2000"))
            .isEqualTo(3);

        assertSqlRejected(changeRequestSql(2004, 2000, "OPEN", "NULL", "change-invalid-open"),
            "ck_change_request_guard");
        assertSqlRejected(changeRequestSql(2005, 2000, "ACCEPTED", "1", "change-invalid-resolved"),
            "ck_change_request_guard");
    }

    @Test
    void rejectsInvalidDomainRowsAndRestrictsDeletionOfTerminalHistory() throws Exception {
        seedAppointment(3000);
        assertSqlRejected("""
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at)
            VALUES (3000,3000,3000,'2035-01-01 08:00:00.000')
            """, "ck_pet_health_log_content");
        assertSqlRejected("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count)
            VALUES (3999,3000,'2035-01-02','2035-01-02 09:00:00.000','2035-01-02 09:30:00.000',1,2)
            """, "ck_doctor_slot_occupied");
        assertSqlRejected("UPDATE appointment SET status='ARRIVED' WHERE id=3000", "ck_appointment_status");

        executeSql("""
            UPDATE appointment
            SET status='COMPLETED', completed_at='2035-01-01 10:00:00.000'
            WHERE id=3000
            """);
        executeSql("""
            INSERT INTO medical_record(
                id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at
            ) VALUES (3000,3000,3000,3000,'SUBMITTED',JSON_OBJECT(),1,'2035-01-01 10:00:00.000')
            """);
        executeSql("""
            INSERT INTO medical_record_version(
                id,record_id,revision_no,before_content_json,after_content_json,reason,actor_user_id,idempotency_key
            ) VALUES (3000,3000,1,NULL,JSON_OBJECT(),'首次提交',3001,'terminal-v1')
            """);

        assertSqlRejected("DELETE FROM appointment WHERE id=3000", "fk_medical_record_appointment");
        assertSqlRejected("DELETE FROM medical_record WHERE id=3000", "fk_medical_record_version_record");
        assertThat(queryLong("SELECT COUNT(*) FROM appointment WHERE id=3000")).isOne();
        assertThat(queryLong("SELECT COUNT(*) FROM medical_record_version WHERE record_id=3000")).isOne();
    }

    private static void seedAppointment(long base) throws Exception {
        executeSql("""
            INSERT INTO sys_user(id,username,display_name) VALUES
            (%1$d,'owner-%1$d','宠物主人'),(%2$d,'doctor-%2$d','接诊医生')
            """.formatted(base, base + 1));
        executeSql("""
            INSERT INTO doctor_profile(id,user_id,doctor_name) VALUES (%1$d,%2$d,'测试医生')
            """.formatted(base, base + 1));
        executeSql("""
            INSERT INTO pet(id,owner_user_id,name,species_code) VALUES (%1$d,%1$d,'测试宠物','CAT')
            """.formatted(base));
        executeSql("""
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count) VALUES
            (%1$d,%1$d,'2035-01-01','2035-01-01 09:00:00.000','2035-01-01 09:30:00.000',1,1),
            (%2$d,%1$d,'2035-01-01','2035-01-01 10:00:00.000','2035-01-01 10:30:00.000',1,0)
            """.formatted(base, base + 1));
        executeSql("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,chief_complaint,confirm_deadline_at,idempotency_key,request_hash
            ) VALUES (
                %1$d,'APT-%1$d',%1$d,%1$d,%1$d,%1$d,'2035-01-01 09:00:00.000','2035-01-01 09:30:00.000',
                '2035-01-01 09:00:00.000','年度检查','2034-12-31 09:00:00.000','create-%1$d',REPEAT('a',64)
            )
            """.formatted(base));
    }

    private static void insertChangeRequest(long id, long appointmentId, String status, String guard, String key)
        throws Exception {
        executeSql(changeRequestSql(id, appointmentId, status, guard, key));
    }

    private static String changeRequestSql(long id, long appointmentId, String status, String guard, String key) {
        return """
            INSERT INTO appointment_change_request(
                id,appointment_id,proposer_user_id,proposer_type,old_slot_id,new_slot_id,
                base_appointment_version,old_start_at,new_start_at,status,open_guard,reason,
                expires_at,idempotency_key,request_hash
            ) VALUES (
                %1$d,%2$d,%2$d,'USER',%2$d,%3$d,0,
                '2035-01-01 09:00:00.000','2035-01-01 10:00:00.000','%4$s',%5$s,
                '调整就诊时间','2034-12-31 08:00:00.000','%6$s',REPEAT('b',64)
            )
            """.formatted(id, appointmentId, appointmentId + 1, status, guard, key);
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
