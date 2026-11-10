package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@Tag(TestTags.MYSQL_IT)
class M0201IT {
    private static MySqlTestContainer mysql;
    private static DataSource dataSource;

    @TempDir
    Path migrationDirectory;

    @BeforeAll
    static void startIsolatedMySql() {
        mysql = MySqlTestContainer.start();
        dataSource = mysql.dataSource();
    }

    @AfterAll
    static void stopIsolatedMySql() {
        if (mysql != null) {
            mysql.close();
        }
    }

    @BeforeEach
    void removeChecksumProbe() throws Exception {
        executeSql("DROP TABLE IF EXISTS m0201_checksum_probe");
        executeSql("DROP TABLE IF EXISTS flyway_checksum_history");
    }

    @Test
    void emptyDatabaseStartsAndSecondStartDoesNotRepeatMigration() throws Exception {
        Path baselineDirectory = copyBaselineMigration();
        try (ConfigurableApplicationContext first = startApplication(baselineDirectory)) {
            assertThat(first.getBean(Flyway.class)).isNotNull();
        }

        assertThat(queryLong("""
            SELECT COUNT(*) FROM flyway_schema_history
            WHERE version='1' AND success=1
            """)).isEqualTo(1);
        assertThat(queryLong("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema=DATABASE()
              AND table_type='BASE TABLE'
              AND table_name NOT LIKE 'flyway\\_%'
            """)).as("M02-01 must not create M02-02 business tables").isZero();
        long checksum = queryLong("SELECT checksum FROM flyway_schema_history WHERE version='1'");

        try (ConfigurableApplicationContext second = startApplication(baselineDirectory)) {
            assertThat(second.getBean(Flyway.class)).isNotNull();
        }

        assertThat(queryLong("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1"))
            .isEqualTo(1);
        assertThat(queryLong("SELECT checksum FROM flyway_schema_history WHERE version='1'"))
            .isEqualTo(checksum);
    }

    @Test
    void changedAppliedMigrationFailsChecksumValidationWithoutRepair() throws Exception {
        Path checksumDirectory = migrationDirectory.resolve("checksum");
        Files.createDirectories(checksumDirectory);
        Path migration = checksumDirectory.resolve("V1__checksum_probe.sql");
        Files.writeString(
            migration,
            "CREATE TABLE m0201_checksum_probe(id BIGINT PRIMARY KEY);\n",
            StandardCharsets.UTF_8
        );
        Flyway original = checksumFlyway(checksumDirectory);
        original.migrate();
        long storedChecksum = queryLong(
            "SELECT checksum FROM flyway_checksum_history WHERE version='1'"
        );

        Files.writeString(
            migration,
            "CREATE TABLE m0201_checksum_probe(id BIGINT PRIMARY KEY);\n-- forbidden mutation\n",
            StandardCharsets.UTF_8
        );
        Flyway changed = checksumFlyway(checksumDirectory);

        assertThatThrownBy(changed::migrate)
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("checksum mismatch");
        assertThat(queryLong("SELECT checksum FROM flyway_checksum_history WHERE version='1'"))
            .isEqualTo(storedChecksum);
        assertThat(queryLong("SELECT COUNT(*) FROM flyway_checksum_history WHERE version='1' AND success=1"))
            .isEqualTo(1);
    }

    private Path copyBaselineMigration() throws Exception {
        Path baselineDirectory = migrationDirectory.resolve("baseline");
        Files.createDirectories(baselineDirectory);
        Path target = baselineDirectory.resolve("V1__initialize_schema_versioning.sql");
        try (InputStream source = M0201IT.class.getResourceAsStream(
            "/db/migration/V1__initialize_schema_versioning.sql"
        )) {
            assertThat(source).isNotNull();
            Files.copy(source, target);
        }
        return baselineDirectory;
    }

    private ConfigurableApplicationContext startApplication(Path location) {
        return new SpringApplicationBuilder(PetHospitalApplication.class)
            .run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + mysql.jdbcUrl(),
                "--spring.datasource.username=" + mysql.username(),
                "--spring.datasource.password=" + mysql.password(),
                "--spring.flyway.enabled=true",
                "--spring.flyway.locations=" + filesystemLocation(location),
                "--spring.flyway.validate-on-migrate=true",
                "--spring.flyway.clean-disabled=true",
                "--spring.flyway.baseline-on-migrate=false",
                "--spring.flyway.out-of-order=false"
            );
    }

    private Flyway checksumFlyway(Path location) {
        return Flyway.configure()
            .dataSource(mysql.jdbcUrl(), mysql.username(), mysql.password())
            .locations(filesystemLocation(location))
            .table("flyway_checksum_history")
            .validateOnMigrate(true)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .outOfOrder(false)
            .load();
    }

    private static String filesystemLocation(Path location) {
        return "filesystem:" + location.toAbsolutePath().toString().replace('\\', '/');
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
}
