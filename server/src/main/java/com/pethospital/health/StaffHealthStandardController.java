package com.pethospital.health;

import com.pethospital.health.HealthStandardDtos.StandardCreateRequest;
import com.pethospital.health.HealthStandardDtos.StandardPage;
import com.pethospital.health.HealthStandardDtos.StandardOfflineRequest;
import com.pethospital.health.HealthStandardDtos.StandardPatchRequest;
import com.pethospital.health.HealthStandardDtos.StandardPublishRequest;
import com.pethospital.health.HealthStandardDtos.StandardView;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/staff/health/standards")
public class StaffHealthStandardController {
    private final HealthStandardService standards;

    public StaffHealthStandardController(HealthStandardService standards) {
        this.standards = standards;
    }

    @GetMapping
    ResponseEntity<ApiResponse<StandardPage>> list(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 16) String status,
        @RequestParam(required = false) @Size(max = 32) String speciesCode,
        @RequestParam(required = false) @Size(max = 64) String metricCode,
        @RequestParam(required = false) @Size(max = 100) String keyword
    ) {
        return ok(standards.list(page, pageSize, status, speciesCode, metricCode, keyword));
    }

    @GetMapping("/{standardId}")
    ResponseEntity<ApiResponse<StandardView>> get(@PathVariable @Positive long standardId) {
        return ok(standards.get(standardId));
    }

    @PostMapping
    ResponseEntity<ApiResponse<StandardView>> create(
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody StandardCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(standards.create(request, operationKey)));
    }

    @PatchMapping("/{standardId}")
    ResponseEntity<ApiResponse<StandardView>> patch(
        @PathVariable @Positive long standardId,
        @Valid @RequestBody StandardPatchRequest request
    ) {
        return ok(standards.patch(standardId, request));
    }

    @PostMapping("/{standardId}/publish")
    ResponseEntity<ApiResponse<StandardView>> publish(
        @PathVariable @Positive long standardId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody StandardPublishRequest request
    ) {
        return ok(standards.publish(standardId, request, operationKey));
    }

    @PostMapping("/{standardId}/offline")
    ResponseEntity<ApiResponse<StandardView>> offline(
        @PathVariable @Positive long standardId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody StandardOfflineRequest request
    ) {
        return ok(standards.offline(standardId, request, operationKey));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
