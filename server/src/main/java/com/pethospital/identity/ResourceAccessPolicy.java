package com.pethospital.identity;

import com.pethospital.shared.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central object-level access policy. Action permissions (for example appointment confirmation)
 * must still be checked by the calling use case; this policy only answers which business object
 * is in the current actor's data scope.
 */
@Component
@Transactional(readOnly = true)
public class ResourceAccessPolicy {
    private static final String MINI = "MINI";
    private static final String WEB = "WEB";
    public static final String DOCTOR_APPOINTMENT_WINDOW = """
        (status IN ('CONFIRMED','IN_CONSULTATION') OR (status='COMPLETED' AND completed_at>=?))
        """;

    private final ObjectProvider<javax.sql.DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final Clock clock;
    private final int completedAppointmentAccessDays;

    public ResourceAccessPolicy(
        ObjectProvider<javax.sql.DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        Clock clock,
        @Value("${identity.access.completed-appointment-days:30}") int completedAppointmentAccessDays
    ) {
        if (completedAppointmentAccessDays < 1 || completedAppointmentAccessDays > 365) {
            throw new IllegalArgumentException("completed appointment access days must be between 1 and 365");
        }
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.clock = clock;
        this.completedAppointmentAccessDays = completedAppointmentAccessDays;
    }

    public PetAccess pet(long petId) {
        return pet(Actor.from(identities.require()), petId);
    }

    public DoctorScope requireDoctorScope() {
        ActorFacts facts = actorFacts(Actor.from(identities.require()));
        if (!facts.staffAudience() || facts.doctorId() == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅有效医生可访问工作台");
        }
        Instant now = clock.instant();
        return new DoctorScope(facts.doctorId(), now, completedSince(now));
    }

    public boolean canReadKnowledgeReviewSource(long fileId, Long versionId) {
        CurrentIdentity actor = identities.require();
        if (!WEB.equals(actor.clientType()) || !actor.authorities().contains("ROLE_DOCTOR")
            || actorFacts(Actor.from(actor)).doctorId() == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc().queryForObject("""
            SELECT EXISTS(
                SELECT 1 FROM knowledge_version v JOIN knowledge_document d ON d.id=v.document_id
                JOIN file_binding b ON b.business_id=v.id AND b.file_id=v.source_file_id AND b.owner_user_id=d.creator_user_id
                JOIN file_object f ON f.id=b.file_id AND f.uploader_user_id=d.creator_user_id
                WHERE v.review_status='IN_REVIEW' AND v.source_file_id=? AND v.id=COALESCE(?,v.id)
                  AND b.business_type='KNOWLEDGE_VERSION' AND f.purpose='KNOWLEDGE' AND f.status='READY'
                  AND f.deleted_at IS NULL)
            """, Boolean.class, fileId, versionId));
    }

    public PetAccess pet(Actor actor, long petId) {
        ActorFacts facts = actorFacts(actor);
        Long ownerId = queryLong("SELECT owner_user_id FROM pet WHERE id=?", petId);
        if (ownerId == null) {
            return PetAccess.NONE;
        }
        if (facts.ownerAudience() && facts.userRole() && ownerId == actor.userId()) {
            return PetAccess.OWNER;
        }
        if (facts.staffAudience() && facts.doctorId() != null
            && hasDoctorPetWindow(facts.doctorId(), petId)) {
            return PetAccess.READ;
        }
        if (facts.staffAudience() && facts.adminRole()) {
            return PetAccess.READ;
        }
        return PetAccess.NONE;
    }

    public PetAccess healthLog(long healthLogId) {
        return healthLog(Actor.from(identities.require()), healthLogId);
    }

    public PetAccess healthLog(Actor actor, long healthLogId) {
        Long petId = queryLong("SELECT pet_id FROM pet_health_log WHERE id=?", healthLogId);
        if (petId == null) {
            return PetAccess.NONE;
        }
        ActorFacts facts = actorFacts(actor);
        Long ownerId = queryLong("SELECT owner_user_id FROM pet WHERE id=?", petId);
        if (ownerId != null && facts.ownerAudience() && facts.userRole()
            && ownerId == actor.userId()) {
            return PetAccess.OWNER;
        }
        if (facts.staffAudience() && facts.doctorId() != null
            && hasDoctorPetWindow(facts.doctorId(), petId)) {
            return PetAccess.READ;
        }
        if (facts.staffAudience() && facts.adminRole()
            && hasPermission(actor.userId(), BackendPermissionCatalog.AUDIT_READ)) {
            return PetAccess.READ;
        }
        return PetAccess.NONE;
    }

    public AppointmentVisibility appointment(long appointmentId) {
        return appointment(Actor.from(identities.require()), appointmentId);
    }

    public AppointmentViewer requireAppointmentViewer() {
        AppointmentViewer viewer = appointmentViewer(Actor.from(identities.require()));
        if (viewer == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权查询预约");
        }
        return viewer;
    }

