package com.pethospital.appointment;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AppointmentChangeDtos {
    private AppointmentChangeDtos() { }

    public enum Status { OPEN, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED }

    public record CreateRequest(
        @NotNull @Min(0) Integer version,
        @NotNull @Positive Long newSlotId,
        @NotBlank @Size(max = 500) String reason
    ) { }

    public record Created(String id, String appointmentId, String newSlotId, Status status, Instant expiresAt, int version) { }

    public record VersionRequest(@NotNull @Min(0) Integer version) { }

    public record RejectRequest(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 500) String reason) { }

    public record Closed(String id, String appointmentId, Status status, Instant respondedAt, int version) { }

    public record Accepted(
        String id, String appointmentId, Status status, String newSlotId, Instant scheduledStartAt,
        Instant scheduledEndAt, int version, int appointmentVersion
    ) { }

    public record HistoryItem(
        String id, String appointmentId, String proposerType, String oldSlotId, String newSlotId,
        Instant oldStartAt, Instant newStartAt, Status status, String reason, Instant expiresAt,
        Instant respondedAt, String responseReason, int version, Instant createdAt
    ) { }

    public record HistoryPage(
        List<HistoryItem> items, int page, int pageSize, @JsonSerialize(as = Number.class) Long total
    ) { }
}
