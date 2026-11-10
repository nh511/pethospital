package com.pethospital.appointment;

import com.pethospital.appointment.AppointmentChangeDtos.Accepted;
import com.pethospital.appointment.AppointmentChangeDtos.Closed;
import com.pethospital.appointment.AppointmentChangeDtos.CreateRequest;
import com.pethospital.appointment.AppointmentChangeDtos.Created;
import com.pethospital.appointment.AppointmentChangeDtos.HistoryPage;
import com.pethospital.appointment.AppointmentChangeDtos.RejectRequest;
import com.pethospital.appointment.AppointmentChangeDtos.VersionRequest;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class AppointmentChangeController {
    static final String CHANGE_REQUESTS = "/api/v1/change-requests";
    private static final String MINI = "/api/v1/appointments/{appointmentId}/change-requests";
    private static final String WEB = "/api/v1/staff/appointments/{appointmentId}/change-requests";
    private final AppointmentChangeService changes;

    public AppointmentChangeController(AppointmentChangeService changes) {
        this.changes = changes;
    }

    @PostMapping(CHANGE_REQUESTS + "/{changeRequestId}/accept")
    ResponseEntity<ApiResponse<Accepted>> accept(
        @PathVariable @Positive long changeRequestId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{16,64}") String key,
        @Valid @RequestBody VersionRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(changes.accept(changeRequestId, key, request)));
    }

    @PostMapping(CHANGE_REQUESTS + "/{changeRequestId}/reject")
    ResponseEntity<ApiResponse<Closed>> reject(
        @PathVariable @Positive long changeRequestId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{16,64}") String key,
        @Valid @RequestBody RejectRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(changes.reject(changeRequestId, key, request)));
    }

    @PostMapping(CHANGE_REQUESTS + "/{changeRequestId}/withdraw")
    ResponseEntity<ApiResponse<Closed>> withdraw(
        @PathVariable @Positive long changeRequestId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{16,64}") String key,
        @Valid @RequestBody VersionRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(changes.withdraw(changeRequestId, key, request)));
    }

    @PostMapping({MINI, WEB})
    ResponseEntity<ApiResponse<Created>> create(
        @PathVariable @Positive long appointmentId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{16,64}") String key,
        @Valid @RequestBody CreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(changes.create(appointmentId, key, request)));
    }

    @GetMapping({MINI, WEB})
    ResponseEntity<ApiResponse<HistoryPage>> list(
        @PathVariable @Positive long appointmentId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(changes.history(appointmentId, page, pageSize)));
    }
}
