package com.pethospital.identity;

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
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class RbacController {
    private final RbacService rbac;

    public RbacController(RbacService rbac) {
        this.rbac = rbac;
    }

    @GetMapping("/roles")
    ResponseEntity<ApiResponse<RolePage>> roles(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ok(rbac.roles(page, pageSize));
    }

    @PostMapping("/roles")
    ResponseEntity<ApiResponse<RoleView>> createRole(@Valid @RequestBody RoleCreateRequest request) {
        return created(rbac.createRole(request));
    }

    @PatchMapping("/roles/{roleId}")
    ResponseEntity<ApiResponse<RoleView>> updateRole(
        @PathVariable @NonZero long roleId,
        @Valid @RequestBody RolePatchRequest request
    ) {
        return ok(rbac.updateRole(roleId, request));
    }

    @GetMapping("/menus")
    ResponseEntity<ApiResponse<List<MenuView>>> menus() {
        return ok(rbac.menus());
    }

    @PostMapping("/menus")
    ResponseEntity<ApiResponse<MenuView>> createMenu(@Valid @RequestBody MenuCreateRequest request) {
        return created(rbac.createMenu(request));
    }

    @PatchMapping("/menus/{menuId}")
    ResponseEntity<ApiResponse<MenuView>> updateMenu(
        @PathVariable @NonZero long menuId,
        @Valid @RequestBody MenuPatchRequest request
    ) {
        return ok(rbac.updateMenu(menuId, request));
    }

    @GetMapping("/users/{userId}/roles")
    ResponseEntity<ApiResponse<UserRolesView>> userRoles(@PathVariable @Positive long userId) {
        return ok(rbac.userRoles(userId));
    }

    @PutMapping("/users/{userId}/roles")
    ResponseEntity<ApiResponse<UserRolesView>> updateUserRoles(
        @PathVariable @Positive long userId,
        @Valid @RequestBody UserRolesPutRequest request
    ) {
        return ok(rbac.updateUserRoles(userId, request));
    }

    @GetMapping("/roles/{roleId}/menus")
    ResponseEntity<ApiResponse<RoleMenusView>> roleMenus(@PathVariable @NonZero long roleId) {
        return ok(rbac.roleMenus(roleId));
    }

    @PutMapping("/roles/{roleId}/menus")
    ResponseEntity<ApiResponse<RoleMenusView>> updateRoleMenus(
        @PathVariable @NonZero long roleId,
        @Valid @RequestBody RoleMenusPutRequest request
    ) {
        return ok(rbac.updateRoleMenus(roleId, request));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }

    private static <T> ResponseEntity<ApiResponse<T>> created(T data) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(data));
    }
}
