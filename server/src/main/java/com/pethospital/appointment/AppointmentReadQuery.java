package com.pethospital.appointment;

import com.pethospital.appointment.AppointmentReadDtos.AppointmentStatusEntry;
import com.pethospital.appointment.AppointmentStateMachine.Status;
import com.pethospital.identity.ResourceAccessPolicy.AppointmentViewer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AppointmentReadQuery {
    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String COLUMNS = """
        SELECT a.id,a.appointment_no,a.doctor_id,d.doctor_name,a.pet_id,p.name AS pet_name,a.status,
               a.scheduled_start_at,a.scheduled_end_at,a.confirm_deadline_at,a.created_at,a.version,
               a.chief_complaint,(SELECT s.id FROM ai_session s
                   WHERE s.id=a.ai_session_id AND s.user_id=a.user_id AND s.pet_id=a.pet_id) AS ai_session_id,
               a.confirmed_at,a.started_at,a.completed_at,
               a.cancelled_at,a.cancelled_by,a.terminal_reason
        """;
    private static final String JOIN = """
        FROM appointment a
        JOIN pet p ON p.id=a.pet_id AND p.owner_user_id=a.user_id
        JOIN doctor_profile d ON d.id=a.doctor_id
        """;
    private final ObjectProvider<JdbcTemplate> templates;

    AppointmentReadQuery(ObjectProvider<JdbcTemplate> templates) {
        this.templates = templates;
    }

    Rows list(AppointmentViewer viewer, Status status, LocalDate date, Long petId, int size, long offset) {
        Criteria criteria = new Criteria(viewer);
        if (status != null) {
            criteria.add("a.status=:status", "status", status.name());
        }
        if (date != null) {
            criteria.add("a.scheduled_start_at>=:start", "start", startOf(date));
            criteria.add("a.scheduled_start_at<:end", "end", startOf(date.plusDays(1)));
        }
        if (petId != null) {
            criteria.add("a.pet_id=:petId", "petId", petId);
        }
        NamedParameterJdbcTemplate jdbc = jdbc();
        String where = criteria.where();
        long total = jdbc.queryForObject("SELECT COUNT(*) " + JOIN + where, criteria.parameters, Long.class);
        criteria.parameters.addValue("size", size).addValue("offset", offset);
        List<Row> rows = jdbc.query(COLUMNS + JOIN + where + " ORDER BY a.created_at DESC,a.id DESC LIMIT :size OFFSET :offset",
            criteria.parameters, (rs, index) -> row(rs));
        return new Rows(rows, total);
    }

    Row find(AppointmentViewer viewer, long id) {
        Criteria criteria = new Criteria(viewer);
        criteria.add("a.id=:id", "id", id);
        return jdbc().query(COLUMNS + JOIN + criteria.where(), criteria.parameters, rs -> rs.next() ? row(rs) : null);
    }

    List<AppointmentStatusEntry> statusLogs(long appointmentId) {
        return templates.getObject().query("""
            SELECT id,from_status,to_status,actor_type,reason,created_at
            FROM appointment_status_log WHERE appointment_id=? ORDER BY created_at,id
            """, (rs, index) -> {
                String from = rs.getString("from_status");
                return new AppointmentStatusEntry(rs.getString("id"), from == null ? null : Status.valueOf(from),
                    Status.valueOf(rs.getString("to_status")), rs.getString("actor_type"), rs.getString("reason"),
                    instant(rs, "created_at"));
            }, appointmentId);
    }

    private NamedParameterJdbcTemplate jdbc() {
        return new NamedParameterJdbcTemplate(templates.getObject());
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(
            rs.getString("id"), rs.getString("appointment_no"), rs.getString("doctor_id"), rs.getString("doctor_name"),
            rs.getString("pet_id"), rs.getString("pet_name"), Status.valueOf(rs.getString("status")),
            instant(rs, "scheduled_start_at"), instant(rs, "scheduled_end_at"), instant(rs, "confirm_deadline_at"),
            instant(rs, "created_at"), rs.getInt("version"), rs.getString("chief_complaint"), rs.getString("ai_session_id"),
            instant(rs, "confirmed_at"), instant(rs, "started_at"), instant(rs, "completed_at"), instant(rs, "cancelled_at"),
            rs.getString("cancelled_by"), rs.getString("terminal_reason")
        );
    }

    private static LocalDateTime startOf(LocalDate date) {
        return date.atStartOfDay(HOSPITAL_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        LocalDateTime value = rs.getObject(name, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static final class Criteria {
        private final StringBuilder sql = new StringBuilder(" WHERE 1=1");
        private final MapSqlParameterSource parameters = new MapSqlParameterSource();

        private Criteria(AppointmentViewer viewer) {
            switch (viewer.role()) {
                case USER -> add("a.user_id=:scopeId", "scopeId", viewer.scopeId());
                case DOCTOR -> add("a.doctor_id=:scopeId", "scopeId", viewer.scopeId());
                case ADMIN -> { }
            }
        }

        private void add(String predicate, String parameter, Object value) {
            sql.append(" AND ").append(predicate);
            parameters.addValue(parameter, value);
        }

        private String where() {
            return sql.toString();
        }
    }

    record Rows(List<Row> items, long total) { }

    record Row(
        String id, String appointmentNo, String doctorId, String doctorName, String petId, String petName,
        Status status, Instant start, Instant end, Instant deadline, Instant created, int version,
        String chiefComplaint, String aiSessionId, Instant confirmed, Instant started, Instant completed,
        Instant cancelled, String cancelledBy, String terminalReason
    ) { }
}
