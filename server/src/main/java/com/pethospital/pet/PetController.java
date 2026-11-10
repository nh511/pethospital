package com.pethospital.pet;

import com.pethospital.pet.PetDtos.PetCreateRequest;
import com.pethospital.pet.PetDtos.PetArchiveRequest;
import com.pethospital.pet.PetDtos.PetArchiveResult;
import com.pethospital.pet.PetDtos.PetDetail;
import com.pethospital.pet.PetDtos.PetPage;
import com.pethospital.pet.PetDtos.PetUpdateRequest;
import com.pethospital.pet.PetDtos.PetView;
import com.pethospital.pet.PetHealthLogDtos.HealthLogCreateRequest;
import com.pethospital.pet.PetHealthLogDtos.HealthLogPage;
import com.pethospital.pet.PetHealthLogDtos.HealthLogUpdateRequest;
import com.pethospital.pet.PetHealthLogDtos.HealthLogView;
import com.pethospital.pet.PetTimelineDtos.TimelinePage;
import com.pethospital.pet.PetTimelineDtos.TimelineType;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/v1/pets")
public class PetController {
    private final PetService pets;
    private final PetHealthLogService healthLogs;
    private final PetTimelineService timeline;

    public PetController(PetService pets, PetHealthLogService healthLogs, PetTimelineService timeline) {
        this.pets = pets;
        this.healthLogs = healthLogs;
        this.timeline = timeline;
    }

    @PostMapping
    ResponseEntity<ApiResponse<PetView>> create(
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody PetCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(pets.create(request, operationKey)));
    }

    @GetMapping
    ResponseEntity<ApiResponse<PetPage>> list(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(pets.list(status, page, pageSize)));
    }

    @GetMapping("/{petId}")
    ResponseEntity<ApiResponse<PetDetail>> detail(@PathVariable @Positive long petId) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(pets.detail(petId)));
    }

    @PatchMapping("/{petId}")
    ResponseEntity<ApiResponse<PetDetail>> patch(
        @PathVariable @Positive long petId,
        @Valid @RequestBody PetUpdateRequest request
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(pets.patch(petId, request)));
    }

    @PostMapping("/{petId}/archive")
    ResponseEntity<ApiResponse<PetArchiveResult>> archive(
        @PathVariable @Positive long petId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody PetArchiveRequest request
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(pets.archive(petId, request, operationKey)));
    }

    @PostMapping("/{petId}/health-logs")
    ResponseEntity<ApiResponse<HealthLogView>> createHealthLog(
        @PathVariable @Positive long petId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody HealthLogCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(healthLogs.create(petId, request, operationKey)));
    }

    @GetMapping("/{petId}/health-logs")
    ResponseEntity<ApiResponse<HealthLogPage>> listHealthLogs(
        @PathVariable @Positive long petId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(healthLogs.list(petId, from, to, page, pageSize)));
    }

    @GetMapping("/{petId}/timeline")
    ResponseEntity<ApiResponse<TimelinePage>> timeline(
        @PathVariable @Positive long petId,
        @RequestParam(required = false) TimelineType type,
        @RequestParam(required = false) @Size(min = 1, max = 96) String cursor,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(timeline.list(petId, type, cursor, pageSize)));
    }

    @GetMapping("/{petId}/health-logs/{logId}")
    ResponseEntity<ApiResponse<HealthLogView>> healthLogDetail(
        @PathVariable @Positive long petId,
        @PathVariable @Positive long logId
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(healthLogs.detail(petId, logId)));
    }

    @PatchMapping("/{petId}/health-logs/{logId}")
    ResponseEntity<ApiResponse<HealthLogView>> patchHealthLog(
        @PathVariable @Positive long petId,
        @PathVariable @Positive long logId,
        @Valid @RequestBody HealthLogUpdateRequest request
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(healthLogs.patch(petId, logId, request)));
    }
}
