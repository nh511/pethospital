package com.pethospital.identity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class ProfileDtos {
    private ProfileDtos() {
    }

    public record ProfilePatchRequest(
        @Size(min = 1, max = 64) String displayName,
        @Positive Long avatarFileId,
        @Size(min = 7, max = 32) String phone,
        @NotNull @Min(0) Integer version
    ) {
    }

    public record UserSelfView(
        long id,
        String displayName,
        String phoneMasked,
        Long avatarFileId,
        List<String> roles,
        List<String> permissions,
        int version
    ) {
        public UserSelfView {
            roles = List.copyOf(roles);
            permissions = List.copyOf(permissions);
        }
    }

    public record MenuView(
        long id,
        String name,
        String routePath,
        String permissionCode,
        int sortNo,
        List<MenuView> children
    ) {
        public MenuView {
            children = List.copyOf(children);
        }
    }
}
