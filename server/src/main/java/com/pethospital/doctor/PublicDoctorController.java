package com.pethospital.doctor;

import com.pethospital.doctor.DoctorDtos.PublicDoctorDetail;
import com.pethospital.doctor.DoctorDtos.PublicDoctorPage;
import com.pethospital.doctor.DoctorSlotDtos.PublicSlotList;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/doctors")
public class PublicDoctorController {
    private final DoctorProfileService doctors;
    private final DoctorSlotService slots;

    public PublicDoctorController(DoctorProfileService doctors, DoctorSlotService slots) {
        this.doctors = doctors;
        this.slots = slots;
    }

    @GetMapping
    ResponseEntity<ApiResponse<PublicDoctorPage>> list(
        @RequestParam(required = false) @Size(max = 64) String specialty,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(doctors.publicList(specialty, page, pageSize)));
    }

    @GetMapping("/{doctorId}")
    ResponseEntity<ApiResponse<PublicDoctorDetail>> detail(@PathVariable @Positive long doctorId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(doctors.publicDetail(doctorId)));
    }

    @GetMapping("/{doctorId}/slots")
    ResponseEntity<ApiResponse<PublicSlotList>> slots(
        @PathVariable @Positive long doctorId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(slots.publicList(doctorId, fromDate, toDate)));
    }
}
