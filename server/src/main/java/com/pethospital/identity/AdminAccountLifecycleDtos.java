package com.pethospital.identity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AdminAccountLifecycleDtos {
    private AdminAccountLifecycleDtos() {
    }

    public record DisableAccountRequest(
        @NotNull @Min(0) Integer version,
        @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record EnableAccountRequest(
        @NotNull @Min(0) Integer version
    ) {
    }

    public record ResetPasswordRequest(
        @NotNull @Min(0) Integer version,
        @NotBlank @Size(min = 8, max = 72) String temporaryPassword,
        @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record AccountLifecycleView(
        long userId,
        String status,
        int version
    ) {
    }
}
