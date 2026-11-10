package com.pethospital.health;

import com.pethospital.health.HealthGuideDtos.GuideCreateRequest;
import com.pethospital.health.HealthGuideDtos.GuidePage;
import com.pethospital.health.HealthGuideDtos.GuidePatchRequest;
import com.pethospital.health.HealthGuideDtos.GuidePublishRequest;
import com.pethospital.health.HealthGuideDtos.GuideOfflineRequest;
import com.pethospital.health.HealthGuideDtos.GuideView;
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
@RequestMapping("/api/v1/staff/health/guides")
public class StaffHealthGuideController {
    private final HealthGuideService guides;

    public StaffHealthGuideController(HealthGuideService guides) {
        this.guides = guides;
    }

    @GetMapping
    ResponseEntity<ApiResponse<GuidePage>> list(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 16) String status,
        @RequestParam(required = false) @Size(max = 32) String speciesCode,
        @RequestParam(required = false) @Size(max = 64) String categoryCode,
        @RequestParam(required = false) @Size(max = 100) String keyword
    ) {
        return ok(guides.list(page, pageSize, status, speciesCode, categoryCode, keyword));
    }

    @GetMapping("/{guideId}")
    ResponseEntity<ApiResponse<GuideView>> get(@PathVariable @Positive long guideId) {
        return ok(guides.get(guideId));
    }

    @PostMapping
    ResponseEntity<ApiResponse<GuideView>> create(
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody GuideCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(guides.create(request, operationKey)));
    }

    @PatchMapping("/{guideId}")
    ResponseEntity<ApiResponse<GuideView>> patch(
        @PathVariable @Positive long guideId,
        @Valid @RequestBody GuidePatchRequest request
    ) {
        return ok(guides.patch(guideId, request));
    }

    @PostMapping("/{guideId}/publish")
    ResponseEntity<ApiResponse<GuideView>> publish(
        @PathVariable @Positive long guideId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody GuidePublishRequest request
    ) {
        return ok(guides.publish(guideId, request, operationKey));
    }

    @PostMapping("/{guideId}/offline")
    ResponseEntity<ApiResponse<GuideView>> offline(
        @PathVariable @Positive long guideId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody GuideOfflineRequest request
    ) {
        return ok(guides.offline(guideId, request, operationKey));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
