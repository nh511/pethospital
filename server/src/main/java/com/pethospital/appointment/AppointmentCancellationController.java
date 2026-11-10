package com.pethospital.appointment;

import com.pethospital.appointment.AppointmentDecisionDtos.DecisionResult;
import com.pethospital.appointment.AppointmentDecisionDtos.ReasonRequest;
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
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class AppointmentCancellationController {
    private final AppointmentDecisionService appointments;

    public AppointmentCancellationController(AppointmentDecisionService appointments) {
        this.appointments = appointments;
    }

    @PostMapping({"/api/v1/appointments/{appointmentId}/cancel", "/api/v1/staff/appointments/{appointmentId}/cancel"})
    ResponseEntity<ApiResponse<DecisionResult>> cancel(
        @PathVariable @Positive long appointmentId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{16,64}") String key,
        @Valid @RequestBody ReasonRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(appointments.cancel(appointmentId, key, request)));
    }
}
