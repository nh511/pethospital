package com.pethospital.pet;

import com.pethospital.pet.PetHealthLogDtos.HealthLogCreateRequest;
import com.pethospital.pet.PetHealthLogDtos.HealthLogView;
import com.pethospital.pet.PetHealthLogDtos.HealthLogUpdateRequest;
import com.pethospital.shared.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PetHealthLogMapper {
    private final ObjectProvider<DataSource> dataSourceProvider;

    public PetHealthLogMapper(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    public void insert(long logId, long petId, long authorUserId, HealthLogCreateRequest request) {
        jdbc().update("""
            INSERT INTO pet_health_log(
                id,pet_id,author_user_id,observed_at,weight_kg,diet_note,stool_note,
                activity_note,symptom_note,version
            ) VALUES (?,?,?,?,?,?,?,?,?,0)
            """,
            logId, petId, authorUserId, Timestamp.from(request.observedAt()), request.weightKg(),
            request.dietNote(), request.stoolNote(), request.activityNote(), request.symptomNote()
        );
    }

    public HealthLogView findOwned(long petId, long logId, long ownerUserId) {
        HealthLogRow row = findOwnedRow(petId, logId, ownerUserId, false);
        return row == null ? null : view(row);
    }

    public HealthLogRow findOwnedRow(long petId, long logId, long ownerUserId, boolean lock) {
        return jdbc().query("""
            SELECT l.id,l.pet_id,l.observed_at,l.weight_kg,l.diet_note,l.stool_note,
                   l.activity_note,l.symptom_note,l.version,l.created_at,l.updated_at
            FROM pet_health_log l JOIN pet p ON p.id=l.pet_id
            WHERE l.id=? AND l.pet_id=? AND l.author_user_id=? AND p.owner_user_id=?
            """ + (lock ? " FOR UPDATE" : ""),
            result -> result.next() ? row(result) : null,
            logId, petId, ownerUserId, ownerUserId
        );
    }

    public int updateOwned(long petId, long logId, long ownerUserId, HealthLogUpdateRequest request) {
        return jdbc().update("""
            UPDATE pet_health_log l
            JOIN pet p ON p.id=l.pet_id
            SET l.observed_at=?,l.weight_kg=?,l.diet_note=?,l.stool_note=?,l.activity_note=?,
                l.symptom_note=?,l.version=l.version+1
            WHERE l.id=? AND l.pet_id=? AND l.author_user_id=? AND p.owner_user_id=? AND l.version=?
            """,
            Timestamp.from(request.observedAt()), request.weightKg(), request.dietNote(), request.stoolNote(),
            request.activityNote(), request.symptomNote(), logId, petId, ownerUserId, ownerUserId, request.version()
        );
    }

    public List<HealthLogView> listOwned(
        long petId, long ownerUserId, Instant from, Instant to, int pageSize, long offset
    ) {
        Query query = rangeQuery("""
            SELECT l.id,l.pet_id,l.observed_at,l.weight_kg,l.diet_note,l.stool_note,
                   l.activity_note,l.symptom_note,l.version,l.created_at,l.updated_at
            FROM pet_health_log l JOIN pet p ON p.id=l.pet_id
            WHERE l.pet_id=? AND l.author_user_id=? AND p.owner_user_id=?
            """, petId, ownerUserId, from, to);
        query.arguments().add(pageSize);
        query.arguments().add(offset);
        List<HealthLogRow> rows = jdbc().query(
            query.sql() + " ORDER BY l.observed_at DESC,l.id DESC LIMIT ? OFFSET ?",
            (result, rowNumber) -> row(result),
            query.arguments().toArray()
        );
        return rows.stream().map(this::view).toList();
    }

    public long countOwned(long petId, long ownerUserId, Instant from, Instant to) {
        Query query = rangeQuery("""
            SELECT COUNT(*) FROM pet_health_log l JOIN pet p ON p.id=l.pet_id
            WHERE l.pet_id=? AND l.author_user_id=? AND p.owner_user_id=?
            """, petId, ownerUserId, from, to);
        Long total = jdbc().queryForObject(query.sql(), Long.class, query.arguments().toArray());
        return total == null ? 0 : total;
    }

    public List<HealthLogView> listAdmin(
        long petId, Instant from, Instant to, int pageSize, long offset
    ) {
        Query query = adminRangeQuery("""
            SELECT l.id,l.pet_id,l.observed_at,l.weight_kg,l.diet_note,l.stool_note,
                   l.activity_note,l.symptom_note,l.version,l.created_at,l.updated_at
            FROM pet_health_log l WHERE l.pet_id=?
            """, petId, from, to);
        query.arguments().add(pageSize);
        query.arguments().add(offset);
        List<HealthLogRow> rows = jdbc().query(
            query.sql() + " ORDER BY l.observed_at DESC,l.id DESC LIMIT ? OFFSET ?",
            (result, rowNumber) -> row(result),
            query.arguments().toArray()
        );
        return rows.stream().map(this::view).toList();
    }

    public long countAdmin(long petId, Instant from, Instant to) {
        Query query = adminRangeQuery(
            "SELECT COUNT(*) FROM pet_health_log l WHERE l.pet_id=?", petId, from, to
        );
        Long total = jdbc().queryForObject(query.sql(), Long.class, query.arguments().toArray());
        return total == null ? 0 : total;
    }

    private Query rangeQuery(String base, long petId, long ownerUserId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(base);
        List<Object> arguments = new ArrayList<>(List.of(petId, ownerUserId, ownerUserId));
        if (from != null) {
            sql.append(" AND l.observed_at>=?");
            arguments.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND l.observed_at<?");
            arguments.add(Timestamp.from(to));
        }
        return new Query(sql.toString(), arguments);
    }

    private Query adminRangeQuery(String base, long petId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(base);
        List<Object> arguments = new ArrayList<>(List.of(petId));
        if (from != null) {
            sql.append(" AND l.observed_at>=?");
            arguments.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND l.observed_at<?");
            arguments.add(Timestamp.from(to));
        }
        return new Query(sql.toString(), arguments);
    }

    public HealthLogView view(HealthLogRow row) {
        List<String> attachmentIds = jdbc().queryForList("""
            SELECT CAST(file_id AS CHAR) FROM file_binding
            WHERE business_type='HEALTH_LOG' AND business_id=? ORDER BY created_at,id
            """, String.class, row.id());
        return new HealthLogView(
            Long.toString(row.id()), Long.toString(row.petId()), row.observedAt().toInstant().toString(),
            row.weightKg(), row.dietNote(), row.stoolNote(), row.activityNote(), row.symptomNote(),
            attachmentIds, "OWNER_REPORTED", row.version(), row.createdAt().toInstant().toString(),
            row.updatedAt().toInstant().toString()
        );
    }

    private static HealthLogRow row(ResultSet result) throws SQLException {
        return new HealthLogRow(
            result.getLong("id"), result.getLong("pet_id"), result.getTimestamp("observed_at"),
            result.getBigDecimal("weight_kg"), result.getString("diet_note"), result.getString("stool_note"),
            result.getString("activity_note"), result.getString("symptom_note"), result.getInt("version"),
            result.getTimestamp("created_at"), result.getTimestamp("updated_at")
        );
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, "HEALTH_LOG_DEPENDENCY_UNAVAILABLE", "健康日志数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private record Query(String sql, List<Object> arguments) {
    }

    public record HealthLogRow(
        long id, long petId, Timestamp observedAt, java.math.BigDecimal weightKg,
        String dietNote, String stoolNote, String activityNote, String symptomNote,
        int version, Timestamp createdAt, Timestamp updatedAt
    ) {
    }
}
