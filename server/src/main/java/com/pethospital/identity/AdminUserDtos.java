package com.pethospital.identity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AdminUserDtos {
    private AdminUserDtos() {
    }

    public record AdminUserCreateRequest(
        @NotBlank @Size(min = 4, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
        @NotBlank @Size(min = 8, max = 72) String initialPassword,
        @NotBlank @Size(max = 64) String displayName,
        @Size(min = 7, max = 32) String phone,
        @NotBlank @Pattern(regexp = "DOCTOR|ADMIN") String roleCode
    ) {
    }

    public record AdminUserPatchRequest(
        @Size(min = 4, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
        @Size(min = 1, max = 64) String displayName,
        @Size(min = 7, max = 32) String phone,
        @NotNull @Min(0) Integer version
    ) {
    }

    public record AdminUserView(
        long id,
        String username,
        String displayName,
        String phoneMasked,
        Long avatarFileId,
        String status,
        List<String> roles,
        int version,
        Instant createdAt,
        Instant updatedAt
    ) {
        public AdminUserView {
            roles = List.copyOf(roles);
        }
    }

    public record AdminUserPage(
        List<AdminUserView> items,
        int page,
        int pageSize,
        long total
    ) {
        public AdminUserPage {
            items = List.copyOf(items);
        }
    }
}
