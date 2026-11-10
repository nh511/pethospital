package com.pethospital.identity;

import com.pethospital.identity.ProfileDtos.MenuView;
import com.pethospital.identity.ProfileDtos.ProfilePatchRequest;
import com.pethospital.identity.ProfileDtos.UserSelfView;
import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.FileBindingService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{7,20}$");

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final PhoneCipher phoneCipher;
    private final FileBindingService fileBindings;

    public ProfileService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        PhoneCipher phoneCipher,
        FileBindingService fileBindings
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.phoneCipher = phoneCipher;
        this.fileBindings = fileBindings;
    }

    @Transactional(readOnly = true)
    public UserSelfView current() {
        return current(jdbc(), identities.require().userId());
    }

    @Transactional
    public UserSelfView update(ProfilePatchRequest request) {
        long userId = identities.require().userId();
        if (request.displayName() == null && request.avatarFileId() == null && request.phone() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "至少提交一个可修改字段");
        }

        JdbcTemplate jdbc = jdbc();
        UserRow existing = findUser(jdbc, userId);
        String displayName = request.displayName() == null
            ? existing.displayName()
            : normalizeDisplayName(request.displayName());
        byte[] phoneCiphertext = existing.phoneCipher();
        String phoneMasked = existing.phoneMasked();
        if (request.phone() != null) {
            String phone = normalizePhone(request.phone());
            phoneCiphertext = phoneCipher.encrypt(phone);
            phoneMasked = maskPhone(phone);
        }

        Long avatarFileId = existing.avatarFileId();
        if (request.avatarFileId() != null) {
            requireUsableAvatar(jdbc, request.avatarFileId(), userId);
            avatarFileId = request.avatarFileId();
        }

        int updated = jdbc.update("""
            UPDATE sys_user
            SET display_name=?,phone_cipher=?,phone_masked=?,avatar_file_id=?,version=version+1
            WHERE id=? AND status='ACTIVE' AND version=?
            """,
            displayName,
            phoneCiphertext,
            phoneMasked,
            avatarFileId,
            userId,
            request.version()
        );
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资料已被更新，请刷新后重试");
        }

        if (request.avatarFileId() != null) {
            fileBindings.replaceMutable(
                FileBusinessType.USER_AVATAR,
                userId,
                List.of(request.avatarFileId())
            );
        }
        return current(jdbc, userId);
    }

    @Transactional(readOnly = true)
    public List<MenuView> menus() {
        CurrentIdentity identity = identities.require();
        if (!"WEB".equals(identity.clientType())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "后台菜单仅供后台账号访问");
        }
        List<MenuRow> rows = jdbc().query("""
            SELECT DISTINCT m.id,m.parent_id,m.name,m.route_path,m.permission_code,m.sort_no
            FROM sys_user_role ur
            JOIN sys_role r ON r.id=ur.role_id AND r.status='ACTIVE'
            JOIN sys_role_menu rm ON rm.role_id=r.id
            JOIN sys_menu m ON m.id=rm.menu_id AND m.status='ACTIVE' AND m.kind='MENU'
            WHERE ur.user_id=?
            ORDER BY m.sort_no,m.id
            """, (result, rowNumber) -> new MenuRow(
                result.getLong("id"),
                result.getObject("parent_id", Long.class),
                result.getString("name"),
                result.getString("route_path"),
                result.getString("permission_code"),
                result.getInt("sort_no")
            ), identity.userId());
        return buildMenuTree(rows);
    }

    private UserSelfView current(JdbcTemplate jdbc, long userId) {
        UserRow user = findUser(jdbc, userId);
        List<String> roles = jdbc.queryForList("""
            SELECT r.code
            FROM sys_user_role ur
            JOIN sys_role r ON r.id=ur.role_id AND r.status='ACTIVE'
            WHERE ur.user_id=?
            ORDER BY r.code
            """, String.class, userId);
        List<String> permissions = jdbc.queryForList("""
            SELECT DISTINCT m.permission_code
            FROM sys_user_role ur
            JOIN sys_role r ON r.id=ur.role_id AND r.status='ACTIVE'
            JOIN sys_role_menu rm ON rm.role_id=r.id
            JOIN sys_menu m ON m.id=rm.menu_id AND m.status='ACTIVE'
            WHERE ur.user_id=?
            ORDER BY m.permission_code
            """, String.class, userId);
        return new UserSelfView(
            user.id(),
            user.displayName(),
            user.phoneMasked(),
            user.avatarFileId(),
            roles,
            permissions,
            user.version()
        );
    }

    private UserRow findUser(JdbcTemplate jdbc, long userId) {
        UserRow user = jdbc.query("""
            SELECT id,display_name,phone_cipher,phone_masked,avatar_file_id,version
            FROM sys_user WHERE id=? AND status='ACTIVE'
            """, result -> result.next() ? new UserRow(
                result.getLong("id"),
                result.getString("display_name"),
                result.getBytes("phone_cipher"),
                result.getString("phone_masked"),
                result.getObject("avatar_file_id", Long.class),
                result.getInt("version")
            ) : null, userId);
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "账号不存在或不可用");
        }
        return user;
    }

    private static void requireUsableAvatar(JdbcTemplate jdbc, long fileId, long userId) {
        List<Long> matches = jdbc.queryForList("""
            SELECT id FROM file_object
            WHERE id=? AND uploader_user_id=? AND purpose='AVATAR' AND status='READY'
            FOR UPDATE
            """, Long.class, fileId, userId);
        if (matches.isEmpty()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AVATAR", "头像文件不可用");
        }
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

    private List<MenuView> buildMenuTree(List<MenuRow> rows) {
        Map<Long, MenuRow> byId = new LinkedHashMap<>();
        Map<Long, List<MenuRow>> children = new HashMap<>();
        for (MenuRow row : rows) {
            byId.put(row.id(), row);
        }
        List<MenuRow> roots = new ArrayList<>();
        for (MenuRow row : rows) {
            if (row.parentId() == null || !byId.containsKey(row.parentId())) {
                roots.add(row);
            } else {
                children.computeIfAbsent(row.parentId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        Set<Long> visiting = new HashSet<>();
        Set<Long> built = new HashSet<>();
        List<MenuView> result = roots.stream()
            .map(row -> buildMenu(row, children, visiting, built))
            .toList();
        if (built.size() != rows.size()) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_CONFIGURATION_ERROR",
                "菜单配置存在循环"
            );
        }
        return result;
    }

    private MenuView buildMenu(
        MenuRow row,
        Map<Long, List<MenuRow>> children,
        Set<Long> visiting,
        Set<Long> built
    ) {
        if (!visiting.add(row.id())) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_CONFIGURATION_ERROR",
                "菜单配置存在循环"
            );
        }
        List<MenuView> childViews = children.getOrDefault(row.id(), List.of()).stream()
            .map(child -> buildMenu(child, children, visiting, built))
            .toList();
        visiting.remove(row.id());
        built.add(row.id());
        return new MenuView(
            row.id(),
            row.name(),
            row.routePath(),
            row.permissionCode(),
            row.sortNo(),
            childViews
        );
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PROFILE_DEPENDENCY_UNAVAILABLE",
                "账号资料数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private record UserRow(
        long id,
        String displayName,
        byte[] phoneCipher,
        String phoneMasked,
        Long avatarFileId,
        int version
    ) {
    }

    private record MenuRow(
        long id,
        Long parentId,
        String name,
        String routePath,
        String permissionCode,
        int sortNo
    ) {
    }
}
