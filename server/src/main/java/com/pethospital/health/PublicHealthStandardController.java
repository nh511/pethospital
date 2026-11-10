package com.pethospital.health;

import com.pethospital.health.HealthStandardDtos.PublicStandardView;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/health/standards")
public class PublicHealthStandardController {
    private final PublicHealthStandardService standards;

    public PublicHealthStandardController(PublicHealthStandardService standards) {
        this.standards = standards;
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<PublicStandardView>>> list(
        @RequestParam @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,32}") String speciesCode,
        @RequestParam(required = false) @Min(0) Integer ageMonths,
        @RequestParam @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,64}") String metricCode
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(standards.list(speciesCode, ageMonths, metricCode)));
    }
}
