package com.pethospital.health;

import com.pethospital.health.HealthGuideDtos.PublicGuideDetail;
import com.pethospital.health.HealthGuideDtos.PublicGuidePage;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/health/guides")
public class PublicHealthGuideController {
    private final PublicHealthGuideService guides;

    public PublicHealthGuideController(PublicHealthGuideService guides) {
        this.guides = guides;
    }

    @GetMapping
    ResponseEntity<ApiResponse<PublicGuidePage>> list(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 32) String speciesCode,
        @RequestParam(required = false) @Size(max = 64) String categoryCode,
        @RequestParam(required = false) @Size(max = 100) String keyword
    ) {
        return ok(guides.list(page, pageSize, speciesCode, categoryCode, keyword));
    }

    @GetMapping("/{guideId}")
    ResponseEntity<ApiResponse<PublicGuideDetail>> get(@PathVariable @Positive long guideId) {
        return ok(guides.get(guideId));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
