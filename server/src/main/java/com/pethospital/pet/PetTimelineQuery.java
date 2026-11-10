package com.pethospital.pet;

import com.pethospital.pet.PetTimelineDtos.TimelineItem;
import com.pethospital.pet.PetTimelineDtos.TimelineType;
import com.pethospital.pet.PetTimelineService.Cursor;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PetTimelineQuery {
    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    PetTimelineQuery(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    List<TimelineItem> list(long petId, long ownerId, TimelineType type, Cursor cursor, int limit) {
        var parameters = new MapSqlParameterSource()
            .addValue("petId", petId)
            .addValue("ownerId", ownerId)
            .addValue("type", type == null ? null : type.name())
            .addValue("limit", limit);
        String boundary = "";
        if (cursor != null) {
            parameters.addValue("time", LocalDateTime.ofInstant(cursor.time(), ZoneOffset.UTC))
                .addValue("id", cursor.id())
                .addValue("cursorType", cursor.type().name());
            boundary = "AND (event_time, event_id, event_type) < (:time, :id, :cursorType)";
        }
        return new NamedParameterJdbcTemplate(jdbcProvider.getObject()).query("""
            SELECT event_type, event_id, event_time, summary
            FROM (
                SELECT 'LOG' AS event_type, id AS event_id, observed_at AS event_time,
                       LEFT(CONCAT_WS(' · ', '主人自述',
                           CONCAT(weight_kg, ' kg'),
                           COALESCE(NULLIF(TRIM(symptom_note), ''), NULLIF(TRIM(diet_note), ''),
                               NULLIF(TRIM(stool_note), ''), NULLIF(TRIM(activity_note), ''))), 240) AS summary
                FROM pet_health_log
                WHERE pet_id=:petId AND author_user_id=:ownerId
                UNION ALL
                SELECT 'MEDICAL', m.id, m.submitted_at,
                       CASE WHEN JSON_TYPE(JSON_EXTRACT(m.content_json, '$.diagnosis'))='STRING'
                            THEN COALESCE(NULLIF(LEFT(TRIM(m.content_json->>'$.diagnosis'), 240), ''), '已提交病历')
                            ELSE '已提交病历' END
                FROM medical_record m
                JOIN appointment a ON a.id=m.appointment_id AND a.pet_id=m.pet_id AND a.doctor_id=m.doctor_id
                WHERE m.pet_id=:petId AND a.user_id=:ownerId AND m.status='SUBMITTED'
                UNION ALL
                SELECT 'AI', id, created_at, title
                FROM ai_session
                WHERE pet_id=:petId AND user_id=:ownerId
                UNION ALL
                SELECT 'APPOINTMENT', id, scheduled_start_at, status
                FROM appointment
                WHERE pet_id=:petId AND user_id=:ownerId
            ) events
            WHERE (:type IS NULL OR event_type=:type)
            %s
            ORDER BY event_time DESC, event_id DESC, event_type DESC
            LIMIT :limit
            """.formatted(boundary), parameters, (rs, row) -> new TimelineItem(
            TimelineType.valueOf(rs.getString("event_type")),
            Long.toString(rs.getLong("event_id")),
            rs.getObject("event_time", LocalDateTime.class).toInstant(ZoneOffset.UTC).toString(),
            rs.getString("summary")
        ));
    }
}
