package com.pethospital.identity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.pethospital.identity.AdminUserDtos.AdminUserCreateRequest;
import com.pethospital.identity.AdminUserDtos.AdminUserPage;
import com.pethospital.identity.AdminUserDtos.AdminUserPatchRequest;
import com.pethospital.identity.AdminUserDtos.AdminUserView;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.TraceFilter;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{7,20}$");
    private static final String USER_PROJECTION = """
        SELECT u.id,u.username,u.display_name,u.phone_masked,u.avatar_file_id,u.status,u.version,
               u.created_at,u.updated_at,
               COALESCE((
                   SELECT GROUP_CONCAT(r.code ORDER BY r.code SEPARATOR ',')
                   FROM sys_user_role ur
                   JOIN sys_role r ON r.id=ur.role_id AND r.status='ACTIVE'
                   WHERE ur.user_id=u.id
               ),'') AS roles
        FROM sys_user u
        """;

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final PasswordEncoder passwordEncoder;
    private final PhoneCipher phoneCipher;

    public AdminUserService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        PasswordEncoder passwordEncoder,
        PhoneCipher phoneCipher
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.passwordEncoder = passwordEncoder;
        this.phoneCipher = phoneCipher;
    }

    @Transactional(readOnly = true)
    public AdminUserPage list(int page, int pageSize) {
        JdbcTemplate jdbc = jdbc();
        CurrentIdentity actor = identities.require();
        requireActiveAdmin(jdbc, actor.userId());
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class);
        List<AdminUserView> items = jdbc.query(
            USER_PROJECTION + " ORDER BY u.created_at DESC,u.id DESC LIMIT ? OFFSET ?",
            this::mapUser,
            pageSize,
            (page - 1) * pageSize
        );
        return new AdminUserPage(items, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public AdminUserView detail(long userId) {
        JdbcTemplate jdbc = jdbc();
        CurrentIdentity actor = identities.require();
        requireActiveAdmin(jdbc, actor.userId());
        return findUser(jdbc, userId);
    }

    @Transactional
    public AdminUserView create(AdminUserCreateRequest request) {
        JdbcTemplate jdbc = jdbc();
        CurrentIdentity actor = identities.require();
        requireActiveAdmin(jdbc, actor.userId());

        String username = normalizeUsername(request.username());
        validatePasswordBytes(request.initialPassword());
        String displayName = normalizeDisplayName(request.displayName());
        String phone = request.phone() == null ? null : normalizePhone(request.phone());
        byte[] encryptedPhone = phone == null ? null : phoneCipher.encrypt(phone);
        String phoneMasked = phone == null ? null : maskPhone(phone);
        long roleId = requireBackendRole(jdbc, request.roleCode());
        long userId = IdWorker.getId();

        try {
            jdbc.update("""
                INSERT INTO sys_user(
                    id,username,password_hash,display_name,phone_cipher,phone_masked,status,token_version,version
                ) VALUES (?,?,?,?,?,?,'ACTIVE',0,0)
                """,
                userId,
                username,
                passwordEncoder.encode(request.initialPassword()),
                displayName,
                encryptedPhone,
                phoneMasked
            );
            jdbc.update(
                "INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
                IdWorker.getId(),
                userId,
                roleId
            );
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        appendCreateAudit(jdbc, actor.userId(), userId, username, displayName, phoneMasked, request.roleCode());
        return findUser(jdbc, userId);
    }

    @Transactional
    public AdminUserView update(long userId, AdminUserPatchRequest request) {
        if (request.username() == null && request.displayName() == null && request.phone() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "至少提交一个可修改字段");
        }
        JdbcTemplate jdbc = jdbc();
        CurrentIdentity actor = identities.require();
        requireActiveAdmin(jdbc, actor.userId());
        AdminUserRow existing = findUserRow(jdbc, userId);

        String username = request.username() == null
            ? existing.username()
            : normalizeUsername(request.username());
        String displayName = request.displayName() == null
            ? existing.displayName()
            : normalizeDisplayName(request.displayName());
        byte[] encryptedPhone = existing.phoneCipher();
        String phoneMasked = existing.phoneMasked();
        if (request.phone() != null) {
            String phone = normalizePhone(request.phone());
            encryptedPhone = phoneCipher.encrypt(phone);
            phoneMasked = maskPhone(phone);
        }

        try {
            int updated = jdbc.update("""
                UPDATE sys_user
                SET username=?,display_name=?,phone_cipher=?,phone_masked=?,version=version+1
                WHERE id=? AND version=?
                """,
                username,
                displayName,
                encryptedPhone,
                phoneMasked,
                userId,
                request.version()
            );
            if (updated != 1) {
                throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "账号资料已更新，请刷新后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        appendUpdateAudit(
            jdbc,
            actor.userId(),
            userId,
            existing.username(),
            existing.displayName(),
            existing.phoneMasked(),
            username,
            displayName,
            phoneMasked
        );
        return findUser(jdbc, userId);
    }

    private AdminUserView findUser(JdbcTemplate jdbc, long userId) {
        AdminUserView user = jdbc.query(
            USER_PROJECTION + " WHERE u.id=?",
            result -> result.next() ? mapUser(result, 0) : null,
            userId
        );
        if (user == null) {
            throw userNotFound();
        }
        return user;
    }

    private AdminUserRow findUserRow(JdbcTemplate jdbc, long userId) {
        AdminUserRow user = jdbc.query("""
            SELECT username,display_name,phone_cipher,phone_masked
            FROM sys_user WHERE id=?
            """, result -> result.next() ? new AdminUserRow(
            result.getString("username"),
            result.getString("display_name"),
            result.getBytes("phone_cipher"),
            result.getString("phone_masked")
        ) : null, userId);
        if (user == null) {
            throw userNotFound();
        }
        return user;
    }

    private AdminUserView mapUser(ResultSet result, int rowNumber) throws SQLException {
        String roleText = result.getString("roles");
        List<String> roles = roleText == null || roleText.isBlank()
            ? List.of()
            : Arrays.asList(roleText.split(","));
        Timestamp createdAt = result.getTimestamp("created_at");
        Timestamp updatedAt = result.getTimestamp("updated_at");
        return new AdminUserView(
            result.getLong("id"),
            result.getString("username"),
            result.getString("display_name"),
            result.getString("phone_masked"),
            result.getObject("avatar_file_id", Long.class),
            result.getString("status"),
            roles,
            result.getInt("version"),
            createdAt.toInstant(),
            updatedAt.toInstant()
        );
    }

    private static void requireActiveAdmin(JdbcTemplate jdbc, long userId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role r ON r.id=ur.role_id
            WHERE u.id=? AND u.status='ACTIVE' AND r.code='ADMIN' AND r.status='ACTIVE'
            """, Integer.class, userId);
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权管理账号");
        }
    }

    private static long requireBackendRole(JdbcTemplate jdbc, String roleCode) {
        if (!"DOCTOR".equals(roleCode) && !"ADMIN".equals(roleCode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "只能创建医生或管理员账号");
        }
        Long roleId = jdbc.query("""
            SELECT id FROM sys_role WHERE code=? AND status='ACTIVE'
            """, result -> result.next() ? result.getLong(1) : null, roleCode);
        if (roleId == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_CONFIGURATION_ERROR",
                "后台角色尚未初始化或已停用"
            );
        }
        return roleId;
    }

    private static void appendCreateAudit(
        JdbcTemplate jdbc,
        long actorUserId,
        long targetUserId,
        String username,
        String displayName,
        String phoneMasked,
        String roleCode
    ) {
        jdbc.update("""
            INSERT INTO audit_log(
                id,actor_user_id,actor_type,action,resource_type,resource_id,after_json,trace_id,result
            ) VALUES (?,?,'ADMIN','ADMIN_USER_CREATE','SYS_USER',?,
                JSON_OBJECT('username',?,'displayName',?,'phoneMasked',?,'roleCode',?),?,'SUCCESS')
            """,
            IdWorker.getId(),
            actorUserId,
            targetUserId,
            username,
            displayName,
            phoneMasked,
            roleCode,
            traceId()
        );
    }

    private static void appendUpdateAudit(
        JdbcTemplate jdbc,
        long actorUserId,
        long targetUserId,
        String beforeUsername,
        String beforeDisplayName,
        String beforePhoneMasked,
        String afterUsername,
        String afterDisplayName,
        String afterPhoneMasked
    ) {
        jdbc.update("""
            INSERT INTO audit_log(
                id,actor_user_id,actor_type,action,resource_type,resource_id,
                before_json,after_json,trace_id,result
            ) VALUES (?,?,'ADMIN','ADMIN_USER_PROFILE_UPDATE','SYS_USER',?,
                JSON_OBJECT('username',?,'displayName',?,'phoneMasked',?),
                JSON_OBJECT('username',?,'displayName',?,'phoneMasked',?),?,'SUCCESS')
            """,
            IdWorker.getId(),
            actorUserId,
            targetUserId,
            beforeUsername,
            beforeDisplayName,
            beforePhoneMasked,
            afterUsername,
            afterDisplayName,
            afterPhoneMasked,
            traceId()
        );
    }

    private static String normalizeUsername(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeDisplayName(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "显示名称不能为空");
        }
        return normalized;
    }

    private static String normalizePhone(String value) {
        String normalized = value.trim().replace(" ", "").replace("-", "");
        if (!PHONE_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "手机号格式不正确");
        }
        return normalized;
    }

    private static String maskPhone(String phone) {
        int prefixLength = phone.startsWith("+") ? Math.min(4, phone.length() - 4) : Math.min(3, phone.length() - 4);
        return phone.substring(0, prefixLength) + "****" + phone.substring(phone.length() - 4);
    }

    private static void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "初始密码UTF-8长度不能超过72字节");
        }
    }

    private static String traceId() {
        String current = TraceFilter.current();
        return current == null ? UUID.randomUUID().toString() : current;
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

    private static BusinessException userNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "账号不存在");
    }

    private record AdminUserRow(
        String username,
        String displayName,
        byte[] phoneCipher,
        String phoneMasked
    ) {
    }
}
