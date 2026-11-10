package com.pethospital.persistence;

import com.pethospital.shared.BusinessException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Validates ownership relationships that cannot be represented by ordinary single-column foreign keys.
 * Call the guard in the same transaction, after locking the relevant aggregate rows and before writing.
 */
public final class CrossTableRelationGuard {
    private static final String RELATION_MISMATCH = "RELATION_MISMATCH";

    private final JdbcTemplate jdbc;

    public CrossTableRelationGuard(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    public void requireHealthLogAuthor(long petId, long authorUserId) {
        requireExists(
            "pet owner",
            "SELECT EXISTS(SELECT 1 FROM pet WHERE id=? AND owner_user_id=?)",
            petId,
            authorUserId
        );
    }

    public void requireAppointmentRelations(
        long userId,
        long petId,
        long doctorProfileId,
        long slotId
    ) {
        requireExists(
            "appointment owner, pet, doctor and slot",
            """
                SELECT EXISTS(
                    SELECT 1
                    FROM pet p
                    JOIN doctor_slot s ON s.id=? AND s.doctor_id=?
                    WHERE p.id=? AND p.owner_user_id=?
                )
                """,
            slotId,
            doctorProfileId,
            petId,
            userId
        );
    }

    public void requireMedicalRecordRelations(long appointmentId, long petId, long doctorProfileId) {
        requireExists(
            "medical record appointment, pet and doctor",
            """
                SELECT EXISTS(
                    SELECT 1 FROM appointment
                    WHERE id=? AND pet_id=? AND doctor_id=?
                )
                """,
            appointmentId,
            petId,
            doctorProfileId
        );
    }

    public void requireAiSessionRelations(long userId, long petId) {
        requireExists(
            "AI session owner and pet",
            "SELECT EXISTS(SELECT 1 FROM pet WHERE id=? AND owner_user_id=?)",
            petId,
            userId
        );
    }

    public void requireKnowledgeVersionBelongsToDocument(long documentId, long versionId) {
        requireExists(
            "knowledge document and active version",
            "SELECT EXISTS(SELECT 1 FROM knowledge_version WHERE id=? AND document_id=?)",
            versionId,
            documentId
        );
    }

    public void requireFileBindingTarget(FileBusinessType businessType, long businessId, long ownerUserId) {
        Objects.requireNonNull(businessType, "businessType");
        String sql = switch (businessType) {
            case PET -> "SELECT EXISTS(SELECT 1 FROM pet WHERE id=? AND owner_user_id=?)";
            case HEALTH_LOG -> """
                SELECT EXISTS(
                    SELECT 1 FROM pet_health_log l
                    JOIN pet p ON p.id=l.pet_id
                    WHERE l.id=? AND p.owner_user_id=?
                )
                """;
            case MEDICAL_DRAFT -> """
                SELECT EXISTS(
                    SELECT 1 FROM medical_record r
                    JOIN appointment a ON a.id=r.appointment_id
                    WHERE r.id=? AND a.user_id=?
                )
                """;
            case MEDICAL_VERSION -> """
                SELECT EXISTS(
                    SELECT 1 FROM medical_record_version v
                    JOIN medical_record r ON r.id=v.record_id
                    JOIN appointment a ON a.id=r.appointment_id
                    WHERE v.id=? AND a.user_id=?
                )
                """;
            case KNOWLEDGE_VERSION -> """
                SELECT EXISTS(
                    SELECT 1 FROM knowledge_version v
                    JOIN knowledge_document d ON d.id=v.document_id
                    WHERE v.id=? AND d.creator_user_id=?
                )
                """;
            case GUIDE -> "SELECT EXISTS(SELECT 1 FROM health_guide WHERE id=? AND editor_user_id=?)";
            case QUALIFICATION -> """
                SELECT EXISTS(
                    SELECT 1 FROM doctor_profile WHERE id=? AND user_id=?
                )
                """;
            case USER_AVATAR -> "SELECT EXISTS(SELECT 1 FROM sys_user WHERE id=? AND id=?)";
        };
        requireExists("file binding " + businessType.name(), sql, businessId, ownerUserId);
    }

    private void requireExists(String relation, String sql, Object... arguments) {
        Integer exists = jdbc.queryForObject(sql, Integer.class, arguments);
        if (exists == null || exists != 1) {
            throw new BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                RELATION_MISMATCH,
                "资源归属关系不一致：" + relation
            );
        }
    }

    public enum FileBusinessType {
        PET,
        HEALTH_LOG,
        MEDICAL_DRAFT,
        MEDICAL_VERSION,
        KNOWLEDGE_VERSION,
        GUIDE,
        QUALIFICATION,
        USER_AVATAR
    }
}
