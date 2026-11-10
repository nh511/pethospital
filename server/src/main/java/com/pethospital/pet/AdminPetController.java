package com.pethospital.pet;

import com.pethospital.pet.AdminPetDtos.AdminPetDetail;
import com.pethospital.pet.AdminPetDtos.AdminPetPage;
import com.pethospital.pet.AdminPetDtos.AdminPetPatchRequest;
import com.pethospital.pet.PetHealthLogDtos.HealthLogPage;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/pets")
public class AdminPetController {
    private final AdminPetService pets;

    public AdminPetController(AdminPetService pets) {
        this.pets = pets;
    }

    @GetMapping
    ResponseEntity<ApiResponse<AdminPetPage>> list(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(pets.list(page, pageSize)));
    }

    @GetMapping("/{petId}")
    ResponseEntity<ApiResponse<AdminPetDetail>> detail(@PathVariable @Positive long petId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(pets.detail(petId)));
    }

    @PatchMapping("/{petId}")
    ResponseEntity<ApiResponse<AdminPetDetail>> patch(
        @PathVariable @Positive long petId,
        @Valid @RequestBody AdminPetPatchRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(pets.patch(petId, request)));
    }

    @GetMapping("/{petId}/health-logs")
    ResponseEntity<ApiResponse<HealthLogPage>> healthLogs(
        @PathVariable @Positive long petId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            ApiResponse.ok(pets.healthLogs(petId, from, to, page, pageSize))
        );
    }
}
