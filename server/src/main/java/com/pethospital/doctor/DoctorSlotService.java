package com.pethospital.doctor;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.doctor.DoctorSlotDtos.SlotCreateRequest;
import com.pethospital.doctor.DoctorSlotDtos.SlotCapacityPatchRequest;
import com.pethospital.doctor.DoctorSlotDtos.SlotCloseRequest;
import com.pethospital.doctor.DoctorSlotDtos.SlotOpenRequest;
import com.pethospital.doctor.DoctorSlotDtos.PublicSlotList;
import com.pethospital.doctor.DoctorSlotDtos.PublicSlotView;
import com.pethospital.doctor.DoctorSlotDtos.StaffSlotList;
import com.pethospital.doctor.DoctorSlotDtos.StaffSlotView;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.AuditService;
import com.pethospital.shared.IdempotencyService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class DoctorSlotService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DoctorSlotService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        IdempotencyService idempotency,
        AuditService audit,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.idempotency = idempotency;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StaffSlotList list(Long requestedDoctorId, LocalDate fromDate, LocalDate toDate) {
        validateRange(fromDate, toDate);
        CurrentIdentity actor = requireStaff();
        JdbcTemplate jdbc = jdbc();
        Long doctorId = resolveListDoctor(jdbc, actor, requestedDoctorId);
        List<StaffSlotView> items;
        if (doctorId == null) {
            items = jdbc.query("""
                SELECT id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
                FROM doctor_slot WHERE slot_date BETWEEN ? AND ?
                ORDER BY start_at,id
                """, (result, row) -> view(result), fromDate, toDate);
        } else {
            items = jdbc.query("""
                SELECT id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
                FROM doctor_slot WHERE doctor_id=? AND slot_date BETWEEN ? AND ?
                ORDER BY start_at,id
                """, (result, row) -> view(result), doctorId, fromDate, toDate);
        }
        return new StaffSlotList(items);
    }

    @Transactional(readOnly = true)
    public PublicSlotList publicList(long doctorId, LocalDate fromDate, LocalDate toDate) {
        validatePublicRange(fromDate, toDate);
        JdbcTemplate jdbc = jdbc();
        ensureEligibleDoctor(jdbc, doctorId);
        List<PublicSlotView> items = jdbc.query("""
            SELECT id,doctor_id,start_at,end_at,capacity,occupied_count,status
            FROM doctor_slot
            WHERE doctor_id=? AND slot_date BETWEEN ? AND ?
              AND status='OPEN' AND occupied_count < capacity AND start_at > ?
            ORDER BY start_at,id
            """, (result, row) -> publicView(result),
            doctorId, fromDate, toDate, utcTimestamp(clock.instant()));
        return new PublicSlotList(items);
    }

    @Transactional
    public StaffSlotView create(SlotCreateRequest request, String operationKey) {
        CurrentIdentity actor = requireStaff();
        validateCreate(request);
        var command = new IdempotencyCommand(
            actor.userId(), actorType(actor), "DOCTOR_SLOT_CREATE", "DOCTOR_SLOT",
            "POST", "/api/v1/staff/slots", operationKey, request, null, null, null
        );
        return idempotency.execute(
            command,
            StaffSlotView.class,
            () -> insert(actor, request)
        );
    }

    @Transactional
    public StaffSlotView patchCapacity(long slotId, SlotCapacityPatchRequest request) {
        if (request.version() < 0) {
            throw badRequest("version不能为负数");
        }
        CurrentIdentity actor = requireStaff();
        JdbcTemplate jdbc = jdbc();
        SlotRow current = lockAuthorizedSlot(jdbc, actor, slotId);
        requireVersion(current, request.version());
        if (request.capacity() < current.occupiedCount()) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "CAPACITY_BELOW_OCCUPIED",
                "容量不能小于已占用数量"
            );
        }
        int updated = jdbc.update("""
            UPDATE doctor_slot SET capacity=?,version=version+1 WHERE id=? AND version=?
            """, request.capacity(), slotId, request.version());
        if (updated != 1) {
            throw versionConflict();
        }
        StaffSlotView result = find(jdbc, slotId);
        appendAudit(actor, "DOCTOR_SLOT_CAPACITY_UPDATE", slotId, snapshot(current), snapshot(result), null);
        return result;
    }

    @Transactional
    public StaffSlotView close(long slotId, SlotCloseRequest request, String operationKey) {
        if (request.version() < 0) {
            throw badRequest("version不能为负数");
        }
        CurrentIdentity actor = requireStaff();
        String reason = request.reason().strip();
        var normalized = new SlotCloseRequest(request.version(), reason);
        var command = new IdempotencyCommand(
            actor.userId(), actorType(actor), "DOCTOR_SLOT_CLOSE", "DOCTOR_SLOT",
            "POST", "/api/v1/staff/slots/" + slotId + "/close", operationKey,
            normalized, null, reason, null
        );
        return idempotency.execute(
            command,
            StaffSlotView.class,
            () -> changeStatus(actor, slotId, request.version(), "CLOSED", false)
        );
    }

    @Transactional
    public StaffSlotView open(long slotId, SlotOpenRequest request, String operationKey) {
        if (request.version() < 0) {
            throw badRequest("version不能为负数");
        }
        CurrentIdentity actor = requireStaff();
        var command = new IdempotencyCommand(
            actor.userId(), actorType(actor), "DOCTOR_SLOT_OPEN", "DOCTOR_SLOT",
            "POST", "/api/v1/staff/slots/" + slotId + "/open", operationKey,
            request, null, null, null
        );
        return idempotency.execute(
            command,
            StaffSlotView.class,
            () -> changeStatus(actor, slotId, request.version(), "OPEN", true)
        );
    }

    private IdempotentResult<StaffSlotView> insert(CurrentIdentity actor, SlotCreateRequest request) {
        JdbcTemplate jdbc = jdbc();
        DoctorRow doctor = lockEligibleDoctor(jdbc, request.doctorId());
        if (!actor.roles().contains("ADMIN") && doctor.userId() != actor.userId()) {
            throw forbidden("医生只能创建本人时段");
        }
        Instant start = request.startAt().toInstant();
        Instant end = request.endAt().toInstant();
        Integer overlaps = jdbc.queryForObject("""
            SELECT COUNT(*) FROM doctor_slot
            WHERE doctor_id=? AND start_at < ? AND end_at > ?
            """, Integer.class, doctor.id(), utcTimestamp(end), utcTimestamp(start));
        if (overlaps != null && overlaps > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "SLOT_OVERLAP", "该医生已有重叠时段");
        }
        long slotId = IdWorker.getId();
        jdbc.update("""
            INSERT INTO doctor_slot(
                id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
            ) VALUES (?,?,?,?,?,?,0,'OPEN',0)
            """, slotId, doctor.id(), request.date(), utcTimestamp(start), utcTimestamp(end), request.capacity());
        StaffSlotView view = find(jdbc, slotId);
        return new IdempotentResult<>(
            view,
            slotId,
            objectMapper.valueToTree(Map.of(
                "doctorId", view.doctorId(),
                "slotDate", view.slotDate().toString(),
                "startAt", view.startAt(),
                "endAt", view.endAt(),
                "capacity", view.capacity(),
                "status", view.status(),
                "version", view.version()
            ))
        );
    }

    private IdempotentResult<StaffSlotView> changeStatus(
        CurrentIdentity actor,
        long slotId,
        int version,
        String targetStatus,
        boolean requireFuture
    ) {
        JdbcTemplate jdbc = jdbc();
        SlotRow current = lockAuthorizedSlot(jdbc, actor, slotId);
        requireVersion(current, version);
        if (current.status().equals(targetStatus)) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "SLOT_STATUS_CONFLICT",
                "时段已经是目标状态"
            );
        }
        if (requireFuture && !current.startAt().isAfter(clock.instant())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAST_SLOT_CANNOT_OPEN", "过去或已开始时段不能重开");
        }
        int updated = jdbc.update("""
            UPDATE doctor_slot SET status=?,version=version+1 WHERE id=? AND version=?
            """, targetStatus, slotId, version);
        if (updated != 1) {
            throw versionConflict();
        }
        StaffSlotView result = find(jdbc, slotId);
        return new IdempotentResult<>(result, slotId, objectMapper.valueToTree(snapshot(result)));
    }

    private SlotRow lockAuthorizedSlot(JdbcTemplate jdbc, CurrentIdentity actor, long slotId) {
        Long doctorId = jdbc.query("SELECT doctor_id FROM doctor_slot WHERE id=?",
            result -> result.next() ? result.getLong("doctor_id") : null, slotId);
        if (doctorId == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SLOT_NOT_FOUND", "时段不存在");
        }
        DoctorRow doctor = lockEligibleDoctor(jdbc, doctorId);
        if (!actor.roles().contains("ADMIN") && doctor.userId() != actor.userId()) {
            throw forbidden("医生只能操作本人时段");
        }
        SlotRow slot = jdbc.query("""
            SELECT id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
            FROM doctor_slot WHERE id=? FOR UPDATE
            """, result -> result.next() ? slotRow(result) : null, slotId);
        if (slot == null || slot.doctorId() != doctor.id()) {
            throw new BusinessException(HttpStatus.CONFLICT, "SLOT_CHANGED", "时段归属已变化，请重试");
        }
        return slot;
    }

    private Long resolveListDoctor(JdbcTemplate jdbc, CurrentIdentity actor, Long requestedDoctorId) {
        if (actor.roles().contains("ADMIN")) {
            if (requestedDoctorId != null) {
                ensureEligibleDoctor(jdbc, requestedDoctorId);
            }
            return requestedDoctorId;
        }
        DoctorRow own = eligibleDoctorByUser(jdbc, actor.userId());
        if (requestedDoctorId != null && requestedDoctorId != own.id()) {
            throw forbidden("医生只能查询本人时段");
        }
        return own.id();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long lockEligibleDoctorUser(long doctorId) {
        return lockEligibleDoctor(jdbc(), doctorId).userId();
    }

    private DoctorRow lockEligibleDoctor(JdbcTemplate jdbc, long doctorId) {
        DoctorRow doctor = jdbc.query("""
            SELECT d.id,d.user_id FROM doctor_profile d
            WHERE d.id=? AND d.status='ACTIVE' FOR UPDATE
            """, result -> result.next() ? new DoctorRow(
                result.getLong("id"), result.getLong("user_id")
            ) : null, doctorId);
        if (doctor == null || !isEligibleAccount(jdbc, doctor.userId())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "DOCTOR_NOT_AVAILABLE", "医生当前不可提供预约服务");
        }
        return doctor;
    }

    private void ensureEligibleDoctor(JdbcTemplate jdbc, long doctorId) {
        DoctorRow doctor = jdbc.query("""
            SELECT id,user_id FROM doctor_profile WHERE id=? AND status='ACTIVE'
            """, result -> result.next() ? new DoctorRow(
                result.getLong("id"), result.getLong("user_id")
            ) : null, doctorId);
        if (doctor == null || !isEligibleAccount(jdbc, doctor.userId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "DOCTOR_NOT_FOUND", "医生不存在或不可排班");
        }
    }

    private DoctorRow eligibleDoctorByUser(JdbcTemplate jdbc, long userId) {
        DoctorRow doctor = jdbc.query("""
            SELECT id,user_id FROM doctor_profile WHERE user_id=? AND status='ACTIVE'
            """, result -> result.next() ? new DoctorRow(
                result.getLong("id"), result.getLong("user_id")
            ) : null, userId);
        if (doctor == null || !isEligibleAccount(jdbc, doctor.userId())) {
            throw forbidden("医生资料当前不可用");
        }
        return doctor;
    }

    private boolean isEligibleAccount(JdbcTemplate jdbc, long userId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role r ON r.id=ur.role_id
            WHERE u.id=? AND u.status='ACTIVE' AND r.code='DOCTOR' AND r.status='ACTIVE'
            """, Integer.class, userId);
        return count != null && count > 0;
    }

    private StaffSlotView find(JdbcTemplate jdbc, long slotId) {
        StaffSlotView view = jdbc.query("""
            SELECT id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
            FROM doctor_slot WHERE id=?
            """, result -> result.next() ? view(result) : null, slotId);
        if (view == null) {
            throw new IllegalStateException("时段创建后无法读取");
        }
        return view;
    }

    private void validateCreate(SlotCreateRequest request) {
        Instant start = request.startAt().toInstant();
        Instant end = request.endAt().toInstant();
        if (!end.isAfter(start)) {
            throw badRequest("结束时间必须晚于开始时间");
        }
        if (!start.isAfter(clock.instant())) {
            throw badRequest("只能创建未来时段");
        }
        LocalDate shanghaiDate = start.atZone(BUSINESS_ZONE).toLocalDate();
        if (!request.date().equals(shanghaiDate)) {
            throw badRequest("date必须与开始时间的上海日期一致");
        }
    }

    private static void validateRange(LocalDate fromDate, LocalDate toDate) {
        if (toDate.isBefore(fromDate)) {
            throw badRequest("toDate不能早于fromDate");
        }
    }

    private static void validatePublicRange(LocalDate fromDate, LocalDate toDate) {
        validateRange(fromDate, toDate);
        if (ChronoUnit.DAYS.between(fromDate, toDate) > 13) {
            throw badRequest("查询日期范围最多包含14天");
        }
    }

    private CurrentIdentity requireStaff() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType())
            || (!actor.roles().contains("DOCTOR") && !actor.roles().contains("ADMIN"))) {
            throw forbidden("仅医生或管理员可访问排班");
        }
        return actor;
    }

    private static ActorType actorType(CurrentIdentity actor) {
        return actor.roles().contains("ADMIN") ? ActorType.ADMIN : ActorType.DOCTOR;
    }

    private static StaffSlotView view(ResultSet result) throws SQLException {
        return view(slotRow(result));
    }

    private static StaffSlotView view(SlotRow slot) {
        return new StaffSlotView(
            Long.toString(slot.id()), Long.toString(slot.doctorId()), slot.slotDate(),
            slot.startAt().toString(), slot.endAt().toString(), slot.capacity(),
            slot.occupiedCount(), slot.capacity() - slot.occupiedCount(), slot.status(), slot.version()
        );
    }

    private static PublicSlotView publicView(ResultSet result) throws SQLException {
        int capacity = result.getInt("capacity");
        int occupiedCount = result.getInt("occupied_count");
        return new PublicSlotView(
            Long.toString(result.getLong("id")),
            Long.toString(result.getLong("doctor_id")),
            utcInstant(result, "start_at").toString(),
            utcInstant(result, "end_at").toString(),
            capacity,
            capacity - occupiedCount,
            result.getString("status")
        );
    }

    private static SlotRow slotRow(ResultSet result) throws SQLException {
        return new SlotRow(
            result.getLong("id"), result.getLong("doctor_id"),
            result.getObject("slot_date", LocalDate.class),
            utcInstant(result, "start_at"), utcInstant(result, "end_at"),
            result.getInt("capacity"), result.getInt("occupied_count"),
            result.getString("status"), result.getInt("version")
        );
    }

    private static Map<String, Object> snapshot(SlotRow slot) {
        return Map.of(
            "capacity", slot.capacity(), "occupiedCount", slot.occupiedCount(),
            "status", slot.status(), "version", slot.version()
        );
    }

    private static Map<String, Object> snapshot(StaffSlotView slot) {
        return Map.of(
            "capacity", slot.capacity(), "occupiedCount", slot.occupiedCount(),
            "status", slot.status(), "version", slot.version()
        );
    }

    private void appendAudit(
        CurrentIdentity actor,
        String action,
        long slotId,
        Object before,
        Object after,
        String reason
    ) {
        audit.append(new AuditEvent(
            actor.userId(), actorType(actor), action, new ResourceReference("DOCTOR_SLOT", slotId),
            objectMapper.valueToTree(before), objectMapper.valueToTree(after), reason,
            null, null, AuditResult.SUCCESS, null
        ));
    }

    private static void requireVersion(SlotRow slot, int version) {
        if (slot.version() != version) {
            throw versionConflict();
        }
    }

    private static Timestamp utcTimestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static Instant utcInstant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SLOT_DEPENDENCY_UNAVAILABLE",
                "排班数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static BusinessException forbidden(String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    private static BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "数据已更新，请刷新后重试");
    }

    private record DoctorRow(long id, long userId) {
    }

    private record SlotRow(
        long id,
        long doctorId,
        LocalDate slotDate,
        Instant startAt,
        Instant endAt,
        int capacity,
        int occupiedCount,
        String status,
        int version
    ) {
    }
}
