package com.pethospital.doctor;

import com.pethospital.doctor.DoctorDtos.DoctorProfileView;
import com.pethospital.doctor.DoctorDtos.StaffDoctorPatchRequest;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff/doctor-profile")
public class StaffDoctorProfileController {
    private final DoctorProfileService doctors;

    public StaffDoctorProfileController(DoctorProfileService doctors) {
        this.doctors = doctors;
    }

    @GetMapping
    ResponseEntity<ApiResponse<DoctorProfileView>> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(doctors.staffProfile()));
    }

    @PatchMapping
    ResponseEntity<ApiResponse<DoctorProfileView>> patch(
        @Valid @RequestBody StaffDoctorPatchRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(doctors.staffPatch(request)));
    }
}
