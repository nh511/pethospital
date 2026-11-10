package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;

import com.pethospital.reporting.ReportDtos.AppointmentCohort;
import com.pethospital.reporting.ReportQuerySupport;
import com.pethospital.reporting.ReportRange;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "identity.profile.phone-key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class M1301IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long OWNER = 130_101;
    private static final long DOCTOR_USER = 130_102;
    private static final long DOCTOR = 130_201;
    private static final long PET = 130_301;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReportQuerySupport queries;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM appointment WHERE id BETWEEN 130601 AND 130603");
        jdbc.update("DELETE FROM doctor_slot WHERE id BETWEEN 130501 AND 130503");
        jdbc.update("DELETE FROM pet WHERE id=?", PET);
        jdbc.update("DELETE FROM doctor_profile WHERE id=?", DOCTOR);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?,?)", OWNER, DOCTOR_USER);
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,? ,NULL,?,'ACTIVE',0,0),(?,? ,NULL,?,'ACTIVE',0,0)
            """, OWNER, "report-owner", "报表主人", DOCTOR_USER, "report-doctor", "报表医生");
        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,?,'ACTIVE',0)
            """, DOCTOR, DOCTOR_USER, "报表医生");
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,sex,neutered,status,version)
            VALUES (?,?,'报表宠物','CAT','UNKNOWN','UNKNOWN','ACTIVE',0)
            """, PET, OWNER);
        insertSlot(130_501, Instant.parse("2026-09-30T15:00:00Z"));
        insertSlot(130_502, Instant.parse("2026-09-30T16:00:00Z"));
        insertSlot(130_503, Instant.parse("2026-10-31T16:00:00Z"));
        insertAppointment(130_601, 130_501,
            Instant.parse("2026-09-30T15:00:00Z"),
            Instant.parse("2026-09-30T16:00:00Z"));
        insertAppointment(130_602, 130_502,
            Instant.parse("2026-09-30T16:00:00Z"),
            Instant.parse("2026-10-31T16:00:00Z"));
        insertAppointment(130_603, 130_503,
            Instant.parse("2026-10-31T16:00:00Z"),
            Instant.parse("2026-10-15T04:00:00Z"));
    }

    @Test
    void createdAndScheduledCohortsStaySeparateAtHalfOpenBoundaries() {
        ReportRange range = octoberShanghai();

        assertThat(count(range, AppointmentCohort.CREATED)).isEqualTo(2);
        assertThat(count(range, AppointmentCohort.SCHEDULED)).isEqualTo(1);
    }

    @Test
    void utcDatabaseWindowIsDisplayedInShanghaiWithoutChangingInstants() {
        ReportRange range = octoberShanghai();
        var metadata = range.metadata(
            Clock.fixed(Instant.parse("2026-10-31T01:00:00Z"), ZoneOffset.UTC),
            AppointmentCohort.CREATED
        );

        assertThat(metadata.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(metadata.from().toString()).isEqualTo("2026-10-01T00:00+08:00");
        assertThat(metadata.to().toString()).isEqualTo("2026-11-01T00:00+08:00");
        assertThat(metadata.generatedAt().toString()).isEqualTo("2026-10-31T09:00+08:00");
        assertThat(queries.appointmentWindow(range, AppointmentCohort.CREATED).fromInclusive())
            .isEqualTo(Timestamp.from(Instant.parse("2026-09-30T16:00:00Z")));
    }

    private int count(ReportRange range, AppointmentCohort cohort) {
        var window = queries.appointmentWindow(range, cohort);
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM appointment a WHERE " + window.predicate("a"),
            Integer.class,
            window.parameters()
        );
    }

    private void insertSlot(long id, Instant start) {
        jdbc.update("""
            INSERT INTO doctor_slot(
              id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
            ) VALUES (?,?,?,?,?,1,1,'OPEN',0)
            """,
            id,
            DOCTOR,
            Date.valueOf(LocalDate.ofInstant(start, ZoneOffset.UTC)),
            Timestamp.from(start),
            Timestamp.from(start.plusSeconds(1800))
        );
    }

    private void insertAppointment(long id, long slotId, Instant scheduled, Instant created) {
        jdbc.update("""
            INSERT INTO appointment(
              id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,
              scheduled_end_at,initial_start_at,status,chief_complaint,confirm_deadline_at,
              idempotency_key,request_hash,version,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,'PENDING','报表边界测试',?,?,?,0,?,?)
            """,
            id,
            "M1301-" + id,
            OWNER,
            PET,
            DOCTOR,
            slotId,
            Timestamp.from(scheduled),
            Timestamp.from(scheduled.plusSeconds(1800)),
            Timestamp.from(scheduled),
            Timestamp.from(scheduled.minusSeconds(3600)),
            "m1301-" + id,
            "%064x".formatted(id),
            Timestamp.from(created),
            Timestamp.from(created)
        );
    }

    private static ReportRange octoberShanghai() {
        return ReportRange.parse(
            "2026-10-01T00:00:00+08:00",
            "2026-11-01T00:00:00+08:00",
            "DAY"
        );
    }
}
