package com.pethospital.appointment;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.pethospital.appointment.AppointmentStateMachine.Action;
import com.pethospital.appointment.AppointmentStateMachine.Status;
import java.time.Instant;
import java.util.List;

public final class AppointmentReadDtos {
    private AppointmentReadDtos() { }

    public record AppointmentSummary(
        String id, String appointmentNo, String doctorId, String doctorName, String petId, String petName,
        Status status, Instant scheduledStartAt, Instant scheduledEndAt, Instant confirmDeadlineAt,
        Instant createdAt, int version, List<Action> allowedActions
    ) { }

    public record AppointmentDetail(
        AppointmentSummary appointment, String chiefComplaint, String aiSessionId, Instant confirmedAt,
        Instant startedAt, Instant completedAt, Instant cancelledAt, String cancelledBy, String terminalReason
    ) { }

    public record AppointmentPage(
        List<AppointmentSummary> items, int page, int pageSize, @JsonSerialize(as = Number.class) Long total
    ) { }

    public record AppointmentStatusEntry(
        String id, Status fromStatus, Status toStatus, String actorType, String reason, Instant createdAt
    ) { }
}
