package com.pethospital.appointment;

import com.pethospital.appointment.AppointmentDecisionDtos.DecisionResult;
import com.pethospital.appointment.AppointmentDecisionDtos.ReasonRequest;
import com.pethospital.appointment.AppointmentDecisionDtos.VersionRequest;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping(AppointmentDecisionController.PATH)
public class AppointmentDecisionController {
    static final String PATH = "/api/v1/staff/appointments";
    private static final String KEY_PATTERN = "[A-Za-z0-9._:-]{16,64}";
    private final AppointmentDecisionService appointments;

    public AppointmentDecisionController(AppointmentDecisionService appointments) {
        this.appointments = appointments;
    }

    @PostMapping("/{appointmentId}/confirm")
    ResponseEntity<ApiResponse<DecisionResult>> confirm(
        @PathVariable @Positive long appointmentId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String key,
        @Valid @RequestBody VersionRequest request
    ) {
        return response(appointments.confirm(appointmentId, key, request));
    }

    @PostMapping("/{appointmentId}/reject")
    ResponseEntity<ApiResponse<DecisionResult>> reject(
        @PathVariable @Positive long appointmentId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String key,
        @Valid @RequestBody ReasonRequest request
    ) {
        return response(appointments.reject(appointmentId, key, request));
    }

    @PostMapping("/{appointmentId}/start")
    ResponseEntity<ApiResponse<DecisionResult>> start(
        @PathVariable @Positive long appointmentId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String key,
        @Valid @RequestBody VersionRequest request
    ) {
        return response(appointments.start(appointmentId, key, request));
    }

    @PostMapping("/{appointmentId}/no-show")
    ResponseEntity<ApiResponse<DecisionResult>> noShow(
        @PathVariable @Positive long appointmentId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String key,
        @Valid @RequestBody ReasonRequest request
    ) {
        return response(appointments.markNoShow(appointmentId, key, request));
    }

    private static ResponseEntity<ApiResponse<DecisionResult>> response(DecisionResult result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }
}
