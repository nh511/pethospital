package com.pethospital.doctor;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class DoctorSlotDtos {
    private DoctorSlotDtos() {
    }

    public record SlotCreateRequest(
        @Positive long doctorId,
        @NotNull LocalDate date,
        @NotNull OffsetDateTime startAt,
        @NotNull OffsetDateTime endAt,
        @Positive int capacity
    ) {
    }

    public record StaffSlotView(
        String slotId,
        String doctorId,
        LocalDate slotDate,
        String startAt,
        String endAt,
        int capacity,
        int occupiedCount,
        int availableCount,
        String status,
        int version
    ) {
    }

    public record StaffSlotList(List<StaffSlotView> items) {
        public StaffSlotList {
            items = List.copyOf(items);
        }
    }

    public record PublicSlotView(
        String id,
        String doctorId,
        String startAt,
        String endAt,
        int capacity,
        int availableCount,
        String status
    ) {
    }

    public record PublicSlotList(List<PublicSlotView> items) {
        public PublicSlotList {
            items = List.copyOf(items);
        }
    }

    public record SlotCapacityPatchRequest(@Positive int capacity, int version) {
    }

    public record SlotCloseRequest(int version, @NotNull @Size(min = 1, max = 500) String reason) {
    }

    public record SlotOpenRequest(int version) {
    }
}
