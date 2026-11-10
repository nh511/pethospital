package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.persistence.demo.DemoFixtureCommand;
import com.pethospital.persistence.demo.DemoFixtureIds;
import com.pethospital.persistence.demo.DemoFixtureService;
import com.pethospital.persistence.demo.DemoFixtureSummary;
import com.pethospital.persistence.entity.AiSessionEntity;
import com.pethospital.persistence.mapper.AiSessionMapper;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Tag(TestTags.MYSQL_IT)
class M0205IT {
    private static final String DEMO_PASSWORD = "M0205-Local-Demo!";
    private static final String CLI_DEMO_PASSWORD = "M0205-Command-Demo!";
    private static final long MAX_SAFE_JAVASCRIPT_INTEGER = 9_007_199_254_740_991L;
    private static final long BIG_SESSION_ID = 9_000_000_000_000_123_456L;

    private static MySqlTestContainer mysql;
    private static ConfigurableApplicationContext application;
    private static DataSource dataSource;
    private static AiSessionMapper mapper;
    private static ObjectMapper objectMapper;
    private static DemoFixtureService fixtureService;
    private static long fixtureUsersAtApplicationStartup;

    @BeforeAll
    static void startApplicationWithIsolatedMySql() throws Exception {
        mysql = MySqlTestContainer.start();
        application = new SpringApplicationBuilder(PetHospitalApplication.class)
            .run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + utcJdbcUrl(),
                "--spring.datasource.username=" + mysql.username(),
                "--spring.datasource.password=" + mysql.password(),
                "--spring.flyway.enabled=true",
                "--spring.flyway.target=5",
                "--spring.flyway.validate-on-migrate=true",
                "--spring.flyway.clean-disabled=true",
                "--spring.flyway.baseline-on-migrate=false",
                "--spring.flyway.out-of-order=false"
            );
        dataSource = application.getBean(DataSource.class);
        mapper = application.getBean(AiSessionMapper.class);
        objectMapper = application.getBean(ObjectMapper.class);
        fixtureService = new DemoFixtureService();
        fixtureUsersAtApplicationStartup = queryLong(
            "SELECT COUNT(*) FROM sys_user WHERE id BETWEEN -1005 AND -1001"
        );
    }

    @AfterAll
    static void stopApplicationAndMySql() {
        if (application != null) {
            application.close();
        }
        if (mysql != null) {
            mysql.close();
        }
    }

    @BeforeEach
    void rebuildFixtureAndRemoveMapperProbes() throws Exception {
        executeSql("DELETE FROM ai_session WHERE id <> " + DemoFixtureIds.AI_SESSION_ONE);
        fixtureService.rebuild(dataSource, DEMO_PASSWORD);
    }

    @Test
    void mapperRoundTripsJsonUtcBigIntAndGeneratedSnowflakeId() throws Exception {
        JsonNode snapshot = objectMapper.readTree("""
            {"petId":"9000000000000123455","name":"UTC往返探针","tags":["json","mysql"]}
            """);
        Instant exactMillisecond = Instant.parse("2035-04-05T06:07:08.123Z");
        AiSessionEntity explicit = newSession("BIGINT/JSON/UTC探针", snapshot, exactMillisecond);
        explicit.setId(BIG_SESSION_ID);

        assertThat(mapper.insert(explicit)).isEqualTo(1);
        AiSessionEntity loaded = mapper.selectById(BIG_SESSION_ID);

        assertThat(loaded.getId()).isEqualTo(BIG_SESSION_ID).isGreaterThan(MAX_SAFE_JAVASCRIPT_INTEGER);
        assertThat(loaded.getPetSnapshotJson()).isEqualTo(snapshot);
        assertThat(loaded.getLastMessageAt()).isEqualTo(exactMillisecond);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(queryString("SELECT @@session.time_zone")).isIn("+00:00", "UTC");

        AiSessionEntity generated = newSession(
            "雪花ID探针",
            objectMapper.readTree("{\"source\":\"M0205IT\"}"),
            Instant.parse("2035-04-05T06:07:09.456Z")
        );
        assertThat(mapper.insert(generated)).isEqualTo(1);
        assertThat(generated.getId())
            .isNotNull()
            .isPositive()
            .isGreaterThan(MAX_SAFE_JAVASCRIPT_INTEGER);
        assertThat(mapper.selectById(generated.getId()).getId()).isEqualTo(generated.getId());
    }

    @Test
    void optimisticVersionRejectsStaleWriteAndPaginationUsesMySql() throws Exception {
        for (int index = 1; index <= 3; index++) {
            AiSessionEntity entity = newSession(
                "分页探针-" + index,
                objectMapper.readTree("{\"index\":" + index + "}"),
                Instant.parse("2035-05-01T01:02:0" + index + ".000Z")
            );
            assertThat(mapper.insert(entity)).isEqualTo(1);
        }

        LambdaQueryWrapper<AiSessionEntity> query = new LambdaQueryWrapper<AiSessionEntity>()
            .eq(AiSessionEntity::getUserId, DemoFixtureIds.USER_ONE)
            .orderByAsc(AiSessionEntity::getId);
        Page<AiSessionEntity> page = mapper.selectPage(new Page<>(1, 2), query);
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getPages()).isEqualTo(2);
        assertThat(page.getRecords()).hasSize(2);

        Long targetId = page.getRecords().getFirst().getId();
        AiSessionEntity current = mapper.selectById(targetId);
        AiSessionEntity stale = mapper.selectById(targetId);
        current.setTitle("乐观锁成功更新");
        stale.setTitle("陈旧版本不得覆盖");

        assertThat(mapper.updateById(current)).isEqualTo(1);
        assertThat(current.getVersion()).isEqualTo(1);
        assertThat(mapper.updateById(stale)).isZero();
        assertThat(mapper.selectById(targetId).getTitle()).isEqualTo("乐观锁成功更新");
    }

    @Test
    void fixedDemoFixtureCanBeRebuiltWithoutDuplicatesAndHashesEnvironmentPassword() throws Exception {
        assertFixtureCounts();
        String firstHash = queryString("SELECT password_hash FROM sys_user WHERE id=-1001");
        assertThat(firstHash).isNotEqualTo(DEMO_PASSWORD);
        assertThat(new BCryptPasswordEncoder().matches(DEMO_PASSWORD, firstHash)).isTrue();

        DemoFixtureSummary summary = fixtureService.rebuild(dataSource, DEMO_PASSWORD);
        assertThat(summary).isEqualTo(new DemoFixtureSummary(5, 2, 2, 3, 2, 1, 2, 1, 2));
        assertFixtureCounts();

        String rebuiltHash = queryString("SELECT password_hash FROM sys_user WHERE id=-1001");
        assertThat(rebuiltHash).isNotEqualTo(firstHash);
        assertThat(new BCryptPasswordEncoder().matches(DEMO_PASSWORD, rebuiltHash)).isTrue();

        DemoFixtureSummary commandSummary = DemoFixtureCommand.run(
            DemoFixtureCommand.Settings.fromEnvironment(integrationCommandEnvironment())
        );
        assertThat(commandSummary).isEqualTo(new DemoFixtureSummary(5, 2, 2, 3, 2, 1, 2, 1, 2));
        String commandHash = queryString("SELECT password_hash FROM sys_user WHERE id=-1001");
        assertThat(new BCryptPasswordEncoder().matches(CLI_DEMO_PASSWORD, commandHash)).isTrue();
        assertThat(queryString("SELECT JSON_UNQUOTE(JSON_EXTRACT(pet_snapshot_json,'$.name')) FROM ai_session WHERE id=-6001"))
            .isEqualTo("可可");
    }

    @Test
    void demoCommandRequiresExplicitLocalNonProductionEnvironmentAndNeverAutoRuns() {
        assertThat(fixtureUsersAtApplicationStartup).isZero();
        assertThat(application.getBeansOfType(DemoFixtureService.class)).isEmpty();
        assertThat(application.getBeansOfType(DemoFixtureCommand.class)).isEmpty();

        Map<String, String> valid = validCommandEnvironment();
        Map<String, String> missingPassword = new HashMap<>(valid);
        missingPassword.remove("DEMO_PASSWORD");
        assertThatThrownBy(() -> DemoFixtureCommand.Settings.fromEnvironment(missingPassword))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DEMO_PASSWORD");

        Map<String, String> production = new HashMap<>(valid);
        production.put("SPRING_PROFILES_ACTIVE", "prod");
        assertThatThrownBy(() -> DemoFixtureCommand.Settings.fromEnvironment(production))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("forbidden in production");

        Map<String, String> remote = new HashMap<>(valid);
        remote.put("DB_URL", "jdbc:mysql://db.example.invalid:3306/pethospital");
        assertThatThrownBy(() -> DemoFixtureCommand.Settings.fromEnvironment(remote))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("local MySQL");

        DemoFixtureCommand.Settings settings = DemoFixtureCommand.Settings.fromEnvironment(valid);
        assertThat(settings.demoPassword()).isEqualTo(DEMO_PASSWORD);
        assertThat(settings.jdbcUrl()).startsWith("jdbc:mysql://localhost:");
    }

    private static AiSessionEntity newSession(String title, JsonNode snapshot, Instant lastMessageAt) {
        AiSessionEntity entity = new AiSessionEntity();
        entity.setUserId(DemoFixtureIds.USER_ONE);
        entity.setPetId(DemoFixtureIds.PET_ONE);
        entity.setTitle(title);
        entity.setStatus("OPEN");
        entity.setPetSnapshotJson(snapshot);
        entity.setSummaryThroughSeq(0);
        entity.setSummaryVersion(0);
        entity.setLastMessageAt(lastMessageAt);
        entity.setVersion(0);
        return entity;
    }

    private static void assertFixtureCounts() throws Exception {
        assertThat(queryLong("SELECT COUNT(*) FROM sys_user WHERE id BETWEEN -1005 AND -1001")).isEqualTo(5);
        assertThat(queryLong("SELECT COUNT(*) FROM doctor_profile WHERE id IN (-2001,-2002)")).isEqualTo(2);
        assertThat(queryLong("SELECT COUNT(*) FROM pet WHERE id IN (-3001,-3002)")).isEqualTo(2);
        assertThat(queryLong("SELECT COUNT(*) FROM pet_health_log WHERE id IN (-3101,-3102)")).isEqualTo(2);
        assertThat(queryLong("SELECT COUNT(*) FROM doctor_slot WHERE id IN (-4001,-4002)")).isEqualTo(2);
        assertThat(queryLong("SELECT COUNT(*) FROM appointment WHERE id=-5001")).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM appointment_status_log WHERE id=-5101")).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM doctor_slot WHERE id=%d AND status='CLOSED' AND occupied_count=1"
            .formatted(DemoFixtureIds.SLOT_THREE))).isOne();
        assertThat(queryLong("""
            SELECT COUNT(*) FROM appointment a JOIN appointment_status_log l ON l.appointment_id=a.id
            WHERE a.id=%d AND a.status='COMPLETED' AND l.id=%d AND l.to_status='COMPLETED'
            """.formatted(DemoFixtureIds.APPOINTMENT_TWO, DemoFixtureIds.APPOINTMENT_LOG_TWO))).isOne();
        assertThat(queryLong("""
            SELECT COUNT(*) FROM medical_record r
            JOIN medical_record_version original ON original.record_id=r.id AND original.id=%d
            JOIN medical_record_version revised ON revised.record_id=r.id AND revised.id=%d
            WHERE r.id=%d AND r.status='SUBMITTED' AND r.revision_no=2 AND r.version=2
              AND original.before_content_json IS NULL AND original.after_content_json=revised.before_content_json
              AND revised.after_content_json=r.content_json
              AND original.after_content_json->>'$.followupAdvice'<>revised.after_content_json->>'$.followupAdvice'
            """.formatted(DemoFixtureIds.MEDICAL_VERSION_ONE, DemoFixtureIds.MEDICAL_VERSION_TWO,
            DemoFixtureIds.MEDICAL_RECORD_ONE))).isOne();
        assertThat(queryLong("SELECT COUNT(*) FROM ai_session WHERE id=-6001")).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM health_guide WHERE id=-7001")).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM health_standard WHERE id=-8001")).isEqualTo(1);
        assertThat(queryLong("""
            SELECT COUNT(*) FROM sys_user_role ur
            JOIN sys_role r ON r.id=ur.role_id
            WHERE ur.id IN (-1201,-1202,-1203,-1204,-1205)
              AND r.code IN ('USER','DOCTOR','ADMIN')
            """)).isEqualTo(5);
    }

    private static Map<String, String> validCommandEnvironment() {
        return Map.of(
            "DEMO_FIXTURE_CONFIRM", "REBUILD_LOCAL_DEMO",
            "DB_URL", "jdbc:mysql://localhost:3306/pethospital?connectionTimeZone=UTC",
            "MYSQL_USER", "petapp",
            "MYSQL_PASSWORD", "local-database-password",
            "DEMO_PASSWORD", DEMO_PASSWORD
        );
    }

    private static Map<String, String> integrationCommandEnvironment() {
        return Map.of(
            "DEMO_FIXTURE_CONFIRM", "REBUILD_LOCAL_DEMO",
            "DB_URL", utcJdbcUrl(),
            "MYSQL_USER", mysql.username(),
            "MYSQL_PASSWORD", mysql.password(),
            "DEMO_PASSWORD", CLI_DEMO_PASSWORD
        );
    }

    private static String utcJdbcUrl() {
        String separator = mysql.jdbcUrl().contains("?") ? "&" : "?";
        return mysql.jdbcUrl() + separator
            + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8";
    }

    private static void executeSql(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
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

    private static String queryString(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
