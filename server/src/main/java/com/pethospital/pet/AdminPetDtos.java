package com.pethospital.pet;

import com.pethospital.pet.PetDtos.OwnerReported;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public final class AdminPetDtos {
    private AdminPetDtos() {
    }

    public record AdminPetOwner(String id, String displayNameMasked) {
    }

    public record AdminPetSummary(
        String id,
        String name,
        String speciesCode,
        String breed,
        String sex,
        LocalDate birthDate,
        String neutered,
        String status,
        int version,
        AdminPetOwner owner,
        String createdAt,
        String updatedAt
    ) {
    }

    public record AdminPetDetail(
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
        AdminPetOwner owner,
        OwnerReported ownerReported,
        String createdAt,
        String updatedAt
    ) {
    }

    public record AdminPetPatchRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Pattern(regexp = "CAT|DOG|OTHER") String speciesCode,
        @Size(max = 64) String breed,
        @NotBlank @Pattern(regexp = "MALE|FEMALE|UNKNOWN") String sex,
        LocalDate birthDate,
        @NotBlank @Pattern(regexp = "YES|NO|UNKNOWN") String neutered,
        @Size(max = 10_000) String ownerHealthNotes,
        @Min(0) int version,
        @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record AdminPetPage(List<AdminPetSummary> items, int page, int pageSize, long total) {
        public AdminPetPage {
            items = List.copyOf(items);
        }
    }
}
