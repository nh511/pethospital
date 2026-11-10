package com.pethospital.identity;

import com.pethospital.identity.AdminUserDtos.AdminUserCreateRequest;
import com.pethospital.identity.AdminUserDtos.AdminUserPage;
import com.pethospital.identity.AdminUserDtos.AdminUserPatchRequest;
import com.pethospital.identity.AdminUserDtos.AdminUserView;
import com.pethospital.identity.AdminAccountLifecycleDtos.AccountLifecycleView;
import com.pethospital.identity.AdminAccountLifecycleDtos.DisableAccountRequest;
import com.pethospital.identity.AdminAccountLifecycleDtos.EnableAccountRequest;
import com.pethospital.identity.AdminAccountLifecycleDtos.ResetPasswordRequest;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final AdminUserService users;
    private final AdminAccountLifecycleService lifecycle;

    public AdminUserController(AdminUserService users, AdminAccountLifecycleService lifecycle) {
        this.users = users;
        this.lifecycle = lifecycle;
    }

    @GetMapping
    ResponseEntity<ApiResponse<AdminUserPage>> list(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(users.list(page, pageSize)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<AdminUserView>> create(@Valid @RequestBody AdminUserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(users.create(request)));
    }

    @GetMapping("/{userId}")
    ResponseEntity<ApiResponse<AdminUserView>> detail(@PathVariable @Positive long userId) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(users.detail(userId)));
    }

    @PatchMapping("/{userId}")
    ResponseEntity<ApiResponse<AdminUserView>> update(
        @PathVariable @Positive long userId,
        @Valid @RequestBody AdminUserPatchRequest request
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(users.update(userId, request)));
    }

    @PostMapping("/{userId}/disable")
    ResponseEntity<ApiResponse<AccountLifecycleView>> disable(
        @PathVariable @Positive long userId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody DisableAccountRequest request
    ) {
        return lifecycleResponse(lifecycle.disable(userId, request, operationKey));
    }

    @PostMapping("/{userId}/enable")
    ResponseEntity<ApiResponse<AccountLifecycleView>> enable(
        @PathVariable @Positive long userId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody EnableAccountRequest request
    ) {
        return lifecycleResponse(lifecycle.enable(userId, request, operationKey));
    }

    @PostMapping("/{userId}/reset-password")
    ResponseEntity<ApiResponse<AccountLifecycleView>> resetPassword(
        @PathVariable @Positive long userId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody ResetPasswordRequest request
    ) {
        return lifecycleResponse(lifecycle.resetPassword(userId, request, operationKey));
    }

    private static ResponseEntity<ApiResponse<AccountLifecycleView>> lifecycleResponse(
        AccountLifecycleView result
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(result));
    }
}
