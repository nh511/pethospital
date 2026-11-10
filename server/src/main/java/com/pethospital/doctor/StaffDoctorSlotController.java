package com.pethospital.doctor;

import com.pethospital.doctor.DoctorSlotDtos.SlotCreateRequest;
import com.pethospital.doctor.DoctorSlotDtos.SlotCapacityPatchRequest;
import com.pethospital.doctor.DoctorSlotDtos.SlotCloseRequest;
import com.pethospital.doctor.DoctorSlotDtos.SlotOpenRequest;
import com.pethospital.doctor.DoctorSlotDtos.StaffSlotList;
import com.pethospital.doctor.DoctorSlotDtos.StaffSlotView;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/v1/staff/slots")
public class StaffDoctorSlotController {
    private final DoctorSlotService slots;

    public StaffDoctorSlotController(DoctorSlotService slots) {
        this.slots = slots;
    }

    @GetMapping
    ResponseEntity<ApiResponse<StaffSlotList>> list(
        @RequestParam(required = false) @Positive Long doctorId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(slots.list(doctorId, fromDate, toDate)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<StaffSlotView>> create(
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody SlotCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(slots.create(request, operationKey)));
    }

    @PatchMapping("/{slotId}")
    ResponseEntity<ApiResponse<StaffSlotView>> patch(
        @PathVariable @Positive long slotId,
        @Valid @RequestBody SlotCapacityPatchRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(slots.patchCapacity(slotId, request)));
    }

    @PostMapping("/{slotId}/close")
    ResponseEntity<ApiResponse<StaffSlotView>> close(
        @PathVariable @Positive long slotId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody SlotCloseRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(slots.close(slotId, request, operationKey)));
    }

    @PostMapping("/{slotId}/open")
    ResponseEntity<ApiResponse<StaffSlotView>> open(
        @PathVariable @Positive long slotId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody SlotOpenRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(slots.open(slotId, request, operationKey)));
    }
}
