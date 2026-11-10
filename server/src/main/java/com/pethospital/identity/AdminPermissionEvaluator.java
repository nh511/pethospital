package com.pethospital.identity;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public final class AdminPermissionEvaluator {
    private final ObjectProvider<DataSource> dataSourceProvider;

    public AdminPermissionEvaluator(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    public AuthorizationDecision decide(Authentication authentication, String permissionCode) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
            || !authentication.isAuthenticated()
            || !hasAuthority(authentication, "AUD_WEB")
            || !hasAuthority(authentication, "ROLE_ADMIN")) {
            return new AuthorizationDecision(false);
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return new AuthorizationDecision(false);
        }
        long userId;
        try {
            userId = Long.parseLong(jwtAuthentication.getToken().getClaimAsString("userId"));
        } catch (RuntimeException exception) {
            return new AuthorizationDecision(false);
        }
        try {
            Integer matches = new JdbcTemplate(dataSource).queryForObject("""
                SELECT COUNT(DISTINCT permitted_role.id)
                FROM sys_user u
                JOIN sys_user_role admin_link ON admin_link.user_id=u.id
                JOIN sys_role admin_role ON admin_role.id=admin_link.role_id
                    AND admin_role.code='ADMIN' AND admin_role.status='ACTIVE'
                JOIN sys_user_role permission_link ON permission_link.user_id=u.id
                JOIN sys_role permitted_role ON permitted_role.id=permission_link.role_id
                    AND permitted_role.status='ACTIVE'
                JOIN sys_role_menu rm ON rm.role_id=permitted_role.id
                JOIN sys_menu m ON m.id=rm.menu_id AND m.status='ACTIVE'
                WHERE u.id=? AND u.status='ACTIVE' AND m.permission_code=?
                """, Integer.class, userId, permissionCode);
            return new AuthorizationDecision(matches != null && matches > 0);
        } catch (DataAccessException exception) {
            return new AuthorizationDecision(false);
        }
    }

    private static boolean hasAuthority(Authentication authentication, String required) {
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> required.equals(authority.getAuthority()));
    }
}
