package com.pethospital.identity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.pethospital.appointment.UnfinishedAppointmentQuery;
import com.pethospital.appointment.UnfinishedAppointmentQuery.UnfinishedAppointments;
import com.pethospital.identity.RbacDtos.MenuCreateRequest;
import com.pethospital.identity.RbacDtos.MenuPatchRequest;
import com.pethospital.identity.RbacDtos.MenuView;
import com.pethospital.identity.RbacDtos.RoleCreateRequest;
import com.pethospital.identity.RbacDtos.RoleMenusPutRequest;
import com.pethospital.identity.RbacDtos.RoleMenusView;
import com.pethospital.identity.RbacDtos.RolePage;
import com.pethospital.identity.RbacDtos.RolePatchRequest;
import com.pethospital.identity.RbacDtos.RoleView;
import com.pethospital.identity.RbacDtos.UserRolesPutRequest;
import com.pethospital.identity.RbacDtos.UserRolesView;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.TraceFilter;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RbacService {
    private static final Set<String> BUILTIN_ROLE_CODES = Set.of("USER", "DOCTOR", "ADMIN");
    private static final Set<String> ADMIN_CORE_PERMISSIONS = Set.of(
        BackendPermissionCatalog.ADMIN_USERS_VIEW,
        BackendPermissionCatalog.ADMIN_USERS_MANAGE,
        BackendPermissionCatalog.ADMIN_RBAC_VIEW,
        BackendPermissionCatalog.ADMIN_RBAC_MANAGE
    );

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final Clock clock;
    private final UnfinishedAppointmentQuery appointments;

    public RbacService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        Clock clock,
        UnfinishedAppointmentQuery appointments
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.clock = clock;
        this.appointments = appointments;
    }

    @Transactional(readOnly = true)
    public RolePage roles(int page, int pageSize) {
        JdbcTemplate jdbc = authorizedJdbc();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM sys_role", Long.class);
        List<RoleView> items = jdbc.query("""
            SELECT id,code,name,status,version FROM sys_role
            ORDER BY created_at,id LIMIT ? OFFSET ?
            """, (result, row) -> new RoleView(
            result.getLong("id"),
            result.getString("code"),
            result.getString("name"),
            result.getString("status"),
            result.getInt("version")
        ), pageSize, (page - 1) * pageSize);
        return new RolePage(items, page, pageSize, total);
    }

    @Transactional
    public RoleView createRole(RoleCreateRequest request) {
        JdbcTemplate jdbc = authorizedJdbc();
        CurrentIdentity actor = identities.require();
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (BUILTIN_ROLE_CODES.contains(code)) {
            throw conflict("BUILTIN_ROLE_PROTECTED", "不能覆盖内置角色编码");
        }
        String name = normalizeName(request.name(), "角色名称");
        long roleId = IdWorker.getId();
        try {
            jdbc.update("""
                INSERT INTO sys_role(id,code,name,status,version) VALUES (?,?,?,'ACTIVE',0)
                """, roleId, code, name);
        } catch (DuplicateKeyException exception) {
            throw conflict("ROLE_CODE_EXISTS", "角色编码已存在");
        }
        audit(jdbc, actor.userId(), "RBAC_ROLE_CREATE", "SYS_ROLE", roleId, null, code + ":" + name);
        return findRole(jdbc, roleId);
    }

    @Transactional
    public RoleView updateRole(long roleId, RolePatchRequest request) {
        if (request.name() == null && request.status() == null) {
            throw badRequest("至少提交一个可修改字段");
        }
        JdbcTemplate jdbc = authorizedJdbc();
        CurrentIdentity actor = identities.require();
        RoleRow existing = lockRole(jdbc, roleId);
        if (existing.version() != request.version()) {
            throw versionConflict();
        }
        String name = request.name() == null ? existing.name() : normalizeName(request.name(), "角色名称");
        String status = request.status() == null ? existing.status() : request.status();
        if (BUILTIN_ROLE_CODES.contains(existing.code()) && !status.equals(existing.status())) {
            throw conflict("BUILTIN_ROLE_PROTECTED", "不能停用内置角色");
        }
        if ("DISABLED".equals(status) && !status.equals(existing.status())) {
            Integer assigned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE role_id=?",
                Integer.class,
                roleId
            );
            if (assigned != null && assigned > 0) {
                throw conflict("ROLE_IN_USE", "角色仍关联账号，不能停用");
            }
        }
        int updated = jdbc.update("""
            UPDATE sys_role SET name=?,status=?,version=version+1 WHERE id=? AND version=?
            """, name, status, roleId, request.version());
        if (updated != 1) {
            throw versionConflict();
        }
        audit(
            jdbc,
            actor.userId(),
            "RBAC_ROLE_UPDATE",
            "SYS_ROLE",
            roleId,
            existing.name() + ":" + existing.status(),
            name + ":" + status
        );
        return findRole(jdbc, roleId);
    }

    @Transactional(readOnly = true)
    public List<MenuView> menus() {
        return buildMenuTree(authorizedJdbc());
    }

    @Transactional
    public MenuView createMenu(MenuCreateRequest request) {
        JdbcTemplate jdbc = authorizedJdbc();
        CurrentIdentity actor = identities.require();
        validatePermissionCode(request.permissionCode());
        validateMenuShape(request.kind(), request.routePath());
        if (request.parentId() != null) {
            requireParentMenu(jdbc, request.parentId(), null);
        }
        long menuId = IdWorker.getId();
        try {
            jdbc.update("""
                INSERT INTO sys_menu(
                    id,parent_id,name,kind,route_path,permission_code,sort_no,status,version
                ) VALUES (?,?,?,?,?,?,?,'ACTIVE',0)
                """,
                menuId,
                request.parentId(),
                normalizeName(request.name(), "菜单名称"),
                request.kind(),
                normalizeRoute(request.routePath()),
                request.permissionCode(),
                request.sortNo()
            );
        } catch (DuplicateKeyException exception) {
            throw conflict("PERMISSION_CODE_EXISTS", "权限编码已被菜单使用");
        }
        audit(
            jdbc,
            actor.userId(),
            "RBAC_MENU_CREATE",
            "SYS_MENU",
            menuId,
            null,
            request.permissionCode()
        );
        return findMenu(jdbc, menuId);
    }

    @Transactional
    public MenuView updateMenu(long menuId, MenuPatchRequest request) {
        JdbcTemplate jdbc = authorizedJdbc();
        CurrentIdentity actor = identities.require();
        MenuRow existing = lockMenu(jdbc, menuId);
        if (existing.version() != request.version()) {
            throw versionConflict();
        }
        validateMenuShape(request.kind(), request.routePath());
        if (request.parentId() != null) {
            requireParentMenu(jdbc, request.parentId(), menuId);
        }
        if ("BUTTON".equals(request.kind())) {
            Integer children = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE parent_id=?",
                Integer.class,
                menuId
            );
            if (children != null && children > 0) {
                throw conflict("MENU_HAS_CHILDREN", "有子菜单时不能改为按钮");
            }
        }
        if (ADMIN_CORE_PERMISSIONS.contains(existing.permissionCode())
            && "DISABLED".equals(request.status())) {
            throw conflict("BUILTIN_PERMISSION_PROTECTED", "不能停用内置管理权限");
        }
        int updated = jdbc.update("""
            UPDATE sys_menu
            SET parent_id=?,name=?,kind=?,route_path=?,sort_no=?,status=?,version=version+1
            WHERE id=? AND version=?
            """,
            request.parentId(),
            normalizeName(request.name(), "菜单名称"),
            request.kind(),
            normalizeRoute(request.routePath()),
            request.sortNo(),
            request.status(),
            menuId,
            request.version()
        );
        if (updated != 1) {
            throw versionConflict();
        }
        if (!existing.status().equals(request.status())) {
            invalidateUsersForMenu(jdbc, menuId);
        }
        audit(
            jdbc,
            actor.userId(),
            "RBAC_MENU_UPDATE",
            "SYS_MENU",
            menuId,
            existing.name() + ":" + existing.status(),
            request.name().trim() + ":" + request.status()
        );
        return findMenu(jdbc, menuId);
    }

    @Transactional(readOnly = true)
    public UserRolesView userRoles(long userId) {
        JdbcTemplate jdbc = authorizedJdbc();
        UserVersion user = findUserVersion(jdbc, userId, false);
        return new UserRolesView(userId, roleIdsForUser(jdbc, userId), user.version());
    }

    @Transactional
    public UserRolesView updateUserRoles(long userId, UserRolesPutRequest request) {
        JdbcTemplate jdbc = authorizedJdbc();
        CurrentIdentity actor = identities.require();
        RoleRow adminRole = lockRoleByCode(jdbc, "ADMIN");
        RoleRow doctorRole = lockRoleByCode(jdbc, "DOCTOR");
        UserVersion user = findUserVersion(jdbc, userId, true);
        if (user.version() != request.userVersion()) {
            throw versionConflict();
        }
        LinkedHashSet<Long> requested = distinctIds(request.roleIds(), "角色ID不能重复");
        requireActiveRoles(jdbc, requested);
        LinkedHashSet<Long> current = new LinkedHashSet<>(roleIdsForUser(jdbc, userId));
        if (current.equals(requested)) {
            return new UserRolesView(userId, List.copyOf(current), user.version());
        }
        if (current.contains(adminRole.id()) && !requested.contains(adminRole.id())) {
            Integer activeAdmins = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT u.id)
                FROM sys_user u
                JOIN sys_user_role ur ON ur.user_id=u.id
                WHERE ur.role_id=? AND u.status='ACTIVE'
                """, Integer.class, adminRole.id());
            if (activeAdmins == null || activeAdmins <= 1) {
                throw conflict("LAST_ADMIN_PROTECTED", "不能移除最后一个有效管理员");
            }
        }
        if (current.contains(doctorRole.id()) && !requested.contains(doctorRole.id())) {
            UnfinishedAppointments pending = appointments.lockForAccount(userId);
            if (pending.doctorCount() > 0) {
                throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "UNFINISHED_APPOINTMENTS",
                    "医生仍有未终结预约，请先处理",
                    Map.of(
                        "pendingCount", pending.doctorCount(),
                        "doctorPendingCount", pending.doctorCount()
                    )
                );
            }
        }
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", userId);
        for (long roleId : requested) {
            jdbc.update(
                "INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
                IdWorker.getId(),
                userId,
                roleId
            );
        }
        jdbc.update(
            "UPDATE sys_user SET version=version+1,token_version=token_version+1 WHERE id=?",
            userId
        );
        revokeUserSessions(jdbc, userId);
        audit(
            jdbc,
            actor.userId(),
            "RBAC_USER_ROLES_UPDATE",
            "SYS_USER",
            userId,
            joinIds(current),
            joinIds(requested)
        );
        return new UserRolesView(userId, roleIdsForUser(jdbc, userId), user.version() + 1);
    }

    @Transactional(readOnly = true)
    public RoleMenusView roleMenus(long roleId) {
        JdbcTemplate jdbc = authorizedJdbc();
        RoleRow role = findRoleRow(jdbc, roleId, false);
        return new RoleMenusView(roleId, menuIdsForRole(jdbc, roleId), role.version());
    }

    @Transactional
    public RoleMenusView updateRoleMenus(long roleId, RoleMenusPutRequest request) {
        JdbcTemplate jdbc = authorizedJdbc();
        CurrentIdentity actor = identities.require();
        RoleRow role = findRoleRow(jdbc, roleId, true);
        if (role.version() != request.roleVersion()) {
            throw versionConflict();
        }
        LinkedHashSet<Long> requested = distinctIds(request.menuIds(), "菜单ID不能重复");
        requireActiveMenus(jdbc, requested);
        if ("ADMIN".equals(role.code())) {
            List<String> permissions = requested.isEmpty() ? List.of() : jdbc.queryForList(
                "SELECT permission_code FROM sys_menu WHERE id IN (" + placeholders(requested.size()) + ")",
                String.class,
                requested.toArray()
            );
            if (!permissions.containsAll(ADMIN_CORE_PERMISSIONS)) {
                throw conflict("LAST_ADMIN_PROTECTED", "内置ADMIN必须保留全部核心管理权限");
            }
        }
        LinkedHashSet<Long> current = new LinkedHashSet<>(menuIdsForRole(jdbc, roleId));
        if (current.equals(requested)) {
            return new RoleMenusView(roleId, List.copyOf(current), role.version());
        }
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=?", roleId);
        for (long menuId : requested) {
            jdbc.update(
                "INSERT INTO sys_role_menu(id,role_id,menu_id) VALUES (?,?,?)",
                IdWorker.getId(),
                roleId,
                menuId
            );
        }
        jdbc.update("UPDATE sys_role SET version=version+1 WHERE id=?", roleId);
        invalidateUsersForRole(jdbc, roleId);
        audit(
            jdbc,
            actor.userId(),
            "RBAC_ROLE_MENUS_UPDATE",
            "SYS_ROLE",
            roleId,
            joinIds(current),
            joinIds(requested)
        );
        return new RoleMenusView(roleId, menuIdsForRole(jdbc, roleId), role.version() + 1);
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
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权管理角色与菜单");
        }
        return jdbc;
    }

    private RoleView findRole(JdbcTemplate jdbc, long roleId) {
        RoleRow row = findRoleRow(jdbc, roleId, false);
        return new RoleView(row.id(), row.code(), row.name(), row.status(), row.version());
    }

    private RoleRow lockRole(JdbcTemplate jdbc, long roleId) {
        return findRoleRow(jdbc, roleId, true);
    }

    private RoleRow lockRoleByCode(JdbcTemplate jdbc, String code) {
        RoleRow role = jdbc.query("""
            SELECT id,code,name,status,version FROM sys_role WHERE code=? FOR UPDATE
            """, result -> result.next() ? new RoleRow(
            result.getLong("id"),
            result.getString("code"),
            result.getString("name"),
            result.getString("status"),
            result.getInt("version")
        ) : null, code);
        if (role == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_CONFIGURATION_ERROR", "内置角色缺失");
        }
        return role;
    }

    private RoleRow findRoleRow(JdbcTemplate jdbc, long roleId, boolean lock) {
        RoleRow role = jdbc.query("""
            SELECT id,code,name,status,version FROM sys_role WHERE id=?
            """ + (lock ? " FOR UPDATE" : ""), result -> result.next() ? new RoleRow(
            result.getLong("id"),
            result.getString("code"),
            result.getString("name"),
            result.getString("status"),
            result.getInt("version")
        ) : null, roleId);
        if (role == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "角色不存在");
        }
        return role;
    }

    private MenuRow lockMenu(JdbcTemplate jdbc, long menuId) {
        MenuRow menu = queryMenu(jdbc, menuId, true);
        if (menu == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MENU_NOT_FOUND", "菜单不存在");
        }
        return menu;
    }

    private MenuRow queryMenu(JdbcTemplate jdbc, long menuId, boolean lock) {
        return jdbc.query("""
            SELECT id,parent_id,name,kind,route_path,permission_code,sort_no,status,version
            FROM sys_menu WHERE id=?
            """ + (lock ? " FOR UPDATE" : ""), result -> result.next() ? new MenuRow(
            result.getLong("id"),
            result.getObject("parent_id", Long.class),
            result.getString("name"),
            result.getString("kind"),
            result.getString("route_path"),
            result.getString("permission_code"),
            result.getInt("sort_no"),
            result.getString("status"),
            result.getInt("version")
        ) : null, menuId);
    }

    private List<MenuView> buildMenuTree(JdbcTemplate jdbc) {
        List<MenuRow> rows = jdbc.query("""
            SELECT id,parent_id,name,kind,route_path,permission_code,sort_no,status,version
            FROM sys_menu ORDER BY sort_no,id
            """, (result, row) -> new MenuRow(
            result.getLong("id"),
            result.getObject("parent_id", Long.class),
            result.getString("name"),
            result.getString("kind"),
            result.getString("route_path"),
            result.getString("permission_code"),
            result.getInt("sort_no"),
            result.getString("status"),
            result.getInt("version")
        ));
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
        List<MenuView> tree = roots.stream()
            .map(row -> toMenuView(row, children, visiting, built))
            .toList();
        if (built.size() != rows.size()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_CONFIGURATION_ERROR", "菜单树存在循环");
        }
        return tree;
    }

    private MenuView toMenuView(
        MenuRow row,
        Map<Long, List<MenuRow>> children,
        Set<Long> visiting,
        Set<Long> built
    ) {
        if (!visiting.add(row.id())) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_CONFIGURATION_ERROR", "菜单树存在循环");
        }
        List<MenuView> childViews = children.getOrDefault(row.id(), List.of()).stream()
            .map(child -> toMenuView(child, children, visiting, built))
            .toList();
        visiting.remove(row.id());
        built.add(row.id());
        return new MenuView(
            row.id(), row.parentId(), row.name(), row.kind(), row.routePath(), row.permissionCode(),
            row.sortNo(), row.status(), row.version(), childViews
        );
    }

    private MenuView findMenu(JdbcTemplate jdbc, long menuId) {
        for (MenuView root : buildMenuTree(jdbc)) {
            MenuView found = findMenu(root, menuId);
            if (found != null) {
                return found;
            }
        }
        throw new BusinessException(HttpStatus.NOT_FOUND, "MENU_NOT_FOUND", "菜单不存在");
    }

    private static MenuView findMenu(MenuView current, long menuId) {
        if (current.id() == menuId) {
            return current;
        }
        for (MenuView child : current.children()) {
            MenuView found = findMenu(child, menuId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void requireParentMenu(JdbcTemplate jdbc, long parentId, Long movingMenuId) {
        Set<Long> visited = new HashSet<>();
        Long current = parentId;
        boolean first = true;
        while (current != null) {
            if (!visited.add(current) || (movingMenuId != null && current.equals(movingMenuId))) {
                throw badRequest("菜单父子关系不能形成循环");
            }
            MenuRow parent = queryMenu(jdbc, current, true);
            if (parent == null) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "MENU_NOT_FOUND", "父菜单不存在");
            }
            if (first && !"MENU".equals(parent.kind())) {
                throw badRequest("父节点必须是菜单");
            }
            first = false;
            current = parent.parentId();
        }
    }

    private UserVersion findUserVersion(JdbcTemplate jdbc, long userId, boolean lock) {
        UserVersion user = jdbc.query("SELECT id,version FROM sys_user WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            result -> result.next() ? new UserVersion(result.getLong("id"), result.getInt("version")) : null,
            userId);
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "账号不存在");
        }
        return user;
    }

    private static List<Long> roleIdsForUser(JdbcTemplate jdbc, long userId) {
        return jdbc.queryForList(
            "SELECT role_id FROM sys_user_role WHERE user_id=? ORDER BY role_id",
            Long.class,
            userId
        );
    }

    private static List<Long> menuIdsForRole(JdbcTemplate jdbc, long roleId) {
        return jdbc.queryForList(
            "SELECT menu_id FROM sys_role_menu WHERE role_id=? ORDER BY menu_id",
            Long.class,
            roleId
        );
    }

    private static void requireActiveRoles(JdbcTemplate jdbc, Set<Long> ids) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role WHERE status='ACTIVE' AND id IN (" + placeholders(ids.size()) + ")",
            Integer.class,
            ids.toArray()
        );
        if (count == null || count != ids.size()) {
            throw badRequest("角色不存在或已停用");
        }
    }

    private static void requireActiveMenus(JdbcTemplate jdbc, Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_menu WHERE status='ACTIVE' AND id IN (" + placeholders(ids.size()) + ")",
            Integer.class,
            ids.toArray()
        );
        if (count == null || count != ids.size()) {
            throw badRequest("菜单不存在或已停用");
        }
    }

    private void invalidateUsersForRole(JdbcTemplate jdbc, long roleId) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            UPDATE sys_user u
            JOIN sys_user_role ur ON ur.user_id=u.id
            SET u.token_version=u.token_version+1
            WHERE ur.role_id=?
            """, roleId);
        jdbc.update("""
            UPDATE auth_session s
            JOIN sys_user_role ur ON ur.user_id=s.user_id
            SET s.revoked_at=?,s.updated_at=?
            WHERE ur.role_id=? AND s.revoked_at IS NULL
            """, now, now, roleId);
    }

    private void invalidateUsersForMenu(JdbcTemplate jdbc, long menuId) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            UPDATE sys_role r JOIN sys_role_menu rm ON rm.role_id=r.id
            SET r.version=r.version+1 WHERE rm.menu_id=?
            """, menuId);
        jdbc.update("""
            UPDATE sys_user u
            JOIN (
                SELECT DISTINCT ur.user_id
                FROM sys_user_role ur JOIN sys_role_menu rm ON rm.role_id=ur.role_id
                WHERE rm.menu_id=?
            ) affected ON affected.user_id=u.id
            SET u.token_version=u.token_version+1
            """, menuId);
        jdbc.update("""
            UPDATE auth_session s
            JOIN (
                SELECT DISTINCT ur.user_id
                FROM sys_user_role ur JOIN sys_role_menu rm ON rm.role_id=ur.role_id
                WHERE rm.menu_id=?
            ) affected ON affected.user_id=s.user_id
            SET s.revoked_at=?,s.updated_at=?
            WHERE s.revoked_at IS NULL
            """, menuId, now, now);
    }

    private void revokeUserSessions(JdbcTemplate jdbc, long userId) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            UPDATE auth_session SET revoked_at=?,updated_at=? WHERE user_id=? AND revoked_at IS NULL
            """, now, now, userId);
    }

    private static void validatePermissionCode(String permissionCode) {
        if (!BackendPermissionCatalog.contains(permissionCode)) {
            throw badRequest("权限编码没有对应的后端能力");
        }
    }

    private static void validateMenuShape(String kind, String routePath) {
        if ("BUTTON".equals(kind) && routePath != null && !routePath.isBlank()) {
            throw badRequest("按钮不能配置路由");
        }
    }

    private static String normalizeRoute(String routePath) {
        if (routePath == null || routePath.isBlank()) {
            return null;
        }
        String normalized = routePath.trim();
        if (!normalized.startsWith("/")) {
            throw badRequest("菜单路由必须以/开头");
        }
        return normalized;
    }

    private static String normalizeName(String value, String label) {
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw badRequest(label + "不能为空");
        }
        return normalized;
    }

    private static <T> LinkedHashSet<T> distinctIds(List<T> ids, String duplicateMessage) {
        LinkedHashSet<T> distinct = new LinkedHashSet<>(ids);
        if (distinct.size() != ids.size()) {
            throw badRequest(duplicateMessage);
        }
        return distinct;
    }

    private static String placeholders(int count) {
        if (count <= 0) {
            throw badRequest("集合不能为空");
        }
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String joinIds(Iterable<Long> ids) {
        List<String> values = new ArrayList<>();
        ids.forEach(id -> values.add(Long.toString(id)));
        return String.join(",", values);
    }

    private static void audit(
        JdbcTemplate jdbc,
        long actorUserId,
        String action,
        String resourceType,
        long resourceId,
        String before,
        String after
    ) {
        String traceId = TraceFilter.current();
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        jdbc.update("""
            INSERT INTO audit_log(
                id,actor_user_id,actor_type,action,resource_type,resource_id,
                before_json,after_json,trace_id,result
            ) VALUES (?,?,'ADMIN',?,?,?,
                IF(? IS NULL,NULL,JSON_OBJECT('summary',?)),
                IF(? IS NULL,NULL,JSON_OBJECT('summary',?)),?,'SUCCESS')
            """,
            IdWorker.getId(),
            actorUserId,
            action,
            resourceType,
            resourceId,
            before,
            before,
            after,
            after,
            traceId
        );
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_DEPENDENCY_UNAVAILABLE",
                "权限管理数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private static BusinessException versionConflict() {
        return conflict("VERSION_CONFLICT", "数据已更新，请刷新后重试");
    }

    private record RoleRow(long id, String code, String name, String status, int version) {
    }

    private record MenuRow(
        long id,
        Long parentId,
        String name,
        String kind,
        String routePath,
        String permissionCode,
        int sortNo,
        String status,
        int version
    ) {
    }

    private record UserVersion(long id, int version) {
    }
}
