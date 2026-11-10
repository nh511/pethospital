package com.pethospital.identity;

import java.util.Set;

public final class BackendPermissionCatalog {
    public static final String ADMIN_USERS_VIEW = "admin:users:view";
    public static final String ADMIN_USERS_MANAGE = "admin:users:manage";
    public static final String ADMIN_RBAC_VIEW = "admin:rbac:view";
    public static final String ADMIN_RBAC_MANAGE = "admin:rbac:manage";
    public static final String ADMIN_DOCTORS_VIEW = "admin:doctors:view";
    public static final String ADMIN_DOCTORS_MANAGE = "admin:doctors:manage";
    public static final String ADMIN_PETS_CORRECT = "admin:pets:correct";
    public static final String AUDIT_READ = "audit:read";
    public static final String AI_CONSULT_AUDIT = "ai-consult:audit";
    public static final String AI_CALL_LOG_READ = "ai:call-logs:read";

    private static final Set<String> KNOWN = Set.of(
        ADMIN_USERS_VIEW,
        ADMIN_USERS_MANAGE,
        ADMIN_RBAC_VIEW,
        ADMIN_RBAC_MANAGE,
        ADMIN_DOCTORS_VIEW,
        ADMIN_DOCTORS_MANAGE,
        ADMIN_PETS_CORRECT,
        AUDIT_READ,
        AI_CONSULT_AUDIT,
        AI_CALL_LOG_READ,
        "profile:view",
        "profile:update"
    );

    private BackendPermissionCatalog() {
    }

    public static boolean contains(String permissionCode) {
        return KNOWN.contains(permissionCode);
    }
}
