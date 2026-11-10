package com.pethospital.identity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.pethospital.identity.AuthDtos.LoginResult;
import com.pethospital.identity.AuthDtos.UserView;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.TraceFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordAuthService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final PasswordEncoder passwordEncoder;
    private final Rs256AccessTokenIssuer tokenIssuer;
    private final LoginSecurityAuditService loginSecurityAudit;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final PolicyCatalog policies;
    private final Clock clock;
    private final long accessTokenSeconds;
    private final long refreshTokenSeconds;
    private final String dummyPasswordHash;

    public PasswordAuthService(
        ObjectProvider<DataSource> dataSourceProvider,
        PasswordEncoder passwordEncoder,
        Rs256AccessTokenIssuer tokenIssuer,
        LoginSecurityAuditService loginSecurityAudit,
        LoginAttemptLimiter loginAttemptLimiter,
        PolicyCatalog policies,
        Clock clock,
        @Value("${identity.auth.access-token-seconds:1800}") long accessTokenSeconds,
        @Value("${identity.auth.refresh-token-seconds:604800}") long refreshTokenSeconds
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.loginSecurityAudit = loginSecurityAudit;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.policies = policies;
        this.clock = clock;
        this.accessTokenSeconds = requirePositive(accessTokenSeconds, "access-token-seconds");
        this.refreshTokenSeconds = requirePositive(refreshTokenSeconds, "refresh-token-seconds");
        this.dummyPasswordHash = passwordEncoder.encode("not-a-real-account-password");
    }

    @Transactional
    public UserView register(
        String username,
        String password,
        String displayName,
        String privacyPolicyVersion
    ) {
        validatePasswordBytes(password);
        var privacyPolicy = policies.current(ConsentPurpose.PRIVACY);
        if (!privacyPolicy.policyVersion().equals(privacyPolicyVersion)) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "POLICY_VERSION_OUTDATED",
                "隐私政策版本已更新，请重新阅读"
            );
        }
        String normalizedUsername = normalizeUsername(username);
        String normalizedDisplayName = displayName.trim();
        JdbcTemplate jdbc = jdbc();
        Long roleId = jdbc.query(
            "SELECT id FROM sys_role WHERE code='USER' AND status='ACTIVE'",
            result -> result.next() ? result.getLong(1) : null
        );
        if (roleId == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_CONFIGURATION_ERROR",
                "用户角色尚未初始化"
            );
        }

        long userId = IdWorker.getId();
        try {
            jdbc.update("""
                INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
                VALUES (?,?,?,?,'ACTIVE',0,0)
                """, userId, normalizedUsername, passwordEncoder.encode(password), normalizedDisplayName);
            jdbc.update(
                "INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
                IdWorker.getId(),
                userId,
                roleId
            );
            jdbc.update("""
                INSERT INTO consent_record(
                    id,user_id,purpose,policy_version,action,notice_hash,idempotency_key
                ) VALUES (?,?,'PRIVACY',?,'ACCEPT',?,?)
                """,
                IdWorker.getId(),
                userId,
                privacyPolicy.policyVersion(),
                privacyPolicy.noticeHash(),
                "register-privacy:" + userId
            );
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        return new UserView(Long.toString(userId), normalizedUsername, normalizedDisplayName, List.of("USER"));
    }

    @Transactional
    public LoginResult login(String username, String password, ClientType clientType) {
        String normalizedUsername = normalizeUsername(username);
        if (loginAttemptLimiter.isBlocked(normalizedUsername, clientType)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "登录尝试过于频繁，请稍后重试");
        }
        JdbcTemplate jdbc = jdbc();
        UserAccount account = jdbc.query(
            """
                SELECT id,username,password_hash,display_name,status,token_version
                FROM sys_user WHERE username=?
                """,
            result -> result.next()
                ? new UserAccount(
                    result.getLong("id"),
                    result.getString("username"),
                    result.getString("password_hash"),
                    result.getString("display_name"),
                    result.getString("status"),
                    result.getInt("token_version")
                )
                : null,
            normalizedUsername
        );

        String storedHash = account == null || account.passwordHash() == null
            ? dummyPasswordHash
            : account.passwordHash();
        if (!passwordEncoder.matches(password, storedHash) || account == null || account.passwordHash() == null) {
            loginAttemptLimiter.recordFailure(normalizedUsername, clientType);
            loginSecurityAudit.recordFailure(
                account == null ? null : account.id(),
                clientType.name(),
                "INVALID_CREDENTIALS"
            );
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        if (!"ACTIVE".equals(account.status())) {
            loginAttemptLimiter.recordFailure(normalizedUsername, clientType);
            loginSecurityAudit.recordFailure(account.id(), clientType.name(), "ACCOUNT_DISABLED");
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用");
        }

        List<String> roles = jdbc.queryForList("""
            SELECT r.code
            FROM sys_user_role ur
            JOIN sys_role r ON r.id=ur.role_id AND r.status='ACTIVE'
            WHERE ur.user_id=?
            ORDER BY r.code
            """, String.class, account.id());
        if (!clientAllowed(clientType, roles)) {
            loginAttemptLimiter.recordFailure(normalizedUsername, clientType);
            loginSecurityAudit.recordFailure(account.id(), clientType.name(), "CLIENT_NOT_ALLOWED");
            throw new BusinessException(HttpStatus.FORBIDDEN, "CLIENT_NOT_ALLOWED", "账号不能登录该客户端");
        }

        loginAttemptLimiter.recordSuccess(normalizedUsername, clientType);

        return issueSession(jdbc, account, clientType, roles);
    }

    LoginResult issueMiniSession(
        long userId,
        String username,
        String displayName,
        int tokenVersion
    ) {
        return issueSession(
            jdbc(),
            new UserAccount(userId, username, null, displayName, "ACTIVE", tokenVersion),
            ClientType.MINI,
            List.of("USER")
        );
    }

    private LoginResult issueSession(
        JdbcTemplate jdbc,
        UserAccount account,
        ClientType clientType,
        List<String> roles
    ) {
        Instant now = clock.instant();
        long sessionId = IdWorker.getId();
        String refreshToken = randomToken();
        jdbc.update("""
            INSERT INTO auth_session(
                id,user_id,client_type,refresh_token_hash,family_id,expires_at,last_seen_at,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """,
            sessionId,
            account.id(),
            clientType.name(),
            sha256Hex(refreshToken),
            UUID.randomUUID().toString(),
            Timestamp.from(now.plusSeconds(refreshTokenSeconds)),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now)
        );

        Rs256AccessTokenIssuer.IssuedAccessToken accessToken = tokenIssuer.issue(
            account.id(),
            sessionId,
            account.tokenVersion(),
            clientType.name(),
            roles,
            now,
            accessTokenSeconds
        );
        appendLoginAudit(jdbc, account, sessionId, clientType, roles);

        return new LoginResult(
            accessToken.value(),
            accessToken.expiresIn(),
            Long.toString(sessionId),
            new UserView(Long.toString(account.id()), account.username(), account.displayName(), roles),
            refreshToken,
            refreshTokenSeconds
        );
    }

    private void appendLoginAudit(
        JdbcTemplate jdbc,
        UserAccount account,
        long sessionId,
        ClientType clientType,
        List<String> roles
    ) {
        String actorType = roles.contains("ADMIN") ? "ADMIN" : roles.contains("DOCTOR") ? "DOCTOR" : "USER";
        String traceId = TraceFilter.current();
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        jdbc.update("""
            INSERT INTO audit_log(
                id,actor_user_id,actor_type,action,resource_type,resource_id,after_json,trace_id,result
            ) VALUES (?,?,?,'AUTH_LOGIN','AUTH_SESSION',?,JSON_OBJECT('clientType',?,'roles',?),?,'SUCCESS')
            """,
            IdWorker.getId(),
            account.id(),
            actorType,
            sessionId,
            clientType.name(),
            String.join(",", roles),
            traceId
        );
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_DEPENDENCY_UNAVAILABLE",
                "认证数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private static boolean clientAllowed(ClientType clientType, List<String> roles) {
        return switch (clientType) {
            case MINI -> roles.contains("USER");
            case WEB -> roles.contains("DOCTOR") || roles.contains("ADMIN");
        };
    }

    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "密码UTF-8长度不能超过72字节");
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long requirePositive(long value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }

    public enum ClientType {
        MINI,
        WEB
    }

    private record UserAccount(
        long id,
        String username,
        String passwordHash,
        String displayName,
        String status,
        int tokenVersion
    ) {
    }
}
