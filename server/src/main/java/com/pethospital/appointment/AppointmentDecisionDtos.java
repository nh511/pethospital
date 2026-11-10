package com.pethospital.appointment;

import com.pethospital.appointment.AppointmentStateMachine.Status;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AppointmentDecisionDtos {
    private AppointmentDecisionDtos() { }

    public record VersionRequest(@NotNull @Min(0) Integer version) { }

    public record ReasonRequest(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 500) String reason) { }

    public record DecisionResult(String id, Status status, int version) { }
}
