package com.pethospital.appointment;

import com.pethospital.appointment.AppointmentBookingDtos.AppointmentCreated;
import com.pethospital.appointment.AppointmentBookingDtos.CreateAppointmentRequest;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping(AppointmentBookingController.PATH)
public class AppointmentBookingController {
    static final String PATH = "/api/v1/appointments";
    private final AppointmentBookingService appointments;

    public AppointmentBookingController(AppointmentBookingService appointments) {
        this.appointments = appointments;
    }

    @PostMapping
    ResponseEntity<ApiResponse<AppointmentCreated>> create(
        @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{16,64}") String operationKey,
        @Valid @RequestBody CreateAppointmentRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(appointments.create(request, operationKey)));
    }
}
