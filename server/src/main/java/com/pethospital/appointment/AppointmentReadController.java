package com.pethospital.appointment;

import com.pethospital.appointment.AppointmentReadDtos.AppointmentDetail;
import com.pethospital.appointment.AppointmentReadDtos.AppointmentPage;
import com.pethospital.appointment.AppointmentReadDtos.AppointmentStatusEntry;
import com.pethospital.appointment.AppointmentStateMachine.Status;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class AppointmentReadController {
    private final AppointmentReadService appointments;

    public AppointmentReadController(AppointmentReadService appointments) {
        this.appointments = appointments;
    }

    @GetMapping("/appointments")
    ResponseEntity<ApiResponse<AppointmentPage>> userAppointments(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return response(appointments.list(null, null, null, page, pageSize));
    }

    @GetMapping({"/appointments/{appointmentId}", "/staff/appointments/{appointmentId}"})
    ResponseEntity<ApiResponse<AppointmentDetail>> appointment(@PathVariable @Positive long appointmentId) {
        return response(appointments.detail(appointmentId));
    }

    @GetMapping("/staff/appointments")
    ResponseEntity<ApiResponse<AppointmentPage>> staffAppointments(
        @RequestParam(required = false) Status status,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false) @Positive Long petId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return response(appointments.list(status, date, petId, page, pageSize));
    }

    @GetMapping("/staff/appointments/{appointmentId}/status-logs")
    ResponseEntity<ApiResponse<List<AppointmentStatusEntry>>> statusLogs(@PathVariable @Positive long appointmentId) {
        return response(appointments.statusLogs(appointmentId));
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
