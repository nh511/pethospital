package com.pethospital.doctor;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.appointment.UnfinishedAppointmentQuery;
import com.pethospital.doctor.DoctorDtos.AdminDoctorCreateRequest;
import com.pethospital.doctor.DoctorDtos.AdminDoctorDisableRequest;
import com.pethospital.doctor.DoctorDtos.AdminDoctorEnableRequest;
import com.pethospital.doctor.DoctorDtos.AdminDoctorPatchRequest;
import com.pethospital.doctor.DoctorDtos.DoctorProfilePage;
import com.pethospital.doctor.DoctorDtos.DoctorProfileView;
import com.pethospital.doctor.DoctorDtos.PublicDoctorDetail;
import com.pethospital.doctor.DoctorDtos.PublicDoctorPage;
import com.pethospital.doctor.DoctorDtos.PublicDoctorSummary;
import com.pethospital.doctor.DoctorDtos.StaffDoctorPatchRequest;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.AuditService;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.FileBindingService;
import com.pethospital.shared.IdempotencyService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DoctorProfileService {
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final IdempotencyService idempotency;
    private final FileBindingService fileBindings;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final UnfinishedAppointmentQuery appointments;

    public DoctorProfileService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        IdempotencyService idempotency,
        FileBindingService fileBindings,
        AuditService audit,
        ObjectMapper objectMapper,
        UnfinishedAppointmentQuery appointments
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.idempotency = idempotency;
        this.fileBindings = fileBindings;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.appointments = appointments;
    }

    @Transactional(readOnly = true)
    public DoctorProfilePage adminList(int page, int pageSize) {
        JdbcTemplate jdbc = jdbc();
        long total = value(jdbc.queryForObject("SELECT COUNT(*) FROM doctor_profile", Long.class));
        List<DoctorProfileView> items = jdbc.query("""
            SELECT id,user_id,doctor_name,title,specialties,introduction,qualification_file_id,
                   status,version,created_at,updated_at
            FROM doctor_profile ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
            """, (result, row) -> view(result), pageSize, Math.multiplyExact((long) page - 1L, pageSize));
        return new DoctorProfilePage(items, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public PublicDoctorPage publicList(String specialty, int page, int pageSize) {
        JdbcTemplate jdbc = jdbc();
        String normalizedSpecialty = nullable(specialty);
        long total;
        List<PublicDoctorSummary> items;
        String eligible = """
            FROM doctor_profile d
            JOIN sys_user u ON u.id=d.user_id AND u.status='ACTIVE'
            WHERE d.status='ACTIVE'
              AND EXISTS (
                  SELECT 1 FROM sys_user_role ur
                  JOIN sys_role r ON r.id=ur.role_id
                  WHERE ur.user_id=d.user_id AND r.code='DOCTOR' AND r.status='ACTIVE'
              )
            """;
        long offset = Math.multiplyExact((long) page - 1L, pageSize);
        if (normalizedSpecialty == null) {
            total = value(jdbc.queryForObject("SELECT COUNT(*) " + eligible, Long.class));
            items = jdbc.query("""
                SELECT d.id,d.doctor_name,d.title,d.specialties
                """ + eligible + " ORDER BY d.doctor_name,d.id LIMIT ? OFFSET ?",
                (result, row) -> publicSummary(result), pageSize, offset);
        } else {
            String filtered = eligible + " AND d.specialties IS NOT NULL AND LOCATE(?,d.specialties)>0";
            total = value(jdbc.queryForObject("SELECT COUNT(*) " + filtered, Long.class, normalizedSpecialty));
            items = jdbc.query("""
                SELECT d.id,d.doctor_name,d.title,d.specialties
                """ + filtered + " ORDER BY d.doctor_name,d.id LIMIT ? OFFSET ?",
                (result, row) -> publicSummary(result), normalizedSpecialty, pageSize, offset);
        }
        return new PublicDoctorPage(items, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public PublicDoctorDetail publicDetail(long doctorId) {
        PublicDoctorDetail detail = jdbc().query("""
            SELECT d.id,d.doctor_name,d.title,d.specialties,d.introduction
            FROM doctor_profile d
            JOIN sys_user u ON u.id=d.user_id AND u.status='ACTIVE'
            WHERE d.id=? AND d.status='ACTIVE'
              AND EXISTS (
                  SELECT 1 FROM sys_user_role ur
                  JOIN sys_role r ON r.id=ur.role_id
                  WHERE ur.user_id=d.user_id AND r.code='DOCTOR' AND r.status='ACTIVE'
              )
            """, result -> result.next() ? new PublicDoctorDetail(
                Long.toString(result.getLong("id")), result.getString("doctor_name"),
                result.getString("title"), result.getString("specialties"),
                result.getString("introduction")
            ) : null, doctorId);
        if (detail == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "DOCTOR_NOT_FOUND", "医生不存在或不可预约");
        }
        return detail;
    }

    @Transactional
    public DoctorProfileView adminCreate(AdminDoctorCreateRequest request, String operationKey) {
        CurrentIdentity actor = identities.require();
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "DOCTOR_PROFILE_CREATE", "DOCTOR_PROFILE",
            "POST", "/api/v1/admin/doctors", operationKey, request, null, null, null
        );
        return idempotency.execute(command, DoctorProfileView.class, () -> createProfile(request));
    }

    @Transactional
    public DoctorProfileView adminPatch(long doctorId, AdminDoctorPatchRequest request) {
        if (request.version() < 0) {
            throw badRequest("version不能为负数");
        }
        CurrentIdentity actor = identities.require();
        JdbcTemplate jdbc = jdbc();
        ProfileRow current = profile(jdbc, doctorId, true);
        if (current.version() != request.version()) {
            throw versionConflict();
        }
        boolean hasChange = request.doctorName() != null || request.title() != null
            || request.specialties() != null || request.introduction() != null
            || request.qualificationFileId() != null;
        if (!hasChange) {
            throw badRequest("至少提交一个可修改字段");
        }
        String doctorName = request.doctorName() == null ? current.doctorName() : required(request.doctorName());
        String title = request.title() == null ? current.title() : nullable(request.title());
        String specialties = request.specialties() == null ? current.specialties() : nullable(request.specialties());
        String introduction = request.introduction() == null ? current.introduction() : nullable(request.introduction());
        Long qualification = request.qualificationFileId() == null
            ? current.qualificationFileId() : request.qualificationFileId();
        int updated = jdbc.update("""
            UPDATE doctor_profile
            SET doctor_name=?,title=?,specialties=?,introduction=?,qualification_file_id=?,version=version+1
            WHERE id=? AND version=?
            """, doctorName, title, specialties, introduction, qualification, doctorId, request.version());
        if (updated != 1) {
            throw versionConflict();
        }
        if (request.qualificationFileId() != null) {
            fileBindings.replaceMutable(FileBusinessType.QUALIFICATION, doctorId, List.of(qualification));
        }
        DoctorProfileView result = find(jdbc, doctorId);
        audit(actor, "DOCTOR_PROFILE_ADMIN_UPDATE", doctorId, snapshot(current), snapshot(result));
        return result;
    }

    @Transactional
    public DoctorProfileView adminDisable(
        long doctorId,
        AdminDoctorDisableRequest request,
        String operationKey
    ) {
        CurrentIdentity actor = identities.require();
        String reason = request.reason().strip();
        var normalized = new AdminDoctorDisableRequest(request.version(), reason);
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "DOCTOR_PROFILE_DISABLE", "DOCTOR_PROFILE",
            "POST", "/api/v1/admin/doctors/" + doctorId + "/disable", operationKey,
            normalized, null, reason, null
        );
        return idempotency.execute(
            command,
            DoctorProfileView.class,
            () -> disableProfile(doctorId, request.version())
        );
    }

    @Transactional
    public DoctorProfileView adminEnable(
        long doctorId,
        AdminDoctorEnableRequest request,
        String operationKey
    ) {
        CurrentIdentity actor = identities.require();
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "DOCTOR_PROFILE_ENABLE", "DOCTOR_PROFILE",
            "POST", "/api/v1/admin/doctors/" + doctorId + "/enable", operationKey,
            request, null, null, null
        );
        return idempotency.execute(
            command,
            DoctorProfileView.class,
            () -> enableProfile(doctorId, request.version())
        );
    }

    @Transactional(readOnly = true)
    public DoctorProfileView staffProfile() {
        CurrentIdentity actor = requireDoctor();
        return findByUser(jdbc(), actor.userId());
    }

    @Transactional
    public DoctorProfileView staffPatch(StaffDoctorPatchRequest request) {
        if (request.version() < 0) {
            throw badRequest("version不能为负数");
        }
        if (request.specialties() == null && request.introduction() == null) {
            throw badRequest("至少提交一个可修改字段");
        }
        CurrentIdentity actor = requireDoctor();
        JdbcTemplate jdbc = jdbc();
        ProfileRow current = profileByUser(jdbc, actor.userId(), true);
        if (current.version() != request.version()) {
            throw versionConflict();
        }
        String specialties = request.specialties() == null ? current.specialties() : nullable(request.specialties());
        String introduction = request.introduction() == null ? current.introduction() : nullable(request.introduction());
        int updated = jdbc.update("""
            UPDATE doctor_profile SET specialties=?,introduction=?,version=version+1
            WHERE id=? AND user_id=? AND version=?
            """, specialties, introduction, current.id(), actor.userId(), request.version());
        if (updated != 1) {
            throw versionConflict();
        }
        DoctorProfileView result = find(jdbc, current.id());
        audit(actor, "DOCTOR_PROFILE_SELF_UPDATE", current.id(), snapshot(current), snapshot(result));
        return result;
    }

    private IdempotentResult<DoctorProfileView> createProfile(AdminDoctorCreateRequest request) {
        JdbcTemplate jdbc = jdbc();
        Integer eligible = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role r ON r.id=ur.role_id
            WHERE u.id=? AND u.status='ACTIVE' AND r.code='DOCTOR' AND r.status='ACTIVE'
            """, Integer.class, request.userId());
        if (eligible == null || eligible == 0) {
            throw new BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "DOCTOR_ROLE_REQUIRED",
                "只有启用且具有DOCTOR角色的账号可以建立医生资料"
            );
        }
        long doctorId = IdWorker.getId();
        try {
            jdbc.update("""
                INSERT INTO doctor_profile(
                    id,user_id,doctor_name,title,specialties,introduction,qualification_file_id,status,version
                ) VALUES (?,?,?,?,?,?,?,'ACTIVE',0)
                """,
                doctorId, request.userId(), required(request.doctorName()), nullable(request.title()),
                nullable(request.specialties()), nullable(request.introduction()), request.qualificationFileId()
            );
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "DOCTOR_PROFILE_EXISTS", "该账号已有医生资料");
        }
        if (request.qualificationFileId() != null) {
            fileBindings.replaceMutable(
                FileBusinessType.QUALIFICATION,
                doctorId,
                List.of(request.qualificationFileId())
            );
        }
        DoctorProfileView result = find(jdbc, doctorId);
        return new IdempotentResult<>(result, doctorId, objectMapper.valueToTree(snapshot(result)));
    }

    private IdempotentResult<DoctorProfileView> disableProfile(long doctorId, int version) {
        JdbcTemplate jdbc = jdbc();
        ProfileRow current = profile(jdbc, doctorId, true);
        requireVersion(current, version);
        if (!"ACTIVE".equals(current.status())) {
            throw stateConflict("医生资料已经停用");
        }
        int pendingCount = appointments.lockForDoctor(doctorId);
        if (pendingCount > 0) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "UNFINISHED_APPOINTMENTS",
                "医生仍有未终结预约，请先处理",
                Map.of("pendingCount", pendingCount, "doctorPendingCount", pendingCount)
            );
        }
        updateStatus(jdbc, doctorId, version, "DISABLED");
        DoctorProfileView result = find(jdbc, doctorId);
        return new IdempotentResult<>(result, doctorId, objectMapper.valueToTree(snapshot(result)));
    }

    private IdempotentResult<DoctorProfileView> enableProfile(long doctorId, int version) {
        JdbcTemplate jdbc = jdbc();
        ProfileRow current = profile(jdbc, doctorId, true);
        requireVersion(current, version);
        if (!"DISABLED".equals(current.status())) {
            throw stateConflict("医生资料已经启用");
        }
        Integer eligible = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role r ON r.id=ur.role_id
            WHERE u.id=? AND u.status='ACTIVE' AND r.code='DOCTOR' AND r.status='ACTIVE'
            """, Integer.class, current.userId());
        if (eligible == null || eligible == 0) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "DOCTOR_ACCOUNT_NOT_ACTIVE",
                "账号和DOCTOR角色均启用后才能启用医生资料"
            );
        }
        updateStatus(jdbc, doctorId, version, "ACTIVE");
        DoctorProfileView result = find(jdbc, doctorId);
        return new IdempotentResult<>(result, doctorId, objectMapper.valueToTree(snapshot(result)));
    }

    private static void updateStatus(JdbcTemplate jdbc, long doctorId, int version, String status) {
        int updated = jdbc.update("""
            UPDATE doctor_profile SET status=?,version=version+1 WHERE id=? AND version=?
            """, status, doctorId, version);
        if (updated != 1) {
            throw versionConflict();
        }
    }

    private void audit(CurrentIdentity actor, String action, long doctorId, Object before, Object after) {
        audit.append(new AuditEvent(
            actor.userId(),
            actor.roles().contains("ADMIN") ? ActorType.ADMIN : ActorType.DOCTOR,
            action,
            new ResourceReference("DOCTOR_PROFILE", doctorId),
            objectMapper.valueToTree(before),
            objectMapper.valueToTree(after),
            null,
            null,
            null,
            AuditResult.SUCCESS,
            null
        ));
    }

    private CurrentIdentity requireDoctor() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType()) || !actor.roles().contains("DOCTOR")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅医生可维护本人资料");
        }
        return actor;
    }

    private DoctorProfileView findByUser(JdbcTemplate jdbc, long userId) {
        ProfileRow row = profileByUser(jdbc, userId, false);
        return view(row);
    }

    private DoctorProfileView find(JdbcTemplate jdbc, long doctorId) {
        return view(profile(jdbc, doctorId, false));
    }

    private ProfileRow profile(JdbcTemplate jdbc, long doctorId, boolean lock) {
        ProfileRow row = jdbc.query("""
            SELECT id,user_id,doctor_name,title,specialties,introduction,qualification_file_id,
                   status,version,created_at,updated_at FROM doctor_profile WHERE id=?
            """ + (lock ? " FOR UPDATE" : ""), result -> result.next() ? row(result) : null, doctorId);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "DOCTOR_PROFILE_NOT_FOUND", "医生资料不存在");
        }
        return row;
    }

    private ProfileRow profileByUser(JdbcTemplate jdbc, long userId, boolean lock) {
        ProfileRow row = jdbc.query("""
            SELECT id,user_id,doctor_name,title,specialties,introduction,qualification_file_id,
                   status,version,created_at,updated_at FROM doctor_profile WHERE user_id=?
            """ + (lock ? " FOR UPDATE" : ""), result -> result.next() ? row(result) : null, userId);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "DOCTOR_PROFILE_NOT_FOUND", "医生资料不存在");
        }
        return row;
    }

    private static ProfileRow row(ResultSet result) throws SQLException {
        return new ProfileRow(
            result.getLong("id"), result.getLong("user_id"), result.getString("doctor_name"),
            result.getString("title"), result.getString("specialties"), result.getString("introduction"),
            result.getObject("qualification_file_id", Long.class), result.getString("status"),
            result.getInt("version"), result.getTimestamp("created_at"), result.getTimestamp("updated_at")
        );
    }

    private static DoctorProfileView view(ResultSet result) throws SQLException {
        return view(row(result));
    }

    private static DoctorProfileView view(ProfileRow row) {
        return new DoctorProfileView(
            Long.toString(row.id()), Long.toString(row.userId()), row.doctorName(), row.title(),
            row.specialties(), row.introduction(),
            row.qualificationFileId() == null ? null : Long.toString(row.qualificationFileId()),
            row.status(), row.version(), row.createdAt().toInstant().toString(), row.updatedAt().toInstant().toString()
        );
    }

    private static PublicDoctorSummary publicSummary(ResultSet result) throws SQLException {
        return new PublicDoctorSummary(
            Long.toString(result.getLong("id")), result.getString("doctor_name"),
            result.getString("title"), result.getString("specialties")
        );
    }

    private static Map<String, Object> snapshot(ProfileRow row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("doctorName", row.doctorName());
        data.put("title", row.title());
        data.put("specialties", row.specialties());
        data.put("qualificationFileId", row.qualificationFileId());
        data.put("status", row.status());
        data.put("version", row.version());
        return data;
    }

    private static Map<String, Object> snapshot(DoctorProfileView view) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("doctorName", view.doctorName());
        data.put("title", view.title());
        data.put("specialties", view.specialties());
        data.put("qualificationFileId", view.qualificationFileId());
        data.put("status", view.status());
        data.put("version", view.version());
        return data;
    }

    private static void requireVersion(ProfileRow profile, int expectedVersion) {
        if (profile.version() != expectedVersion) {
            throw versionConflict();
        }
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DOCTOR_DEPENDENCY_UNAVAILABLE",
                "医生资料数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private static String required(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw badRequest("医生姓名不能为空");
        }
        return normalized;
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "数据已更新，请刷新后重试");
    }

    private static BusinessException stateConflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
    }

    private record ProfileRow(
        long id,
        long userId,
        String doctorName,
        String title,
        String specialties,
        String introduction,
        Long qualificationFileId,
        String status,
        int version,
        Timestamp createdAt,
        Timestamp updatedAt
    ) {
    }
}
