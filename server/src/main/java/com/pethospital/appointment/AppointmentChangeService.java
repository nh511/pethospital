package com.pethospital.appointment;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.appointment.AppointmentChangeDtos.Accepted;
import com.pethospital.appointment.AppointmentChangeDtos.Closed;
import com.pethospital.appointment.AppointmentChangeDtos.CreateRequest;
import com.pethospital.appointment.AppointmentChangeDtos.Created;
import com.pethospital.appointment.AppointmentChangeDtos.HistoryItem;
import com.pethospital.appointment.AppointmentChangeDtos.HistoryPage;
import com.pethospital.appointment.AppointmentChangeDtos.RejectRequest;
import com.pethospital.appointment.AppointmentChangeDtos.Status;
import com.pethospital.appointment.AppointmentChangeDtos.VersionRequest;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.identity.ResourceAccessPolicy.AppointmentRole;
import com.pethospital.identity.ResourceAccessPolicy.AppointmentViewer;
import com.pethospital.pet.ActivePetGuard;
import com.pethospital.doctor.DoctorSlotService;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
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
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentChangeService {
    private static final String RESOURCE = "APPOINTMENT_CHANGE_REQUEST";
    private static final String EXPIRY_REASON = "改期申请已到期";
    private final ResourceAccessPolicy access;
    private final CurrentIdentityProvider identities;
    private final DoctorSlotService doctors;
    private final ActivePetGuard pets;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final NoticeService notices;
    private final AppointmentStateMachine rules;
    private final RequestHasher hashes;
    private final ObjectMapper json;
    private final Clock clock;
    private final ObjectProvider<JdbcTemplate> templates;

    public AppointmentChangeService(
        ResourceAccessPolicy access, CurrentIdentityProvider identities, DoctorSlotService doctors, ActivePetGuard pets,
        IdempotencyService idempotency, RequestHasher hashes, ObjectMapper json, Clock clock,
        ObjectProvider<JdbcTemplate> templates, NoticeService notices, AppointmentStateMachine rules, AuditService audit
    ) {
        this.access = access;
        this.identities = identities;
        this.doctors = doctors;
        this.pets = pets;
        this.idempotency = idempotency;
        this.hashes = hashes;
        this.json = json;
        this.clock = clock;
        this.templates = templates;
        this.notices = notices;
        this.rules = rules;
        this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Accepted accept(long id, String key, VersionRequest request) {
        AppointmentViewer viewer = access.requireAppointmentViewer();
        if (viewer.role() == AppointmentRole.ADMIN) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "管理员不能代为接受改期");
        }
        JdbcTemplate jdbc = templates.getObject();
        Change change = requireChange(jdbc, id, false);
        Candidate candidate = requireCandidate(jdbc, change.appointmentId(), viewer);
        long actorId = identities.require().userId();
        requireOtherParty(change, viewer, actorId);
        String path = AppointmentChangeController.CHANGE_REQUESTS + "/" + id + "/accept";
        var before = json.createObjectNode()
            .put("slotId", Long.toString(change.oldSlotId()))
            .put("scheduledStartAt", change.oldStart().toString())
            .put("appointmentVersion", change.baseVersion());
        IdempotencyCommand command = new IdempotencyCommand(actorId, ActorType.valueOf(viewer.role().name()),
            "APPOINTMENT_CHANGE_ACCEPT", RESOURCE, "POST", path, key,
            request, before, null, null);
        return idempotency.execute(command, Accepted.class,
            () -> acceptLocked(jdbc, id, change, candidate, viewer, actorId, request.version()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Closed reject(long id, String key, RejectRequest request) {
        RejectRequest normalized = new RejectRequest(request.version(), request.reason().strip());
        return closeManual(id, key, Status.REJECTED, normalized.version(), normalized.reason(), normalized);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Closed withdraw(long id, String key, VersionRequest request) {
        return closeManual(id, key, Status.WITHDRAWN, request.version(), null, request);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean expire(long id) {
        JdbcTemplate jdbc = templates.getObject();
        Change observed = requireChange(jdbc, id, false);
        if (observed.status() != Status.OPEN || observed.expiresAt().isAfter(clock.instant())) {
            return false;
        }
        Candidate candidate = requireCandidate(jdbc, observed.appointmentId(), null);
        long doctorUserId = lockParties(jdbc, observed, candidate, false);
        Change change = requireChange(jdbc, id, true);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (change.status() != Status.OPEN || change.expiresAt().isAfter(now)) {
            return false;
        }
        expireLocked(jdbc, id, change, candidate, doctorUserId, now);
        return true;
    }

    private Closed closeManual(long id, String key, Status status, int version, String reason, Object body) {
        AppointmentViewer viewer = access.requireAppointmentViewer();
        if (viewer.role() == AppointmentRole.ADMIN) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "管理员不能处理改期申请");
        }
        JdbcTemplate jdbc = templates.getObject();
        Change observed = requireChange(jdbc, id, false);
        Candidate candidate = requireCandidate(jdbc, observed.appointmentId(), viewer);
        long actorId = identities.require().userId();
        requireClosingParty(observed, viewer, actorId, status);
        String action = status == Status.REJECTED ? "reject" : "withdraw";
        String path = AppointmentChangeController.CHANGE_REQUESTS + "/" + id + "/" + action;
        IdempotencyCommand command = new IdempotencyCommand(actorId, ActorType.valueOf(viewer.role().name()),
            status == Status.REJECTED ? "APPOINTMENT_CHANGE_REJECT" : "APPOINTMENT_CHANGE_WITHDRAW",
            RESOURCE, "POST", path, key, body, changeSnapshot(observed), reason, null);
        return idempotency.execute(command, Closed.class, () -> {
            long doctorUserId = lockParties(jdbc, observed, candidate, viewer.role() == AppointmentRole.DOCTOR);
            Change change = requireChange(jdbc, id, true);
            requireClosingParty(change, viewer, actorId, status);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            if (change.status() == Status.EXPIRED || (change.status() == Status.OPEN && !change.expiresAt().isAfter(now))) {
                throw new BusinessException(HttpStatus.GONE, "REQUEST_EXPIRED", "改期申请已到期");
            }
            if (change.version() != version) {
                throw conflict("VERSION_CONFLICT", "改期申请版本已变化");
            }
            Closed result = finish(jdbc, id, change, candidate, doctorUserId, status, actorId, reason, now);
            return new IdempotentResult<>(result, id, json.valueToTree(result));
        });
    }

    private static void requireClosingParty(Change change, AppointmentViewer viewer, long actorId, Status status) {
        if (status == Status.REJECTED) {
            requireOtherParty(change, viewer, actorId);
        } else if (change.proposerId() != actorId || !change.proposerType().equals(viewer.role().name())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅申请发起方可撤回");
        }
    }

    private long lockParties(JdbcTemplate jdbc, Change observed, Candidate candidate, boolean eligibleDoctor) {
        Long doctorUserId = eligibleDoctor ? Long.valueOf(doctors.lockEligibleDoctorUser(candidate.doctorId()))
            : jdbc.query("SELECT user_id FROM doctor_profile WHERE id=? FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null, candidate.doctorId());
        Long petId = jdbc.query("SELECT id FROM pet WHERE id=? AND owner_user_id=? FOR UPDATE",
            rs -> rs.next() ? rs.getLong(1) : null, candidate.petId(), candidate.ownerId());
        Appointment appointment = lockAppointment(jdbc, observed.appointmentId());
        if (doctorUserId == null || petId == null || appointment == null || !candidate.equals(appointment.candidate())) {
            throw conflict("VERSION_CONFLICT", "预约归属或时段已变化，请刷新后重试");
        }
        return doctorUserId;
    }

    private void expireLocked(JdbcTemplate jdbc, long id, Change change, Candidate candidate, long doctorUserId, Instant now) {
        Closed result = finish(jdbc, id, change, candidate, doctorUserId, Status.EXPIRED, null, EXPIRY_REASON, now);
        audit.append(new AuditEvent(null, ActorType.SYSTEM, "APPOINTMENT_CHANGE_EXPIRE",
            new ResourceReference(RESOURCE, id), changeSnapshot(change), json.valueToTree(result),
            EXPIRY_REASON, null, null, AuditResult.SUCCESS, null));
    }

    private Closed finish(
        JdbcTemplate jdbc, long id, Change change, Candidate candidate, long doctorUserId, Status status,
        Long responderId, String reason, Instant now
    ) {
        int updated = jdbc.update("""
            UPDATE appointment_change_request SET status=?,open_guard=NULL,responder_user_id=?,responded_at=?,
                response_reason=?,version=version+1 WHERE id=? AND status='OPEN' AND version=?
            """, status.name(), responderId, utc(now), reason, id, change.version());
        if (updated != 1) {
            throw conflict("CHANGE_REQUEST_NOT_AVAILABLE", "改期申请已结束");
        }
        NoticeType type = switch (status) {
            case REJECTED -> NoticeType.APPOINTMENT_CHANGE_REJECTED;
            case WITHDRAWN -> NoticeType.APPOINTMENT_CHANGE_WITHDRAWN;
            case EXPIRED -> NoticeType.APPOINTMENT_CHANGE_EXPIRED;
            default -> throw new IllegalArgumentException("Unsupported change request outcome");
        };
        String eventKey = type.name() + ":" + id;
        notices.publish(new NoticeCommand(candidate.ownerId(), eventKey, type, "APPOINTMENT", change.appointmentId()));
        if (doctorUserId != candidate.ownerId()) {
            notices.publish(new NoticeCommand(doctorUserId, eventKey, type, "APPOINTMENT", change.appointmentId()));
        }
        return new Closed(Long.toString(id), Long.toString(change.appointmentId()), status, now, change.version() + 1);
    }

    private JsonNode changeSnapshot(Change change) {
        return json.createObjectNode().put("status", change.status().name()).put("version", change.version());
    }

    private IdempotentResult<Accepted> acceptLocked(
        JdbcTemplate jdbc, long id, Change observed, Candidate candidate, AppointmentViewer viewer,
        long actorId, int version
    ) {
        long doctorUserId = doctors.lockEligibleDoctorUser(candidate.doctorId());
        pets.lockOwnedActive(candidate.petId(), candidate.ownerId());
        if (candidate.slotId() != observed.oldSlotId()) {
            throw conflict("VERSION_CONFLICT", "原预约时段已变化");
        }
        Slot target = lockTarget(jdbc, candidate, observed.newSlotId(), HttpStatus.CONFLICT);
        Appointment appointment = lockAppointment(jdbc, observed.appointmentId());
        Change change = requireChange(jdbc, id, true);
        requireOtherParty(change, viewer, actorId);
        if (appointment == null || !candidate.equals(appointment.candidate()) || change.version() != version
            || change.baseVersion() != appointment.version() || !change.equals(observed)
            || !appointment.start().equals(change.oldStart()) || !target.start().equals(change.newStart())) {
            throw conflict("VERSION_CONFLICT", "预约或改期申请已变化，请刷新后重试");
        }
        boolean pending = appointment.status() == AppointmentStateMachine.Status.PENDING;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (change.status() != AppointmentChangeDtos.Status.OPEN
            || (!pending && appointment.status() != AppointmentStateMachine.Status.CONFIRMED)
            || !change.expiresAt().isAfter(now) || !appointment.start().isAfter(now)
            || !target.start().isAfter(now) || (pending && !appointment.deadline().isAfter(now))
            || !"OPEN".equals(target.status())) {
            throw conflict("CHANGE_REQUEST_NOT_AVAILABLE", "改期申请已失效或目标时段不可预约");
        }
        Long overlap = jdbc.query("""
            SELECT id FROM appointment WHERE pet_id=? AND id<>?
              AND status IN ('PENDING','CONFIRMED','IN_CONSULTATION')
              AND scheduled_start_at<? AND scheduled_end_at>? LIMIT 1 FOR UPDATE
            """, rs -> rs.next() ? rs.getLong("id") : null, candidate.petId(), change.appointmentId(),
            utc(target.end()), utc(target.start()));
        if (overlap != null) {
            throw conflict("PET_APPOINTMENT_OVERLAP", "宠物已有时间重叠的预约");
        }
        int reserved = jdbc.update("""
            UPDATE doctor_slot SET occupied_count=occupied_count+1,version=version+1
            WHERE id=? AND occupied_count<capacity
            """, target.id());
        if (reserved != 1) {
            throw conflict("SLOT_FULL", "目标时段已满");
        }
        int released = jdbc.update("""
            UPDATE doctor_slot SET occupied_count=occupied_count-1,version=version+1
            WHERE id=? AND occupied_count>0
            """, candidate.slotId());
        if (released != 1) {
            throw conflict("SLOT_OCCUPANCY_CONFLICT", "原时段占位状态不一致");
        }
        // The database also requires the stored deadline to precede the start of a confirmed appointment.
        Instant deadline = rules.rescheduleDeadline(appointment.deadline(), target.start());
        jdbc.update("""
            UPDATE appointment SET slot_id=?,scheduled_start_at=?,scheduled_end_at=?,confirm_deadline_at=?,version=version+1
            WHERE id=?
            """, target.id(), utc(target.start()), utc(target.end()), utc(deadline), change.appointmentId());
        jdbc.update("""
            UPDATE appointment_change_request SET status='ACCEPTED',open_guard=NULL,
                responder_user_id=?,responded_at=?,version=version+1 WHERE id=?
            """, actorId, utc(now), id);
        String eventKey = "appointment-rescheduled:" + id;
        notices.publish(new NoticeCommand(candidate.ownerId(), eventKey, NoticeType.APPOINTMENT_RESCHEDULED,
            "APPOINTMENT", change.appointmentId()));
        if (doctorUserId != candidate.ownerId()) {
            notices.publish(new NoticeCommand(doctorUserId, eventKey, NoticeType.APPOINTMENT_RESCHEDULED,
                "APPOINTMENT", change.appointmentId()));
        }
        Accepted result = new Accepted(Long.toString(id), Long.toString(change.appointmentId()),
            AppointmentChangeDtos.Status.ACCEPTED, Long.toString(target.id()), target.start(), target.end(),
            version + 1, appointment.version() + 1);
        return new IdempotentResult<>(result, id, json.valueToTree(result));
    }

    private static void requireOtherParty(Change change, AppointmentViewer viewer, long actorId) {
        if (change.proposerId() == actorId || change.proposerType().equals(viewer.role().name())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅改期申请的另一方可接受");
        }
    }

    private static Change requireChange(JdbcTemplate jdbc, long id, boolean lock) {
        Change change = jdbc.query("""
            SELECT appointment_id,proposer_user_id,proposer_type,old_slot_id,new_slot_id,
                   old_start_at,new_start_at,base_appointment_version,status,expires_at,version
            FROM appointment_change_request WHERE id=?
            """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next() ? new Change(
                rs.getLong("appointment_id"), rs.getLong("proposer_user_id"), rs.getString("proposer_type"),
                rs.getLong("old_slot_id"), rs.getLong("new_slot_id"), instant(rs, "old_start_at"),
                instant(rs, "new_start_at"), rs.getInt("base_appointment_version"),
                AppointmentChangeDtos.Status.valueOf(rs.getString("status")), instant(rs, "expires_at"),
                rs.getInt("version")) : null, id);
        if (change == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "CHANGE_REQUEST_NOT_FOUND", "改期申请不存在");
        }
        return change;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Created create(long appointmentId, String key, CreateRequest request) {
        AppointmentViewer viewer = access.requireAppointmentViewer();
        if (viewer.role() == AppointmentRole.ADMIN) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅预约主人或对应医生可提出改期");
        }
        ActorType actor = ActorType.valueOf(viewer.role().name());
        String root = actor == ActorType.USER ? AppointmentBookingController.PATH : AppointmentDecisionController.PATH;
        String path = root + "/" + appointmentId + "/change-requests";
        CreateRequest normalized = new CreateRequest(request.version(), request.newSlotId(), request.reason().strip());
        IdempotencyCommand command = new IdempotencyCommand(identities.require().userId(), actor,
            "APPOINTMENT_CHANGE_CREATE", "APPOINTMENT_CHANGE_REQUEST", "POST", path, key,
            normalized, null, normalized.reason(), null);
        return idempotency.execute(command, Created.class, () -> insert(appointmentId, normalized, viewer, command));
    }

    @Transactional(readOnly = true)
    public HistoryPage history(long appointmentId, int page, int size) {
        JdbcTemplate jdbc = templates.getObject();
        requireCandidate(jdbc, appointmentId, access.requireAppointmentViewer());
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM appointment_change_request WHERE appointment_id=?",
            Long.class, appointmentId);
        List<HistoryItem> items = jdbc.query("""
            SELECT id,appointment_id,proposer_type,old_slot_id,new_slot_id,old_start_at,new_start_at,
                   status,reason,expires_at,responded_at,response_reason,version,created_at
            FROM appointment_change_request WHERE appointment_id=?
            ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
            """, (rs, index) -> new HistoryItem(
            rs.getString("id"), rs.getString("appointment_id"), rs.getString("proposer_type"),
            rs.getString("old_slot_id"), rs.getString("new_slot_id"), instant(rs, "old_start_at"), instant(rs, "new_start_at"),
            AppointmentChangeDtos.Status.valueOf(rs.getString("status")), rs.getString("reason"), instant(rs, "expires_at"),
            instant(rs, "responded_at"), rs.getString("response_reason"), rs.getInt("version"), instant(rs, "created_at")),
            appointmentId, size, (page - 1L) * size);
        return new HistoryPage(items, page, size, total);
    }

    private IdempotentResult<Created> insert(
        long appointmentId, CreateRequest request, AppointmentViewer viewer, IdempotencyCommand command
    ) {
        JdbcTemplate jdbc = templates.getObject();
        Candidate candidate = requireCandidate(jdbc, appointmentId, viewer);
        long newSlotId = request.newSlotId();
        if (newSlotId == candidate.slotId()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SAME_SLOT", "请选择不同的预约时段");
        }
        long doctorUserId = doctors.lockEligibleDoctorUser(candidate.doctorId());
        pets.lockOwnedActive(candidate.petId(), candidate.ownerId());
        Slot target = lockTarget(jdbc, candidate, newSlotId, HttpStatus.BAD_REQUEST);
        Appointment appointment = lockAppointment(jdbc, appointmentId);
        if (appointment == null || !candidate.equals(appointment.candidate()) || appointment.version() != request.version()) {
            throw conflict("VERSION_CONFLICT", "预约已变化，请刷新后重试");
        }
        if (appointment.status() != AppointmentStateMachine.Status.PENDING
            && appointment.status() != AppointmentStateMachine.Status.CONFIRMED) {
            throw conflict("STATE_CONFLICT", "当前预约状态不可申请改期");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = Stream.of(now.plus(24, ChronoUnit.HOURS), appointment.start(), target.start(),
                appointment.status() == AppointmentStateMachine.Status.PENDING ? appointment.deadline() : Instant.MAX)
            .min(Instant::compareTo).orElseThrow();
        if (!"OPEN".equals(target.status()) || !expiresAt.isAfter(now)) {
            throw conflict("CHANGE_REQUEST_NOT_AVAILABLE", "改期窗口已关闭或目标时段不可预约");
        }
        Long open = jdbc.query("""
            SELECT id FROM appointment_change_request WHERE appointment_id=? AND open_guard=1 FOR UPDATE
            """, rs -> rs.next() ? rs.getLong(1) : null, appointmentId);
        if (open != null) {
            Change previous = requireChange(jdbc, open, true);
            if (previous.expiresAt().isAfter(now)) {
                throw conflict("CHANGE_REQUEST_ALREADY_OPEN", "该预约已有待处理的改期申请");
            }
            expireLocked(jdbc, open, previous, candidate, doctorUserId, now);
        }
        long id = IdWorker.getId();
        jdbc.update("""
            INSERT INTO appointment_change_request(
                id,appointment_id,proposer_user_id,proposer_type,old_slot_id,new_slot_id,base_appointment_version,
                old_start_at,new_start_at,status,open_guard,reason,expires_at,idempotency_key,request_hash,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,'OPEN',1,?,?,?,?,?,?)
            """, id, appointmentId, command.actorUserId(), command.actorType().name(), candidate.slotId(), newSlotId,
            appointment.version(), utc(appointment.start()), utc(target.start()), request.reason(), utc(expiresAt),
            command.operationKey(), hashes.hash(command.method(), command.path(), request), utc(now), utc(now));
        Created result = new Created(Long.toString(id), Long.toString(appointmentId), Long.toString(newSlotId),
            AppointmentChangeDtos.Status.OPEN, expiresAt, 0);
        return new IdempotentResult<>(result, id, json.valueToTree(result));
    }

    private static Candidate requireCandidate(JdbcTemplate jdbc, long id, AppointmentViewer viewer) {
        Long owner = viewer != null && viewer.role() == AppointmentRole.USER ? viewer.scopeId() : null;
        Long doctor = viewer != null && viewer.role() == AppointmentRole.DOCTOR ? viewer.scopeId() : null;
        Candidate result = jdbc.query("""
            SELECT user_id,pet_id,doctor_id,slot_id FROM appointment
            WHERE id=? AND (? IS NULL OR user_id=?) AND (? IS NULL OR doctor_id=?)
            """, rs -> rs.next() ? candidate(rs) : null, id, owner, owner, doctor, doctor);
        if (result == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "预约不存在");
        }
        return result;
    }

    private static Appointment lockAppointment(JdbcTemplate jdbc, long id) {
        return jdbc.query("""
            SELECT user_id,pet_id,doctor_id,slot_id,scheduled_start_at,confirm_deadline_at,status,version
            FROM appointment WHERE id=? FOR UPDATE
            """, rs -> rs.next() ? new Appointment(candidate(rs), instant(rs, "scheduled_start_at"),
                instant(rs, "confirm_deadline_at"), AppointmentStateMachine.Status.valueOf(rs.getString("status")),
                rs.getInt("version")) : null, id);
    }

    private static Slot lockTarget(JdbcTemplate jdbc, Candidate candidate, long newSlotId, HttpStatus mismatchStatus) {
        Long targetDoctor = jdbc.query("SELECT doctor_id FROM doctor_slot WHERE id=?",
            rs -> rs.next() ? rs.getLong(1) : null, newSlotId);
        if (targetDoctor == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SLOT_NOT_FOUND", "预约时段不存在");
        }
        if (targetDoctor != candidate.doctorId()) {
            throw new BusinessException(mismatchStatus, "SLOT_DOCTOR_MISMATCH", "改期时段必须属于原接诊医生");
        }
        Slot first = lockSlot(jdbc, Math.min(candidate.slotId(), newSlotId));
        Slot second = lockSlot(jdbc, Math.max(candidate.slotId(), newSlotId));
        if (first.doctorId() != candidate.doctorId() || second.doctorId() != candidate.doctorId()) {
            throw conflict("VERSION_CONFLICT", "预约时段归属已变化，请刷新后重试");
        }
        return first.id() == newSlotId ? first : second;
    }

    private static Slot lockSlot(JdbcTemplate jdbc, long id) {
        Slot slot = jdbc.query("SELECT id,doctor_id,start_at,end_at,status FROM doctor_slot WHERE id=? FOR UPDATE",
            rs -> rs.next() ? new Slot(rs.getLong("id"), rs.getLong("doctor_id"), instant(rs, "start_at"),
                instant(rs, "end_at"), rs.getString("status")) : null, id);
        if (slot == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SLOT_NOT_FOUND", "预约时段不存在");
        }
        return slot;
    }

    private static Candidate candidate(ResultSet rs) throws SQLException {
        return new Candidate(rs.getLong("user_id"), rs.getLong("pet_id"), rs.getLong("doctor_id"), rs.getLong("slot_id"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime time = rs.getObject(column, LocalDateTime.class);
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private record Candidate(long ownerId, long petId, long doctorId, long slotId) { }
    private record Slot(long id, long doctorId, Instant start, Instant end, String status) { }
    private record Change(
        long appointmentId, long proposerId, String proposerType, long oldSlotId, long newSlotId,
        Instant oldStart, Instant newStart, int baseVersion, AppointmentChangeDtos.Status status, Instant expiresAt, int version
    ) { }
    private record Appointment(
        Candidate candidate, Instant start, Instant deadline, AppointmentStateMachine.Status status, int version
    ) { }
}
