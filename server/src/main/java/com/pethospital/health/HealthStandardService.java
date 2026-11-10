package com.pethospital.health;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.health.HealthStandardDtos.StandardCreateRequest;
import com.pethospital.health.HealthStandardDtos.StandardPage;
import com.pethospital.health.HealthStandardDtos.StandardOfflineRequest;
import com.pethospital.health.HealthStandardDtos.StandardPatchRequest;
import com.pethospital.health.HealthStandardDtos.StandardPublishRequest;
import com.pethospital.health.HealthStandardDtos.StandardView;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.AuditService;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.IdempotencyService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HealthStandardService {
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "OFFLINE");

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public HealthStandardService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        IdempotencyService idempotency,
        AuditService audit,
        ObjectMapper objectMapper
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.idempotency = idempotency;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public StandardPage list(
        int page,
        int pageSize,
        String status,
        String speciesCode,
        String metricCode,
        String keyword
    ) {
        requireReader();
        List<Object> arguments = new ArrayList<>();
        String where = filters(status, speciesCode, metricCode, keyword, arguments);
        JdbcTemplate jdbc = jdbc();
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM health_standard" + where,
            Long.class,
            arguments.toArray()
        );
        arguments.add(pageSize);
        arguments.add(Math.multiplyExact((long) page - 1L, pageSize));
        List<StandardView> items = jdbc.query("""
            SELECT id,name,species_code,age_min_months,age_max_months,metric_code,unit,
                   reference_min,reference_max,description,conditions_note,source_note,status,
                   editor_doctor_id,version,created_at,updated_at
            FROM health_standard
            """ + where + " ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",
            (result, rowNumber) -> view(result), arguments.toArray());
        return new StandardPage(items, page, pageSize, count == null ? 0 : count);
    }

    @Transactional(readOnly = true)
    public StandardView get(long standardId) {
        requireReader();
        return view(row(jdbc(), standardId, false));
    }

    @Transactional
    public StandardView create(StandardCreateRequest request, String operationKey) {
        CurrentIdentity actor = requireDoctor();
        JdbcTemplate jdbc = jdbc();
        long doctorId = activeDoctorId(jdbc, actor.userId());
        NormalizedStandard input = normalize(
            request.name(), request.speciesCode(), request.ageMinMonths(), request.ageMaxMonths(),
            request.metricCode(), request.unit(), request.referenceMin(), request.referenceMax(),
            request.description(), request.conditionsNote(), request.sourceNote()
        );
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.DOCTOR, "HEALTH_STANDARD_CREATE", "HEALTH_STANDARD",
            "POST", "/api/v1/staff/health/standards", operationKey, request, null, null, null
        );
        return idempotency.execute(command, StandardView.class,
            () -> createStandard(jdbc, doctorId, input));
    }

    @Transactional
    public StandardView patch(long standardId, StandardPatchRequest request) {
        CurrentIdentity actor = requireDoctor();
        JdbcTemplate jdbc = jdbc();
        long doctorId = activeDoctorId(jdbc, actor.userId());
        StandardRow current = row(jdbc, standardId, true);
        if (current.version() != request.version()) {
            throw versionConflict();
        }
        NormalizedStandard input = normalize(
            request.name(), request.speciesCode(), request.ageMinMonths(), request.ageMaxMonths(),
            request.metricCode(), request.unit(), request.referenceMin(), request.referenceMax(),
            request.description(), request.conditionsNote(), request.sourceNote()
        );
        int updated = jdbc.update("""
            UPDATE health_standard
            SET name=?,species_code=?,age_min_months=?,age_max_months=?,metric_code=?,unit=?,
                reference_min=?,reference_max=?,description=?,conditions_note=?,source_note=?,
                status='DRAFT',editor_doctor_id=?,version=version+1
            WHERE id=? AND version=?
            """, input.name(), input.speciesCode(), input.ageMinMonths(), input.ageMaxMonths(),
            input.metricCode(), input.unit(), input.referenceMin(), input.referenceMax(),
            input.description(), input.conditionsNote(), input.sourceNote(), doctorId,
            standardId, request.version());
        if (updated != 1) {
            throw versionConflict();
        }
        StandardView result = view(row(jdbc, standardId, false));
        audit.append(new AuditEvent(
            actor.userId(), ActorType.DOCTOR, "HEALTH_STANDARD_UPDATE",
            new ResourceReference("HEALTH_STANDARD", standardId),
            objectMapper.valueToTree(snapshot(current)), objectMapper.valueToTree(snapshot(result)),
            null, null, null, AuditResult.SUCCESS, null
        ));
        return result;
    }

    @Transactional
    public StandardView publish(long standardId, StandardPublishRequest request, String operationKey) {
        CurrentIdentity actor = requireAdmin();
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "HEALTH_STANDARD_PUBLISH", "HEALTH_STANDARD",
            "POST", "/api/v1/staff/health/standards/" + standardId + "/publish",
            operationKey, request, null, null, null
        );
        return idempotency.execute(command, StandardView.class,
            () -> changeStatus(standardId, request.version(), "PUBLISHED", actor.userId()));
    }

    @Transactional
    public StandardView offline(long standardId, StandardOfflineRequest request, String operationKey) {
        CurrentIdentity actor = requireAdmin();
        String reason = required(request.reason(), "下架原因不能为空");
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "HEALTH_STANDARD_OFFLINE", "HEALTH_STANDARD",
            "POST", "/api/v1/staff/health/standards/" + standardId + "/offline",
            operationKey, request, null, reason, null
        );
        return idempotency.execute(command, StandardView.class,
            () -> changeStatus(standardId, request.version(), "OFFLINE", actor.userId()));
    }

    private IdempotentResult<StandardView> changeStatus(
        long standardId,
        int version,
        String targetStatus,
        long actorUserId
    ) {
        JdbcTemplate jdbc = jdbc();
        StandardRow current = row(jdbc, standardId, true);
        if (version < 0 || current.version() != version) {
            throw versionConflict();
        }
        boolean allowed = "PUBLISHED".equals(targetStatus)
            ? "DRAFT".equals(current.status()) || "OFFLINE".equals(current.status())
            : "PUBLISHED".equals(current.status());
        if (!allowed) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "HEALTH_STANDARD_STATE_CONFLICT",
                "PUBLISHED".equals(targetStatus) ? "只有草稿或已下架标准可以发布" : "只有已发布标准可以下架"
            );
        }
        int updated = jdbc.update("""
            UPDATE health_standard SET status=?,version=version+1 WHERE id=? AND version=?
            """, targetStatus, standardId, version);
        if (updated != 1) {
            throw versionConflict();
        }
        StandardView result = view(row(jdbc, standardId, false));
        return new IdempotentResult<>(result, standardId, objectMapper.valueToTree(snapshot(result)));
    }

    private IdempotentResult<StandardView> createStandard(
        JdbcTemplate jdbc,
        long doctorId,
        NormalizedStandard input
    ) {
        long standardId = IdWorker.getId();
        jdbc.update("""
            INSERT INTO health_standard(
                id,name,species_code,age_min_months,age_max_months,metric_code,unit,
                reference_min,reference_max,description,conditions_note,source_note,
                status,editor_doctor_id,version
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,0)
            """, standardId, input.name(), input.speciesCode(), input.ageMinMonths(),
            input.ageMaxMonths(), input.metricCode(), input.unit(), input.referenceMin(),
            input.referenceMax(), input.description(), input.conditionsNote(), input.sourceNote(),
            doctorId);
        StandardView result = view(row(jdbc, standardId, false));
        return new IdempotentResult<>(result, standardId, objectMapper.valueToTree(snapshot(result)));
    }

    private static NormalizedStandard normalize(
        String name,
        String speciesCode,
        Integer ageMinMonths,
        Integer ageMaxMonths,
        String metricCode,
        String unit,
        BigDecimal referenceMin,
        BigDecimal referenceMax,
        String description,
        String conditionsNote,
        String sourceNote
    ) {
        requirePair(ageMinMonths, ageMaxMonths, "年龄上下界必须同时填写或同时留空");
        requirePair(referenceMin, referenceMax, "参考区间上下界必须同时填写或同时留空");
        if (ageMinMonths != null && ageMinMonths > ageMaxMonths) {
            throw badRequest("年龄下界不能大于上界");
        }
        if (referenceMin != null && referenceMin.compareTo(referenceMax) > 0) {
            throw badRequest("参考区间下界不能大于上界");
        }
        return new NormalizedStandard(
            required(name, "标准名称不能为空"), code(speciesCode), ageMinMonths, ageMaxMonths,
            code(metricCode), optional(unit), referenceMin, referenceMax,
            required(description, "说明不能为空"), required(conditionsNote, "适用条件不能为空"),
            required(sourceNote, "来源不能为空")
        );
    }

    private static void requirePair(Object lower, Object upper, String message) {
        if ((lower == null) != (upper == null)) {
            throw badRequest(message);
        }
    }

    private static String filters(
        String status,
        String speciesCode,
        String metricCode,
        String keyword,
        List<Object> arguments
    ) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null && !status.isBlank()) {
            String normalized = status.strip().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalized)) {
                throw badRequest("status不合法");
            }
            where.append(" AND status=?");
            arguments.add(normalized);
        }
        if (speciesCode != null && !speciesCode.isBlank()) {
            where.append(" AND species_code=?");
            arguments.add(code(speciesCode));
        }
        if (metricCode != null && !metricCode.isBlank()) {
            where.append(" AND metric_code=?");
            arguments.add(code(metricCode));
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND name LIKE ? ESCAPE '\\\\'");
            arguments.add("%" + like(keyword.strip()) + "%");
        }
        return where.toString();
    }

    private CurrentIdentity requireReader() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType())
            || (!actor.roles().contains("ADMIN") && !actor.roles().contains("DOCTOR"))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅医生或管理员可查看健康标准后台");
        }
        return actor;
    }

    private CurrentIdentity requireDoctor() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType()) || !actor.roles().contains("DOCTOR")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅医生可维护健康标准");
        }
        return actor;
    }

    private CurrentIdentity requireAdmin() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType()) || !actor.roles().contains("ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅管理员可发布或下架健康标准");
        }
        return actor;
    }

    private static long activeDoctorId(JdbcTemplate jdbc, long userId) {
        Long doctorId = jdbc.query("""
            SELECT id FROM doctor_profile WHERE user_id=? AND status='ACTIVE'
            """, result -> result.next() ? result.getLong("id") : null, userId);
        if (doctorId == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "DOCTOR_PROFILE_INACTIVE", "医生资料无效或已停用");
        }
        return doctorId;
    }

    private static StandardRow row(JdbcTemplate jdbc, long standardId, boolean lock) {
        StandardRow result = jdbc.query("""
            SELECT id,name,species_code,age_min_months,age_max_months,metric_code,unit,
                   reference_min,reference_max,description,conditions_note,source_note,status,
                   editor_doctor_id,version,created_at,updated_at
            FROM health_standard WHERE id=?
            """ + (lock ? " FOR UPDATE" : ""),
            rs -> rs.next() ? row(rs) : null, standardId);
        if (result == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "HEALTH_STANDARD_NOT_FOUND", "健康标准不存在");
        }
        return result;
    }

    private static StandardRow row(ResultSet result) throws SQLException {
        return new StandardRow(
            result.getLong("id"), result.getString("name"), result.getString("species_code"),
            result.getObject("age_min_months", Integer.class), result.getObject("age_max_months", Integer.class),
            result.getString("metric_code"), result.getString("unit"),
            result.getBigDecimal("reference_min"), result.getBigDecimal("reference_max"),
            result.getString("description"), result.getString("conditions_note"),
            result.getString("source_note"), result.getString("status"),
            result.getLong("editor_doctor_id"), result.getInt("version"),
            result.getTimestamp("created_at"), result.getTimestamp("updated_at")
        );
    }

    private static StandardView view(ResultSet result) throws SQLException {
        return view(row(result));
    }

    private static StandardView view(StandardRow row) {
        return new StandardView(
            Long.toString(row.id()), row.name(), row.speciesCode(), row.ageMinMonths(), row.ageMaxMonths(),
            row.metricCode(), row.unit(), row.referenceMin(), row.referenceMax(), row.description(),
            row.conditionsNote(), row.sourceNote(), row.status(), Long.toString(row.editorDoctorId()),
            row.version(), instant(row.createdAt()), instant(row.updatedAt())
        );
    }

    private static Map<String, Object> snapshot(StandardRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", row.name());
        result.put("speciesCode", row.speciesCode());
        result.put("ageMinMonths", row.ageMinMonths());
        result.put("ageMaxMonths", row.ageMaxMonths());
        result.put("metricCode", row.metricCode());
        result.put("unit", row.unit());
        result.put("referenceMin", row.referenceMin());
        result.put("referenceMax", row.referenceMax());
        result.put("description", row.description());
        result.put("conditionsNote", row.conditionsNote());
        result.put("sourceNote", row.sourceNote());
        result.put("status", row.status());
        result.put("editorDoctorId", row.editorDoctorId());
        result.put("version", row.version());
        return result;
    }

    private static Map<String, Object> snapshot(StandardView view) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", view.name());
        result.put("speciesCode", view.speciesCode());
        result.put("ageMinMonths", view.ageMinMonths());
        result.put("ageMaxMonths", view.ageMaxMonths());
        result.put("metricCode", view.metricCode());
        result.put("unit", view.unit());
        result.put("referenceMin", view.referenceMin());
        result.put("referenceMax", view.referenceMax());
        result.put("description", view.description());
        result.put("conditionsNote", view.conditionsNote());
        result.put("sourceNote", view.sourceNote());
        result.put("status", view.status());
        result.put("editorDoctorId", view.editorDoctorId());
        result.put("version", view.version());
        return result;
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "数据库不可用");
        }
        return new JdbcTemplate(dataSource);
    }

    private static String required(String value, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw badRequest(message);
        }
        return normalized;
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String code(String value) {
        return required(value, "代码不能为空").toUpperCase(Locale.ROOT);
    }

    private static String like(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String instant(Timestamp value) {
        return value.toInstant().toString();
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_HEALTH_STANDARD", message);
    }

    private static BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "健康标准已被其他操作修改");
    }

    private record NormalizedStandard(
        String name,
        String speciesCode,
        Integer ageMinMonths,
        Integer ageMaxMonths,
        String metricCode,
        String unit,
        BigDecimal referenceMin,
        BigDecimal referenceMax,
        String description,
        String conditionsNote,
        String sourceNote
    ) {
    }

    private record StandardRow(
        long id,
        String name,
        String speciesCode,
        Integer ageMinMonths,
        Integer ageMaxMonths,
        String metricCode,
        String unit,
        BigDecimal referenceMin,
        BigDecimal referenceMax,
        String description,
        String conditionsNote,
        String sourceNote,
        String status,
        long editorDoctorId,
        int version,
        Timestamp createdAt,
        Timestamp updatedAt
    ) {
    }
}
