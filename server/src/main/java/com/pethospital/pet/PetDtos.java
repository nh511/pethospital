package com.pethospital.pet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PetDtos {
    private PetDtos() {
    }

    public record PetCreateRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Pattern(regexp = "CAT|DOG|OTHER") String speciesCode,
        @Size(max = 64) String breed,
        @NotBlank @Pattern(regexp = "MALE|FEMALE|UNKNOWN") String sex,
        LocalDate birthDate,
        @NotBlank @Pattern(regexp = "YES|NO|UNKNOWN") String neutered,
        @Positive Long avatarFileId,
        @Size(max = 10_000) String ownerHealthNotes
    ) {
    }

    public record PetView(
        String id,
        String name,
        String speciesCode,
        String breed,
        String sex,
        LocalDate birthDate,
        String neutered,
        String avatarFileId,
        String status,
        int version,
        String createdAt,
        String updatedAt
    ) {
    }

    public record PetUpdateRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Pattern(regexp = "CAT|DOG|OTHER") String speciesCode,
        @Size(max = 64) String breed,
        @NotBlank @Pattern(regexp = "MALE|FEMALE|UNKNOWN") String sex,
        LocalDate birthDate,
        @NotBlank @Pattern(regexp = "YES|NO|UNKNOWN") String neutered,
        @Positive Long avatarFileId,
        @Size(max = 10_000) String ownerHealthNotes,
        @Min(0) int version
    ) {
    }

    public record PetArchiveRequest(
        @Size(max = 500) String reason,
        @Min(0) int version
    ) {
    }

    public record PetArchiveResult(String petId, String status, int version) {
    }

    public record PetDetail(
        String id,
        String name,
        String speciesCode,
        String breed,
        String sex,
        LocalDate birthDate,
        String neutered,
        String avatarFileId,
        String status,
        int version,
        String createdAt,
        String updatedAt,
        OwnerReported ownerReported,
        ClinicalSummary clinicalSummary
    ) {
    }

    public record OwnerReported(String healthNotes) {
    }

    public record ClinicalSummary(
        WeightSnapshot latestWeight,
        SubmittedHistory submittedHistory
    ) {
    }

    public record WeightSnapshot(BigDecimal weightKg, String observedAt, String sourceType) {
    }

    public record SubmittedHistory(
        List<String> allergies,
        List<String> chronicConditions,
        List<MedicationSummary> medications,
        String sourceAt,
        String recordId,
        int revisionNo
    ) {
        public SubmittedHistory {
            allergies = List.copyOf(allergies);
            chronicConditions = List.copyOf(chronicConditions);
            medications = List.copyOf(medications);
        }
    }

    public record MedicationSummary(
        String name,
        String dosage,
        String frequency,
        String duration
    ) {
    }

    public record PetPage(List<PetView> items, int page, int pageSize, long total) {
        public PetPage {
            items = List.copyOf(items);
        }
    }
}
