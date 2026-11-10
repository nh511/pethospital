package com.pethospital.pet;

import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.identity.ResourceAccessPolicy.DoctorScope;
import com.pethospital.pet.StaffWorkspaceDtos.AppointmentCard;
import com.pethospital.pet.StaffWorkspaceDtos.StaffPetSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class StaffWorkspaceQuery {
    private static final String PET_COLUMNS = """
        SELECT p.id,p.owner_user_id,p.name,p.species_code,p.breed,p.sex,p.birth_date,p.neutered,p.status
        """;
    private static final String PET_SCOPE = """
        FROM pet p WHERE EXISTS (
            SELECT 1 FROM appointment a
            WHERE a.doctor_id=? AND a.pet_id=p.id AND a.user_id=p.owner_user_id AND %s
        )
        """.formatted(ResourceAccessPolicy.DOCTOR_APPOINTMENT_WINDOW);
    private static final String APPOINTMENT_CARDS = """
        SELECT a.id, CASE WHEN a.status<>'PENDING' THEN a.pet_id END AS pet_id,
               p.name AS pet_name,a.status,a.chief_complaint,a.scheduled_start_at,a.scheduled_end_at
        FROM appointment a JOIN pet p ON p.id=a.pet_id AND p.owner_user_id=a.user_id
        WHERE a.doctor_id=?
        """;
    private static final String UPCOMING_ORDER = " ORDER BY a.scheduled_start_at, a.id LIMIT 20";
    private final ObjectProvider<JdbcTemplate> jdbc;

    StaffWorkspaceQuery(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc;
    }

    List<StaffPetSummary> listPets(DoctorScope scope, int limit, long offset) {
        return jdbc.getObject().query(PET_COLUMNS + PET_SCOPE + " ORDER BY p.id DESC LIMIT ? OFFSET ?",
            (rs, index) -> pet(rs), scope.doctorId(), utc(scope.completedSince()), limit, offset);
    }

    long countPets(DoctorScope scope) {
        return jdbc.getObject().queryForObject("SELECT COUNT(*) " + PET_SCOPE,
            Long.class, scope.doctorId(), utc(scope.completedSince()));
    }

    StaffPetRow findPet(DoctorScope scope, long petId) {
        return jdbc.getObject().query(PET_COLUMNS + PET_SCOPE + " AND p.id=?",
            rs -> rs.next() ? new StaffPetRow(pet(rs), rs.getLong("owner_user_id")) : null,
            scope.doctorId(), utc(scope.completedSince()), petId);
    }

    List<AppointmentCard> today(DoctorScope scope, Instant from, Instant to) {
        return jdbc.getObject().query(APPOINTMENT_CARDS + """
            AND a.status IN ('CONFIRMED','IN_CONSULTATION','COMPLETED')
            AND a.scheduled_start_at>=? AND a.scheduled_start_at<?
            """ + UPCOMING_ORDER, (rs, index) -> appointment(rs), scope.doctorId(), utc(from), utc(to));
    }

    List<AppointmentCard> pending(DoctorScope scope) {
        return jdbc.getObject().query(APPOINTMENT_CARDS + " AND a.status='PENDING'" + UPCOMING_ORDER,
            (rs, index) -> appointment(rs), scope.doctorId());
    }

    List<AppointmentCard> recentCompleted(DoctorScope scope) {
        return jdbc.getObject().query(APPOINTMENT_CARDS + """
            AND a.status='COMPLETED' AND a.completed_at>=?
            ORDER BY a.completed_at DESC, a.id DESC LIMIT 20
            """, (rs, index) -> appointment(rs), scope.doctorId(), utc(scope.completedSince()));
    }

    private static AppointmentCard appointment(ResultSet rs) throws SQLException {
        return new AppointmentCard(
            rs.getString("id"), rs.getString("pet_id"), rs.getString("pet_name"), rs.getString("status"),
            rs.getString("chief_complaint"), instant(rs, "scheduled_start_at"), instant(rs, "scheduled_end_at")
        );
    }

    private static StaffPetSummary pet(ResultSet rs) throws SQLException {
        return new StaffPetSummary(
            rs.getString("id"), rs.getString("name"), rs.getString("species_code"), rs.getString("breed"),
            rs.getString("sex"), rs.getObject("birth_date", LocalDate.class), rs.getString("neutered"), rs.getString("status")
        );
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC).toString();
    }

    record StaffPetRow(StaffPetSummary pet, long ownerId) { }
}
