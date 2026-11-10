package com.pethospital.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class RbacDtos {
    private RbacDtos() {
    }

    public record RoleCreateRequest(
        @NotBlank @Size(min = 2, max = 32)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String code,
        @NotBlank @Size(max = 64) String name
    ) {
    }

    public record RolePatchRequest(
        @Size(min = 1, max = 64) String name,
        @Pattern(regexp = "ACTIVE|DISABLED") String status,
        @NotNull @Min(0) Integer version
    ) {
    }

    public record RoleView(long id, String code, String name, String status, int version) {
    }

    public record RolePage(List<RoleView> items, int page, int pageSize, long total) {
        public RolePage {
            items = List.copyOf(items);
        }
    }

    public record MenuCreateRequest(
        @Positive Long parentId,
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Pattern(regexp = "MENU|BUTTON") String kind,
        @Size(max = 255) String routePath,
        @NotBlank @Size(max = 128) String permissionCode,
        @NotNull @Min(0) @Max(1000000) Integer sortNo
    ) {
    }

    public record MenuPatchRequest(
        @Positive Long parentId,
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Pattern(regexp = "MENU|BUTTON") String kind,
        @Size(max = 255) String routePath,
        @NotNull @Min(0) @Max(1000000) Integer sortNo,
        @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status,
        @NotNull @Min(0) Integer version
    ) {
    }

    public record MenuView(
        long id,
        Long parentId,
        String name,
        String kind,
        String routePath,
        String permissionCode,
        int sortNo,
        String status,
        int version,
        List<@Valid MenuView> children
    ) {
        public MenuView {
            children = List.copyOf(children);
        }
    }

    public record UserRolesPutRequest(
        @NotNull @Size(min = 1, max = 16) List<@NonZero Long> roleIds,
        @NotNull @Min(0) Integer userVersion
    ) {
        public UserRolesPutRequest {
            roleIds = roleIds == null ? null : List.copyOf(roleIds);
        }
    }

    public record UserRolesView(long userId, List<Long> roleIds, int userVersion) {
        public UserRolesView {
            roleIds = List.copyOf(roleIds);
        }
    }

    public record RoleMenusPutRequest(
        @NotNull @Size(max = 256) List<@NonZero Long> menuIds,
        @NotNull @Min(0) Integer roleVersion
    ) {
        public RoleMenusPutRequest {
            menuIds = menuIds == null ? null : List.copyOf(menuIds);
        }
    }

    public record RoleMenusView(long roleId, List<Long> menuIds, int roleVersion) {
        public RoleMenusView {
            menuIds = List.copyOf(menuIds);
        }
    }
}
