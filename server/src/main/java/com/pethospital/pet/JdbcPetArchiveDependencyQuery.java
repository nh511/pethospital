package com.pethospital.pet;

import com.pethospital.shared.BusinessException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcPetArchiveDependencyQuery implements PetArchiveDependencyQuery {
    private final ObjectProvider<DataSource> dataSourceProvider;

    public JdbcPetArchiveDependencyQuery(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    @Override
    public ArchiveDependencies lockForPet(long petId) {
        JdbcTemplate jdbc = jdbc();
        List<Long> appointments = jdbc.queryForList("""
            SELECT id FROM appointment
            WHERE pet_id=? AND status IN ('PENDING','CONFIRMED','IN_CONSULTATION')
            FOR UPDATE
            """, Long.class, petId);
        List<Long> messages = jdbc.queryForList("""
            SELECT m.id FROM ai_message m
            JOIN ai_session s ON s.id=m.session_id
            WHERE s.pet_id=? AND m.processing_status IN ('QUEUED','PROCESSING')
            FOR UPDATE
            """, Long.class, petId);
        return new ArchiveDependencies(appointments.size(), messages.size());
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PET_DEPENDENCY_UNAVAILABLE",
                "宠物归档依赖数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }
}
