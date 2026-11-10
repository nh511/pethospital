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
public class AppointmentChangeTimeoutScanner {
    private static final Logger log = LoggerFactory.getLogger(AppointmentChangeTimeoutScanner.class);
    private final ObjectProvider<JdbcTemplate> templates;
    private final AppointmentChangeService changes;
    private final Clock clock;

    public AppointmentChangeTimeoutScanner(
        ObjectProvider<JdbcTemplate> templates, AppointmentChangeService changes, Clock clock
    ) {
        this.templates = templates;
        this.changes = changes;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${appointment.timeout-scan.interval-ms:30000}",
        initialDelayString = "${appointment.timeout-scan.interval-ms:30000}")
    public int scan() {
        var due = templates.getObject().queryForList("""
            SELECT id FROM appointment_change_request WHERE status='OPEN' AND expires_at<=?
            ORDER BY expires_at,id LIMIT 100
            """, Long.class, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        int expired = 0;
        for (long id : due) {
            try {
                if (changes.expire(id)) {
                    expired++;
                }
            } catch (RuntimeException failure) {
                log.warn("Appointment change expiry failed: type={}", failure.getClass().getSimpleName());
            }
        }
        return expired;
    }
}
