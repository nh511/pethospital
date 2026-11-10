package com.pethospital.clinical;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pethospital.appointment.AppointmentStateMachine;
import com.pethospital.clinical.MedicalDraftDtos.CompleteRequest;
import com.pethospital.clinical.MedicalDraftDtos.Completed;
import com.pethospital.clinical.MedicalDraftDtos.CompletedAppointment;
import com.pethospital.clinical.MedicalDraftDtos.Draft;
import com.pethospital.clinical.MedicalDraftDtos.DraftResult;
import com.pethospital.clinical.MedicalDraftDtos.MedicalRecordInput;
import com.pethospital.clinical.MedicalDraftDtos.Revised;
import com.pethospital.clinical.MedicalDraftDtos.RevisionRequest;
import com.pethospital.clinical.MedicalDraftDtos.SaveRequest;
import com.pethospital.doctor.DoctorSlotService;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.persistence.CrossTableRelationGuard;
import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import com.pethospital.pet.ActivePetGuard;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.FileBindingService;
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
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MedicalDraftService {
    private final ResourceAccessPolicy access;
    private final DoctorSlotService doctors;
    private final ActivePetGuard pets;
    private final FileBindingService files;
    private final ObjectMapper json;
    private final ObjectProvider<DataSource> dataSources;
    private final CurrentIdentityProvider identities;
    private final AppointmentStateMachine rules;
    private final IdempotencyService idempotency;
    private final RequestHasher hashes;
    private final NoticeService notices;
    private final Clock clock;

    public MedicalDraftService(
        ResourceAccessPolicy access, DoctorSlotService doctors, ActivePetGuard pets,
        FileBindingService files, ObjectMapper json, ObjectProvider<DataSource> dataSources,
        CurrentIdentityProvider identities, AppointmentStateMachine rules, IdempotencyService idempotency,
        RequestHasher hashes, NoticeService notices, Clock clock
    ) {
        this.access = access;
        this.doctors = doctors;
        this.pets = pets;
        this.files = files;
        this.json = json;
        this.dataSources = dataSources;
        this.identities = identities;
        this.rules = rules;
        this.idempotency = idempotency;
        this.hashes = hashes;
        this.notices = notices;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DraftResult get(long appointmentId) {
        long doctorId = access.requireDoctorScope().doctorId();
        JdbcTemplate jdbc = new JdbcTemplate(dataSources.getObject());
        Appointment appointment = requireAppointment(jdbc, appointmentId, doctorId, false);
        requireConsultation(appointment);
        Stored stored = readRecord(jdbc, appointmentId, false);
        if (stored == null) {
            return new DraftResult(null);
        }
        requireDraft(stored, appointment.petId(), doctorId);
        try {
            ObjectNode content = (ObjectNode) json.readTree(stored.content());
            content.remove("attachmentIds");
            MedicalRecordInput input = json.treeToValue(content, MedicalRecordInput.class);
            return result(jdbc, appointmentId, stored.id(), stored.version(), input);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("已保存的病历草稿内容无法读取", invalid);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DraftResult save(long appointmentId, SaveRequest request) {
        long doctorId = access.requireDoctorScope().doctorId();
        DataSource dataSource = dataSources.getObject();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Appointment candidate = requireAppointment(jdbc, appointmentId, doctorId, false);
        Appointment appointment = lockForWrite(jdbc, appointmentId, doctorId, candidate, false);
        requireConsultation(appointment);
        new CrossTableRelationGuard(dataSource).requireMedicalRecordRelations(appointmentId, appointment.petId(), doctorId);
        Stored stored = requireVersion(jdbc, appointmentId, appointment.petId(), doctorId, request.version());
        int currentVersion = request.version();
        long recordId = stored == null ? IdWorker.getId() : stored.id();
        List<Long> attachmentIds = request.attachmentIds().stream().distinct().sorted().toList();
        String content = content(request.medicalRecord(), attachmentIds);
        if (stored == null) {
            jdbc.update("""
                INSERT INTO medical_record(id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,version)
                VALUES (?,?,?,?,'DRAFT',CAST(? AS JSON),0,1)
                """, recordId, appointmentId, appointment.petId(), doctorId, content);
        } else {
            int updated = jdbc.update("""
                UPDATE medical_record SET content_json=CAST(? AS JSON),version=version+1
                WHERE id=? AND status='DRAFT' AND version=?
                """, content, recordId, currentVersion);
            if (updated != 1) {
                throw conflict("VERSION_CONFLICT", "病历草稿已更新，请刷新后重试");
            }
        }
        files.replaceMutable(FileBusinessType.MEDICAL_DRAFT, recordId, attachmentIds);
        return result(jdbc, appointmentId, recordId, currentVersion + 1, request.medicalRecord());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Completed complete(long appointmentId, String key, CompleteRequest request) {
        long doctorId = access.requireDoctorScope().doctorId();
        long doctorUserId = identities.require().userId();
        DataSource dataSource = dataSources.getObject();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Appointment candidate = requireAppointment(jdbc, appointmentId, doctorId, false);
        String path = MedicalDraftController.APPOINTMENTS + "/" + appointmentId + "/complete";
        IdempotencyCommand command = new IdempotencyCommand(doctorUserId, ActorType.DOCTOR, "APPOINTMENT_COMPLETE",
            "APPOINTMENT", "POST", path, key, request, null, null, null);
        return idempotency.execute(command, Completed.class,
            () -> completeLocked(jdbc, dataSource, appointmentId, doctorId, candidate, request, command));
    }

    private IdempotentResult<Completed> completeLocked(
        JdbcTemplate jdbc, DataSource dataSource, long appointmentId, long doctorId,
        Appointment candidate, CompleteRequest request, IdempotencyCommand command
    ) {
        Appointment appointment = lockForWrite(jdbc, appointmentId, doctorId, candidate, true);
        if (appointment.version() != request.version()) {
            throw conflict("VERSION_CONFLICT", "预约已变化，请刷新后重试");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var next = rules.transition(AppointmentStateMachine.Status.valueOf(appointment.status()),
            AppointmentStateMachine.Action.COMPLETE, AppointmentStateMachine.Actor.DOCTOR, now,
            appointment.start().toInstant(ZoneOffset.UTC), appointment.deadline().toInstant(ZoneOffset.UTC));
        new CrossTableRelationGuard(dataSource).requireMedicalRecordRelations(appointmentId, appointment.petId(), doctorId);
        Stored stored = requireVersion(jdbc, appointmentId, appointment.petId(), doctorId, request.recordVersion());
        long recordId = stored == null ? IdWorker.getId() : stored.id();
        List<Long> attachments = request.attachmentIds().stream().distinct().sorted().toList();
        String content = content(request.medicalRecord(), attachments);
        LocalDateTime occurredAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        if (stored == null) {
            jdbc.update("""
                INSERT INTO medical_record(id,appointment_id,pet_id,doctor_id,status,content_json,
                    revision_no,submitted_at,version)
                VALUES (?,?,?,?,'SUBMITTED',CAST(? AS JSON),1,?,1)
                """, recordId, appointmentId, appointment.petId(), doctorId, content, occurredAt);
        } else {
            files.replaceMutable(FileBusinessType.MEDICAL_DRAFT, recordId, List.of());
            int updated = jdbc.update("""
                UPDATE medical_record SET content_json=CAST(? AS JSON),status='SUBMITTED',revision_no=1,
                    submitted_at=?,version=version+1 WHERE id=? AND status='DRAFT' AND version=?
                """, content, occurredAt, recordId, request.recordVersion());
            if (updated != 1) {
                throw conflict("VERSION_CONFLICT", "病历草稿已更新，请刷新后重试");
            }
        }
        appendVersion(jdbc, recordId, 1, null, content, "首次提交", command, occurredAt, attachments);
        int completed = jdbc.update("""
            UPDATE appointment SET status=?,completed_at=?,version=version+1 WHERE id=? AND version=?
            """, next.name(), occurredAt, appointmentId, request.version());
        if (completed != 1) {
            throw conflict("VERSION_CONFLICT", "预约已变化，请刷新后重试");
        }
        jdbc.update("""
            UPDATE appointment_change_request SET status='EXPIRED',open_guard=NULL,responded_at=?,
                response_reason='预约状态已变化',version=version+1 WHERE appointment_id=? AND status='OPEN'
            """, occurredAt, appointmentId);
        jdbc.update("""
            INSERT INTO appointment_status_log(id,appointment_id,from_status,to_status,actor_type,actor_user_id,
                operation_key,request_hash,created_at)
            VALUES (?,?,'IN_CONSULTATION','COMPLETED','DOCTOR',?,?,?,?)
            """, IdWorker.getId(), appointmentId, command.actorUserId(), command.operationKey(),
            hashes.hash(command.method(), command.path(), request), occurredAt);
        notices.publish(new NoticeCommand(appointment.ownerId(), "medical-completed:" + recordId,
            NoticeType.MEDICAL_COMPLETED, "MEDICAL_RECORD", recordId));
        Completed result = new Completed(new CompletedAppointment(Long.toString(appointmentId), next.name(),
            request.version() + 1, now), Long.toString(recordId), 1);
        return new IdempotentResult<>(result, appointmentId, json.valueToTree(result));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Revised revise(long recordId, String key, RevisionRequest request) {
        long doctorId = access.requireDoctorScope().doctorId();
        long actorId = identities.require().userId();
        JdbcTemplate jdbc = new JdbcTemplate(dataSources.getObject());
        requireAuthoredRecord(jdbc, recordId, doctorId, false);
        String path = MedicalRevisionController.RECORDS + "/" + recordId + "/revisions";
        IdempotencyCommand command = new IdempotencyCommand(actorId, ActorType.DOCTOR, "MEDICAL_RECORD_REVISE",
            "MEDICAL_RECORD", "POST", path, key, request, null, null, null);
        return idempotency.execute(command, Revised.class, () -> reviseLocked(jdbc, recordId, doctorId, request, command));
    }

    private IdempotentResult<Revised> reviseLocked(
        JdbcTemplate jdbc, long recordId, long doctorId, RevisionRequest request, IdempotencyCommand command
    ) {
        doctors.lockEligibleDoctorUser(doctorId);
        AuthoredRecord record = requireAuthoredRecord(jdbc, recordId, doctorId, true);
        if (!"SUBMITTED".equals(record.status()) || !"COMPLETED".equals(record.appointmentStatus())) {
            throw conflict("STATE_CONFLICT", "仅已完成诊疗的正式病历可修订");
        }
        if (record.version() != request.version()) {
            throw conflict("VERSION_CONFLICT", "病历已更新，请刷新后重试");
        }
        int revisionNo = record.revisionNo() + 1;
        List<Long> attachments = request.attachmentIds().stream().distinct().sorted().toList();
        String content = content(request.medicalRecord(), attachments);
        int updated = jdbc.update("""
            UPDATE medical_record SET content_json=CAST(? AS JSON),revision_no=?,version=version+1
            WHERE id=? AND status='SUBMITTED' AND version=?
            """, content, revisionNo, recordId, request.version());
        if (updated != 1) {
            throw conflict("VERSION_CONFLICT", "病历已更新，请刷新后重试");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        long revisionId = appendVersion(jdbc, recordId, revisionNo, record.content(), content, request.reason().strip(),
            command, LocalDateTime.ofInstant(now, ZoneOffset.UTC), attachments);
        Revised result = new Revised(Long.toString(recordId), Long.toString(revisionId), revisionNo, record.version() + 1, now);
        return new IdempotentResult<>(result, recordId, json.valueToTree(result));
    }

    private long appendVersion(
        JdbcTemplate jdbc, long recordId, int revisionNo, String before, String after, String reason,
        IdempotencyCommand command, LocalDateTime occurredAt, List<Long> attachments
    ) {
        long revisionId = IdWorker.getId();
        jdbc.update("""
            INSERT INTO medical_record_version(id,record_id,revision_no,before_content_json,after_content_json,
                reason,actor_user_id,idempotency_key,created_at)
            VALUES (?,?,?,CAST(? AS JSON),CAST(? AS JSON),?,?,?,?)
            """, revisionId, recordId, revisionNo, before, after, reason, command.actorUserId(), command.operationKey(), occurredAt);
        files.appendImmutable(FileBusinessType.MEDICAL_VERSION, revisionId, attachments);
        return revisionId;
    }

    private static AuthoredRecord requireAuthoredRecord(JdbcTemplate jdbc, long id, long doctorId, boolean lock) {
        AuthoredRecord record = jdbc.query("""
            SELECT r.status,r.version,r.revision_no,r.content_json,a.status AS appointment_status
            FROM medical_record r
            JOIN appointment a ON a.id=r.appointment_id AND a.pet_id=r.pet_id AND a.doctor_id=r.doctor_id
            WHERE r.id=? AND r.doctor_id=?
            """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next()
            ? new AuthoredRecord(rs.getString("status"), rs.getString("appointment_status"), rs.getInt("version"),
                rs.getInt("revision_no"), rs.getString("content_json")) : null, id, doctorId);
        if (record == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MEDICAL_RECORD_NOT_FOUND", "病历不存在");
        }
        return record;
    }

    private Appointment lockForWrite(JdbcTemplate jdbc, long id, long doctorId, Appointment candidate, boolean lockSlot) {
        doctors.lockEligibleDoctorUser(doctorId);
        pets.lockOwnedActive(candidate.petId(), candidate.ownerId());
        if (lockSlot) {
            Long slot = jdbc.query("SELECT id FROM doctor_slot WHERE id=? AND doctor_id=? FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null, candidate.slotId(), doctorId);
            if (slot == null) {
                throw conflict("VERSION_CONFLICT", "预约时段已变化");
            }
        }
        Appointment appointment = requireAppointment(jdbc, id, doctorId, true);
        if (candidate.petId() != appointment.petId() || candidate.ownerId() != appointment.ownerId()
            || candidate.slotId() != appointment.slotId()) {
            throw conflict("VERSION_CONFLICT", "预约归属或时段已变化");
        }
        return appointment;
    }

    private static Stored requireVersion(JdbcTemplate jdbc, long appointmentId, long petId, long doctorId, int expectedVersion) {
        Stored stored = readRecord(jdbc, appointmentId, true);
        if (stored != null) {
            requireDraft(stored, petId, doctorId);
        }
        if ((stored == null ? 0 : stored.version()) != expectedVersion) {
            throw conflict("VERSION_CONFLICT", "病历草稿已更新，请刷新后重试");
        }
        return stored;
    }

    private String content(MedicalRecordInput input, List<Long> attachmentIds) {
        ObjectNode content = json.valueToTree(input);
        content.set("attachmentIds", json.valueToTree(attachmentIds));
        return content.toString();
    }

    private static Appointment requireAppointment(JdbcTemplate jdbc, long id, long doctorId, boolean lock) {
        Appointment result = jdbc.query("""
            SELECT user_id,pet_id,slot_id,status,version,scheduled_start_at,confirm_deadline_at
            FROM appointment WHERE id=? AND doctor_id=?
            """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next()
            ? new Appointment(rs.getLong("user_id"), rs.getLong("pet_id"), rs.getLong("slot_id"),
                rs.getString("status"), rs.getInt("version"), rs.getObject("scheduled_start_at", LocalDateTime.class),
                rs.getObject("confirm_deadline_at", LocalDateTime.class)) : null, id, doctorId);
        if (result == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "预约不存在");
        }
        return result;
    }

    private static Stored readRecord(JdbcTemplate jdbc, long id, boolean lock) {
        return jdbc.query("""
            SELECT id,pet_id,doctor_id,status,version,content_json FROM medical_record WHERE appointment_id=?
            """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next()
            ? new Stored(rs.getLong("id"), rs.getLong("pet_id"), rs.getLong("doctor_id"), rs.getString("status"),
                rs.getInt("version"), rs.getString("content_json")) : null, id);
    }

    private static DraftResult result(JdbcTemplate jdbc, long appointmentId, long id, int version, MedicalRecordInput input) {
        List<String> attachmentIds = jdbc.query("""
            SELECT file_id FROM file_binding WHERE business_type='MEDICAL_DRAFT' AND business_id=? ORDER BY file_id
            """, (rs, row) -> rs.getString(1), id);
        return new DraftResult(new Draft(Long.toString(id), Long.toString(appointmentId), "DRAFT", version, 0, input, attachmentIds));
    }

    private static void requireConsultation(Appointment appointment) {
        if (!"IN_CONSULTATION".equals(appointment.status())) {
            throw conflict("STATE_CONFLICT", "仅诊疗中预约可读写病历草稿");
        }
    }

    private static void requireDraft(Stored record, long petId, long doctorId) {
        if (record.petId() != petId || record.doctorId() != doctorId) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MEDICAL_DRAFT_NOT_FOUND", "病历草稿不存在");
        }
        if (!"DRAFT".equals(record.status())) {
            throw conflict("STATE_CONFLICT", "病历已正式提交");
        }
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private record Appointment(
        long ownerId, long petId, long slotId, String status, int version, LocalDateTime start, LocalDateTime deadline
    ) { }
    private record Stored(long id, long petId, long doctorId, String status, int version, String content) { }
    private record AuthoredRecord(String status, String appointmentStatus, int version, int revisionNo, String content) { }
}
