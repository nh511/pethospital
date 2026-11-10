package com.pethospital.doctor;

import com.pethospital.doctor.DoctorDtos.AdminDoctorCreateRequest;
import com.pethospital.doctor.DoctorDtos.AdminDoctorDisableRequest;
import com.pethospital.doctor.DoctorDtos.AdminDoctorEnableRequest;
import com.pethospital.doctor.DoctorDtos.AdminDoctorPatchRequest;
import com.pethospital.doctor.DoctorDtos.DoctorProfilePage;
import com.pethospital.doctor.DoctorDtos.DoctorProfileView;
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
@RequestMapping("/api/v1/admin/doctors")
public class AdminDoctorController {
    private final DoctorProfileService doctors;

    public AdminDoctorController(DoctorProfileService doctors) {
        this.doctors = doctors;
    }

    @GetMapping
    ResponseEntity<ApiResponse<DoctorProfilePage>> list(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(doctors.adminList(page, pageSize)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<DoctorProfileView>> create(
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody AdminDoctorCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(doctors.adminCreate(request, operationKey)));
    }

    @PatchMapping("/{doctorId}")
    ResponseEntity<ApiResponse<DoctorProfileView>> patch(
        @PathVariable @Positive long doctorId,
        @Valid @RequestBody AdminDoctorPatchRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(doctors.adminPatch(doctorId, request)));
    }

    @PostMapping("/{doctorId}/disable")
    ResponseEntity<ApiResponse<DoctorProfileView>> disable(
        @PathVariable @Positive long doctorId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody AdminDoctorDisableRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(doctors.adminDisable(doctorId, request, operationKey)));
    }

    @PostMapping("/{doctorId}/enable")
    ResponseEntity<ApiResponse<DoctorProfileView>> enable(
        @PathVariable @Positive long doctorId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody AdminDoctorEnableRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(doctors.adminEnable(doctorId, request, operationKey)));
    }
}
