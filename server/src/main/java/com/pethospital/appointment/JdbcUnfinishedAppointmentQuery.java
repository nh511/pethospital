package com.pethospital.appointment;

import com.pethospital.shared.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcUnfinishedAppointmentQuery implements UnfinishedAppointmentQuery {
    private final ObjectProvider<DataSource> dataSourceProvider;

    public JdbcUnfinishedAppointmentQuery(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    @Override
    public UnfinishedAppointments lockForAccount(long userId) {
        JdbcTemplate jdbc = jdbc();
        List<Long> ownerAppointments = jdbc.queryForList("""
            SELECT id FROM appointment
            WHERE user_id=? AND status IN ('PENDING','CONFIRMED','IN_CONSULTATION')
            FOR UPDATE
            """, Long.class, userId);
        List<Long> doctorAppointments = jdbc.queryForList("""
            SELECT a.id FROM appointment a
            JOIN doctor_profile d ON d.id=a.doctor_id
            WHERE d.user_id=? AND a.status IN ('PENDING','CONFIRMED','IN_CONSULTATION')
            FOR UPDATE
            """, Long.class, userId);
        LinkedHashSet<Long> all = new LinkedHashSet<>(ownerAppointments);
        all.addAll(doctorAppointments);
        return new UnfinishedAppointments(ownerAppointments.size(), doctorAppointments.size(), all.size());
    }

    @Override
    public int lockForDoctor(long doctorId) {
        return jdbc().queryForList("""
            SELECT id FROM appointment
            WHERE doctor_id=? AND status IN ('PENDING','CONFIRMED','IN_CONSULTATION')
            FOR UPDATE
            """, Long.class, doctorId).size();
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "APPOINTMENT_DEPENDENCY_UNAVAILABLE",
                "预约数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }
}
