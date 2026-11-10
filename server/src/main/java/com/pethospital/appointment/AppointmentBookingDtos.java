package com.pethospital.appointment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class AppointmentBookingDtos {
    private AppointmentBookingDtos() { }

    public record CreateAppointmentRequest(
        @NotNull @Positive Long petId,
        @NotNull @Positive Long doctorId,
        @NotNull @Positive Long slotId,
        @NotBlank @Size(max = 1000) String chiefComplaint,
        @Positive Long aiSessionId
    ) { }

    public record AppointmentCreated(
        String id, String appointmentNo, AppointmentStateMachine.Status status, String scheduledStartAt,
        String scheduledEndAt, String confirmDeadlineAt, int version
    ) { }
}
