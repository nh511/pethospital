package com.pethospital.pet;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PetHealthLogDtos {
    private PetHealthLogDtos() {
    }

    public record HealthLogCreateRequest(
        @NotNull Instant observedAt,
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 4, fraction = 2) BigDecimal weightKg,
        @Size(max = 10_000) String dietNote,
        @Size(max = 10_000) String stoolNote,
        @Size(max = 10_000) String activityNote,
        @Size(max = 10_000) String symptomNote,
        @NotNull @Size(max = 20) List<@Positive Long> attachmentIds
    ) {
        public HealthLogCreateRequest {
            attachmentIds = attachmentIds == null ? null : List.copyOf(attachmentIds);
        }
    }

    public record HealthLogUpdateRequest(
        @NotNull Instant observedAt,
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 4, fraction = 2) BigDecimal weightKg,
        @Size(max = 10_000) String dietNote,
        @Size(max = 10_000) String stoolNote,
        @Size(max = 10_000) String activityNote,
        @Size(max = 10_000) String symptomNote,
        @NotNull @Size(max = 20) List<@Positive Long> attachmentIds,
        @jakarta.validation.constraints.Min(0) int version
    ) {
        public HealthLogUpdateRequest {
            attachmentIds = attachmentIds == null ? null : List.copyOf(attachmentIds);
        }
    }

    public record HealthLogView(
        String id,
        String petId,
        String observedAt,
        BigDecimal weightKg,
        String dietNote,
        String stoolNote,
        String activityNote,
        String symptomNote,
        List<String> attachmentIds,
        String sourceType,
        int version,
        String createdAt,
        String updatedAt
    ) {
        public HealthLogView {
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    public record HealthLogPage(
        List<HealthLogView> items, int page, int pageSize, @JsonSerialize(as = Number.class) Long total
    ) {
        public HealthLogPage {
            items = List.copyOf(items);
        }
    }
}
