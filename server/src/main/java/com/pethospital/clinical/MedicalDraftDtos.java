package com.pethospital.clinical;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class MedicalDraftDtos {
    private MedicalDraftDtos() { }

    public record Medication(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 128) String dosage,
        @NotBlank @Size(max = 128) String frequency,
        @NotBlank @Size(max = 128) String duration
    ) { }

    public record MedicalRecordInput(
        @NotBlank @Size(max = 10000) String chiefComplaint,
        @NotBlank @Size(max = 10000) String examination,
        @NotBlank @Size(max = 10000) String diagnosis,
        @NotBlank @Size(max = 10000) String treatment,
        @Size(max = 50) List<@NotNull @Valid Medication> medications,
        @Size(max = 50) List<@NotBlank @Size(max = 256) String> allergies,
        @Size(max = 50) List<@NotBlank @Size(max = 256) String> chronicConditions,
        @Size(max = 10000) String followupAdvice,
        Instant followupAt
    ) {
        public MedicalRecordInput {
            medications = medications == null ? List.of() : medications;
            allergies = allergies == null ? List.of() : allergies;
            chronicConditions = chronicConditions == null ? List.of() : chronicConditions;
        }
    }

    public record SaveRequest(
        @NotNull @Min(0) Integer version,
        @NotNull @Valid MedicalRecordInput medicalRecord,
        @NotNull @Size(max = 20) List<@NotNull @Positive Long> attachmentIds
    ) { }

    public record Draft(
        String id, String appointmentId, String status, int version, int revisionNo,
        MedicalRecordInput medicalRecord, List<String> attachmentIds
    ) { }

    public record DraftResult(Draft draft) { }

    public record CompleteRequest(
        @NotNull @Min(0) Integer version,
        @NotNull @Min(0) Integer recordVersion,
        @NotNull @Valid MedicalRecordInput medicalRecord,
        @NotNull @Size(max = 20) List<@NotNull @Positive Long> attachmentIds
    ) { }

    public record CompletedAppointment(String id, String status, int version, Instant completedAt) { }

    public record Completed(CompletedAppointment appointment, String medicalRecordId, int revisionNo) { }

    public record RevisionRequest(
        @NotNull @Min(0) Integer version,
        @NotBlank @Size(max = 500) String reason,
        @NotNull @Valid MedicalRecordInput medicalRecord,
        @NotNull @Size(max = 20) List<@NotNull @Positive Long> attachmentIds
    ) { }

    public record Revised(String medicalRecordId, String revisionId, int revisionNo, int version, Instant createdAt) { }
}
