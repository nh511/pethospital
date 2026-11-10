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
class M0202IT {
    private static final Set<String> EXPECTED_TABLES = Set.of(
        "sys_user", "sys_role", "sys_user_role", "sys_menu", "sys_role_menu", "auth_session",
        "doctor_profile", "file_object", "file_binding", "audit_log", "user_notice", "consent_record"
    );
    private static final Set<String> MUTABLE_TABLES = Set.of(
        "sys_user", "sys_role", "sys_menu", "auth_session", "doctor_profile", "file_object", "user_notice"
    );
    private static final Set<String> VERSIONED_TABLES = Set.of(
        "sys_user", "sys_role", "sys_menu", "doctor_profile"
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
            .target(MigrationVersion.fromVersion("2"))
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
    void createsExactlyTheTwelveDesignedTablesAndCommonColumns() throws Exception {
        assertThat(queryStrings("""
            SELECT table_name FROM information_schema.tables
            WHERE table_schema=DATABASE()
              AND table_type='BASE TABLE'
              AND table_name <> 'flyway_schema_history'
            """)).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);

        for (String table : EXPECTED_TABLES) {
            assertColumn(table, "id", "bigint", null, false, null);
            assertColumn(table, "created_at", "datetime", null, false, 3);
        }
        for (String table : MUTABLE_TABLES) {
            assertColumn(table, "updated_at", "datetime", null, false, 3);
        }
        for (String table : VERSIONED_TABLES) {
            assertColumn(table, "version", "int", null, false, null);
        }
        assertColumn("sys_user", "password_hash", "varchar", 255L, true, null);
        assertColumn("sys_user", "phone_cipher", "varbinary", 512L, true, null);
        assertColumn("sys_user", "phone_masked", "varchar", 32L, true, null);
        assertColumn("sys_user", "wechat_openid_cipher", "varbinary", 512L, true, null);
    }

    @Test
    void declaresAllUniqueRelationshipsAndRestrictiveForeignKeys() throws Exception {
        assertThat(queryStrings("""
            SELECT DISTINCT index_name FROM information_schema.statistics
            WHERE table_schema=DATABASE() AND non_unique=0 AND index_name <> 'PRIMARY'
            """)).contains(
                "uk_sys_user_username", "uk_sys_user_wechat_openid_hash", "uk_sys_role_code",
                "uk_sys_user_role", "uk_sys_menu_permission_code", "uk_sys_role_menu",
                "uk_auth_session_refresh_token_hash", "uk_doctor_profile_user", "uk_file_object_key",
                "uk_file_binding_business", "uk_audit_actor_operation", "uk_user_notice_event",
                "uk_consent_idempotency"
            );
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.referential_constraints
            WHERE constraint_schema=DATABASE()
            """)).isEqualTo(17);
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.referential_constraints
            WHERE constraint_schema=DATABASE() AND delete_rule='RESTRICT' AND update_rule='RESTRICT'
            """)).isEqualTo(17);

        executeSql("INSERT INTO sys_user(id,username,display_name) VALUES (100,'relation-user','关系用户')");
        executeSql("INSERT INTO sys_role(id,code,name) VALUES (100,'RELATION_ROLE','关系角色')");
        executeSql("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (100,100,100)");
        assertSqlRejected(
            "INSERT INTO sys_user_role(id,user_id,role_id) VALUES (101,100,100)",
            "uk_sys_user_role"
        );
    }

    @Test
    void nullableIdentifiersAndEnumChecksBehaveAsDesigned() throws Exception {
        executeSql("INSERT INTO sys_user(id,display_name) VALUES (200,'无账号用户一'),(201,'无账号用户二')");
        assertThat(queryLong("SELECT COUNT(*) FROM sys_user WHERE username IS NULL AND id IN (200,201)"))
            .isEqualTo(2);
        assertSqlRejected(
            "INSERT INTO sys_user(id,display_name,status) VALUES (202,'坏状态','BROKEN')",
            "ck_sys_user_status"
        );
        assertSqlRejected(
            """
            INSERT INTO file_object(id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status)
            VALUES (200,200,'invalid-purpose','x.png','image/png',1,REPEAT('a',64),'EXECUTABLE','READY')
            """,
            "ck_file_object_purpose"
        );
        assertSqlRejected(
            """
            INSERT INTO consent_record(id,user_id,purpose,policy_version,action,notice_hash,idempotency_key)
            VALUES (200,200,'PRIVACY','v1','DELETE',REPEAT('b',64),'consent-invalid')
            """,
            "ck_consent_action"
        );
    }

    @Test
    void foreignKeysRejectOrphansAndParentDeletion() throws Exception {
        assertSqlRejected(
            "INSERT INTO sys_user_role(id,user_id,role_id) VALUES (300,999999,999999)",
            "fk_sys_user_role_user"
        );
        executeSql("INSERT INTO sys_user(id,username,display_name) VALUES (300,'file-owner','文件所有者')");
        executeSql("""
            INSERT INTO file_object(id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status)
            VALUES (300,300,'protected-object','report.pdf','application/pdf',100,REPEAT('c',64),'MEDICAL','READY')
            """);
        assertSqlRejected("DELETE FROM sys_user WHERE id=300", "fk_file_object_uploader");
    }

    private static void assertColumn(
        String table,
        String column,
        String dataType,
        Long maxLength,
        boolean nullable,
        Integer datetimePrecision
    ) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT data_type, character_maximum_length, is_nullable, datetime_precision
                 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                 """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as(table + "." + column + " must exist").isTrue();
                assertThat(result.getString("data_type")).isEqualTo(dataType);
                if (maxLength != null) {
                    assertThat(result.getLong("character_maximum_length")).isEqualTo(maxLength);
                }
                assertThat(result.getString("is_nullable")).isEqualTo(nullable ? "YES" : "NO");
                if (datetimePrecision != null) {
                    assertThat(result.getInt("datetime_precision")).isEqualTo(datetimePrecision);
                }
                assertThat(result.next()).isFalse();
            }
        }
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
