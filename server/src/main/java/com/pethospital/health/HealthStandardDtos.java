package com.pethospital.health;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class HealthStandardDtos {
    private HealthStandardDtos() {
    }

    public record StandardCreateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,32}") String speciesCode,
        @Min(0) Integer ageMinMonths,
        @Min(0) Integer ageMaxMonths,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,64}") String metricCode,
        @Size(max = 32) String unit,
        @Digits(integer = 8, fraction = 4) BigDecimal referenceMin,
        @Digits(integer = 8, fraction = 4) BigDecimal referenceMax,
        @NotBlank @Size(max = 20_000) String description,
        @NotBlank @Size(max = 20_000) String conditionsNote,
        @NotBlank @Size(max = 1000) String sourceNote
    ) {
    }

    public record StandardPatchRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,32}") String speciesCode,
        @Min(0) Integer ageMinMonths,
        @Min(0) Integer ageMaxMonths,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,64}") String metricCode,
        @Size(max = 32) String unit,
        @Digits(integer = 8, fraction = 4) BigDecimal referenceMin,
        @Digits(integer = 8, fraction = 4) BigDecimal referenceMax,
        @NotBlank @Size(max = 20_000) String description,
        @NotBlank @Size(max = 20_000) String conditionsNote,
        @NotBlank @Size(max = 1000) String sourceNote,
        @NotNull @Min(0) Integer version
    ) {
    }

    public record StandardPublishRequest(@NotNull @Min(0) Integer version) {
    }

    public record StandardOfflineRequest(
        @NotNull @Min(0) Integer version,
        @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record StandardView(
        String standardId,
        String name,
        String speciesCode,
        Integer ageMinMonths,
        Integer ageMaxMonths,
        String metricCode,
        String unit,
        BigDecimal referenceMin,
        BigDecimal referenceMax,
        String description,
        String conditionsNote,
        String sourceNote,
        String status,
        String editorDoctorId,
        int version,
        String createdAt,
        String updatedAt
    ) {
    }

    public record StandardPage(List<StandardView> items, int page, int pageSize, long total) {
        public StandardPage {
            items = List.copyOf(items);
        }
    }

    public record PublicStandardView(
        String standardId,
        String name,
        String speciesCode,
        Integer ageMinMonths,
        Integer ageMaxMonths,
        String metricCode,
        String unit,
        BigDecimal referenceMin,
        BigDecimal referenceMax,
        String description,
        String conditionsNote,
        String sourceNote
    ) {
    }
}
