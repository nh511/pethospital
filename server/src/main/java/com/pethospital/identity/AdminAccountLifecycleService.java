package com.pethospital.identity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.pethospital.appointment.UnfinishedAppointmentQuery;
import com.pethospital.appointment.UnfinishedAppointmentQuery.UnfinishedAppointments;
import com.pethospital.identity.AdminAccountLifecycleDtos.AccountLifecycleView;
import com.pethospital.identity.AdminAccountLifecycleDtos.DisableAccountRequest;
import com.pethospital.identity.AdminAccountLifecycleDtos.EnableAccountRequest;
import com.pethospital.identity.AdminAccountLifecycleDtos.ResetPasswordRequest;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.TraceFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAccountLifecycleService {
    private static final String DISABLE_ACTION = "ADMIN_USER_DISABLE";
    private static final String ENABLE_ACTION = "ADMIN_USER_ENABLE";
    private static final String RESET_PASSWORD_ACTION = "ADMIN_USER_PASSWORD_RESET";

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final PasswordEncoder passwordEncoder;
    private final UnfinishedAppointmentQuery appointments;
    private final Clock clock;

    public AdminAccountLifecycleService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        PasswordEncoder passwordEncoder,
        UnfinishedAppointmentQuery appointments,
        Clock clock
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.passwordEncoder = passwordEncoder;
        this.appointments = appointments;
        this.clock = clock;
    }

    @Transactional
    public AccountLifecycleView disable(long userId, DisableAccountRequest request, String operationKey) {
        String reason = request.reason().trim();
        JdbcTemplate jdbc = authorizedJdbc();
        CurrentIdentity actor = identities.require();
        String requestHash = requestHash(DISABLE_ACTION, userId, request.version(), reason, null);
        AccountLifecycleView replay = lockIdempotency(
            jdbc, actor.userId(), operationKey, DISABLE_ACTION, userId, requestHash
        );
        if (replay != null) {
            return replay;
        }

        long adminRoleId = lockAdminRole(jdbc);
        AccountRow account = lockAccount(jdbc, userId);
        requireVersion(account, request.version());
        if (!"ACTIVE".equals(account.status())) {
            throw stateConflict("账号已经停用");
        }
        protectLastAdmin(jdbc, userId, adminRoleId);
        UnfinishedAppointments pending = appointments.lockForAccount(userId);
        if (pending.totalCount() > 0) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "UNFINISHED_APPOINTMENTS",
                "账号仍有未终结预约，请先处理",
                Map.of(
                    "pendingCount", pending.totalCount(),
                    "ownerPendingCount", pending.ownerCount(),
                    "doctorPendingCount", pending.doctorCount()
                )
            );
        }

        int nextVersion = account.version() + 1;
        updateAccountState(jdbc, userId, request.version(), "DISABLED", null);
        revokeSessions(jdbc, userId);
        AccountLifecycleView result = new AccountLifecycleView(userId, "DISABLED", nextVersion);
        appendAudit(jdbc, actor.userId(), operationKey, requestHash, DISABLE_ACTION, result, reason);
        return result;
    }

    @Transactional
    public AccountLifecycleView enable(long userId, EnableAccountRequest request, String operationKey) {
        JdbcTemplate jdbc = authorizedJdbc();
        CurrentIdentity actor = identities.require();
        String requestHash = requestHash(ENABLE_ACTION, userId, request.version(), null, null);
        AccountLifecycleView replay = lockIdempotency(
            jdbc, actor.userId(), operationKey, ENABLE_ACTION, userId, requestHash
        );
        if (replay != null) {
            return replay;
        }

        lockAdminRole(jdbc);
        AccountRow account = lockAccount(jdbc, userId);
        requireVersion(account, request.version());
        if (!"DISABLED".equals(account.status())) {
            throw stateConflict("账号已经启用");
        }
        int nextVersion = account.version() + 1;
        updateAccountState(jdbc, userId, request.version(), "ACTIVE", null);
        revokeSessions(jdbc, userId);
        AccountLifecycleView result = new AccountLifecycleView(userId, "ACTIVE", nextVersion);
        appendAudit(jdbc, actor.userId(), operationKey, requestHash, ENABLE_ACTION, result, null);
        return result;
    }

    @Transactional
    public AccountLifecycleView resetPassword(
        long userId,
        ResetPasswordRequest request,
        String operationKey
    ) {
        validatePasswordBytes(request.temporaryPassword());
        String reason = request.reason().trim();
        JdbcTemplate jdbc = authorizedJdbc();
        CurrentIdentity actor = identities.require();
        String requestHash = requestHash(
            RESET_PASSWORD_ACTION,
            userId,
            request.version(),
            reason,
            request.temporaryPassword()
        );
        AccountLifecycleView replay = lockIdempotency(
            jdbc, actor.userId(), operationKey, RESET_PASSWORD_ACTION, userId, requestHash
        );
        if (replay != null) {
            return replay;
        }

        lockAdminRole(jdbc);
        AccountRow account = lockAccount(jdbc, userId);
        requireVersion(account, request.version());
        String passwordHash = passwordEncoder.encode(request.temporaryPassword());
        int nextVersion = account.version() + 1;
        updateAccountState(jdbc, userId, request.version(), account.status(), passwordHash);
        revokeSessions(jdbc, userId);
        AccountLifecycleView result = new AccountLifecycleView(userId, account.status(), nextVersion);
        appendAudit(jdbc, actor.userId(), operationKey, requestHash, RESET_PASSWORD_ACTION, result, reason);
        return result;
    }

    private JdbcTemplate authorizedJdbc() {
        JdbcTemplate jdbc = jdbc();
        CurrentIdentity actor = identities.require();
        Integer activeAdmin = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role r ON r.id=ur.role_id
            WHERE u.id=? AND u.status='ACTIVE' AND r.code='ADMIN' AND r.status='ACTIVE'
            """, Integer.class, actor.userId());
        if (activeAdmin == null || activeAdmin == 0) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权管理账号生命周期");
        }
        return jdbc;
    }

    private static AccountLifecycleView lockIdempotency(
        JdbcTemplate jdbc,
        long actorUserId,
        String operationKey,
        String action,
        long userId,
        String requestHash
    ) {
        IdempotencyRow existing = jdbc.query("""
            SELECT action,resource_type,resource_id,request_hash,
                   JSON_UNQUOTE(JSON_EXTRACT(after_json,'$.status')) AS result_status,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(after_json,'$.version')) AS UNSIGNED) AS result_version
            FROM audit_log
            WHERE actor_user_id=? AND operation_key=?
            FOR UPDATE
            """, result -> result.next() ? new IdempotencyRow(
            result.getString("action"),
            result.getString("resource_type"),
            result.getLong("resource_id"),
            result.getString("request_hash"),
            result.getString("result_status"),
            result.getInt("result_version")
        ) : null, actorUserId, operationKey);
        if (existing == null) {
            return null;
        }
        if (!action.equals(existing.action())
            || !"SYS_USER".equals(existing.resourceType())
            || userId != existing.resourceId()
            || !requestHash.equals(existing.requestHash())) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "幂等键已用于其他请求"
            );
        }
        return new AccountLifecycleView(userId, existing.status(), existing.version());
    }

    private static long lockAdminRole(JdbcTemplate jdbc) {
        Long roleId = jdbc.query("""
            SELECT id FROM sys_role WHERE code='ADMIN' AND status='ACTIVE' FOR UPDATE
            """, result -> result.next() ? result.getLong(1) : null);
        if (roleId == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_CONFIGURATION_ERROR",
                "内置管理员角色不可用"
            );
        }
        return roleId;
    }

    private static AccountRow lockAccount(JdbcTemplate jdbc, long userId) {
        AccountRow account = jdbc.query("""
            SELECT status,version FROM sys_user WHERE id=? FOR UPDATE
            """, result -> result.next()
            ? new AccountRow(result.getString("status"), result.getInt("version"))
            : null, userId);
        if (account == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "账号不存在");
        }
        return account;
    }

    private static void protectLastAdmin(JdbcTemplate jdbc, long userId, long adminRoleId) {
        Integer targetIsAdmin = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?
            """, Integer.class, userId, adminRoleId);
        if (targetIsAdmin == null || targetIsAdmin == 0) {
            return;
        }
        Integer activeAdmins = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT u.id)
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            WHERE ur.role_id=? AND u.status='ACTIVE'
            """, Integer.class, adminRoleId);
        if (activeAdmins == null || activeAdmins <= 1) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "LAST_ADMIN_PROTECTED",
                "不能停用最后一个有效管理员"
            );
        }
    }

    private static void requireVersion(AccountRow account, int expectedVersion) {
        if (account.version() != expectedVersion) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "账号已更新，请刷新后重试");
        }
    }

    private static void updateAccountState(
        JdbcTemplate jdbc,
        long userId,
        int expectedVersion,
        String status,
        String passwordHash
    ) {
        int updated;
        if (passwordHash == null) {
            updated = jdbc.update("""
                UPDATE sys_user
                SET status=?,version=version+1,token_version=token_version+1
                WHERE id=? AND version=?
                """, status, userId, expectedVersion);
        } else {
            updated = jdbc.update("""
                UPDATE sys_user
                SET password_hash=?,version=version+1,token_version=token_version+1
                WHERE id=? AND version=?
                """, passwordHash, userId, expectedVersion);
        }
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "账号已更新，请刷新后重试");
        }
    }

    private void revokeSessions(JdbcTemplate jdbc, long userId) {
        Instant now = clock.instant();
        jdbc.update("""
            UPDATE auth_session SET revoked_at=?,updated_at=?
            WHERE user_id=? AND revoked_at IS NULL
            """, Timestamp.from(now), Timestamp.from(now), userId);
    }

    private static void appendAudit(
        JdbcTemplate jdbc,
        long actorUserId,
        String operationKey,
        String requestHash,
        String action,
        AccountLifecycleView result,
        String reason
    ) {
        jdbc.update("""
            INSERT INTO audit_log(
                id,actor_user_id,actor_type,action,resource_type,resource_id,
                after_json,reason,trace_id,operation_key,request_hash,result
            ) VALUES (?,?,'ADMIN',?,'SYS_USER',?,
                JSON_OBJECT('status',?,'version',?),?,?,?,?,'SUCCESS')
            """,
            IdWorker.getId(),
            actorUserId,
            action,
            result.userId(),
            result.status(),
            result.version(),
            reason,
            traceId(),
            operationKey,
            requestHash
        );
    }

    private static String requestHash(
        String action,
        long userId,
        int version,
        String reason,
        String temporaryPassword
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, action);
            updateDigest(digest, Long.toString(userId));
            updateDigest(digest, Integer.toString(version));
            updateDigest(digest, reason);
            updateDigest(digest, temporaryPassword);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "临时密码UTF-8长度不能超过72字节"
            );
        }
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_DEPENDENCY_UNAVAILABLE",
                "账号管理数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private static BusinessException stateConflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
    }

    private static String traceId() {
        String current = TraceFilter.current();
        return current == null ? UUID.randomUUID().toString() : current;
    }

    private record AccountRow(String status, int version) {
    }

    private record IdempotencyRow(
        String action,
        String resourceType,
        long resourceId,
        String requestHash,
        String status,
        int version
    ) {
    }
}
