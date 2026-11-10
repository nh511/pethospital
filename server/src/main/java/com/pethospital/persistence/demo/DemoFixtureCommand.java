package com.pethospital.persistence.demo;

import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class DemoFixtureCommand {
    private DemoFixtureCommand() {
    }

    public static void main(String[] args) {
        Settings settings = Settings.fromEnvironment(System.getenv());
        DemoFixtureSummary summary = run(settings);
        printSummary(summary);
    }

    public static DemoFixtureSummary run(Settings settings) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            settings.jdbcUrl(),
            settings.databaseUser(),
            settings.databasePassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .validateOnMigrate(true)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .outOfOrder(false)
            .load()
            .migrate();
        return new DemoFixtureService().rebuild(dataSource, settings.demoPassword());
    }

    private static void printSummary(DemoFixtureSummary summary) {
        System.out.printf(
            "Demo fixture rebuilt: users=%d, doctors=%d, pets=%d, slots=%d, appointments=%d, aiSessions=%d, healthContents=%d, medicalRecords=%d, medicalVersions=%d%n",
            summary.users(),
            summary.doctors(),
            summary.pets(),
            summary.slots(),
            summary.appointments(),
            summary.aiSessions(),
            summary.healthContents(),
            summary.medicalRecords(),
            summary.medicalVersions()
        );
    }

    public record Settings(
        String jdbcUrl,
        String databaseUser,
        String databasePassword,
        String demoPassword
    ) {
        public static Settings fromEnvironment(Map<String, String> environment) {
            String confirmation = required(environment, "DEMO_FIXTURE_CONFIRM");
            if (!"REBUILD_LOCAL_DEMO".equals(confirmation)) {
                throw new IllegalArgumentException(
                    "DEMO_FIXTURE_CONFIRM must equal REBUILD_LOCAL_DEMO"
                );
            }
            rejectProduction(environment.get("APP_ENV"));
            rejectProduction(environment.get("SPRING_PROFILES_ACTIVE"));
            rejectProduction(environment.get("SPRING_PROFILES_DEFAULT"));

            String jdbcUrl = required(environment, "DB_URL");
            String lowerUrl = jdbcUrl.toLowerCase(Locale.ROOT);
            if (!lowerUrl.startsWith("jdbc:mysql://127.0.0.1:")
                && !lowerUrl.startsWith("jdbc:mysql://localhost:")
                && !lowerUrl.startsWith("jdbc:mysql://[::1]:")) {
                throw new IllegalArgumentException("Demo fixtures can only target a local MySQL URL");
            }
            String demoPassword = required(environment, "DEMO_PASSWORD");
            if (demoPassword.length() < 8) {
                throw new IllegalArgumentException("DEMO_PASSWORD must contain at least 8 characters");
            }
            return new Settings(
                jdbcUrl,
                required(environment, "MYSQL_USER"),
                required(environment, "MYSQL_PASSWORD"),
                demoPassword
            );
        }

        private static void rejectProduction(String value) {
            if (value == null) {
                return;
            }
            for (String token : value.toLowerCase(Locale.ROOT).split("[,; ]+")) {
                if ("prod".equals(token) || "production".equals(token)) {
                    throw new IllegalArgumentException("Demo fixtures are forbidden in production");
                }
            }
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }
    }
}
