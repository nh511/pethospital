package com.pethospital.appointment;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.appointment.AppointmentBookingDtos.AppointmentCreated;
import com.pethospital.appointment.AppointmentBookingDtos.CreateAppointmentRequest;
import com.pethospital.appointment.AppointmentStateMachine.Status;
import com.pethospital.doctor.DoctorSlotService;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.persistence.CrossTableRelationGuard;
import com.pethospital.pet.ActivePetGuard;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.IdempotencyService;
import com.pethospital.shared.NoticeDtos.NoticeCommand;
import com.pethospital.shared.NoticeDtos.NoticeType;
import com.pethospital.shared.NoticeService;
import com.pethospital.shared.RequestHasher;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentBookingService {
    private static final String RESOURCE = "APPOINTMENT";
    private final AppointmentStateMachine rules;
    private final CurrentIdentityProvider identities;
    private final DoctorSlotService doctors;
    private final ActivePetGuard pets;
    private final IdempotencyService idempotency;
    private final NoticeService notices;
    private final RequestHasher hashes;
    private final ObjectMapper json;
    private final Clock clock;
    private final ObjectProvider<DataSource> dataSources;

    public AppointmentBookingService(
        CurrentIdentityProvider identities, DoctorSlotService doctors, ActivePetGuard pets,
        IdempotencyService idempotency, NoticeService notices, RequestHasher hashes, ObjectMapper json,
        Clock clock, ObjectProvider<DataSource> dataSources, AppointmentStateMachine rules
    ) {
        this.identities = identities;
        this.doctors = doctors;
        this.pets = pets;
        this.idempotency = idempotency;
        this.notices = notices;
        this.hashes = hashes;
        this.json = json;
        this.clock = clock;
        this.dataSources = dataSources;
        this.rules = rules;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppointmentCreated create(CreateAppointmentRequest request, String operationKey) {
        CurrentIdentity actor = identities.require();
        if (!"MINI".equals(actor.clientType()) || !actor.roles().contains("USER")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅宠物主人可创建预约");
        }
        CreateAppointmentRequest normalized = new CreateAppointmentRequest(
            request.petId(), request.doctorId(), request.slotId(), request.chiefComplaint().strip(), request.aiSessionId());
        IdempotencyCommand command = new IdempotencyCommand(
            actor.userId(), ActorType.USER, "APPOINTMENT_CREATE", RESOURCE, "POST",
            AppointmentBookingController.PATH, operationKey, normalized, null, null, null);
        return idempotency.execute(command, AppointmentCreated.class, () -> book(command, normalized));
    }

    private IdempotentResult<AppointmentCreated> book(IdempotencyCommand command, CreateAppointmentRequest request) {
        DataSource dataSource = dataSources.getObject();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long ownerId = command.actorUserId();
        long doctorUserId = doctors.lockEligibleDoctorUser(request.doctorId());
        pets.lockOwnedActive(request.petId(), ownerId);
        Slot slot = lockSlot(jdbc, request.slotId());
        if (slot.doctorId() != request.doctorId()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SLOT_DOCTOR_MISMATCH", "时段与医生不匹配");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant startsAt = slot.start().toInstant(ZoneOffset.UTC);
        if (!"OPEN".equals(slot.status()) || !startsAt.isAfter(now)) {
            throw conflict("SLOT_NOT_BOOKABLE", "预约时段已关闭或已开始");
        }
        new CrossTableRelationGuard(dataSource)
            .requireAppointmentRelations(ownerId, request.petId(), request.doctorId(), request.slotId());
        requireNoOverlap(jdbc, request.petId(), slot);
        requireOwnedAiSession(jdbc, request.aiSessionId(), ownerId, request.petId());

        int updated = jdbc.update("""
            UPDATE doctor_slot SET occupied_count=occupied_count+1,version=version+1
            WHERE id=? AND occupied_count<capacity
            """, request.slotId());
        if (updated != 1) {
            throw conflict("SLOT_FULL", "预约时段已满");
        }

        long id = IdWorker.getId();
        String appointmentNo = "APT" + id;
        Instant deadline = rules.confirmationDeadline(now, startsAt);
        String requestHash = hashes.hash(command.method(), command.path(), request);
        jdbc.update("""
            INSERT INTO appointment(
                id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
                initial_start_at,status,chief_complaint,ai_session_id,confirm_deadline_at,idempotency_key,
                request_hash,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?,?)
            """, id, appointmentNo, ownerId, request.petId(), request.doctorId(), request.slotId(), slot.start(),
            slot.end(), slot.start(), request.chiefComplaint(), request.aiSessionId(), utc(deadline),
            command.operationKey(), requestHash, utc(now), utc(now));
        jdbc.update("""
            INSERT INTO appointment_status_log(
                id,appointment_id,from_status,to_status,actor_type,actor_user_id,operation_key,request_hash
            ) VALUES (?,?,NULL,'PENDING','USER',?,?,?)
            """, IdWorker.getId(), id, ownerId, command.operationKey(), requestHash);
        notices.publish(new NoticeCommand(doctorUserId, "appointment-created:" + id, NoticeType.APPOINTMENT_CREATED, RESOURCE, id));
        AppointmentCreated result = new AppointmentCreated(
            Long.toString(id), appointmentNo, Status.PENDING, startsAt.toString(),
            slot.end().toInstant(ZoneOffset.UTC).toString(), deadline.toString(), 0);
        return new IdempotentResult<>(result, id, json.valueToTree(result));
    }

    private static Slot lockSlot(JdbcTemplate jdbc, long id) {
        Slot slot = jdbc.query("""
            SELECT doctor_id,start_at,end_at,status FROM doctor_slot WHERE id=? FOR UPDATE
            """, rs -> rs.next() ? new Slot(
                rs.getLong("doctor_id"), rs.getObject("start_at", LocalDateTime.class),
                rs.getObject("end_at", LocalDateTime.class), rs.getString("status")) : null, id);
        if (slot == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SLOT_NOT_FOUND", "预约时段不存在");
        }
        return slot;
    }

    private static void requireNoOverlap(JdbcTemplate jdbc, long petId, Slot slot) {
        Long overlapping = jdbc.query("""
            SELECT id FROM appointment
            WHERE pet_id=? AND status IN ('PENDING','CONFIRMED','IN_CONSULTATION')
            AND scheduled_start_at<? AND scheduled_end_at>? LIMIT 1 FOR UPDATE
            """, rs -> rs.next() ? rs.getLong("id") : null, petId, slot.end(), slot.start());
        if (overlapping != null) {
            throw conflict("PET_APPOINTMENT_OVERLAP", "宠物已有时间重叠的预约");
        }
    }

    private static void requireOwnedAiSession(JdbcTemplate jdbc, Long sessionId, long ownerId, long petId) {
        if (sessionId == null) {
            return;
        }
        Long session = jdbc.query("""
            SELECT id FROM ai_session WHERE id=? AND user_id=? AND pet_id=? FOR UPDATE
            """, rs -> rs.next() ? rs.getLong("id") : null, sessionId, ownerId, petId);
        if (session == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AI_SESSION_MISMATCH", "问诊会话与预约宠物不匹配");
        }
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private record Slot(long doctorId, LocalDateTime start, LocalDateTime end, String status) { }
}
