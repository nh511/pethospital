package com.pethospital.appointment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AppointmentTimeoutScanner {
    private static final Logger log = LoggerFactory.getLogger(AppointmentTimeoutScanner.class);
    private final ObjectProvider<JdbcTemplate> templates;
    private final AppointmentDecisionService appointments;
    private final Clock clock;

    public AppointmentTimeoutScanner(
        ObjectProvider<JdbcTemplate> templates, AppointmentDecisionService appointments, Clock clock
    ) {
        this.templates = templates;
        this.appointments = appointments;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${appointment.timeout-scan.interval-ms:30000}",
        initialDelayString = "${appointment.timeout-scan.interval-ms:30000}")
    public int scan() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var due = templates.getObject().queryForList("""
            SELECT id FROM appointment WHERE status='PENDING' AND confirm_deadline_at<=?
            ORDER BY confirm_deadline_at,id LIMIT 100
            """, Long.class, now);
        int expired = 0;
        for (long id : due) {
            try {
                if (appointments.expirePending(id)) {
                    expired++;
                }
            } catch (RuntimeException failure) {
                log.warn("Appointment timeout transition failed: type={}", failure.getClass().getSimpleName());
            }
        }
        return expired;
    }
}
