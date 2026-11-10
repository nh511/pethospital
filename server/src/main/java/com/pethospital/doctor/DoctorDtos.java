package com.pethospital.doctor;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class DoctorDtos {
    private DoctorDtos() {
    }

    public record AdminDoctorCreateRequest(
        @Positive long userId,
        @NotBlank @Size(max = 64) String doctorName,
        @Size(max = 64) String title,
        @Size(max = 255) String specialties,
        @Size(max = 10_000) String introduction,
        @Positive Long qualificationFileId
    ) {
    }

    public record AdminDoctorPatchRequest(
        @Size(min = 1, max = 64) String doctorName,
        @Size(max = 64) String title,
        @Size(max = 255) String specialties,
        @Size(max = 10_000) String introduction,
        @Positive Long qualificationFileId,
        int version
    ) {
    }

    public record StaffDoctorPatchRequest(
        @Size(max = 255) String specialties,
        @Size(max = 10_000) String introduction,
        int version
    ) {
    }

    public record AdminDoctorDisableRequest(
        @NotNull @Min(0) Integer version,
        @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record AdminDoctorEnableRequest(@NotNull @Min(0) Integer version) {
    }

    public record DoctorProfileView(
        String doctorId,
        String userId,
        String doctorName,
        String title,
        String specialties,
        String introduction,
        String qualificationFileId,
        String status,
        int version,
        String createdAt,
        String updatedAt
    ) {
    }

    public record DoctorProfilePage(List<DoctorProfileView> items, int page, int pageSize, long total) {
        public DoctorProfilePage {
            items = List.copyOf(items);
        }
    }

    public record PublicDoctorSummary(
        String doctorId,
        String doctorName,
        String title,
        String specialties
    ) {
    }

    public record PublicDoctorDetail(
        String doctorId,
        String doctorName,
        String title,
        String specialties,
        String introduction
    ) {
    }

    public record PublicDoctorPage(List<PublicDoctorSummary> items, int page, int pageSize, long total) {
        public PublicDoctorPage {
            items = List.copyOf(items);
        }
    }
}
