package com.pethospital.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.AdminPermissionEvaluator;
import com.pethospital.identity.BackendPermissionCatalog;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.OperationsLogDtos.AiCallLogPage;
import com.pethospital.shared.OperationsLogDtos.AiCallLogView;
import com.pethospital.shared.OperationsLogDtos.AuditLogPage;
import com.pethospital.shared.OperationsLogDtos.AuditLogView;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsLogService {
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    private static final Duration MAX_WINDOW = Duration.ofDays(31);

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final AdminPermissionEvaluator permissions;
    private final AuditService audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OperationsLogService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        AdminPermissionEvaluator permissions,
        AuditService audits,
        ObjectMapper objectMapper,
        ObjectProvider<Clock> clockProvider
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.permissions = permissions;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    @Transactional
    public AuditLogPage auditLogs(
        Long actorUserId,
        String resourceType,
        Long resourceId,
        String action,
        String from,
        String to,
        int page,
        int pageSize
    ) {
        CurrentIdentity actor = identities.require();
        Window window = window(from, to);
        requirePositive(actorUserId, "actorUserId");
        requirePositive(resourceId, "resourceId");
        String normalizedResource = code(resourceType, "resourceType", 64);
        String normalizedAction = code(action, "action", 128);

        Query query = new Query(" FROM audit_log WHERE created_at>=? AND created_at<?", window);
        query.add(" AND actor_user_id=?", actorUserId);
        query.add(" AND resource_type=?", normalizedResource);
        query.add(" AND resource_id=?", resourceId);
        query.add(" AND action=?", normalizedAction);
        JdbcTemplate jdbc = jdbc();
        long total = count(jdbc, query);
        List<Object> pageArgs = query.argsWithPage(pageSize, offset(page, pageSize));
        List<AuditLogView> items = jdbc.query("""
            SELECT id,actor_user_id,actor_type,action,resource_type,resource_id,
                   result,trace_id,ip_masked,created_at
            """ + query.sql() + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new AuditLogView(
                Long.toString(rs.getLong("id")),
                nullableId(rs.getObject("actor_user_id", Long.class)),
                rs.getString("actor_type"),
                rs.getString("action"),
                rs.getString("resource_type"),
                nullableId(rs.getObject("resource_id", Long.class)),
                rs.getString("result"),
                rs.getString("trace_id"),
                rs.getString("ip_masked"),
                instant(rs.getTimestamp("created_at"))
            ), pageArgs.toArray());

        appendReadAudit(actor, "ADMIN_AUDIT_LOG_READ", "AUDIT_LOG", Map.of(
            "from", window.from().toString(),
            "to", window.to().toString(),
            "page", page,
            "pageSize", pageSize,
            "resultCount", items.size()
        ));
        return new AuditLogPage(items, page, pageSize, total);
    }

    @Transactional
    public AiCallLogPage aiCallLogs(
        String callKind,
        String provider,
        String model,
        String status,
        Boolean includeContext,
        String from,
        String to,
        int page,
        int pageSize
    ) {
        CurrentIdentity actor = identities.require();
        boolean context = Boolean.TRUE.equals(includeContext);
        if (context && !permissions.decide(
            SecurityContextHolder.getContext().getAuthentication(),
            BackendPermissionCatalog.AI_CONSULT_AUDIT
        ).isGranted()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权查看模型上下文");
        }
        Window window = window(from, to);
        String normalizedKind = enumCode(callKind, "callKind", List.of("GENERATION", "SUMMARY", "EMBEDDING"));
        String normalizedStatus = enumCode(status, "status", List.of("CALLING", "COMPLETED", "FAILED", "FAILED_UNKNOWN"));
        String normalizedProvider = text(provider, "provider", 64);
        String normalizedModel = text(model, "model", 128);

        Query query = new Query(" FROM ai_call_log WHERE created_at>=? AND created_at<?", window);
        query.add(" AND call_kind=?", normalizedKind);
        query.add(" AND provider=?", normalizedProvider);
        query.add(" AND model=?", normalizedModel);
        query.add(" AND status=?", normalizedStatus);
        JdbcTemplate jdbc = jdbc();
        long total = count(jdbc, query);
        String snapshotColumn = context ? "input_snapshot_json" : "NULL AS input_snapshot_json";
        List<Object> pageArgs = query.argsWithPage(pageSize, offset(page, pageSize));
        List<AiCallLogView> items = jdbc.query("""
            SELECT id,session_id,user_message_id,call_kind,business_ref,generation_no,attempt_no,
                   provider,model,status,prompt_version,safety_version,prompt_tokens,
                   completion_tokens,cached_tokens,latency_ms,started_at,finished_at,error_code,
            """ + snapshotColumn + ",created_at" + query.sql()
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new AiCallLogView(
                Long.toString(rs.getLong("id")),
                nullableId(rs.getObject("session_id", Long.class)),
                nullableId(rs.getObject("user_message_id", Long.class)),
                rs.getString("call_kind"),
                rs.getString("business_ref"),
                rs.getInt("generation_no"),
                rs.getInt("attempt_no"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("status"),
                rs.getString("prompt_version"),
                rs.getString("safety_version"),
                rs.getObject("prompt_tokens", Integer.class),
                rs.getObject("completion_tokens", Integer.class),
                rs.getObject("cached_tokens", Integer.class),
                rs.getObject("latency_ms", Long.class),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("error_code"),
                json(rs.getString("input_snapshot_json")),
                instant(rs.getTimestamp("created_at"))
            ), pageArgs.toArray());

        appendReadAudit(actor, context ? "ADMIN_AI_CALL_CONTEXT_READ" : "ADMIN_AI_CALL_LOG_READ",
            "AI_CALL_LOG", Map.of(
                "from", window.from().toString(),
                "to", window.to().toString(),
                "includeContext", context,
                "page", page,
                "pageSize", pageSize,
                "resultCount", items.size()
            ));
        return new AiCallLogPage(items, page, pageSize, total);
    }

    private void appendReadAudit(
        CurrentIdentity actor,
        String action,
        String resourceType,
        Map<String, Object> safeSummary
    ) {
        audits.append(new AuditEvent(
            actor.userId(), ActorType.ADMIN, action, new ResourceReference(resourceType, null),
            null, objectMapper.valueToTree(safeSummary), null, null, null,
            AuditResult.SUCCESS, null
        ));
    }

    private Window window(String from, String to) {
        Instant end = parse(to, "to", clock.instant());
        Instant start = parse(from, "from", end.minus(DEFAULT_WINDOW));
        Duration duration = Duration.between(start, end);
        if (duration.isNegative() || duration.isZero()) {
            throw validation("from必须早于to");
        }
        if (duration.compareTo(MAX_WINDOW) > 0) {
            throw validation("查询时间范围不能超过31天");
        }
        if (end.isAfter(clock.instant().plus(Duration.ofMinutes(1)))) {
            throw validation("to不能晚于当前时间");
        }
        return new Window(start, end);
    }

    private static Instant parse(String value, String name, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException exception) {
            throw validation(name + "必须是带时区的ISO-8601时间");
        }
    }

    private static String code(String value, String name, int max) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max
            || !normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw validation(name + "格式无效");
        }
        return normalized;
    }

    private static String enumCode(String value, String name, List<String> allowed) {
        String normalized = code(value, name, 24);
        if (normalized != null && !allowed.contains(normalized)) {
            throw validation(name + "取值无效");
        }
        return normalized;
    }

    private static String text(String value, String name, int max) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw validation(name + "格式无效");
        }
        return normalized;
    }

    private static void requirePositive(Long value, String name) {
        if (value != null && value <= 0) throw validation(name + "必须为正整数");
    }

    private static long offset(int page, int pageSize) {
        return Math.multiplyExact((long) page - 1L, pageSize);
    }

    private static long count(JdbcTemplate jdbc, Query query) {
        Long value = jdbc.queryForObject("SELECT COUNT(*)" + query.sql(), Long.class, query.args().toArray());
        return value == null ? 0L : value;
    }

    private JsonNode json(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("stored AI input snapshot is not valid JSON", exception);
        }
    }

    private static String nullableId(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private static String instant(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "LOG_DEPENDENCY_UNAVAILABLE", "日志数据源不可用");
        }
        return new JdbcTemplate(dataSource);
    }

    private static BusinessException validation(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private record Window(Instant from, Instant to) {
    }

    private static final class Query {
        private final StringBuilder sql;
        private final List<Object> args = new ArrayList<>();

        Query(String sql, Window window) {
            this.sql = new StringBuilder(sql);
            args.add(Timestamp.from(window.from()));
            args.add(Timestamp.from(window.to()));
        }

        void add(String fragment, Object value) {
            if (value != null) {
                sql.append(fragment);
                args.add(value);
            }
        }

        String sql() {
            return sql.toString();
        }

        List<Object> args() {
            return List.copyOf(args);
        }

        List<Object> argsWithPage(int limit, long offset) {
            List<Object> result = new ArrayList<>(args);
            result.add(limit);
            result.add(offset);
            return result;
        }
    }
}
