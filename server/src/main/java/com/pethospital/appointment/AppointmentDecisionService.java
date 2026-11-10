package com.pethospital.appointment;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.appointment.AppointmentDecisionDtos.DecisionResult;
import com.pethospital.appointment.AppointmentDecisionDtos.ReasonRequest;
import com.pethospital.appointment.AppointmentDecisionDtos.VersionRequest;
import com.pethospital.appointment.AppointmentStateMachine.Action;
import com.pethospital.appointment.AppointmentStateMachine.Actor;
import com.pethospital.appointment.AppointmentStateMachine.Status;
import com.pethospital.doctor.DoctorSlotService;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.identity.ResourceAccessPolicy.AppointmentRole;
import com.pethospital.identity.ResourceAccessPolicy.AppointmentViewer;
import com.pethospital.pet.PetMapper;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.AuditService;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.IdempotencyService;
import com.pethospital.shared.NoticeDtos.NoticeCommand;
import com.pethospital.shared.NoticeDtos.NoticeType;
import com.pethospital.shared.NoticeService;
import com.pethospital.shared.RequestHasher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class AppointmentDecisionService {
    private static final String RESOURCE = "APPOINTMENT";
    private static final String TIMEOUT_REASON = "待确认预约已超时";
    private final AppointmentStateMachine rules;
    private final ResourceAccessPolicy access;
    private final CurrentIdentityProvider identities;
    private final DoctorSlotService doctors;
    private final PetMapper pets;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final NoticeService notices;
    private final RequestHasher hashes;
    private final ObjectMapper json;
    private final Clock clock;
    private final ObjectProvider<JdbcTemplate> templates;

    public AppointmentDecisionService(
        ResourceAccessPolicy access, CurrentIdentityProvider identities, DoctorSlotService doctors, PetMapper pets,
        IdempotencyService idempotency, AuditService audit, NoticeService notices, RequestHasher hashes, ObjectMapper json, Clock clock,
        ObjectProvider<JdbcTemplate> templates, AppointmentStateMachine rules
    ) {
        this.access = access;
        this.identities = identities;
        this.doctors = doctors;
        this.pets = pets;
        this.idempotency = idempotency;
        this.audit = audit;
        this.notices = notices;
        this.hashes = hashes;
        this.json = json;
        this.clock = clock;
        this.templates = templates;
        this.rules = rules;
    }

    public DecisionResult confirm(long id, String key, VersionRequest request) {
        return decide(id, key, Action.CONFIRM, request.version(), null, request, doctorViewer());
    }

    public DecisionResult reject(long id, String key, ReasonRequest request) {
        return decideWithReason(id, key, Action.REJECT, request, doctorViewer());
    }

    public DecisionResult cancel(long id, String key, ReasonRequest request) {
        return decideWithReason(id, key, Action.CANCEL, request, access.requireAppointmentViewer());
    }

    public DecisionResult start(long id, String key, VersionRequest request) {
        return decide(id, key, Action.START, request.version(), null, request, doctorViewer());
    }

    public DecisionResult markNoShow(long id, String key, ReasonRequest request) {
        return decideWithReason(id, key, Action.MARK_NO_SHOW, request, doctorViewer());
    }

    public boolean expirePending(long id) {
        JdbcTemplate jdbc = templates.getObject();
        LockedAppointment appointment = lockAppointment(jdbc, id, null, Action.CANCEL);
        Instant now = now();
        if (appointment == null || appointment.status() != Status.PENDING || now.isBefore(appointment.deadline())) {
            return false;
        }
        String hash = hashes.hash("POST", "/internal/appointments/" + id + "/expire", Map.of("version", appointment.version()));
        Transition event = new Transition(Action.CANCEL, Actor.SYSTEM, null, TIMEOUT_REASON, UUID.randomUUID().toString(), hash, now);
        DecisionResult result = transition(jdbc, appointment, event);
        audit.append(new AuditEvent(null, ActorType.SYSTEM, "APPOINTMENT_TIMEOUT_CANCEL",
            new ResourceReference(RESOURCE, id),
            json.createObjectNode().put("status", appointment.status().name()).put("version", appointment.version()),
            json.valueToTree(result), TIMEOUT_REASON, null, hash, AuditResult.SUCCESS, null));
        return true;
    }

    private AppointmentViewer doctorViewer() {
        return new AppointmentViewer(AppointmentRole.DOCTOR, access.requireDoctorScope().doctorId());
    }

    private DecisionResult decideWithReason(long id, String key, Action action, ReasonRequest request, AppointmentViewer viewer) {
        ReasonRequest normalized = new ReasonRequest(request.version(), request.reason().strip());
        return decide(id, key, action, normalized.version(), normalized.reason(), normalized, viewer);
    }

    private DecisionResult decide(
        long id, String key, Action action, int version, String reason, Object payload, AppointmentViewer viewer
    ) {
        long actorId = identities.require().userId();
        Actor actor = Actor.valueOf(viewer.role().name());
        String root = actor == Actor.USER ? AppointmentBookingController.PATH : AppointmentDecisionController.PATH;
        String actionPath = action == Action.MARK_NO_SHOW ? "no-show" : action.name().toLowerCase(Locale.ROOT);
        String path = root + "/" + id + "/" + actionPath;
        IdempotencyCommand command = new IdempotencyCommand(actorId, ActorType.valueOf(actor.name()),
            "APPOINTMENT_" + action.name(), RESOURCE, "POST", path, key, payload, null, reason, null);
        return idempotency.execute(command, DecisionResult.class, () -> {
            JdbcTemplate jdbc = templates.getObject();
            LockedAppointment appointment = lockAppointment(jdbc, id, viewer, action);
            if (appointment == null) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "预约不存在");
            }
            if (appointment.version() != version) {
                throw versionConflict();
            }
            Transition event = new Transition(action, actor, actorId, reason, key, hashes.hash("POST", path, payload), now());
            DecisionResult result = transition(jdbc, appointment, event);
            return new IdempotentResult<>(result, id, json.valueToTree(result));
        });
    }

    private LockedAppointment lockAppointment(JdbcTemplate jdbc, long id, AppointmentViewer viewer, Action action) {
        Long ownerScope = viewer != null && viewer.role() == AppointmentRole.USER ? viewer.scopeId() : null;
        Long doctorScope = viewer != null && viewer.role() == AppointmentRole.DOCTOR ? viewer.scopeId() : null;
        Candidate candidate = jdbc.query("""
            SELECT doctor_id,user_id,pet_id,slot_id FROM appointment
            WHERE id=? AND (? IS NULL OR user_id=?) AND (? IS NULL OR doctor_id=?)
            """, rs -> rs.next() ? candidate(rs) : null, id, ownerScope, ownerScope, doctorScope, doctorScope);
        if (candidate == null) {
            return null;
        }
        long doctorId = candidate.doctorId();
        Long doctorUserId;
        if (doctorScope != null) {
            doctorUserId = doctors.lockEligibleDoctorUser(doctorId);
        } else {
            doctorUserId = jdbc.query("SELECT user_id FROM doctor_profile WHERE id=? FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null, doctorId);
        }
        PetMapper.PetRow pet = pets.findOwnedRow(candidate.petId(), candidate.userId(), true);
        if (pet == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PET_NOT_FOUND", "宠物不存在");
        }
        if (action != Action.CANCEL && !"ACTIVE".equals(pet.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PET_ARCHIVED", "宠物已归档");
        }
        Long slot = jdbc.query("SELECT id FROM doctor_slot WHERE id=? AND doctor_id=? FOR UPDATE",
            rs -> rs.next() ? rs.getLong(1) : null, candidate.slotId(), doctorId);
        LockedAppointment appointment = jdbc.query("""
            SELECT doctor_id,user_id,pet_id,slot_id,status,scheduled_start_at,confirm_deadline_at,version
            FROM appointment WHERE id=? AND doctor_id=? FOR UPDATE
            """, rs -> rs.next() ? new LockedAppointment(id, candidate(rs), doctorUserId,
                Status.valueOf(rs.getString("status")), instant(rs, "scheduled_start_at"),
                instant(rs, "confirm_deadline_at"), rs.getInt("version")) : null, id, doctorId);
        if (doctorUserId == null || slot == null || appointment == null || !candidate.equals(appointment.candidate())) {
            throw versionConflict();
        }
        return appointment;
    }

    private DecisionResult transition(JdbcTemplate jdbc, LockedAppointment appointment, Transition event) {
        Status next = rules.transition(appointment.status(), event.action(), event.actor(), event.now(),
            appointment.start(), appointment.deadline());
        long id = appointment.id();
        Candidate candidate = appointment.candidate();
        LocalDateTime occurredAt = LocalDateTime.ofInstant(event.now(), ZoneOffset.UTC);
        boolean cancelled = next == Status.CANCELLED;
        if (next == Status.REJECTED || cancelled) {
            int released = jdbc.update("""
                UPDATE doctor_slot SET occupied_count=occupied_count-1,version=version+1
                WHERE id=? AND occupied_count>0
                """, candidate.slotId());
            if (released != 1) {
                throw new BusinessException(HttpStatus.CONFLICT, "SLOT_OCCUPANCY_CONFLICT", "时段占位状态不一致");
            }
        }
        int changed = jdbc.update("""
            UPDATE appointment SET status=?,
                confirmed_at=CASE ? WHEN 'CONFIRMED' THEN ? WHEN 'REJECTED' THEN NULL ELSE confirmed_at END,
                started_at=CASE ? WHEN 'IN_CONSULTATION' THEN ? ELSE started_at END,
                cancelled_by=?,cancelled_at=?,terminal_reason=?,version=version+1
            WHERE id=? AND version=?
            """, next.name(), next.name(), occurredAt, next.name(), occurredAt, cancelled ? event.actor().name() : null,
            cancelled ? occurredAt : null, event.reason(), id, appointment.version());
        if (changed != 1) {
            throw versionConflict();
        }
        jdbc.update("""
            UPDATE appointment_change_request
            SET status='EXPIRED',open_guard=NULL,responded_at=?,response_reason='预约状态已变化',version=version+1
            WHERE appointment_id=? AND status='OPEN'
            """, occurredAt, id);
        jdbc.update("""
            INSERT INTO appointment_status_log(
                id,appointment_id,from_status,to_status,actor_type,actor_user_id,reason,operation_key,request_hash,created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?)
            """, IdWorker.getId(), id, appointment.status().name(), next.name(), event.actor().name(),
            event.actorId(), event.reason(), event.key(), event.hash(), occurredAt);
        NoticeType notice = switch (next) {
            case CONFIRMED -> NoticeType.APPOINTMENT_CONFIRMED;
            case REJECTED -> NoticeType.APPOINTMENT_REJECTED;
            case CANCELLED -> NoticeType.APPOINTMENT_CANCELLED;
            default -> null;
        };
        if (notice != null) {
            String eventKey = notice.name() + ":" + id;
            notices.publish(new NoticeCommand(candidate.userId(), eventKey, notice, RESOURCE, id));
            if (cancelled && candidate.userId() != appointment.doctorUserId()) {
                notices.publish(new NoticeCommand(appointment.doctorUserId(), eventKey, notice, RESOURCE, id));
            }
        }
        return new DecisionResult(Long.toString(id), next, appointment.version() + 1);
    }

    private static Candidate candidate(ResultSet rs) throws SQLException {
        return new Candidate(rs.getLong("doctor_id"), rs.getLong("user_id"), rs.getLong("pet_id"), rs.getLong("slot_id"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "预约已变化，请刷新后重试");
    }

    private record Candidate(long doctorId, long userId, long petId, long slotId) { }

    private record LockedAppointment(
        long id, Candidate candidate, Long doctorUserId, Status status, Instant start, Instant deadline, int version
    ) { }

    private record Transition(Action action, Actor actor, Long actorId, String reason, String key, String hash, Instant now) { }
}