    public AppointmentVisibility appointment(Actor actor, long appointmentId) {
        AppointmentRow row = jdbc().query(
            "SELECT user_id,doctor_id,status FROM appointment WHERE id=?",
            rs -> rs.next() ? new AppointmentRow(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null,
            appointmentId
        );
        if (row == null) {
            return AppointmentVisibility.NONE;
        }
        AppointmentViewer viewer = appointmentViewer(actor);
        if (viewer == null) {
            return AppointmentVisibility.NONE;
        }
        return switch (viewer.role()) {
            case USER -> row.userId() == viewer.scopeId() ? AppointmentVisibility.FULL : AppointmentVisibility.NONE;
            case ADMIN -> AppointmentVisibility.FULL;
            case DOCTOR -> {
                if (row.doctorId() != viewer.scopeId()) {
                    yield AppointmentVisibility.NONE;
                }
                yield "PENDING".equals(row.status()) ? AppointmentVisibility.LIMITED : AppointmentVisibility.FULL;
            }
        };
    }

    public MedicalAccess medicalRecord(long recordId) {
        return medicalRecord(Actor.from(identities.require()), recordId);
    }

    public MedicalAccess medicalRecord(Actor actor, long recordId) {
        MedicalRow row = jdbc().query(
            """
                SELECT mr.pet_id,mr.doctor_id,mr.status,p.owner_user_id
                FROM medical_record mr
                JOIN pet p ON p.id=mr.pet_id
                WHERE mr.id=?
                """,
            rs -> rs.next()
                ? new MedicalRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4))
                : null,
            recordId
        );
        return medicalAccess(actor, row);
    }

    public MedicalAccess medicalRecordVersion(long versionId) {
        return medicalRecordVersion(Actor.from(identities.require()), versionId);
    }

    public MedicalAccess medicalRecordVersion(Actor actor, long versionId) {
        MedicalRow row = jdbc().query(
            """
                SELECT mr.pet_id,mr.doctor_id,mr.status,p.owner_user_id
                FROM medical_record_version mrv
                JOIN medical_record mr ON mr.id=mrv.record_id
                JOIN pet p ON p.id=mr.pet_id
                WHERE mrv.id=?
                """,
            rs -> rs.next()
                ? new MedicalRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4))
                : null,
            versionId
        );
        MedicalAccess recordAccess = medicalAccess(actor, row);
        return recordAccess == MedicalAccess.AUTHOR ? MedicalAccess.READ : recordAccess;
    }

    public AiSessionAccess aiSession(long sessionId) {
        return aiSession(Actor.from(identities.require()), sessionId);
    }

    public AiSessionAccess aiSession(Actor actor, long sessionId) {
        Long ownerId = queryLong("SELECT user_id FROM ai_session WHERE id=?", sessionId);
        if (ownerId == null) {
            return AiSessionAccess.NONE;
        }
        ActorFacts facts = actorFacts(actor);
        if (facts.ownerAudience() && facts.userRole() && ownerId == actor.userId()) {
            return AiSessionAccess.OWNER;
        }
        if (!facts.staffAudience()) {
            return AiSessionAccess.NONE;
        }
        if (facts.doctorId() != null && hasDoctorSessionWindow(facts.doctorId(), sessionId)) {
            return AiSessionAccess.READ;
        }
        if (facts.adminRole()
            && hasPermission(actor.userId(), BackendPermissionCatalog.AI_CONSULT_AUDIT)) {
            return AiSessionAccess.READ;
        }
        return AiSessionAccess.NONE;
    }

    private MedicalAccess medicalAccess(Actor actor, MedicalRow row) {
        if (row == null) {
            return MedicalAccess.NONE;
        }
        ActorFacts facts = actorFacts(actor);
        if (facts.ownerAudience() && facts.userRole() && row.ownerId() == actor.userId()) {
            return "SUBMITTED".equals(row.status()) ? MedicalAccess.READ : MedicalAccess.NONE;
        }
        if (!facts.staffAudience()) {
            return MedicalAccess.NONE;
        }
        if (facts.doctorId() != null && row.doctorId() == facts.doctorId()) {
            return MedicalAccess.AUTHOR;
        }
        if (facts.doctorId() != null && "SUBMITTED".equals(row.status())
            && hasDoctorPetWindow(facts.doctorId(), row.petId())) {
            return MedicalAccess.READ;
        }
        if (facts.adminRole() && "SUBMITTED".equals(row.status())
            && hasPermission(actor.userId(), BackendPermissionCatalog.AUDIT_READ)) {
            return MedicalAccess.READ;
        }
        return MedicalAccess.NONE;
    }

    private AppointmentViewer appointmentViewer(Actor actor) {
        ActorFacts facts = actorFacts(actor);
        if (facts.ownerAudience() && facts.userRole()) {
            return new AppointmentViewer(AppointmentRole.USER, actor.userId());
        }
        if (facts.staffAudience() && facts.adminRole()) {
            return new AppointmentViewer(AppointmentRole.ADMIN, actor.userId());
        }
        if (facts.staffAudience() && facts.doctorId() != null) {
            return new AppointmentViewer(AppointmentRole.DOCTOR, facts.doctorId());
        }
        return null;
    }

    private ActorFacts actorFacts(Actor actor) {
        ActorFacts stored = jdbc().query(
            """
                SELECT u.status,
                  EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id
                    WHERE ur.user_id=u.id AND r.code='USER' AND r.status='ACTIVE'),
                  EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id
                    WHERE ur.user_id=u.id AND r.code='ADMIN' AND r.status='ACTIVE'),
                  (SELECT dp.id FROM doctor_profile dp
                    WHERE dp.user_id=u.id AND dp.status='ACTIVE'
                    AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id
                      WHERE ur.user_id=u.id AND r.code='DOCTOR' AND r.status='ACTIVE')
                    LIMIT 1)
                FROM sys_user u WHERE u.id=?
                """,
            rs -> {
                if (!rs.next() || !"ACTIVE".equals(rs.getString(1))) {
                    return ActorFacts.inactive(actor.audience());
                }
                Long doctorId = rs.getObject(4, Long.class);
                return new ActorFacts(
                    actor.audience(),
                    rs.getBoolean(2),
                    rs.getBoolean(3),
                    doctorId
                );
            },
            actor.userId()
        );
        return stored == null ? ActorFacts.inactive(actor.audience()) : stored;
    }

    private boolean hasDoctorPetWindow(long doctorId, long petId) {
        return jdbc().queryForObject(
            """
                SELECT EXISTS(SELECT 1 FROM appointment WHERE doctor_id=? AND pet_id=? AND %s)
                """.formatted(DOCTOR_APPOINTMENT_WINDOW),
            Boolean.class,
            doctorId,
            petId,
            accessCutoff()
        );
    }

    private boolean hasDoctorSessionWindow(long doctorId, long sessionId) {
        return jdbc().queryForObject(
            """
                SELECT EXISTS(SELECT 1 FROM appointment WHERE doctor_id=? AND ai_session_id=? AND %s)
                """.formatted(DOCTOR_APPOINTMENT_WINDOW),
            Boolean.class,
            doctorId,
            sessionId,
            accessCutoff()
        );
    }

    private boolean hasPermission(long userId, String permissionCode) {
        return jdbc().queryForObject(
            """
                SELECT EXISTS(SELECT 1
                  FROM sys_user_role ur
                  JOIN sys_role r ON r.id=ur.role_id AND r.status='ACTIVE'
                  JOIN sys_role_menu rm ON rm.role_id=r.id
                  JOIN sys_menu m ON m.id=rm.menu_id AND m.status='ACTIVE'
                  WHERE ur.user_id=? AND m.permission_code=?)
                """,
            Boolean.class,
            userId,
            permissionCode
        );
    }

    private LocalDateTime accessCutoff() {
        return LocalDateTime.ofInstant(completedSince(clock.instant()), ZoneOffset.UTC);
    }

    private Instant completedSince(Instant now) {
        return now.minus(Duration.ofDays(completedAppointmentAccessDays));
    }

    private Long queryLong(String sql, long id) {
        return jdbc().query(sql, rs -> rs.next() ? rs.getLong(1) : null, id);
    }

    private JdbcTemplate jdbc() {
        javax.sql.DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException("Resource access policy requires a configured datasource");
        }
        return new JdbcTemplate(dataSource);
    }

    public record Actor(long userId, String audience) {
        public Actor {
            if (userId <= 0) {
                throw new IllegalArgumentException("userId must be positive");
            }
            audience = Objects.requireNonNull(audience, "audience").trim().toUpperCase(Locale.ROOT);
            if (!MINI.equals(audience) && !WEB.equals(audience)) {
                throw new IllegalArgumentException("audience must be MINI or WEB");
            }
        }

        public static Actor from(CurrentIdentity identity) {
            return new Actor(identity.userId(), identity.clientType());
        }
    }

    public record DoctorScope(long doctorId, Instant now, Instant completedSince) { }

    public enum AppointmentRole { USER, DOCTOR, ADMIN }

    public record AppointmentViewer(AppointmentRole role, long scopeId) { }

    public enum PetAccess { NONE, READ, OWNER }

    public enum AppointmentVisibility { NONE, LIMITED, FULL }

    /** AUTHOR means the original doctor owns the record; the version rows remain immutable. */
    public enum MedicalAccess { NONE, READ, AUTHOR }

    public enum AiSessionAccess { NONE, READ, OWNER }

    private record ActorFacts(String audience, boolean userRole, boolean adminRole, Long doctorId) {
        static ActorFacts inactive(String audience) {
            return new ActorFacts(audience, false, false, null);
        }

        boolean ownerAudience() {
            return MINI.equals(audience);
        }

        boolean staffAudience() {
            return WEB.equals(audience);
        }
    }

    private record AppointmentRow(long userId, long doctorId, String status) { }

    private record MedicalRow(long petId, long doctorId, String status, long ownerId) { }
}
